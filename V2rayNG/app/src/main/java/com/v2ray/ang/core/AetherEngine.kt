package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.AppLog.Section
import com.v2ray.ang.handler.MmkvManager
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
 * through [ROUTES] (up to [ROUNDS] times) until one works and remembers the winner for that network.
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
     * changes one thing at a time: Encrypted Client Hello with its key fetched from several
     * different places (the key changes often, so it is always looked up fresh; when one
     * resolver is blocked another may not be), TCP with a split handshake for networks that
     * drop QUIC, WireGuard with rising disguise, IPv6, and last the two-hop modes.
     */
    val ROUTES = listOf(
        Route("MASQUE", listOf("--masque", "--quic", "--turbo", "-4"), 45),
        Route("MASQUE + ECH", listOf("--masque", "--quic", "--balanced", "-4", "--ech", "auto"), 35),
        Route(
            "MASQUE + ECH (Google DoH)",
            listOf("--masque", "--quic", "--balanced", "-4", "--ech", "auto", "--ech-dns", "https://dns.google/dns-query"),
            35,
        ),
        Route(
            "MASQUE + ECH (Quad9 DoH)",
            listOf("--masque", "--quic", "--balanced", "-4", "--ech", "auto", "--ech-dns", "https://dns.quad9.net/dns-query"),
            35,
        ),
        Route(
            "MASQUE + ECH (8.8.8.8)",
            listOf(
                "--masque", "--quic", "--balanced", "-4", "--ech", "auto",
                "--ech-dns", "udp://8.8.8.8", "--ech-domain", "crypto.cloudflare.com",
            ),
            35,
        ),
        Route("MASQUE HTTP/2", listOf("--masque", "--http2", "--fragment", "--balanced", "-4"), 35),
        Route("MASQUE HTTP/2 + ECH", listOf("--masque", "--http2", "--fragment", "--balanced", "-4", "--ech", "auto"), 35),
        Route("WireGuard", listOf("--wg", "--balanced", "-4", "--noize", "balanced"), 35),
        Route("WireGuard GFW", listOf("--wg", "--thorough", "-4", "--noize", "gfw"), 50),
        Route("MASQUE IPv4+IPv6", listOf("--masque", "--quic", "--balanced", "--dual"), 35),
        Route("MASQUE IPv6", listOf("--masque", "--quic", "--balanced", "-6"), 30),
        Route("WireGuard IPv6", listOf("--wg", "--balanced", "-6", "--noize", "balanced"), 30),
        Route("Gool", listOf("--gool", "--balanced", "-4"), 50),
        Route("MASQUE x2", listOf("--mim", "--balanced", "-4"), 50),
    )

    /** Every route is tried this many times over before the network is declared closed. */
    const val ROUNDS = 2

    /** Longest the whole search can take, plus slack; the screen waits this long. */
    val MAX_SEARCH_MILLIS: Long = (ROUTES.sumOf { it.waitSeconds } * ROUNDS + 60) * 1000L

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
    fun readStatus(context: Context, notBefore: Long = 0): Status? = try {
        // A status older than [notBefore] was left behind by an earlier run and says nothing
        // about this one.
        statusFile(context).takeIf { it.exists() && it.lastModified() >= notBefore }?.readText().let(::decodeStatus)
    } catch (e: Exception) {
        null
    }

    private fun writeStatus(context: Context, status: Status) {
        try {
            statusFile(context).writeText(encodeStatus(status))
        } catch (e: Exception) {
            AppLog.error(Section.Aether, "Status could not be written: ${e.message}")
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
            AppLog.error(Section.Aether, "The engine is not included in this build or needs Android 8+")
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
                val total = order.size * ROUNDS
                AppLog.info(Section.Aether, "Looking for a route on network '$network' (remembered route: ${winner?.let { ROUTES.getOrNull(it)?.label } ?: "none"})")
                var served = false
                search@ for (round in 0 until ROUNDS) {
                    if (round > 0) AppLog.info(Section.Aether, "No route worked; trying all of them once more")
                    for ((position, index) in order.withIndex()) {
                        if (stopped()) return
                        val route = ROUTES[index]
                        val step = round * order.size + position + 1
                        writeStatus(context, Status(Phase.Searching, step, total, route.label))
                        AppLog.info(Section.Aether, "Route $step of $total: ${route.label}")
                        AppLog.debug(Section.Aether, "Engine options: ${route.args.joinToString(" ")}")
                        if (!launch(context, route)) continue
                        if (waitForPort(route)) {
                            AppLog.info(Section.Aether, "Connected with ${route.label}")
                            try {
                                winnerFile(context, network).writeText(index.toString())
                            } catch (_: Exception) {
                            }
                            writeStatus(context, Status(Phase.Ready, step, total, route.label))
                            served = true
                            // Stay here while the engine lives; it reconnects by itself after a drop.
                            process?.waitFor()
                            if (stopped()) return
                            AppLog.error(Section.Aether, "Engine stopped while connected (${lastOutput()}); searching again")
                            break@search
                        }
                        killProcess()
                    }
                }
                if (!served) {
                    AppLog.error(Section.Aether, "No route worked on this network after $ROUNDS rounds")
                    writeStatus(context, Status(Phase.Failed, total, total, ""))
                    return
                }
            }
        } catch (_: InterruptedException) {
            // Stopped on purpose.
        } catch (e: Exception) {
            AppLog.error(Section.Aether, "Search stopped by an error: ${e.javaClass.simpleName}: ${e.message}")
            writeStatus(context, Status(Phase.Failed))
        }
    }

    private fun launch(context: Context, route: Route): Boolean = try {
        killProcess()
        synchronized(recentOutput) { recentOutput.clear() }
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
        // The output must be read, or the engine blocks once the pipe fills up.
        Thread({
            try {
                started.inputStream.bufferedReader().forEachLine { line ->
                    val clean = line.replace(ANSI_COLOURS, "").trim()
                    if (clean.isNotEmpty()) {
                        rememberOutput(clean)
                        AppLog.debug(Section.Aether, "engine: $clean")
                    }
                }
            } catch (_: Exception) {
            }
        }, "aether-log").apply { isDaemon = true }.start()
        true
    } catch (e: Exception) {
        AppLog.error(Section.Aether, "Engine could not be started: ${e.javaClass.simpleName}: ${e.message}")
        process = null
        false
    }

    private const val PROBE_TIMEOUT_MILLIS = 6000
    private val ANSI_COLOURS = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val recentOutput = ArrayDeque<String>()

    private fun rememberOutput(line: String) = synchronized(recentOutput) {
        recentOutput.addLast(line)
        while (recentOutput.size > 4) recentOutput.removeFirst()
    }

    /** The engine's last few lines: what it said right before it gave up. */
    private fun lastOutput(): String = synchronized(recentOutput) {
        recentOutput.joinToString(" / ").ifEmpty { "it printed nothing" }
    }

    /** True once the engine accepts connections; false when it exits or the time runs out. */
    private fun waitForPort(route: Route): Boolean {
        val deadline = System.nanoTime() + route.waitSeconds * 1_000_000_000L
        var portSeen = false
        var probes = 0
        while (System.nanoTime() < deadline) {
            if (stopped()) return false
            val running = process ?: return false
            if (!running.isRunning()) {
                // Give the reader thread a moment to deliver the engine's last words.
                Thread.sleep(200)
                val code = runCatching { running.exitValue() }.getOrNull()
                AppLog.error(Section.Aether, "${route.label}: engine quit by itself (code $code): ${lastOutput()}")
                return false
            }
            // The engine opens its port the moment it starts, long before any tunnel is up, so
            // an open port proves nothing: only a real answer fetched through it does.
            if (isPortOpen()) {
                if (!portSeen) {
                    portSeen = true
                    AppLog.debug(Section.Aether, "${route.label}: engine is up, waiting for its tunnel")
                }
                val host = PROBE_HOSTS[probes++ % PROBE_HOSTS.size]
                if (carriesTraffic(host)) {
                    AppLog.info(Section.Aether, "${route.label}: a real request to $host went through")
                    return true
                }
            }
            Thread.sleep(700)
        }
        AppLog.error(Section.Aether, "${route.label}: no connection within ${route.waitSeconds}s (${lastOutput()})")
        return false
    }

    private val PROBE_HOSTS = listOf("cp.cloudflare.com", "www.gstatic.com")

    /**
     * True when a plain web request to [host] gets an HTTP answer through the engine's SOCKS5
     * port. The engine resolves the name itself, so this also proves its DNS works.
     */
    private fun carriesTraffic(host: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, PORT), 500)
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            if (input.read() != 5 || input.read() != 0) return false
            out.write(socksConnectRequest(host, 80))
            out.flush()
            val head = ByteArray(4)
            if (!readFully(input, head) || head[1].toInt() != 0) return false
            val rest = when (head[3].toInt()) {
                1 -> 4 + 2
                4 -> 16 + 2
                3 -> input.read() + 2
                else -> return false
            }
            if (!readFully(input, ByteArray(rest))) return false
            out.write("GET /generate_204 HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            val answer = ByteArray(5)
            readFully(input, answer) && String(answer, Charsets.US_ASCII) == "HTTP/"
        }
    } catch (e: Exception) {
        false
    }

    /** SOCKS5 "connect to this name and port" request. */
    internal fun socksConnectRequest(host: String, port: Int): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte())
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray): Boolean {
        var done = 0
        while (done < target.size) {
            val count = input.read(target, done, target.size - done)
            if (count < 0) return false
            done += count
        }
        return true
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
