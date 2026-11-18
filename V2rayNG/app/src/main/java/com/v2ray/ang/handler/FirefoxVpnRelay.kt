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
 * Which address to dial is not written anywhere: the server list names one
 * endpoint per location and the pass names another. Both are tried once at
 * connect time and the one that answers is kept.
 */
object FirefoxVpnRelay {

    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val PROBE_TIMEOUT_MILLIS = 6_000
    private const val PROBE_BUDGET_MILLIS = 9_000
    private const val PROBE_TARGET = "www.gstatic.com:443"
    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
    private const val MAX_HEADER_BYTES = 16 * 1024

    /** Bytes carried per read: a page-sized chunk, so a pump is not the limit. */
    private const val PUMP_BUFFER_BYTES = 32 * 1024

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

    @Volatile
    private var shape: Shape = SHAPES[0]

    @Volatile
    private var bearerToken: String = ""

    /** When set, the pipe to the proxy goes through this Cloudflare Worker. */
    @Volatile
    private var cloudflareAddress: String? = null

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
     * [cloudflareAddress] is the user's own Worker; without it the proxy is
     * dialled directly.
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
        this.cloudflareAddress = cloudflareAddress
        this.activeEndpoint = endpoints.firstOrNull()
        this.shape = SHAPES[0]
        this.problem = null
        failures.set(0)
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

        // Behind the Worker the phone cannot ask the proxy itself, so the
        // address is the one the server list named for the location.
        if (cloudflareAddress == null && endpoints.isNotEmpty()) {
            resolve()
        }
        if (cloudflareAddress == null) keepWarm()
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
        endpoints = emptyList()
        activeEndpoint = null
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

    // ── Asking the proxy which address takes a tunnel ─────────────

    private data class Probe(val endpoint: Endpoint, val shape: Shape, val status: String?)

