package com.v2ray.ang.core

import com.v2ray.ang.handler.Failover
import com.v2ray.ang.handler.GamingMode
import com.v2ray.ang.handler.AppLog
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.FragmentVpn
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ServiceRuntimeState
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress

object CoreServiceManager {

    /** How often the tunnel is asked, quietly, whether it still carries anything. */
    private const val WATCH_EVERY_MILLIS = 45_000L

    /**
     * How many empty answers in a row are answered by taking another config.
     *
     * Three, not two: a phone that loses its signal for a moment, or a network
     * that is merely slow, must not be answered by tearing the tunnel down.
     *
     * A question the phone could not ask at all — because the phone itself has
     * no connection, or because the tunnel was being rebuilt — is not counted:
     * it says nothing about the config, and a config was being thrown away for
     * the state of the phone.
     */
    private const val WATCH_MISSES_BEFORE_MOVING = 3

    /**
     * The addresses the watch asks through the tunnel. Any one of them
     * answering means the config carries traffic, so a single site being
     * blocked on this line — which is the ordinary state of affairs on the
     * lines this app is for — is not mistaken for a dead config. A 204 answer
     * is small enough to cost nothing every few minutes.
     */
    private val WATCH_PROBES = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://detectportal.firefox.com/success.txt",
        "https://www.apple.com/library/test/success.html",
    )

    /** A short rest before a moved-to config is raised, so the old tunnel is really gone. */
    private const val RESTART_AFTER_MILLIS = 1_200L

    /** The built-in Mozilla tunnel's place in the config list; not a config to fall back on. */
    private const val FIREFOX_VPN_GUID = "firefox-vpn"

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null
    private val connectionTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The config the watcher moved to on its own, so the next start is not taken for a new round. */
    @Volatile
    private var autoMovedTo: String? = null

    @Volatile
    private var isReloading = false

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            ServiceRuntimeState.markRunning(false)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        AppLog.info(
            AppLog.Section.Connection,
            "Starting with config '${config.remarks}' (${config.configType.name})" +
                (if (isReload) ", reload after a network change" else "") +
                (if (GamingMode.isEnabled()) ", DNS mode on" else ""),
        )
        // The Aether profile is only a pointer to the engine's local port; the engine itself
        // has to be running behind it.
        if (AetherEngine.isAetherProfile(config)) AetherEngine.start(service) else AetherEngine.stop()
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            AppLog.error(AppLog.Section.Core, "The config could not be turned into core settings: ${result.errorMessage}")
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(result.content, tunFd)

        if (!isRunning()) {
            // The core keeps the reason to itself — it only says no — so the parts of the
            // profile that it most often refuses are written down here. A core version that
            // drops a setting (it stopped accepting "allowInsecure", for one) shows up as a
            // connection that never opens, and the reason has to be findable.
            AppLog.error(
                AppLog.Section.Core,
                "The core would not take the config: security ${config.security ?: "none"}, " +
                    "network ${config.network ?: "none"}, " +
                    (if (config.insecure == true) "certificate check off" else "certificate checked")
            )
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        // The rules were written; whether they are obeyed is another matter, and it is
        // the one that decides whether an advert is fetched and where a site at home is
        // answered from. The tunnel is asked a moment after it settles.
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000)
            if (isRunning()) {
                runCatching { RoutingSelfTest.run() }
                    .onFailure { LogUtil.w(AppConfig.TAG, "Routing test could not be run", it) }
            }
        }
        ServiceRuntimeState.markRunning(true)
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
        watchConfig(service, guid)
    }

    /**
     * Configs die, whichever group they belong to: a free public server often,
     * a private one often enough. While one of them is the running config, a
     * request is sent through the tunnel now and then; when nothing comes back a
     * few times in a row another config of the same group is taken and the
     * service starts again with it, without asking and without a word.
     *
     * Nothing is said on the way because a move is not a failure: it is the
     * connection being kept. Only when there is nothing left to take is the
     * tunnel left alone, which is what the owner then sees.
     */
    private fun watchConfig(service: Service, guid: String) {
        failoverScope.coroutineContext.cancelChildren()
        // The app's own ways of reaching the internet are not watched: moving
        // away from one of them would change what the phone is doing, not only
        // where it does it.
        if (guid == FIREFOX_VPN_GUID ||
            FragmentVpn.isFragmentProfile(guid) ||
            AetherEngine.isAetherProfile(MmkvManager.decodeServerConfig(guid))
        ) return
        // Asked for, this is; imposed, it is not. The switch in the settings is
        // there for a phone or a network where a config is better left alone.
        if (!SettingsManager.isAutoFailoverEnabled()) {
            Failover.reset()
            return
        }
        // A start the owner asked for opens a new round; a start that came out of
        // a move continues the one that is running.
        if (guid != autoMovedTo) Failover.reset()
        autoMovedTo = null
        failoverScope.launch {
            var misses = 0
            while (isActive) {
                kotlinx.coroutines.delay(WATCH_EVERY_MILLIS)
                if (!isRunning() || isReloading) continue
                // Without a connection of its own the phone cannot ask anything,
                // and a silence now would be the phone's, not the config's.
                if (!phoneIsOnline(service)) continue
                val verdict = askThroughTunnel(service)
                if (verdict == Verdict.Carries) {
                    misses = 0
                    Failover.settle()
                    continue
                }
                if (verdict == Verdict.Unreachable) continue
                misses++
                if (misses == 2) {
                    // Try one quiet in-place recovery before failing over or dropping:
                    // for WireGuard, escalate MTU/noise anti-DPI if carrier started dropping data.
                    if (currentConfig?.configType == EConfigType.WIREGUARD) {
                        CoreOutboundBuilder.nextWgFallbackMode(guid)?.let { nextWg ->
                            CoreOutboundBuilder.rememberWgMode(guid, nextWg)
                            AppLog.info(AppLog.Section.Connection, "WireGuard tunnel stalled; stepping up to ${nextWg.key}")
                        }
                    }
                    reloadCore()
                    continue
                }
                if (misses < WATCH_MISSES_BEFORE_MOVING) continue
                // The config answered nothing three times while the phone itself was
                // online: try another one of its group, or keep reloading in place
                // so a single-server user never loses the VPN during transient drops.
                val next = nextConfigAfter(guid)
                if (next == null) {
                    AppLog.info(AppLog.Section.Connection, "Config stalled and no alternative in group; keeping tunnel alive and reloading")
                    misses = 1
                    reloadCore()
                    continue
                }
                AppLog.info(AppLog.Section.Connection, "The config stopped answering; another one of its group is taken")
                misses = 0
                Failover.moved(next)
                autoMovedTo = next
                MmkvManager.setSelectServer(next)
                val control = serviceControl?.get() ?: return@launch
                // Stopping cancels this watcher, so the restart runs on its own.
                CoroutineScope(Dispatchers.Default).launch {
                    control.stopService()
                    kotlinx.coroutines.delay(RESTART_AFTER_MILLIS)
                    LauncherManager.startService(service)
                }
                return@launch
            }
        }
    }

    /** What one question through the tunnel came back with. */
    private enum class Verdict { Carries, Silent, Unreachable }

    /**
     * Asks every address in [WATCH_PROBES] through the tunnel and stops at the
     * first that answers. A phone with no connection of its own is reported as
     * [Verdict.Unreachable] rather than as a silent config, because the two are
     * different failures with different answers and only one of them is the
     * config's.
     */
    private suspend fun askThroughTunnel(context: Context?): Verdict {
        var asked = false
        for (address in WATCH_PROBES) {
            if (!phoneIsOnline(context)) return Verdict.Unreachable
            val millis = runCatching { coreController.measureDelay(address) }.getOrDefault(-1L)
            if (millis >= 0L) return Verdict.Carries
            // -1 was the old "nothing came back", and the core also throws when it
            // was never asked; both are a silence of some kind, and the caller
            // decides whether it counts.
            asked = true
        }
        return if (asked) Verdict.Silent else Verdict.Unreachable
    }

    /**
     * True when the phone has a network of its own. Asked of the system rather
     * than guessed from a failure, so a dropped signal never spends a move.
     */
    private fun phoneIsOnline(context: Context?): Boolean = try {
        val manager = context?.getSystemService(android.net.ConnectivityManager::class.java)
        val network = manager?.activeNetwork ?: return true
        val caps = manager.getNetworkCapabilities(network) ?: return true
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        // Nothing to ask: do not let a system call decide the config's fate.
        true
    }

    /**
     * Another config of the group the running one belongs to: the quickest of
     * those whose latency is known, and none that was already taken in this
     * round. The app's own ways of reaching the internet are left out, since
     * taking one of them would change what the phone is doing, not only where.
     */
    private fun nextConfigAfter(guid: String): String? {
        val group = currentConfig?.subscriptionId.orEmpty()
        val guids = (if (group.isEmpty()) MmkvManager.decodeAllServerList() else MmkvManager.decodeServerList(group))
            .filter {
                it != FIREFOX_VPN_GUID &&
                    !FragmentVpn.isFragmentProfile(it) &&
                    !AetherEngine.isAetherProfile(MmkvManager.decodeServerConfig(it))
            }
        return Failover.next(guid, guids) { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L }
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        connectionTestScope.coroutineContext.cancelChildren()
        failoverScope.coroutineContext.cancelChildren()
        val service = getService() ?: return false

        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null

        if (isRunning()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        AetherEngine.stop()
        AppLog.info(AppLog.Section.Connection, "Service stopped")

        ServiceRuntimeState.markRunning(false)
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private fun reloadCore(): Boolean {
        if (isReloading) return false
        val service = getService() ?: return false
        if (!isRunning()) return false

        return try {
            val tunFd = currentVpnInterface

            isReloading = true
            connectionTestScope.coroutineContext.cancelChildren()
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

            coreController.stopLoop()
            // Allow Xray to release its local SOCKS port (10808) cleanly before rebinding.
            Thread.sleep(250L)

            val retryDelays = longArrayOf(0L, 1_200L, 2_000L, 3_000L)
            var lastError: Exception? = null
            for (delayMs in retryDelays) {
                if (serviceControl?.get() == null) break
                if (delayMs > 0L) Thread.sleep(delayMs)
                try {
                    launchCore(service, tunFd, isReload = true)
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    LogUtil.w(AppConfig.TAG, "StartCore-Manager: Reload attempt failed (${e.message}), retrying...")
                }
            }
            if (lastError != null) throw lastError

            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        } finally {
            isReloading = false
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay(requestId: String) {
        val service = getService() ?: return
        if (!isRunning() || isReloading) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            return
        }

        connectionTestScope.coroutineContext.cancelChildren()
        connectionTestScope.launch {
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":").orEmpty()
            }
            if (time == -1L) {
                ensureActive()
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
            }

            ensureActive()
            val endpoint = if (time >= 0) SpeedtestManager.getRemoteIPInfo() else null
            if (time >= 0) {
                AppLog.info(AppLog.Section.Test, "Connection test passed: $time ms")
            } else {
                AppLog.error(AppLog.Section.Test, "Connection test failed: ${errorStr.ifBlank { "no answer through the tunnel" }}")
            }
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isRunning()) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result, requestId)
                } else {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
                }
            }
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            // Not marked here: a reload shuts the old loop down after the new one started, and a
            // false write at that point would re-enable the startup animation for a live session.
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    measureV2rayDelay(intent.getStringExtra("content").orEmpty())
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
