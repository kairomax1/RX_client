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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The Aether engine (https://github.com/QW-AI-Code/Aether, AGPL-3.0-only): a separate program
 * shipped as libaether.so that builds its own free route through Cloudflare's network and
 * offers it as a SOCKS proxy on a local port. RX VPN does not link it; it starts it as a
 * process and points an ordinary SOCKS profile at [PORT], so everything else in the app
 * (routing, DNS mode, the tunnel) treats it like any other config.
 *
 * Which protocol, address family and disguise get through differs from network to network, and
 * finding out one after the other takes minutes. So several engines race, each on a private
 * port with one of the [ROUTES]; a route counts only once a real web request went through it
 * (the engine opens its port long before its tunnel is up). The quickest of those that made it
 * stays, the rest are stopped, and a small relay hands the connections arriving on [PORT] to
 * it. The winner and the routes that failed are remembered per network, so the next search
 * starts with what worked and leaves what did not for last.
 *
 * How fast the route is, and whether any gets through at all, is up to that free network.
 */
@android.annotation.SuppressLint("NewApi") // the engine only ever runs on Android 8+, see isAvailable
object AetherEngine {

    const val HOST = "127.0.0.1"
    const val PORT = 1819
    const val PROFILE_NAME = "Aether"
    private const val BINARY = "libaether.so"

    /** The engine is built for Android 8.0 and newer. */
    private const val MIN_SDK = Build.VERSION_CODES.O

    /**
     * One way of reaching the network: a label for the screen and the engine options for it.
     * [ech] routes also get an Encrypted Client Hello key, which the app looks up itself.
     */
    data class Route(val label: String, val args: List<String>, val ech: Boolean = false) {
        /** Needs an IPv6 address on the phone; pointless without one. */
        val ipv6Only: Boolean get() = "-6" in args
    }

    /**
     * Order for a network nothing is known about yet. The first [PARALLEL] race at once, so
     * they differ as much as possible: IPv6 and IPv4, QUIC, TCP and WireGuard.
     */
    val ROUTES = listOf(
        Route("MASQUE IPv6", listOf("--masque", "--quic", "--balanced", "-6")),
        Route("MASQUE", listOf("--masque", "--quic", "--turbo", "-4")),
        Route("WireGuard IPv6", listOf("--wg", "--balanced", "-6", "--noize", "balanced")),
        Route("MASQUE HTTP/2", listOf("--masque", "--http2", "--fragment", "--balanced", "-4")),
        Route("MASQUE + ECH", listOf("--masque", "--quic", "--balanced", "-4"), ech = true),
        Route("WireGuard", listOf("--wg", "--balanced", "-4", "--noize", "balanced")),
        Route("MASQUE IPv4+IPv6", listOf("--masque", "--quic", "--balanced", "--dual")),
        Route("MASQUE HTTP/2 + ECH", listOf("--masque", "--http2", "--fragment", "--balanced", "-4"), ech = true),
        Route("WireGuard GFW", listOf("--wg", "--thorough", "-4", "--noize", "gfw")),
        Route("Gool", listOf("--gool", "--balanced", "-4")),
        Route("MASQUE x2", listOf("--mim", "--balanced", "-4")),
    )

    /** Every route is tried this many times over before the network is declared closed. */
    const val ROUNDS = 2

    /** How many engines search at the same time. */
    const val PARALLEL = 4

    /** A route gets this long; one that visibly makes progress gets up to the second value. */
    private const val ROUTE_SECONDS = 40
    private const val ROUTE_MAX_SECONDS = 75

    /** The route remembered for this network searches alone for this long before the rest join. */
    private const val HEAD_START_MILLIS = 12_000L

    /** After the first route works, others get this long to work too and be compared. */
    private const val COMPARE_MILLIS = 2_500L

    /** Longest the whole search can take, plus slack; the screen waits this long. */
    val MAX_SEARCH_MILLIS: Long =
        ((ROUTES.size * ROUTE_MAX_SECONDS / PARALLEL + ROUTE_MAX_SECONDS) * ROUNDS + 90) * 1000L

    enum class Phase { Searching, Ready, Failed }

    /** What the engine is doing, written by the service process and read by the screen. */
    data class Status(val phase: Phase, val attempt: Int = 0, val total: Int = 0, val label: String = "")

    @Volatile
    private var supervisor: Thread? = null

    /** One running engine and what is known about it so far. */
    private class Candidate(val route: Route, val port: Int, val process: Process) {
        val startedAt = System.currentTimeMillis()
        @Volatile var progressAt = startedAt
        /** Milliseconds one web request took through it; set once it carries traffic. */
        @Volatile var answerMillis: Long = -1
        @Volatile var readyAfter: Long = -1
        @Volatile var abandoned = false
        private val recent = ArrayDeque<String>()

        fun remember(line: String) = synchronized(recent) {
            recent.addLast(line)
            while (recent.size > 3) recent.removeFirst()
        }

        /** The engine's last few lines: what it said right before it gave up. */
        fun lastWords(): String = synchronized(recent) { recent.joinToString(" / ").ifEmpty { "it printed nothing" } }

        val ready: Boolean get() = answerMillis >= 0
    }

    private val candidates = ConcurrentHashMap.newKeySet<Candidate>()

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

    /**
     * The order to try routes in: the one that last worked on this network first, then the
     * usual order with the routes that failed here moved to the back (most failures last).
     * IPv6-only routes are left out on a phone without an IPv6 address.
     */
    internal fun planOrder(
        routes: List<Route>,
        winner: String?,
        failures: Map<String, Int>,
        hasIpv6: Boolean,
    ): List<Route> {
        val usable = routes.filter { hasIpv6 || !it.ipv6Only }
        val first = usable.firstOrNull { it.label == winner }
        val rest = usable.filter { it !== first }.sortedBy { failures[it.label] ?: 0 }
        return listOfNotNull(first) + rest
    }

    internal fun encodeFailures(failures: Map<String, Int>): String =
        failures.filterValues { it > 0 }.entries.joinToString("\n") { "${it.key}=${it.value}" }

    internal fun decodeFailures(text: String?): Map<String, Int> =
        text.orEmpty().lines().mapNotNull { line ->
            val at = line.lastIndexOf('=')
            val count = if (at > 0) line.substring(at + 1).trim().toIntOrNull() else null
            if (count != null && count > 0) line.substring(0, at) to count else null
        }.toMap()

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
        writeStatus(appContext, Status(Phase.Searching, 0, ROUTES.size * ROUNDS, ""))
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
        Relay.close()
        candidates.toList().forEach(::kill)
    }

    private fun stopped(): Boolean = Thread.currentThread() != supervisor

    private fun supervise(context: Context) {
        try {
            if (!Relay.open()) {
                AppLog.error(Section.Aether, "Port $PORT is taken by something else; Aether cannot serve")
                writeStatus(context, Status(Phase.Failed))
                return
            }
            ensureIdentities(context)
            while (!stopped()) {
                val winner = search(context)
                if (stopped()) return
                if (winner == null) {
                    AppLog.error(Section.Aether, "No route worked on this network after $ROUNDS rounds")
                    writeStatus(context, Status(Phase.Failed))
                    return
                }
                // Stay here while the engine lives; it reconnects by itself after a drop.
                winner.process.waitFor()
                Relay.target = 0
                candidates.remove(winner)
                if (stopped()) return
                AppLog.error(Section.Aether, "Engine stopped while connected (${winner.lastWords()}); searching again")
            }
        } catch (_: InterruptedException) {
            // Stopped on purpose.
        } catch (e: Exception) {
            AppLog.error(Section.Aether, "Search stopped by an error: ${e.javaClass.simpleName}: ${e.message}")
            writeStatus(context, Status(Phase.Failed))
        } finally {
            if (!stopped()) {
                Relay.close()
                candidates.toList().forEach(::kill)
            }
        }
    }

    /**
     * Races the routes until one carries traffic; null when none did in [ROUNDS] rounds or the
     * search was stopped. The returned engine keeps running and the relay points at it.
     */
    private fun search(context: Context): Candidate? {
        val began = System.currentTimeMillis()
        val network = networkKey(context)
        val remembered = readText(winnerFile(context, network))?.trim()
        val failures = decodeFailures(readText(failuresFile(context, network))).toMutableMap()
        val ipv6 = hasGlobalIpv6()
        val order = planOrder(ROUTES, remembered, failures, ipv6)
        val headStart = order.firstOrNull()?.label == remembered
        val total = order.size * ROUNDS
        AppLog.info(
            Section.Aether,
            "Looking for a route on network '$network' (IPv6 on this phone: ${if (ipv6) "yes" else "no"}; " +
                "remembered route: ${if (headStart) remembered else "none"}; failed here before: " +
                "${failures.keys.joinToString().ifEmpty { "none" }})",
        )
        // Looked up meanwhile; the routes that need it come later in the order.
        val echKey = EchKey.lookUpInBackground()
        var launched = 0
        // Only this search's engines are cleaned up at its end: after a quick stop and start
        // another search may already be racing its own.
        val mine = ArrayList<Candidate>()
        var chosen: Candidate? = null
        try {
            for (round in 0 until ROUNDS) {
                if (round > 0) AppLog.info(Section.Aether, "No route worked; trying all of them once more")
                val queue = ArrayDeque(order)
                val running = ArrayList<Candidate>()
                var firstReadyAt = 0L
                while (queue.isNotEmpty() || running.isNotEmpty()) {
                    if (stopped()) return null
                    val now = System.currentTimeMillis()
                    // Routes that quit or ran out of time leave the race.
                    val each = running.iterator()
                    while (each.hasNext()) {
                        val candidate = each.next()
                        if (candidate.ready) continue
                        val reason = whyOver(candidate, now) ?: continue
                        AppLog.error(Section.Aether, "${candidate.route.label}: $reason (${candidate.lastWords()})")
                        failures[candidate.route.label] = minOf((failures[candidate.route.label] ?: 0) + 1, 9)
                        kill(candidate)
                        each.remove()
                    }
                    val ready = running.filter { it.ready }
                    if (ready.isNotEmpty()) {
                        if (firstReadyAt == 0L) firstReadyAt = now
                        if (now - firstReadyAt >= COMPARE_MILLIS || ready.size == running.size) {
                            val best = ready.minByOrNull { it.answerMillis }!!
                            if (stopped()) return null
                            chosen = best
                            Relay.target = best.port
                            failures.remove(best.route.label)
                            writeText(winnerFile(context, network), best.route.label)
                            writeText(failuresFile(context, network), encodeFailures(failures))
                            AppLog.info(
                                Section.Aether,
                                "Connected with ${best.route.label} after ${(now - began) / 1000}s" +
                                    (if (ready.size > 1) "; quickest of ${ready.joinToString { "${it.route.label} ${it.answerMillis}ms" }}" else " (answer in ${best.answerMillis}ms)"),
                            )
                            writeStatus(context, Status(Phase.Ready, launched, total, best.route.label))
                            return best
                        }
                    } else {
                        // The remembered route searches alone at first: it usually just works.
                        val alone = round == 0 && headStart && now - began < HEAD_START_MILLIS &&
                            (launched == 0 || running.isNotEmpty())
                        val limit = if (alone) 1 else PARALLEL
                        while (running.size < limit && queue.isNotEmpty()) {
                            val route = queue.removeFirst()
                            val key = if (route.ech) echKey.await() else null
                            if (route.ech && key == null) {
                                AppLog.error(Section.Aether, "${route.label}: skipped, no ECH key could be fetched on this network")
                                continue
                            }
                            launched++
                            writeStatus(context, Status(Phase.Searching, launched, total, route.label))
                            AppLog.info(Section.Aether, "Route $launched of $total: ${route.label}")
                            launch(context, route, key)?.let {
                                running.add(it)
                                mine.add(it)
                            }
                        }
                    }
                    Thread.sleep(200)
                }
                writeText(failuresFile(context, network), encodeFailures(failures))
            }
            return null
        } finally {
            // Whatever is still racing when the search ends without it must not live on.
            mine.filter { it !== chosen }.forEach(::kill)
        }
    }

    /** Null while the candidate may go on; otherwise why its turn is over. */
    private fun whyOver(candidate: Candidate, now: Long): String? {
        if (!candidate.process.isRunning()) {
            val code = runCatching { candidate.process.exitValue() }.getOrNull()
            return "engine quit by itself (code $code)"
        }
        val age = now - candidate.startedAt
        return when {
            age < ROUTE_SECONDS * 1000L -> null
            age >= ROUTE_MAX_SECONDS * 1000L -> "no connection within ${ROUTE_MAX_SECONDS}s"
            isProgressing(age, now - candidate.progressAt) -> null
            else -> "no connection within ${age / 1000}s and no progress"
        }
    }

    /** Past its normal time a route may go on only while the engine reported progress lately. */
    internal fun isProgressing(ageMillis: Long, sinceProgressMillis: Long): Boolean =
        ageMillis < ROUTE_MAX_SECONDS * 1000L && sinceProgressMillis < 10_000L

    private val PROGRESS = Regex("candidate ok|selecting best|validated|handshake complete|established|connected", RegexOption.IGNORE_CASE)
    /** The engine's own date in front of each line; the log has its own clock. */
    private val ENGINE_TIME = Regex("^\\[\\d{4}-\\S+\\s+")
    private val ANSI_COLOURS = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    /**
     * Makes the engine's identities once, alone, before several engines start at the same time
     * and would each try to register one. An identity that exists is kept by the engine.
     */
    private fun ensureIdentities(context: Context) {
        val marker = File(workDir(context), "rx-identities")
        if (marker.exists()) return
        var allMade = true
        for (which in listOf("masque", "wg")) {
            if (stopped()) return
            val process = try {
                engineProcess(context, listOf("--register", which))
            } catch (e: Exception) {
                AppLog.error(Section.Aether, "Engine could not be started: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
            val output = StringBuffer()
            Thread({
                runCatching { process.inputStream.bufferedReader().forEachLine { output.append(it.replace(ANSI_COLOURS, "").trim()).append(" / ") } }
            }, "aether-log").apply { isDaemon = true }.start()
            val finished = try {
                process.waitFor(20, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                throw e
            }
            if (finished && process.exitValue() == 0) {
                AppLog.info(Section.Aether, "Identity '$which' is ready")
            } else {
                allMade = false
                process.destroyForcibly()
                AppLog.error(Section.Aether, "Identity '$which' could not be made yet: ${output.toString().takeLast(300)}")
            }
        }
        if (allMade) writeText(marker, "1")
    }

    private fun engineProcess(context: Context, args: List<String>): Process {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        // Persistent, so the engine keeps its identity instead of registering a new one each time.
        val dir = workDir(context)
        val builder = ProcessBuilder(listOf(binary(context).absolutePath) + args).directory(dir).redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", dir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            // An executable does not get the app's library search path; its C++ runtime sits
            // next to it.
            put("LD_LIBRARY_PATH", listOfNotNull(nativeDir, get("LD_LIBRARY_PATH")?.takeIf { it.isNotBlank() }).joinToString(":"))
        }
        return builder.start()
    }

    private fun launch(context: Context, route: Route, echKey: String?): Candidate? = try {
        val port = freePort()
        val args = listOf("--bind", "$HOST:$port", "--quick-reconnect") + route.args +
            (if (echKey != null) listOf("--ech", echKey) else emptyList())
        AppLog.debug(Section.Aether, "${route.label}: engine options ${(route.args + if (echKey != null) listOf("--ech", "<key>") else emptyList()).joinToString(" ")} on port $port")
        val candidate = Candidate(route, port, engineProcess(context, args))
        candidates.add(candidate)
        if (stopped()) {
            // Stopped while the engine was being launched: it must not be left running.
            kill(candidate)
            null
        } else {
            // The output must be read, or the engine blocks once the pipe fills up.
            Thread({
                try {
                    candidate.process.inputStream.bufferedReader().forEachLine { line ->
                        val clean = line.replace(ANSI_COLOURS, "").trim().replace(ENGINE_TIME, "")
                        if (clean.isNotEmpty()) {
                            candidate.remember(clean)
                            if (PROGRESS.containsMatchIn(clean)) candidate.progressAt = System.currentTimeMillis()
                            AppLog.debug(Section.Aether, "${route.label} engine: $clean")
                        }
                    }
                } catch (_: Exception) {
                }
            }, "aether-log").apply { isDaemon = true }.start()
            Thread({ watch(candidate) }, "aether-probe").apply { isDaemon = true }.start()
            candidate
        }
    } catch (e: Exception) {
        AppLog.error(Section.Aether, "${route.label}: engine could not be started: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private val PROBE_HOSTS = listOf("cp.cloudflare.com", "www.gstatic.com")
    private const val PROBE_TIMEOUT_MILLIS = 6000

    /**
     * Keeps asking for a web page through the candidate until one arrives. The engine opens
     * its port the moment it starts, long before any tunnel is up, so an open port proves
     * nothing: only a real answer fetched through it does.
     */
    private fun watch(candidate: Candidate) {
        var probes = 0
        try {
            while (!candidate.abandoned && candidate.process.isRunning()) {
                val host = PROBE_HOSTS[probes++ % PROBE_HOSTS.size]
                if (carriesTraffic(candidate.port, host) >= 0) {
                    candidate.readyAfter = System.currentTimeMillis() - candidate.startedAt
                    // The first answer includes the wait for the tunnel; time a second one.
                    val again = carriesTraffic(candidate.port, host)
                    AppLog.info(
                        Section.Aether,
                        "${candidate.route.label}: a real request to $host went through after ${candidate.readyAfter / 1000}s",
                    )
                    candidate.answerMillis = if (again >= 0) again else PROBE_TIMEOUT_MILLIS.toLong()
                    return
                }
                Thread.sleep(300)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Milliseconds a plain web request to [host] took through the SOCKS5 port [port]; -1 when
     * no HTTP answer came. The engine resolves the name itself, so this also proves its DNS.
     */
    private fun carriesTraffic(port: Int, host: String): Long = try {
        val began = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, port), 500)
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            if (input.read() != 5 || input.read() != 0) return -1
            out.write(socksConnectRequest(host, 80))
            out.flush()
            val head = ByteArray(4)
            if (!readFully(input, head) || head[1].toInt() != 0) return -1
            val rest = when (head[3].toInt()) {
                1 -> 4 + 2
                4 -> 16 + 2
                3 -> input.read() + 2
                else -> return -1
            }
            if (!readFully(input, ByteArray(rest))) return -1
            out.write("GET /generate_204 HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            val answer = ByteArray(5)
            if (readFully(input, answer) && String(answer, Charsets.US_ASCII) == "HTTP/") {
                (System.nanoTime() - began) / 1_000_000
            } else {
                -1
            }
        }
    } catch (e: Exception) {
        -1
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

    private var nextPort = 0

    /** A local port nothing listens on, for one engine of the race. */
    private fun freePort(): Int {
        repeat(60) {
            val port = PORT + 11 + (nextPort++ % 60)
            try {
                ServerSocket(port, 1, InetAddress.getByName(HOST)).close()
                return port
            } catch (_: Exception) {
            }
        }
        throw IllegalStateException("no free local port")
    }

    private fun kill(candidate: Candidate) {
        candidate.abandoned = true
        candidates.remove(candidate)
        try {
            candidate.process.destroy()
            if (!candidate.process.waitFor(1, TimeUnit.SECONDS)) candidate.process.destroyForcibly()
        } catch (_: Exception) {
            runCatching { candidate.process.destroyForcibly() }
        }
    }

    private fun failuresFile(context: Context, network: String) = File(workDir(context), "rx-failed-$network")

    private fun readText(file: File): String? = try {
        file.takeIf { it.exists() }?.readText()
    } catch (e: Exception) {
        null
    }

    private fun writeText(file: File, text: String) {
        try {
            file.writeText(text)
        } catch (_: Exception) {
        }
    }

    /** True when the phone has a public IPv6 address on a real network (not the VPN's own). */
    private fun hasGlobalIpv6(): Boolean = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.asSequence() }
            .any { it is Inet6Address && isGlobalIpv6(it.address) }
    } catch (e: Exception) {
        // Not knowing is no reason to leave the IPv6 routes out.
        true
    }

    /** Public IPv6 space is 2000::/3; link-local, private and loopback addresses lie outside. */
    internal fun isGlobalIpv6(address: ByteArray): Boolean =
        address.size == 16 && (address[0].toInt() and 0xE0) == 0x20

    /**
     * Hands every connection that arrives on [PORT] to the engine that won the race, which
     * listens on a private port. UDP needs nothing: the engine names its own UDP port in the
     * SOCKS answer and the client talks to that directly.
     */
    private object Relay {
        /** Port of the winning engine; 0 while there is none. */
        @Volatile var target = 0
        @Volatile private var server: ServerSocket? = null
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val pool = Executors.newCachedThreadPool { task -> Thread(task, "aether-relay").apply { isDaemon = true } }

        fun open(): Boolean {
            close()
            val listening = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(HOST), PORT), 128)
                }
            } catch (e: Exception) {
                return false
            }
            server = listening
            Thread({
                while (!listening.isClosed) {
                    val client = try {
                        listening.accept()
                    } catch (e: Exception) {
                        break
                    }
                    pool.execute { serve(client) }
                }
            }, "aether-relay-accept").apply { isDaemon = true }.start()
            return true
        }

        fun close() {
            target = 0
            runCatching { server?.close() }
            server = null
            sockets.toList().forEach { runCatching { it.close() } }
            sockets.clear()
        }

        private fun serve(client: Socket) {
            val port = target
            if (port == 0) {
                runCatching { client.close() }
                return
            }
            val engine = Socket()
            sockets.add(client)
            sockets.add(engine)
            try {
                engine.connect(InetSocketAddress(HOST, port), 2000)
                client.tcpNoDelay = true
                engine.tcpNoDelay = true
                val back = pool.submit(Runnable { pump(engine, client) })
                pump(client, engine)
                runCatching { back.get() }
            } catch (_: Exception) {
            } finally {
                runCatching { client.close() }
                runCatching { engine.close() }
                sockets.remove(client)
                sockets.remove(engine)
            }
        }

        /** Copies until [from] ends, then tells [to] that nothing more comes from this side. */
        private fun pump(from: Socket, to: Socket) {
            try {
                val buffer = ByteArray(32 * 1024)
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                to.shutdownOutput()
            } catch (_: Exception) {
                // One side broke: nothing more can arrive either way.
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }
    }

    /**
     * The Encrypted Client Hello key of Cloudflare, which hides the server name from the
     * operator. It is published in DNS and changes often. Left to itself the engine asks one
     * resolver and gives up when that is blocked, so the app asks many resolvers at once for
     * two names and takes the first sound answer.
     */
    internal object EchKey {
        private val RESOLVERS = listOf("8.8.8.8", "1.1.1.1", "9.9.9.9", "8.8.4.4", "1.0.0.1", "208.67.222.222", "4.2.2.4", "94.140.14.14")
        private val NAMES = listOf("crypto.cloudflare.com", "cloudflare-ech.com")
        private const val TIMEOUT_MILLIS = 2500

        class Pending(private val found: AtomicReference<String?>, private val done: CountDownLatch) {
            /** The key in base64, or null when no resolver gave one in time. */
            fun await(): String? {
                done.await(TIMEOUT_MILLIS + 500L, TimeUnit.MILLISECONDS)
                return found.get()
            }
        }

        fun lookUpInBackground(): Pending {
            val found = AtomicReference<String?>(null)
            val done = CountDownLatch(1)
            val left = java.util.concurrent.atomic.AtomicInteger(RESOLVERS.size * NAMES.size)
            for (resolver in RESOLVERS) for (name in NAMES) {
                Thread({
                    val key = runCatching { ask(resolver, name) }.getOrNull()
                    if (key != null && found.compareAndSet(null, android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))) {
                        AppLog.info(Section.Aether, "ECH key fetched for $name from $resolver (${key.size} bytes)")
                        done.countDown()
                    }
                    if (left.decrementAndGet() == 0) done.countDown()
                }, "aether-ech").apply { isDaemon = true }.start()
            }
            return Pending(found, done)
        }

        private fun ask(resolver: String, name: String): ByteArray? = DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            val query = httpsQuery(name, 0x5258)
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(resolver), 53))
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            echFromAnswer(buffer.copyOf(packet.length))
        }

        /** DNS question for the HTTPS record (type 65) of [name]. */
        internal fun httpsQuery(name: String, id: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            name.trim('.').split('.').forEach { label ->
                out.write(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
            out.write(byteArrayOf(0, 0, 65, 0, 1))
            return out.toByteArray()
        }

        /**
         * The ECH key list inside a DNS answer, or null when it holds none or is malformed
         * (operators forge answers; a forged one does not carry a well-formed key list).
         */
        internal fun echFromAnswer(message: ByteArray): ByteArray? = try {
            fun u16(at: Int) = ((message[at].toInt() and 0xFF) shl 8) or (message[at + 1].toInt() and 0xFF)
            fun skipName(start: Int): Int {
                var at = start
                while (true) {
                    val length = message[at].toInt() and 0xFF
                    if (length == 0) return at + 1
                    if ((length and 0xC0) == 0xC0) return at + 2
                    at += 1 + length
                }
            }
            var at = 12
            repeat(u16(4)) { at = skipName(at) + 4 }
            var key: ByteArray? = null
            repeat(u16(6)) {
                at = skipName(at)
                val type = u16(at)
                val length = u16(at + 8)
                val data = at + 10
                at = data + length
                if (type == 65 && key == null && u16(data) != 0) {
                    var cursor = skipName(data + 2)
                    while (cursor + 4 <= at) {
                        val parameter = u16(cursor)
                        val size = u16(cursor + 2)
                        if (parameter == 5) {
                            val value = message.copyOfRange(cursor + 4, cursor + 4 + size)
                            if (isEchConfigList(value)) key = value
                        }
                        cursor += 4 + size
                    }
                }
            }
            key
        } catch (e: Exception) {
            null
        }

        /** A key list states its own length and starts with the ECH version 0xfe0d. */
        internal fun isEchConfigList(value: ByteArray): Boolean =
            value.size > 8 &&
                (((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)) == value.size - 2 &&
                (value[2].toInt() and 0xFF) == 0xFE && (value[3].toInt() and 0xFF) == 0x0D
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