    /**
     * Tries every address with every shape at the same time and keeps the first
     * that answers 200. Asking one after another would spend a full timeout per
     * attempt, which on a network that drops the packets is minutes.
     */
    private fun resolve(): Boolean {
        val list = endpoints
        if (list.isEmpty()) return false
        val attempts = mutableListOf<Pair<Endpoint, Shape>>()
        for ((index, endpoint) in list.withIndex()) {
            // The first address gets every shape; the spares only the two
            // Firefox itself sends.
            val shapes = if (index == 0) SHAPES else SHAPES.take(2)
            for (candidate in shapes) attempts += endpoint to candidate
        }

        val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "firefox-vpn-probe").apply { isDaemon = true }
        }
        val jobs = mutableListOf<Future<Probe>>()
        try {
            val token = bearerToken
            for ((endpoint, candidate) in attempts) {
                jobs += pool.submit(
                    Callable {
                        Probe(
                            endpoint,
                            candidate,
                            tryShape(endpoint.host, endpoint.port, token, candidate, PROBE_TARGET)
                        )
                    }
                )
            }

            val deadline = System.currentTimeMillis() + PROBE_BUDGET_MILLIS
            val pending = jobs.toMutableList()
            val report = StringBuilder()
            var firstStatus: String? = null
            var winner: Probe? = null
            while (pending.isNotEmpty() && winner == null && System.currentTimeMillis() < deadline) {
                val iterator = pending.listIterator()
                while (iterator.hasNext()) {
                    val job = iterator.next()
                    if (!job.isDone) continue
                    iterator.remove()
                    val result = try {
                        job.get()
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (report.isNotEmpty()) report.append(", ")
                    report.append(result.endpoint.host).append('/').append(result.shape.name)
                        .append('=').append(result.status?.trim() ?: "no answer")
                    if (firstStatus == null) firstStatus = result.status?.trim()
                    if (result.status != null && result.status.contains(" 200")) winner = result
                }
                if (winner == null && pending.isNotEmpty()) {
                    try {
                        Thread.sleep(60)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            for (job in pending) job.cancel(true)

            val chosen = winner
            if (chosen != null) {
                activeEndpoint = chosen.endpoint
                shape = chosen.shape
                problem = null
                failures.set(0)
                AppLog.info(
                    AppLog.Section.Connection,
                    "Firefox VPN proxy: ${chosen.endpoint} takes ${chosen.shape.name}"
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

    private fun tryShape(
        host: String,
        port: Int,
        token: String,
        candidate: Shape,
        target: String
    ): String? = try {
        val tls = dialTls(host, port, PROBE_TIMEOUT_MILLIS) ?: return null
        tls.use {
            it.getOutputStream().apply {
                write(connectRequest(target, token, host, candidate).toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            readHead(BufferedInputStream(it.getInputStream()))?.substringBefore("\r\n")
        }
    } catch (_: Exception) {
        null
    }

    /**
     * How long a real page takes through [endpoint], in milliseconds: the
     * handshake to the proxy, the CONNECT, the handshake to the page and its
     * answer. A location that drops anywhere on the way has no number, because
     * a number that was not measured would be a lie.
     */
    fun measure(endpoint: Endpoint, token: String, timeoutMillis: Int = PROBE_TIMEOUT_MILLIS): Int? {
        val started = System.nanoTime()
        val pageHost = PROBE_TARGET.substringBefore(':')
        val pagePort = PROBE_TARGET.substringAfter(':').toInt()
        return try {
            // The number is what a tunnel really costs, so it is measured the
            // way one is opened: on a connection that is ready when there is
            // one, otherwise on a fresh one.
            val ready = takeWarm(endpoint.host, endpoint.port)
            val tls = ready ?: dialTls(endpoint.host, endpoint.port, timeoutMillis) ?: return null
            if (ready == null) dialed.incrementAndGet()
            tls.use { outer ->
                outer.soTimeout = timeoutMillis
                outer.getOutputStream().apply {
                    write(
                        connectRequest(PROBE_TARGET, token, endpoint.host, SHAPES[0])
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    flush()
                }
                val answer = readHead(BufferedInputStream(outer.getInputStream()))?.substringBefore("\r\n")
                if (answer == null || !answer.contains(" 200")) return null

                val inner = SSLContext.getDefault().socketFactory
                    .createSocket(outer, pageHost, pagePort, false) as SSLSocket
                val parameters = inner.sslParameters
                parameters.serverNames = listOf(SNIHostName(pageHost))
                parameters.applicationProtocols = arrayOf("http/1.1")
                inner.sslParameters = parameters
                inner.soTimeout = timeoutMillis
                inner.startHandshake()
                inner.getOutputStream().apply {
                    write(
                        (
                            "GET /generate_204 HTTP/1.1\r\nHost: $pageHost\r\n" +
                                "User-Agent: $FIREFOX_UA\r\nConnection: close\r\n\r\n"
                            ).toByteArray(Charsets.ISO_8859_1)
                    )
                    flush()
                }
                val status = readHead(BufferedInputStream(inner.getInputStream()))?.substringBefore("\r\n")
                if (status == null || !(status.contains(" 204") || status.contains(" 200"))) return null
                ((System.nanoTime() - started) / 1_000_000L).toInt()
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
            while (running) {
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
                    if (made != null && running) {
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
                pump(pipe.input, toClient, down = true)
            }
            pump(fromClient, pipe.output, down = false)
            try {
                back.join(2000)
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
            if (shape.withAgent) append("User-Agent: $FIREFOX_UA\r\n")
            append("\r\n")
        }

    internal fun connectRequest(target: String, token: String): String =
        connectRequest(target, token, activeEndpoint?.host.orEmpty(), shape)

    /** Opens the connection to the proxy and asks it for the tunnel. */
    private fun openTunnel(target: String): Tunnel? {
        val viaCloudflare = cloudflareAddress
        if (viaCloudflare != null) {
            // The Worker makes the TLS connection and the CONNECT itself, so
            // what comes back is already a tunnel to the target.
            val endpoint = activeEndpoint ?: return null
            val pipe = FirefoxVpnCloudflare.openTunnel(
                address = viaCloudflare,
                context = appContext ?: return null,
                proxyHost = endpoint.host,
                proxyPort = endpoint.port,
                target = target,
                token = bearerToken,
                hostHeader = if (shape.hostIsTarget) target else endpoint.host,
                withAgent = shape.withAgent
            ) ?: run {
                noteFailure(FirefoxVpnCloudflare.problem ?: "the relay carried nothing")
                return null
            }
            failures.set(0)
            problem = null
            return object : Tunnel {
                override val input: InputStream get() = pipe.input
                override val output: OutputStream get() = pipe.output
                override fun close() = pipe.close()
            }
        }

        val endpoint = activeEndpoint
        if (endpoint == null) {
            noteFailure("no address to dial")
            return null
        }

        // A connection that is already made saves two round trips; only when
        // there is none, or when the proxy has closed the one there was, is a
        // fresh one dialled.
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
            if (statusLine != null && statusLine.contains(" 200")) {
                buffered = stream
            } else {
                close(tls)
                tls = null
                statusLine = null
            }
        }
        if (tls == null) {
            val fresh = dialTls(endpoint.host, endpoint.port) ?: run {
                noteFailure("${endpoint.host} cannot be reached")
                return null
            }
            dialed.incrementAndGet()
            val stream = BufferedInputStream(fresh.getInputStream())
            statusLine = try {
                fresh.getOutputStream().apply {
                    write(connectRequest(target, bearerToken).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                readHead(stream)?.substringBefore("\r\n")
            } catch (e: Exception) {
                noteFailure(e.message ?: "the proxy closed the connection")
                null
            }
            if (statusLine == null || !statusLine.contains(" 200")) {
                noteFailure(statusLine?.trim() ?: "the proxy did not answer")
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
        // A tunnel may be silent for as long as its two ends keep it: a wait
        // that cuts it off after a few quiet seconds drops the connections an
        // app holds open, which is what made the tunnel feel unstable.
        try {
            tunnel.soTimeout = 0
        } catch (_: Exception) {
        }
        failures.set(0)
        problem = null
        // The bytes already read past the head belong to the tunnel, so the
        // same buffered stream travels on instead of a fresh one.
        return object : Tunnel {
            override val input: InputStream get() = tunnelIn
            override val output: OutputStream get() = tunnel.getOutputStream()
            override fun close() {
                close(tunnel)
            }
        }
    }

    /**
     * Counts a refused tunnel. The screen and the log say it once, not once per
     * request: a thousand copies of the same line is what buried the real
     * reason before.
     */
    private fun noteFailure(reason: String) {
        problem = reason
        val count = failures.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastFailureLogAt > 15_000) {
            lastFailureLogAt = now
            AppLog.error(
                AppLog.Section.Connection,
                "Firefox VPN: the proxy refused $count tunnels ($reason)"
            )
        }
        // Three refusals in a row mean this address is not the one, not that
        // the page asked for something odd.
        if (count >= 3) {
            failures.set(0)
            rotate()
        }
    }

    /** Moves to the next address the server list named. */
    private fun rotate() {
        val list = endpoints
        if (list.size < 2) return
        val current = activeEndpoint
        val index = list.indexOfFirst { it.host == current?.host && it.port == current.port }
        val next = list[((index + 1) % list.size)]
        activeEndpoint = next
        // What is ready belongs to the address that was being used.
        drainWarm()
        if (running && cloudflareAddress == null) keepWarm()
        AppLog.info(AppLog.Section.Connection, "Firefox VPN: trying $next instead")
    }

    /**
     * The TLS connection to the proxy, dialled straight from the phone.
     *
     * Nagle is off: a tunnel carries small writes (the start of a handshake,
     * a tap of a button) that would otherwise wait for a partner that is
     * waiting for them. The windows are widened so a slow phone link does not
     * decide the speed of the whole connection.
     */
    private fun dialTls(host: String, port: Int, timeoutMillis: Int = CONNECT_TIMEOUT_MILLIS): SSLSocket? =
        try {
            val address = InetSocketAddress(host, port)
            val raw = Socket()
            raw.tcpNoDelay = true
            raw.receiveBufferSize = SOCKET_BUFFER_BYTES
            raw.sendBufferSize = SOCKET_BUFFER_BYTES
            raw.connect(address, timeoutMillis)
            raw.soTimeout = timeoutMillis

            val factory: SSLSocketFactory = tlsContext.socketFactory
            val tls = factory.createSocket(raw, host, port, true) as SSLSocket
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
            tls.startHandshake()
            tls
        } catch (_: Exception) {
            null
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

    private fun pump(input: InputStream, output: OutputStream, down: Boolean) {
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
        try {
            output.close()
        } catch (_: Exception) {
        }
    }
}
