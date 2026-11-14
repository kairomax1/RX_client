package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The Aether engine (https://github.com/QW-AI-Code/Aether, AGPL-3.0-only): a separate program
 * shipped as libaether.so that builds its own free route through Cloudflare's network and
 * offers it as a SOCKS proxy on a local port. RX VPN does not link it; it starts it as a
 * process and points an ordinary SOCKS profile at that port, so everything else in the app
 * (routing, DNS mode, the tunnel) treats it like any other config.
 *
 * The engine opens its port only after a route has been proven to carry data. Which protocol,
 * address family and disguise get through differs from network to network, so the app walks
 * through [ROUTES] until one works and remembers the winner for that network.
 *
 * How fast the route is, and whether any gets through at all, is up to that free network.
 */
object AetherEngine {

    const val HOST = "127.0.0.1"
    const val PORT = 1819
    const val PROFILE_NAME = "Aether"
    private const val BINARY = "libaether.so"

    /** The engine is built for Android 8.0 and newer. */
    private const val MIN_SDK = Build.VERSION_CODES.O

    /** One way of reaching the network: a label for the screen and the engine options for it. */
    data class Route(val label: String, val args: List<String>, val waitSeconds: Int)

    /**
     * Tried in this order. Starts with what is fastest when it works (MASQUE over QUIC), then
     * changes one thing at a time: Encrypted Client Hello, TCP with a split handshake for
     * networks that drop QUIC, WireGuard with rising disguise, IPv6, and last the two-hop mode.
     */
    val ROUTES = listOf(
        Route("MASQUE", listOf("--masque", "--turbo", "-4"), 40),
        Route("MASQUE + ECH", listOf("--masque", "--balanced", "-4", "--ech", "auto"), 45),
        Route("MASQUE HTTP/2", listOf("--masque", "--balanced", "-4", "--http2", "--fragment"), 45),
        Route("WireGuard", listOf("--wg", "--balanced", "-4", "--noize", "balanced"), 45),
        Route("WireGuard GFW", listOf("--wg", "--thorough", "-4", "--noize", "gfw"), 60),
        Route("MASQUE IPv6", listOf("--masque", "--balanced", "-6"), 40),
        Route("WireGuard IPv6", listOf("--wg", "--balanced", "-6", "--noize", "balanced"), 40),
        Route("Gool", listOf("--gool", "--balanced", "-4"), 60),
    )

    /** Longest a full walk through [ROUTES] can take, plus slack; the screen waits this long. */
    val MAX_SEARCH_MILLIS: Long = (ROUTES.sumOf { it.waitSeconds } + 30) * 1000L

    enum class Phase { Searching, Ready, Failed }

    /** What the engine is doing, written by the service process and read by the screen. */
    data class Status(val phase: Phase, val attempt: Int = 0, val total: Int = 0, val label: String = "")

    @Volatile
    private var supervisor: Thread? = null

    @Volatile
    private var process: Process? = null

    private fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, BINARY)
    private fun workDir(context: Context) = File(context.filesDir, "aether").apply { mkdirs() }
    private fun statusFile(context: Context) = File(workDir(context), "rx-status")
    private fun winnerFile(context: Context, network: String) = File(workDir(context), "rx-winner-$network")

    /** True when this build carries an engine that can run on this phone. */
    fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= MIN_SDK && binary(context).exists()

    /** True for the profile that stands for the engine. */
    fun isAetherProfile(profile: ProfileItem?): Boolean = isAetherEndpoint(
        isSocks = profile?.configType == EConfigType.SOCKS,
        server = profile?.server,
        port = profile?.serverPort,
    )

    internal fun isAetherEndpoint(isSocks: Boolean, server: String?, port: String?): Boolean =
        isSocks && server == HOST && port == PORT.toString()

    /** Id of the Aether profile, creating it in the default group when it does not exist yet. */
    fun ensureProfile(): String {
        MmkvManager.decodeAllServerList().firstOrNull { isAetherProfile(MmkvManager.decodeServerConfig(it)) }
            ?.let { return it }
        val profile = ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = PROFILE_NAME
            server = HOST
            serverPort = PORT.toString()
        }
        return MmkvManager.encodeServerConfig("", profile)
    }

    /** The order to try the routes in: the one that last worked on this network first. */
    internal fun routeOrder(total: Int, winner: Int?): List<Int> {
        val all = (0 until total).toList()
        return if (winner != null && winner in all) listOf(winner) + (all - winner) else all
    }

    internal fun encodeStatus(status: Status): String =
        listOf(status.phase.name, status.attempt, status.total, status.label).joinToString("|")

    internal fun decodeStatus(text: String?): Status? {
        val parts = text?.trim()?.split('|') ?: return null
        if (parts.size < 4) return null
        val phase = Phase.entries.firstOrNull { it.name == parts[0] } ?: return null
        return Status(phase, parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0, parts[3])
    }

    /** Latest status; null when the engine has not been started. Safe to call from any process. */
    fun readStatus(context: Context): Status? = try {
        statusFile(context).takeIf { it.exists() }?.readText().let(::decodeStatus)
    } catch (e: Exception) {
        null
    }

    private fun writeStatus(context: Context, status: Status) {
        try {
            statusFile(context).writeText(encodeStatus(status))
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Aether: status could not be written: ${e.message}")
        }
    }

    /**
     * Starts the search for a working route in the background, unless it already runs. Returns
     * at once; [readStatus] tells how far it is.
     */
    @Synchronized
    fun start(context: Context) {
        if (supervisor?.isAlive == true) return
        val appContext = context.applicationContext
        if (!isAvailable(appContext)) {
            LogUtil.w(AppConfig.TAG, "Aether: engine is not included in this build")
            writeStatus(appContext, Status(Phase.Failed))
            return
        }
        writeStatus(appContext, Status(Phase.Searching, 0, ROUTES.size, ""))
        // Assigned before it starts: the thread recognises a stop by no longer being this one.
        val thread = Thread({ supervise(appContext) }, "aether-supervisor").apply { isDaemon = true }
        supervisor = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        val thread = supervisor
        supervisor = null
        thread?.interrupt()
        killProcess()
    }

    private fun stopped(): Boolean = Thread.currentThread() != supervisor

    private fun supervise(context: Context) {
        try {
            while (!stopped()) {
                val network = networkKey(context)
                val winner = try {
                    winnerFile(context, network).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
                } catch (e: Exception) {
                    null
                }
                val order = routeOrder(ROUTES.size, winner)
                LogUtil.w(AppConfig.TAG, "Aether: looking for a route on network '$network' (remembered: ${winner ?: "none"})")
                var served = false
                for ((position, index) in order.withIndex()) {
                    if (stopped()) return
                    val route = ROUTES[index]
                    writeStatus(context, Status(Phase.Searching, position + 1, order.size, route.label))
                    LogUtil.w(AppConfig.TAG, "Aether: trying ${position + 1}/${order.size}: ${route.label} ${route.args}")
                    if (!launch(context, route)) continue
                    if (waitForPort(route.waitSeconds)) {
                        LogUtil.w(AppConfig.TAG, "Aether: connected with ${route.label}")
                        try {
                            winnerFile(context, network).writeText(index.toString())
                        } catch (_: Exception) {
                        }
                        writeStatus(context, Status(Phase.Ready, position + 1, order.size, route.label))
                        served = true
                        // Stay here while the engine lives; it reconnects by itself after a drop.
                        process?.waitFor()
                        if (stopped()) return
                        LogUtil.w(AppConfig.TAG, "Aether: engine exited while connected; searching again")
                        break
                    }
                    LogUtil.w(AppConfig.TAG, "Aether: ${route.label} did not connect within ${route.waitSeconds}s")
                    killProcess()
                }
                if (!served) {
                    LogUtil.w(AppConfig.TAG, "Aether: no route worked on this network")
                    writeStatus(context, Status(Phase.Failed, order.size, order.size, ""))
                    return
                }
            }
        } catch (_: InterruptedException) {
            // Stopped on purpose.
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Aether: search stopped by an error", e)
            writeStatus(context, Status(Phase.Failed))
        }
    }

    private fun launch(context: Context, route: Route): Boolean = try {
        killProcess()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        // Persistent, so the engine keeps its identity instead of registering a new one each time.
        val dir = workDir(context)
        val command = listOf(binary(context).absolutePath, "--bind", "$HOST:$PORT", "--quick-reconnect") + route.args
        val builder = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", dir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            // An executable does not get the app's library search path; its C++ runtime sits
            // next to it.
            put("LD_LIBRARY_PATH", listOfNotNull(nativeDir, get("LD_LIBRARY_PATH")?.takeIf { it.isNotBlank() }).joinToString(":"))
        }
        val started = builder.start()
        synchronized(this) {
            // Stopped while the engine was being launched: it must not be left running.
            if (stopped()) {
                started.destroy()
                return false
            }
            process = started
        }
        // The output must be read, or the engine blocks once the pipe fills up. Logged as
        // warnings so it shows in the app's log screen at the default level.
        Thread({
            try {
                started.inputStream.bufferedReader().forEachLine { LogUtil.w(AppConfig.TAG, "Aether> $it") }
            } catch (_: Exception) {
            }
        }, "aether-log").apply { isDaemon = true }.start()
        true
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Aether: engine could not start (${e.message})", e)
        process = null
        false
    }

    /** True once the engine accepts connections; false when it exits or the time runs out. */
    private fun waitForPort(seconds: Int): Boolean {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            if (stopped()) return false
            val running = process ?: return false
            if (!running.isRunning()) {
                LogUtil.w(AppConfig.TAG, "Aether: engine exited with code ${runCatching { running.exitValue() }.getOrNull()}")
                return false
            }
            if (isPortOpen()) return true
            Thread.sleep(500)
        }
        return false
    }

    /** True when something accepts connections on the engine's local port. */
    fun isPortOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), 300) }
        true
    } catch (e: Exception) {
        false
    }

    private fun killProcess() {
        val running = process ?: return
        process = null
        try {
            running.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!running.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) running.destroyForcibly()
            }
        } catch (_: Exception) {
        }
    }

    /** "wifi", "cell-<operator>" or "other": routes that work differ between these. */
    private fun networkKey(context: Context): String = try {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.allNetworks
            .mapNotNull { connectivity.getNetworkCapabilities(it) }
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        when {
            capabilities.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> "wifi"
            capabilities.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> {
                val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                "cell-" + telephony?.networkOperator.orEmpty().filter { it.isLetterOrDigit() }
            }
            else -> "other"
        }
    } catch (e: Exception) {
        "other"
    }

    private fun Process.isRunning(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}
