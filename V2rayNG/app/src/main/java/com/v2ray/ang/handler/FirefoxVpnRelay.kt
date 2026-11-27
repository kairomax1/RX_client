package com.v2ray.ang.handler

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * A loopback HTTP proxy that Mozilla's Fastly proxy accepts.
 *
 * The core's own HTTP outbound can only present a username and password, while
 * the built-in VPN's proxy wants `Proxy-Authorization: Bearer <ProxyPass>`
 * over TLS. So the core is pointed at this relay on 127.0.0.1: the relay opens
 * one TLS connection to the proxy per tunnel, sends the CONNECT with the
 * Bearer header, and then copies bytes both ways. The target's name is passed
 * on untouched, so the name is resolved by the proxy, not by this phone.
 *
 * Candidate addresses come from the server list and the pass. They are ranked
 * by an authenticated page probe, and failover is restricted to addresses that
 * accepted a CONNECT with this pass; an untested address is never promoted just
 * because it appeared next in a list.
 */
object FirefoxVpnRelay {

    private const val CONNECT_TIMEOUT_MILLIS = 20_000
    private const val PROBE_TIMEOUT_MILLIS = 8_000
    private const val PROBE_BUDGET_MILLIS = 12_000
    /** Small collection window lets concurrently healthy endpoints be ranked, without serial waits. */
    private const val PROBE_SETTLE_MILLIS = 250L
    private const val PROBE_TARGET = "www.gstatic.com:443"
    private const val FALLBACK_PROBE_TARGET = "cp.cloudflare.com:443"
    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
    private const val MAX_HEADER_BYTES = 16 * 1024

    /** Bytes carried per read: a page-sized chunk, so a pump is not the limit. */
    private const val PUMP_BUFFER_BYTES = 64 * 1024

    /**
     * Sockets kept ready to the proxy, so opening a tunnel does not wait for a
     * TCP and a TLS handshake first. Two is what a phone page opens at once;
     * a third is dialled while they are being used.
     */
    private const val WARM_COUNT = 4

    /**
     * How long a ready socket is trusted. Fastly closes an idle connection
     * quietly, and a socket closed that way is noticed the moment a CONNECT
     * is written to it — the tunnel then opens on a fresh one — so keeping
     * them longer costs nothing but the handshake that replaces them.
     */
    private const val WARM_AGE_MILLIS = 30_000

    /** Send and receive windows: a phone on a slow link still fills a pipe. */
    private const val SOCKET_BUFFER_BYTES = 256 * 1024

    /** One address the proxy may be reached on, and what it belongs to. */
    data class Endpoint(val host: String, val port: Int, val label: String = "") {
        override fun toString(): String =
            if (label.isEmpty()) "$host:$port" else "$label ($host:$port)"
    }

    /**
     * The shape of the CONNECT the proxy accepts. Which one it is can only be
     * learned by asking, so the shapes are tried once at connect time and the
     * one that answers 200 is kept.
     */
    internal data class Shape(
        val name: String,
        val hostIsTarget: Boolean,
        val withAgent: Boolean
    )

    internal val SHAPES = listOf(
        Shape("host-target+ua", true, true),
        Shape("host-proxy+ua", false, true),
        Shape("host-target", true, false),
        Shape("host-proxy", false, false)
    )

    /**
     * Connections to the proxy already made, waiting to be given a CONNECT.
     *
     * A page opens several at once and each one used to pay for a TCP and a
     * TLS handshake before it could ask for anything — two round trips to a
     * server on another continent. Keeping a few ready takes that off the
     * wait, and a session the proxy remembers makes the ones that are not
     * ready start faster too.
     */
    private data class Warm(val socket: SSLSocket, val host: String, val port: Int, val madeAt: Long) {
        fun fresh(now: Long): Boolean =
            !socket.isClosed && socket.isConnected && now - madeAt < WARM_AGE_MILLIS
    }

    private val warm = ConcurrentLinkedQueue<Warm>()
    private val warmInFlight = AtomicInteger(0)
    /** Quarantines repeated failures until a verified spare can be used. */
    private val endpointFailures = FirefoxEndpointFailures()

    @Volatile
    private var warmThread: Thread? = null

    @Volatile
    private var warmHost: String = ""

    @Volatile
    private var warmPort: Int = 0

