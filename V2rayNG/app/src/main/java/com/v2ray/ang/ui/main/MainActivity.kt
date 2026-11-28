package com.v2ray.ang.ui.main

import com.v2ray.ang.util.ExternalLinks
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.handler.TuningRunner
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.FirefoxVpnStore
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.ServiceRuntimeState
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.ipscanner.IpScannerActivity
import com.v2ray.ang.ui.onboarding.LanguageOnboarding
import com.v2ray.ang.ui.onboarding.WelcomeMessage
import com.v2ray.ang.ui.onboarding.WelcomeMessageOverlay
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val gamingModeViewModel: GamingModeViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)

            if (restartService) LauncherManager.restartService(this)
        }

    private val publicConfigsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            val keep = result.data?.getIntExtra(com.v2ray.ang.ui.publicconfigs.PublicConfigsActivity.EXTRA_KEEP, 0) ?: 0
            val autoConnect = result.data?.getBooleanExtra(
                com.v2ray.ang.ui.publicconfigs.PublicConfigsActivity.EXTRA_AUTO_CONNECT,
                true,
            ) ?: true
            if (result.resultCode == RESULT_OK && keep > 0) {
                mainViewModel.testPublicConfigs(keep, connect = autoConnect, openTab = true)
            } else if (refreshGroups) {
                mainViewModel.onAction(MainAction.RefreshGroups)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Persian unless the user already picked something; no question is asked.
        LanguageOnboarding.applyDefaultOnce()
        mainViewModel.onAction(MainAction.Initialize)
        // Official releases only; shows the red dot and the update notification when one is out.
        lifecycleScope.launch { UpdateNotifier.checkOnAppOpen(applicationContext) }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onResume() {
        super.onResume()
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            mainViewModel.onAction(MainAction.RefreshGroups)
        }
    }

    @Composable
    override fun ScreenContent() {
        val context = LocalContext.current
        // Dismissed after the first cold start; rememberSaveable keeps it dismissed on recreation.
        // A session that is already tunnelling has nothing to introduce, so reopening the app goes
        // straight to the home screen instead of replaying the brand animation.
        var showSplash by rememberSaveable {
            mutableStateOf(!ServiceRuntimeState.isRunning(context.applicationContext))
        }
        // The maker's greeting: once, the first time the home screen is shown.
        var showWelcome by rememberSaveable { mutableStateOf(WelcomeMessage.shouldShow()) }
        // The home screen stays hidden under the intro and scales in when the intro starts leaving.
        // Without an intro the target is already 1, so nothing animates.
        var revealContent by rememberSaveable { mutableStateOf(!showSplash) }
        val reveal by animateFloatAsState(
            targetValue = if (revealContent) 1f else 0f,
            animationSpec = tween(
                durationMillis = MainSplashTimeline.REVEAL_MS,
                delayMillis = MainSplashTimeline.REVEAL_DELAY_MS,
            ),
            label = "mainReveal",
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = MainSplashTimeline.revealScale(reveal)
                        alpha = reveal
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                MainContent()
            }
            if (showSplash) {
                MainSplashOverlay(
                    onLeaving = { revealContent = true },
                    onFinished = {
                        revealContent = true
                        showSplash = false
                    },
                )
            }
            // Waits for the intro to leave, then rises over the home screen.
            if (showWelcome && !showSplash) {
                WelcomeMessageOverlay(
                    onDismiss = {
                        WelcomeMessage.markShown()
                        showWelcome = false
                    }
                )
            }
        }
    }

    /**
     * The main screen itself, kept in its own composable so its callbacks keep this activity as
     * their receiver while they sit inside the splash overlay's [Box].
     */
    @Composable
    private fun MainContent() {
        BackHandler { moveTaskToBack(false) }
        MainScreen(
            mainViewModel = mainViewModel,
            gamingModeViewModel = gamingModeViewModel,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    is MainAction.OpenSubscriptionLink -> openSubscriptionLink(action.url)
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun openSubscriptionLink(url: String) {
        // Links come from a remote panel, so only http(s) and tg:// are ever handed to ACTION_VIEW.
        val safeUrl = SubscriptionInfoParser.sanitizeLink(url) ?: return
        ExternalLinks.open(this, safeUrl)
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.PublicConfigs -> {
                publicConfigsLauncher.launch(Intent(this, com.v2ray.ang.ui.publicconfigs.PublicConfigsActivity::class.java))
                return
            }
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            // Shown as a card by the main screen; there is no activity to open.
            MainDestination.GamingMode -> return
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.XboxDns -> Intent(this, com.v2ray.ang.ui.xboxdns.XboxDnsActivity::class.java)
            MainDestination.FirefoxVpn -> Intent(this, com.v2ray.ang.ui.firefoxvpn.FirefoxVpnActivity::class.java)
            MainDestination.IpScanner -> Intent(this, IpScannerActivity::class.java)
            MainDestination.Log -> Intent(this, com.v2ray.ang.ui.applog.AppLogActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        val state = mainViewModel.uiState.value
        when {
            state.isRunning -> {
                // Said out loud, so nothing is taken in its place: this stop was asked for.
                mainViewModel.onStopRequested()
                LauncherManager.stopService(this)
            }
            // A tap while still connecting cancels the attempt.
            state.connectPhase == ConnectPhase.Connecting -> {
                mainViewModel.onStopRequested()
                // Cancelling also stops the search for the best settings, if one is running.
                tuningJob?.cancel()
                tuningJob = null
                mainViewModel.setTuning(TuningRunner.Phase.Idle)
                mainViewModel.onConnectCancelled()
                LauncherManager.stopService(this)
            }

            else -> requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        if (openFirefoxVpnScreenIfRelayMissing()) return
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    /** A stale loopback Firefox profile cannot be started like a saved proxy. */
    private fun openFirefoxVpnScreenIfRelayMissing(): Boolean {
        // Match the GUID LauncherManager will actually start, not a potentially stale UI snapshot.
        val selectedGuid = MmkvManager.getSelectServer()
        val serviceRunning = ServiceRuntimeState.isRunning(this)
        val tunnelPort = FirefoxVpnStore.loadTunnelPort(this)
        if (!MainConnectionPolicy.shouldOpenFirefoxVpnScreen(selectedGuid, serviceRunning, tunnelPort)) {
            return false
        }

        AppLog.info(
            AppLog.Section.Connection,
            "Firefox VPN: the main connect action opened the Firefox screen because its relay is no longer running"
        )
        com.v2ray.ang.ui.firefoxvpn.FirefoxVpnActivity.start(this)
        return true
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (openFirefoxVpnScreenIfRelayMissing()) return
        val state = mainViewModel.uiState.value
        if (!MainConnectionPolicy.canConnect(
                state.isRunning, state.selectedGuid, state.routingMode, state.routedAppCount
            )
        ) {
            toast(
                if (state.selectedGuid.isNullOrEmpty()) R.string.title_file_chooser
                else R.string.rx_select_apps_hint
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        // Every config is measured on this line before it is used: which mux and
        // fragment settings are fastest depends on the network, and the answer
        // found for one config says nothing about the next. This used to be
        // skipped while gaming, which is why a config could be played through
        // carrying settings that lost packets; it now runs there too, and the
        // one combination that was found for this config last time is tried
        // first so the search is two attempts rather than five.
        if (mainViewModel.needsTuning(state.selectedGuid)) {
            tuneThenConnect(state.selectedGuid.orEmpty())
            return
        }
        connectNow()
    }

    private fun connectNow() {
        mainViewModel.onConnectRequested()
        if (!LauncherManager.startService(this, quiet = true)) {
            mainViewModel.onConnectFailed()
        }
    }

    /**
     * Looks for the fastest mux and fragment settings, then connects. The screen
     * says what is happening the whole time, because this takes longer than a
     * plain connect and the user asked to be told.
     */
    private fun tuneThenConnect(guid: String) {
        tuningJob?.cancel()
        tuningJob = lifecycleScope.launch {
            val mode = com.v2ray.ang.handler.Tuning.currentMode()
            val result = TuningRunner.tune(this@MainActivity, guid, mode) { phase ->
                mainViewModel.setTuning(phase, mode)
            }
            if (!result.bypassed) {
                // Neither plain nor fragment/mux bypassed the block: keep it stopped.
                LauncherManager.stopService(this@MainActivity)
                mainViewModel.onConnectFailed(canMove = false)
                AppLog.error(AppLog.Section.Connection, "Tuning: could not bypass -1ms config; stopped")
                return@launch
            }
            // The search left the tunnel down and the best setting applied.
            mainViewModel.onConnectRequested()
            if (!LauncherManager.startService(this@MainActivity, quiet = true)) {
                mainViewModel.onConnectFailed()
            }
            AppLog.info(AppLog.Section.Connection, "Tuning: connecting with ${result.choice.key}")
        }
    }

    private var tuningJob: kotlinx.coroutines.Job? = null

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text, askSubName = true))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