    /**
     * TLS sessions are remembered per context, so every handshake after the
     * first to the same server is resumed instead of made again. One context
     * for the life of the process is what makes that work.
     */
    private val tlsContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, null, null) }
    }

    /** How many tunnels opened on a connection that was already there. */
    private val warmed = AtomicInteger(0)
    private val dialed = AtomicInteger(0)

    /** Only the Worker's pipe needs it, to read this install's key. */
    @Volatile
    private var appContext: android.content.Context? = null

    fun attachContext(context: android.content.Context) {
        appContext = context.applicationContext
    }

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val openSockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var running = false

    /** Every address worth trying, the best first. */
    @Volatile
    private var endpoints: List<Endpoint> = emptyList()

    @Volatile
    private var activeEndpoint: Endpoint? = null

    /** Only endpoints which accepted the authenticated probe are eligible for failover. */
    private data class VerifiedRoute(
        val endpoint: Endpoint,
        val shape: Shape,
        val latencyMillis: Long,
    )

    @Volatile
    private var verifiedRoutes: List<VerifiedRoute> = emptyList()

    @Volatile
    private var shape: Shape = SHAPES[0]

    @Volatile
    private var bearerToken: String = ""

    /** Configured Cloudflare Worker, retained as an alternate route if direct access fails. */
    @Volatile
    private var cloudflareAddress: String? = null

    /** The route currently used for new tunnels; direct is preferred when it probes healthy. */
    @Volatile
    private var cloudflareActive = false

    /** Why the last tunnel was refused; null while tunnels are being accepted. */
    @Volatile
    var problem: String? = null
        private set

    private val failures = AtomicInteger(0)
    @Volatile
    private var lastFailureLogAt = 0L

    /** Bytes carried since the relay was started: down is what the phone received. */
    private val bytesDown = java.util.concurrent.atomic.AtomicLong(0)
    private val bytesUp = java.util.concurrent.atomic.AtomicLong(0)
    private val startedAt = java.util.concurrent.atomic.AtomicLong(0)

    /** One reading of the counter, taken when it is asked for. */
    data class Usage(val down: Long, val up: Long, val sinceMillis: Long, val open: Int)

    /** True only in the process that currently owns the loopback listener. */
    fun isRunning(): Boolean = running && serverSocket?.isBound == true && serverSocket?.isClosed == false

    /**
     * What this tunnel has carried so far. Counted where the bytes pass, in
     * [pump], so the number is the real traffic and not an estimate: that is the
     * only figure worth showing somebody on a plan with a limit.
     */
    fun usage(): Usage = Usage(
        down = bytesDown.get(),
        up = bytesUp.get(),
        sinceMillis = startedAt.get(),
        open = openSockets.size,
    )

    /**
     * Starts the relay and returns the loopback port the core should use.
     * [cloudflareAddress] is an optional fallback: direct proxy access is
     * preferred when its authenticated probe succeeds.
     */
    @Synchronized
    fun start(
        endpoints: List<Endpoint>,
        token: String,
        cloudflareAddress: String? = null
    ): Int {
        stop()
        this.endpoints = endpoints
        this.bearerToken = token
        this.cloudflareAddress = cloudflareAddress?.trim()?.takeIf { it.isNotEmpty() }
        this.cloudflareActive = false
        this.activeEndpoint = endpoints.firstOrNull()
        this.verifiedRoutes = emptyList()
        this.shape = SHAPES[0]
        this.problem = null
        failures.set(0)
        endpointFailures.reset()
        bytesDown.set(0)
        bytesUp.set(0)
        startedAt.set(System.currentTimeMillis())

        val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        running = true
        acceptThread = thread(name = "firefox-vpn-relay", isDaemon = true) {
            while (running) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                openSockets.add(client)
                thread(name = "firefox-vpn-tunnel", isDaemon = true) { handle(client) }
            }
        }

        // Direct access avoids an extra WebSocket hop, so prefer it whenever
        // the authenticated proxy probe succeeds. The Worker is a real fallback
        // for networks that cannot reach Mozilla's proxy from the phone.
        val directReady = endpoints.isNotEmpty() && resolve()
        if (!directReady && this.cloudflareAddress != null) {
            cloudflareActive = true
            problem = null
            AppLog.info(
                AppLog.Section.Connection,
                "Firefox VPN: direct proxy probe failed; using the configured relay"
            )
        }
        if (!cloudflareActive) keepWarm()
        AppLog.info(
            AppLog.Section.Connection,
            "Firefox VPN relay listening on 127.0.0.1:${server.localPort} through " +
                (activeEndpoint?.toString() ?: "no address")
        )
        return server.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        cloudflareAddress = null
        cloudflareActive = false
        endpoints = emptyList()
        activeEndpoint = null
        verifiedRoutes = emptyList()
        endpointFailures.reset()
        warmThread?.interrupt()
        warmThread = null
        drainWarm()
        val opened = dialed.getAndSet(0)
        val ready = warmed.getAndSet(0)
        if (opened + ready > 0) {
            AppLog.info(
                AppLog.Section.Connection,
                "Firefox VPN: $ready of ${opened + ready} tunnels opened on a connection that was ready"
            )
        }
        for (socket in openSockets) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        openSockets.clear()
    }

    /** The address the tunnels are being opened through. */
    fun endpoint(): Endpoint? = activeEndpoint

    /** Drops idle sockets tied to the old default network and warms the new path. */
    fun onNetworkChanged() {
        if (!running) return
        warmThread?.interrupt()
        warmThread = null
        drainWarm()
        if (!cloudflareActive) keepWarm()
        AppLog.info(AppLog.Section.Connection, "Firefox VPN: refreshed idle relay sockets after network handover")
    }

    // ── Asking the proxy which address takes a tunnel ─────────────

    private data class Probe(
        val endpoint: Endpoint,
        val shape: Shape,
        val status: String?,
        val latencyMillis: Long,
    )

    private data class PageTarget(
        val authority: String,
        val host: String,
        val port: Int,
        val path: String,
    )

    /**
     * Tests candidate endpoints concurrently and keeps every authenticated route
     * that answers within the short settle window. A single fast-but-flaky route
     * is not allowed to hide another endpoint that is already responding.
     */
    private fun resolve(): Boolean {
        val list = endpoints
        if (list.isEmpty()) {
            verifiedRoutes = emptyList()
            return false
        }
        val attempts = mutableListOf<Pair<Endpoint, Shape>>()
        for ((index, endpoint) in list.withIndex()) {
            // Probe all known request shapes on the primary address; use the two
            // Firefox-native shapes for spare addresses to bound the work.
            val shapes = if (index == 0) SHAPES else SHAPES.take(2)
            for (candidate in shapes) attempts += endpoint to candidate
        }

        val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "firefox-vpn-probe").apply { isDaemon = true }
        }
        val configuredTarget = RemoteControl.mozillaProbeTargetOr(PROBE_TARGET).trim()
        val alternateTarget = if (configuredTarget.equals(FALLBACK_PROBE_TARGET, ignoreCase = true)) {
            PROBE_TARGET
        } else {
            FALLBACK_PROBE_TARGET
        }
        val probeTargets = listOf(configuredTarget, alternateTarget).distinct()
        val jobs = mutableListOf<Future<Probe>>()
        try {
            val token = bearerToken
            for ((endpoint, candidate) in attempts) {
                jobs += pool.submit(
                    Callable {
                        val started = System.nanoTime()
                        val status = tryShapeWithFallback(
                            endpoint.host,
                            endpoint.port,
                            token,
                            candidate,
                            probeTargets,
                        )
                        Probe(
                            endpoint = endpoint,
                            shape = candidate,
                            status = status,
                            latencyMillis = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L),
                        )
                    }
                )
            }

            val deadline = System.currentTimeMillis() + PROBE_BUDGET_MILLIS
            val pending = jobs.toMutableList()
            val completed = mutableListOf<Probe>()
            val report = StringBuilder()
            var firstStatus: String? = null
            var firstSuccessAt: Long? = null
            while (
                pending.isNotEmpty() && System.currentTimeMillis() < deadline &&
                    (firstSuccessAt == null || System.currentTimeMillis() - firstSuccessAt < PROBE_SETTLE_MILLIS)
            ) {
                val iterator = pending.listIterator()
                var consumed = false
                while (iterator.hasNext()) {
                    val job = iterator.next()
                    if (!job.isDone) continue
                    iterator.remove()
                    consumed = true
                    val result = try {
                        job.get()
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (report.isNotEmpty()) report.append(", ")
                    report.append(result.endpoint.host).append('/').append(result.shape.name)
                        .append('=').append(result.status?.trim() ?: "no answer")
                    if (firstStatus == null) firstStatus = result.status?.trim()
                    if (isAcceptedConnect(result.status)) {
                        completed += result
                        if (firstSuccessAt == null) firstSuccessAt = System.currentTimeMillis()
                    }
                }
                if (!consumed && pending.isNotEmpty()) {
                    try {
                        Thread.sleep(if (firstSuccessAt == null) 60L else 30L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            for (job in pending) job.cancel(true)

            val endpointOrder = list.withIndex().associate { (index, endpoint) ->
                "${endpoint.host.lowercase()}:${endpoint.port}" to index
            }
            val routes = completed
                .groupBy { "${it.endpoint.host.lowercase()}:${it.endpoint.port}" }
                .values
                .map { sameEndpoint -> sameEndpoint.minBy { it.latencyMillis } }
                .sortedWith(
                    compareBy<Probe> { endpointFailures.isQuarantined(it.endpoint) }
                        .thenBy { endpointOrder["${it.endpoint.host.lowercase()}:${it.endpoint.port}"] ?: Int.MAX_VALUE }
                        .thenBy { it.latencyMillis }
                )
                .map { VerifiedRoute(it.endpoint, it.shape, it.latencyMillis) }
            verifiedRoutes = routes

            val chosen = routes.firstOrNull()
            if (chosen != null) {
                activeEndpoint = chosen.endpoint
                shape = chosen.shape
                endpointFailures.recordSuccess(chosen.endpoint)
                problem = null
                failures.set(0)
                AppLog.info(
                    AppLog.Section.Connection,
                    "Firefox VPN proxy: ${chosen.endpoint} takes ${chosen.shape.name}; " +
                        "${routes.size} authenticated route(s) verified"
                )
                return true
            }
            problem = if (firstStatus != null) {
                "no address took a tunnel: $firstStatus"
            } else {
                "no address answered"
            }
            AppLog.error(
                AppLog.Section.Connection,
                "Firefox VPN proxy: no address took a tunnel ($report)"
            )
            return false
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isAcceptedConnect(status: String?): Boolean =
        status?.trim()?.matches(Regex("HTTP/1\\.[01] 200(?:\\s.*)?")) == true

    private fun tryShapeWithFallback(
        host: String,
        port: Int,
        token: String,
        candidate: Shape,
        targets: List<String>,
    ): String? {
        val budgetPerTarget = (PROBE_TIMEOUT_MILLIS / targets.size.coerceAtLeast(1)).coerceAtLeast(1)
        var lastStatus: String? = null
        for (target in targets) {
            val status = tryShape(host, port, token, candidate, target, budgetPerTarget)
            if (isAcceptedConnect(status)) return status
            if (status != null) lastStatus = status
        }
        return lastStatus
    }

    private fun tryShape(
        host: String,
        port: Int,
        token: String,
        candidate: Shape,
        target: String,
        timeoutMillis: Int,
    ): String? {
        return try {
            val deadlineNanos = System.nanoTime() + timeoutMillis.coerceAtLeast(1).toLong() * 1_000_000L
            val tls = dialTls(host, port, timeoutMillis, deadlineNanos) ?: return null
            tls.use {
                val remaining = remainingTimeoutMillis(deadlineNanos)
                if (remaining <= 0) return null
                it.soTimeout = remaining
                it.getOutputStream().apply {
                    write(connectRequest(target, token, host, candidate).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                readHead(BufferedInputStream(it.getInputStream()))?.substringBefore("\r\n")
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * How long a real page takes through [endpoint], in milliseconds: DNS/TCP,
     * proxy TLS, authenticated CONNECT, destination TLS and an HTTP response.
     * A second independent probe target is tried within the same time budget so
     * a block on one website is not mistaken for a dead route.
     */
    fun measure(endpoint: Endpoint, token: String, timeoutMillis: Int = PROBE_TIMEOUT_MILLIS): Int? {
        val configured = RemoteControl.mozillaProbeTargetOr(PROBE_TARGET).trim()
        val fallback = if (configured.equals(FALLBACK_PROBE_TARGET, ignoreCase = true)) {
            PROBE_TARGET
        } else {
            FALLBACK_PROBE_TARGET
        }
        val targets = listOfNotNull(
            pageTarget(configured, "/generate_204"),
            pageTarget(fallback, "/generate_204"),
        ).distinctBy { it.authority.lowercase() }
        if (targets.isEmpty()) return null

        val totalBudget = timeoutMillis.coerceAtLeast(1)
        val primaryBudget = if (targets.size < 2 || totalBudget <= 1) {
            totalBudget
        } else {
            (totalBudget * 2 / 3).coerceIn(1, totalBudget - 1)
        }
        val budgets = listOf(primaryBudget, totalBudget - primaryBudget)
        for ((index, target) in targets.withIndex()) {
            val budget = budgets.getOrElse(index) { 0 }
            if (budget <= 0) continue
            val measured = measureTarget(endpoint, token, target, budget)
            if (measured != null) return measured
        }
        return null
    }

    private fun pageTarget(authority: String, path: String): PageTarget? {
        val uri = runCatching { java.net.URI("https://$authority") }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val resolvedAuthority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: "$host:$port"
        return PageTarget(resolvedAuthority, host, port, path)
    }

    private fun measureTarget(
        endpoint: Endpoint,
        token: String,
        target: PageTarget,
        timeoutMillis: Int,
    ): Int? {
        val startedAtNanos = System.nanoTime()
        val deadlineNanos = startedAtNanos + timeoutMillis.coerceAtLeast(1).toLong() * 1_000_000L
        val warmedSocket = takeWarm(endpoint.host, endpoint.port)
        if (warmedSocket != null) {
            val warmedResult = measureOnSocket(endpoint, token, target, warmedSocket, deadlineNanos, startedAtNanos)
            if (warmedResult != null) return warmedResult
        }

        val remaining = remainingTimeoutMillis(deadlineNanos)
        if (remaining <= 0) return null
        val fresh = dialTls(endpoint.host, endpoint.port, remaining, deadlineNanos) ?: return null
        dialed.incrementAndGet()
        return measureOnSocket(endpoint, token, target, fresh, deadlineNanos, startedAtNanos)
    }

    private fun measureOnSocket(
        endpoint: Endpoint,
        token: String,
        target: PageTarget,
        outerSocket: SSLSocket,
        deadlineNanos: Long,
        startedAtNanos: Long,
    ): Int? {
        val ua = RemoteControl.mozillaUserAgentOr(FIREFOX_UA)
        val routeShape = verifiedRoutes.firstOrNull {
            it.endpoint.host.equals(endpoint.host, ignoreCase = true) && it.endpoint.port == endpoint.port
        }?.shape ?: SHAPES[0]
        return try {
            outerSocket.use { outer ->
                outer.soTimeout = remainingTimeoutMillis(deadlineNanos).takeIf { it > 0 } ?: return null
                outer.getOutputStream().apply {
                    write(
                        connectRequest(target.authority, token, endpoint.host, routeShape)
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    flush()
                }
                val answer = readHead(BufferedInputStream(outer.getInputStream()))?.substringBefore("\r\n")
                if (!isAcceptedConnect(answer)) return null

                val remainingForPage = remainingTimeoutMillis(deadlineNanos)
                if (remainingForPage <= 0) return null
                val inner = SSLContext.getDefault().socketFactory
                    .createSocket(outer, target.host, target.port, false) as SSLSocket
                inner.use { page ->
                    val parameters = page.sslParameters
                    parameters.serverNames = listOf(SNIHostName(target.host))
                    parameters.applicationProtocols = arrayOf("http/1.1")
                    page.sslParameters = parameters
                    page.soTimeout = remainingTimeoutMillis(deadlineNanos).takeIf { it > 0 } ?: return null
                    page.startHandshake()
                    val hostHeader = if (target.port == 443) target.host else target.authority
                    page.getOutputStream().apply {
                        write(
                            (
                                "GET ${target.path} HTTP/1.1\r\nHost: $hostHeader\r\n" +
                                    "User-Agent: $ua\r\nConnection: close\r\n\r\n"
                            ).toByteArray(Charsets.ISO_8859_1)
                        )
                        flush()
                    }
                    val status = readHead(BufferedInputStream(page.getInputStream()))?.substringBefore("\r\n")
                    val code = status?.substringAfter(' ')?.take(3)?.toIntOrNull()
                    if (code == null || code !in 200..399) return null
                    ((System.nanoTime() - startedAtNanos) / 1_000_000L).toInt()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Connections kept ready ────────────────────────────────────

    /**
     * Keeps a few connections to the proxy ready. One is dialled at a time in
     * the background, so the phone is never busy handshaking when a page asks
     * for a tunnel, and a socket that has waited too long is let go.
     */
    private fun keepWarm() {
        val endpoint = activeEndpoint ?: return
        warmHost = endpoint.host
        warmPort = endpoint.port
        warmThread?.interrupt()
        warmThread = thread(name = "firefox-vpn-warm", isDaemon = true) {
            while (running && !cloudflareActive) {
                val now = System.currentTimeMillis()
                var ready = 0
                val kept = mutableListOf<Warm>()
                for (candidate in warm) {
                    if (candidate.fresh(now)) {
                        kept += candidate
                        ready++
                    } else {
                        close(candidate.socket)
                    }
                }
                // The queue is emptied and refilled so a stale socket cannot
                // be handed out while it is being counted.
                warm.clear()
                warm.addAll(kept)

                val wanted = WARM_COUNT - ready - warmInFlight.get()
                if (wanted > 0) {
                    warmInFlight.incrementAndGet()
                    val made = dialTls(warmHost, warmPort)
                    warmInFlight.decrementAndGet()
                    if (made != null && running && !cloudflareActive) {
                        warm.add(Warm(made, warmHost, warmPort, System.currentTimeMillis()))
                    } else {
                        close(made)
                    }
                }
                try {
                    Thread.sleep(if (ready >= WARM_COUNT) 5_000 else 300)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    /**
     * Hands over a connection that is ready for this address, or null when
     * there is none. A connection made for another address is not one: the
     * proxy it belongs to is a different server.
     */
    private fun takeWarm(host: String, port: Int): SSLSocket? {
        val now = System.currentTimeMillis()
        val others = mutableListOf<Warm>()
        var found: SSLSocket? = null
        while (true) {
            val candidate = warm.poll() ?: break
            when {
                found != null || candidate.host != host || candidate.port != port ->
                    if (candidate.fresh(now)) others += candidate else close(candidate.socket)
                !candidate.fresh(now) -> close(candidate.socket)
                else -> found = candidate.socket
            }
        }
        // What was taken out for another address goes back.
        warm.addAll(others)
        if (found != null) warmed.incrementAndGet()
        return found
    }

    private fun drainWarm() {
        while (true) {
            val candidate = warm.poll() ?: return
            close(candidate.socket)
        }
    }

    private fun close(socket: SSLSocket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    // ── Serving the core ──────────────────────────────────────────

    private fun handle(client: Socket) {
        var upstream: Tunnel? = null
        try {
            // The core talks to this relay over loopback: no delay, and wider
            // windows, so the phone side is never what the tunnel waits for.
            runCatching {
                client.tcpNoDelay = true
                client.keepAlive = true
                client.receiveBufferSize = SOCKET_BUFFER_BYTES
                client.sendBufferSize = SOCKET_BUFFER_BYTES
            }
            client.soTimeout = CONNECT_TIMEOUT_MILLIS
            val fromClient = BufferedInputStream(client.getInputStream())
            val head = readHead(fromClient) ?: return

            val firstLine = head.substringBefore("\r\n")
            val isConnect = firstLine.startsWith("CONNECT ", ignoreCase = true)
            val target = if (isConnect) {
                firstLine.substringAfter(' ').substringBefore(' ')
            } else {
                // A plain request line: "GET http://host:port/path HTTP/1.1".
                val absolute = firstLine.split(' ').getOrNull(1).orEmpty()
                authorityOf(absolute) ?: return
            }
            if (target.isEmpty()) return

            upstream = openTunnel(target) ?: run {
                client.getOutputStream().apply {
                    write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                return
            }

            val toClient = client.getOutputStream()
            if (isConnect) {
                toClient.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                toClient.flush()
            } else {
                // The same request head travels inside the tunnel.
                upstream.output.apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }

            client.soTimeout = 0
            // Both directions must run at the same time: waiting for the phone
            // to finish before reading the other way is what left the tunnel
            // silent after the connection was announced.
            val pipe = upstream
            val back = thread(name = "firefox-vpn-down", isDaemon = true) {
                pump(pipe.input, toClient, down = true, clientSocket = client)
            }
            pump(fromClient, pipe.output, down = false, clientSocket = null)
            try {
                back.join(60_000)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            // A tunnel that ends is ordinary; only a tunnel that was refused
            // is worth counting.
            AppLog.debug(
                AppLog.Section.Connection,
                "Firefox VPN relay: ${e.message ?: "the tunnel closed"}"
            )
        } finally {
            try {
                upstream?.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
            openSockets.remove(client)
        }
    }

    /** A pipe that carries bytes to the proxy, however it was opened. */
    internal interface Tunnel {
        val input: InputStream
        val output: OutputStream
        fun close()
    }

    /** The CONNECT Firefox itself sends; the shape is the one the proxy took. */
    internal fun connectRequest(target: String, token: String, host: String, shape: Shape): String =
        buildString {
            append("CONNECT $target HTTP/1.1\r\n")
            append("Host: ").append(if (shape.hostIsTarget) target else host).append("\r\n")
            append("Proxy-Authorization: Bearer $token\r\n")
            if (shape.withAgent) append("User-Agent: ${RemoteControl.mozillaUserAgentOr(FIREFOX_UA)}\r\n")
            append("\r\n")
        }

    internal fun connectRequest(target: String, token: String): String =
        connectRequest(target, token, activeEndpoint?.host.orEmpty(), shape)

    /** Opens a tunnel over the currently preferred route, falling back once if it fails. */
    private fun openTunnel(target: String): Tunnel? {
        if (cloudflareActive) {
            val workerTunnel = openCloudflareTunnel(target)
            if (workerTunnel != null) return workerTunnel

            val workerProblem = FirefoxVpnCloudflare.problem ?: "the Worker did not carry the tunnel"
            AppLog.info(
                AppLog.Section.Connection,
                "Firefox VPN: Worker route failed ($workerProblem); trying direct access"
            )
            cloudflareActive = false
            val directTunnel = openDirectWithFailover(target)
            if (directTunnel != null) {
                keepWarm()
                AppLog.info(AppLog.Section.Connection, "Firefox VPN: direct route recovered the tunnel")
                return directTunnel
            }

            val directProblem = problem
            cloudflareActive = cloudflareAddress != null
            problem = buildString {
                append(workerProblem)
                if (!directProblem.isNullOrBlank()) append("; direct fallback: ").append(directProblem)
            }
            return null
        }

        val directTunnel = openDirectWithFailover(target)
        if (directTunnel != null) return directTunnel

        val directProblem = problem.orEmpty()
        if (cloudflareAddress == null || !shouldTryWorker(directProblem)) return null

        AppLog.info(
            AppLog.Section.Connection,
            "Firefox VPN: direct route failed ($directProblem); trying the configured Worker"
        )
        val workerTunnel = openCloudflareTunnel(target)
        if (workerTunnel != null) {
            cloudflareActive = true
            warmThread?.interrupt()
            warmThread = null
            drainWarm()
            return workerTunnel
        }
        problem = directProblem.ifBlank { FirefoxVpnCloudflare.problem.orEmpty() }
        return null
    }

    /** Opens the user's Worker route; success means its authenticated CONNECT returned 200. */
    private fun openCloudflareTunnel(target: String): Tunnel? {
        val address = cloudflareAddress ?: return null
        val endpoint = activeEndpoint ?: run {
            noteFailure("no address to dial")
            return null
        }
        val context = appContext ?: run {
            problem = "the relay has no application context"
            return null
        }
        val started = System.nanoTime()
        val pipe = FirefoxVpnCloudflare.openTunnel(
            address = address,
            context = context,
            proxyHost = endpoint.host,
            proxyPort = endpoint.port,
            target = target,
            token = bearerToken,
            hostHeader = if (shape.hostIsTarget) target else endpoint.host,
            withAgent = shape.withAgent
        ) ?: run {
            problem = FirefoxVpnCloudflare.problem ?: "the Worker carried nothing"
            return null
        }
        endpointFailures.recordSuccess(endpoint)
        failures.set(0)
        problem = null
        AppLog.debug(
            AppLog.Section.Connection,
            "Firefox VPN: Worker tunnel opened in ${((System.nanoTime() - started) / 1_000_000L)}ms"
        )
        return object : Tunnel {
            override val input: InputStream get() = pipe.input
            override val output: OutputStream get() = pipe.output
            override fun close() = pipe.close()
        }
    }

    /** Repeats the same request only when a repeated failure switched endpoints. */
    private fun openDirectWithFailover(target: String): Tunnel? {
        val attempted = mutableSetOf<String>()
        val attemptLimit = verifiedRoutes.size.coerceAtLeast(1)
        while (attempted.size < attemptLimit) {
            val endpoint = activeEndpoint ?: return null
            val key = "${endpoint.host.lowercase()}:${endpoint.port}"
            if (!attempted.add(key)) return null
            val tunnel = openDirectTunnel(target)
            if (tunnel != null) return tunnel
            val currentKey = activeEndpoint?.let { "${it.host.lowercase()}:${it.port}" }
            if (currentKey == null || currentKey == key) return null
        }
        return null
    }

    /** Opens a direct TLS connection to the proxy, reusing a warm socket when possible. */
    private fun openDirectTunnel(target: String): Tunnel? {
        val endpoint = activeEndpoint
        if (endpoint == null) {
            noteFailure("no address to dial")
            return null
        }

        var tls = takeWarm(endpoint.host, endpoint.port)
        var statusLine: String? = null
        var buffered: BufferedInputStream? = null
        if (tls != null) {
            val stream = BufferedInputStream(tls.getInputStream())
            statusLine = try {
                tls.getOutputStream().apply {
                    write(connectRequest(target, bearerToken).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                readHead(stream)?.substringBefore("\r\n")
            } catch (_: Exception) {
                null
            }
            if (isAcceptedConnect(statusLine)) {
                buffered = stream
            } else {
                close(tls)
                tls = null
                // A warm connection can have gone stale after a network change.
                // Retry once on a fresh socket before counting the route as bad.
                statusLine = null
            }
        }
        if (tls == null) {
            val fresh = dialTls(endpoint.host, endpoint.port) ?: run {
                noteFailure("${endpoint.host} cannot be reached", endpoint)
                return null
            }
            dialed.incrementAndGet()
            val stream = BufferedInputStream(fresh.getInputStream())
            var requestFailure: String? = null
            statusLine = try {
                fresh.getOutputStream().apply {
                    write(connectRequest(target, bearerToken).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                readHead(stream)?.substringBefore("\r\n")
            } catch (e: Exception) {
                requestFailure = e.message ?: "the proxy closed the connection"
                null
            }
            if (!isAcceptedConnect(statusLine)) {
                noteFailure(requestFailure ?: statusLine?.trim() ?: "the proxy did not answer", endpoint)
                close(fresh)
                return null
            }
            tls = fresh
            buffered = stream
        }
        val tunnel = tls ?: return null
        val tunnelIn = buffered ?: run {
            close(tunnel)
            return null
        }
        try {
            tunnel.soTimeout = 0
        } catch (_: Exception) {
        }
        endpointFailures.recordSuccess(endpoint)
        failures.set(0)
        problem = null
        return object : Tunnel {
            override val input: InputStream get() = tunnelIn
            override val output: OutputStream get() = tunnel.getOutputStream()
            override fun close() {
                close(tunnel)
            }
        }
    }

    /**
     * Counts a failed attempt without treating target-specific HTTP denials as
     * endpoint failures. A verified spare is selected only after a repeated
     * transport/proxy-route failure, which avoids switching on one dropped packet.
     */
    private fun noteFailure(reason: String, endpoint: Endpoint? = activeEndpoint) {
        problem = reason
        val count = failures.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastFailureLogAt > 15_000) {
            lastFailureLogAt = now
            AppLog.error(
                AppLog.Section.Connection,
                "Firefox VPN: could not open $count consecutive tunnels ($reason)"
            )
        }
        if (endpoint != null && isEndpointFailure(reason) && endpointFailures.recordFailure(endpoint)) {
            failures.set(0)
            rotate()
        }
    }

    private fun isEndpointFailure(reason: String): Boolean {
        val status = Regex("HTTP/1\\.[01] (\\d{3})").find(reason)?.groupValues?.getOrNull(1)
        if (status != null) {
            val code = status.toIntOrNull() ?: return false
            return code == 421 || code in 500..599
        }
        val lower = reason.lowercase()
        return listOf(
            "cannot be reached", "no answer", "did not answer", "timed out", "timeout",
            "connection reset", "connection refused", "broken pipe", "unreachable", "closed",
            "handshake",
        ).any(lower::contains)
    }

    private fun shouldTryWorker(reason: String): Boolean {
        val status = Regex("HTTP/1\\.[01] (\\d{3})").find(reason)?.groupValues?.getOrNull(1)
        if (status != null) return status.toIntOrNull()?.let { it in 500..599 } == true
        val lower = reason.lowercase()
        return listOf(
            "cannot be reached", "no answer", "did not answer", "timed out", "timeout",
            "connection reset", "connection refused", "broken pipe", "unreachable", "closed",
            "handshake",
        ).any(lower::contains)
    }

    /** Switches only to a spare which already passed the same authenticated CONNECT probe. */
    private fun rotate() {
        val current = activeEndpoint ?: return
        val currentKey = "${current.host.lowercase()}:${current.port}"
        val next = verifiedRoutes.firstOrNull { route ->
            val candidateKey = "${route.endpoint.host.lowercase()}:${route.endpoint.port}"
            candidateKey != currentKey && !endpointFailures.isQuarantined(route.endpoint)
        } ?: run {
            AppLog.info(
                AppLog.Section.Connection,
                "Firefox VPN: no other authenticated endpoint is currently healthy"
            )
            return
        }
        activeEndpoint = next.endpoint
        shape = next.shape
        drainWarm()
        if (running && !cloudflareActive) keepWarm()
        AppLog.info(AppLog.Section.Connection, "Firefox VPN: switched to verified endpoint ${next.endpoint}")
    }

    /**
     * The TLS connection to the proxy, dialled straight from the phone.
     *
     * Nagle is off: a tunnel carries small writes (the start of a handshake,
     * a tap of a button) that would otherwise wait for a partner that is
     * waiting for them. The windows are widened so a slow phone link does not
     * decide the speed of the whole connection.
     */
    private fun remainingTimeoutMillis(deadlineNanos: Long): Int {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return 0
        return ((remainingNanos + 999_999L) / 1_000_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
    }

    private fun dialTls(
        host: String,
        port: Int,
        timeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
        deadlineNanos: Long? = null,
    ): SSLSocket? {
        var raw: Socket? = null
        return try {
            val address = InetSocketAddress(host, port)
            val rawSocket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = SOCKET_BUFFER_BYTES
                sendBufferSize = SOCKET_BUFFER_BYTES
            }
            raw = rawSocket
            val connectTimeout = deadlineNanos?.let(::remainingTimeoutMillis) ?: timeoutMillis
            if (connectTimeout <= 0) {
                rawSocket.close()
                return null
            }
            rawSocket.connect(address, connectTimeout)
            val handshakeTimeout = deadlineNanos?.let(::remainingTimeoutMillis) ?: timeoutMillis
            if (handshakeTimeout <= 0) {
                rawSocket.close()
                return null
            }
            rawSocket.soTimeout = handshakeTimeout

            val factory: SSLSocketFactory = tlsContext.socketFactory
            val tls = factory.createSocket(rawSocket, host, port, true) as SSLSocket
            try {
                tls.tcpNoDelay = true
                tls.receiveBufferSize = SOCKET_BUFFER_BYTES
                tls.sendBufferSize = SOCKET_BUFFER_BYTES
            } catch (_: Exception) {
            }
            val parameters = tls.sslParameters
            parameters.serverNames = listOf(SNIHostName(host))
            // Firefox offers both; offering one alone is what makes the proxy take
            // HTTP/1.1 for a plain CONNECT.
            parameters.applicationProtocols = arrayOf("http/1.1")
            tls.sslParameters = parameters
            val tlsHandshakeTimeout = deadlineNanos?.let(::remainingTimeoutMillis) ?: timeoutMillis
            if (tlsHandshakeTimeout <= 0) {
                tls.close()
                return null
            }
            tls.soTimeout = tlsHandshakeTimeout
            tls.startHandshake()
            tls
        } catch (_: Exception) {
            runCatching { raw?.close() }
            null
        }
    }

    internal fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        var matched = 0
        while (buffer.length < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            val c = next.toChar()
            buffer.append(c)
            matched = when {
                (matched == 0 || matched == 2) && c == '\r' -> matched + 1
                (matched == 1 || matched == 3) && c == '\n' -> matched + 1
                else -> 0
            }
            if (matched == 4) return buffer.toString()
        }
        return null
    }

    internal fun authorityOf(absoluteUrl: String): String? {
        val withoutScheme = absoluteUrl.substringAfter("://", absoluteUrl)
        val authority = withoutScheme.substringBefore('/')
        if (authority.isEmpty()) return null
        return if (authority.contains(':')) authority else "$authority:80"
    }

    private fun pump(input: InputStream, output: OutputStream, down: Boolean, clientSocket: Socket?) {
        val buffer = ByteArray(PUMP_BUFFER_BYTES)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: Exception) {
                -1
            }
            if (read <= 0) break
            try {
                output.write(buffer, 0, read)
                output.flush()
                if (down) bytesDown.addAndGet(read.toLong()) else bytesUp.addAndGet(read.toLong())
            } catch (_: Exception) {
                break
            }
        }
        // Never call output.close() on the upstream TLS pipe: closing SSLSocket.getOutputStream()
        // terminates the entire SSLSocket while the downstream pump may still be receiving data.
        if (down && clientSocket != null) {
            runCatching { clientSocket.shutdownOutput() }
        } else if (!down) {
            runCatching { output.flush() }
        }
    }
}
