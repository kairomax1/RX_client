package com.v2ray.ang.handler

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
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
 * one TLS connection to the Fastly host per tunnel, sends the CONNECT with the
 * Bearer header, and then copies bytes both ways. The target's name is passed
 * on untouched, so the name is resolved by the proxy, not by this phone.
 */
object FirefoxVpnRelay {

    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"

    /** Only the Worker's pipe needs it, to read this install's key. */
    @Volatile
    private var appContext: android.content.Context? = null

    fun attachContext(context: android.content.Context) {
        appContext = context.applicationContext
    }
    private const val MAX_HEADER_BYTES = 16 * 1024

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val openSockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var running = false

    private var proxyHost: String = ""
    private var proxyPort: Int = 443
    private var bearerToken: String = ""

    /** When set, the pipe to the proxy goes through this Cloudflare Worker. */
    private var cloudflareAddress: String? = null

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

    @Volatile
    private var shape: Shape = SHAPES[0]

    /**
     * Starts the relay and returns the loopback port the core should use.
     * [cloudflareAddress] is the user's own Worker; without it the proxy is
     * dialled directly.
     */
    @Synchronized
    fun start(
        host: String,
        port: Int,
        token: String,
        cloudflareAddress: String? = null,
        shapeName: String? = null
    ): Int {
        stop()
        proxyHost = host
        proxyPort = port
        bearerToken = token
        this.cloudflareAddress = cloudflareAddress
        shape = SHAPES.firstOrNull { it.name == shapeName } ?: SHAPES[0]

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
        AppLog.info(AppLog.Section.Connection, "Firefox VPN relay listening on 127.0.0.1:${server.localPort}")
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
        for (socket in openSockets) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        openSockets.clear()
    }

    private fun handle(client: Socket) {
        var upstream: Tunnel? = null
        try {
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
                pump(pipe.input, toClient)
            }
            pump(fromClient, pipe.output)
            try {
                back.join(2000)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            AppLog.error(AppLog.Section.Connection, "Firefox VPN relay: ${e.message ?: "tunnel closed"}")
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
    internal fun connectRequest(target: String, token: String): String =
        connectRequest(target, token, proxyHost, shape)

    internal fun connectRequest(target: String, token: String, host: String, shape: Shape): String =
        buildString {
            append("CONNECT $target HTTP/1.1\r\n")
            append("Host: ").append(if (shape.hostIsTarget) target else host).append("\r\n")
            append("Proxy-Authorization: Bearer $token\r\n")
            if (shape.withAgent) append("User-Agent: $FIREFOX_UA\r\n")
            append("\r\n")
        }

    /**
     * Asks the proxy with each shape once and returns the name of the one it
     * answers 200 to, together with a short report for the log. Null when
     * every shape was refused.
     */
    internal fun probeShapes(
        host: String,
        port: Int,
        token: String,
        testTarget: String = "www.gstatic.com:443"
    ): Pair<String?, String> {
        val report = StringBuilder()
        for (candidate in SHAPES) {
            val status = tryShape(host, port, token, candidate, testTarget)
            if (report.isNotEmpty()) report.append(", ")
            report.append(candidate.name).append('=').append(status ?: "no answer")
            if (status != null && status.contains(" 200")) {
                return Pair(candidate.name, report.toString())
            }
        }
        return Pair(null, report.toString())
    }

    private fun tryShape(
        host: String,
        port: Int,
        token: String,
        candidate: Shape,
        target: String
    ): String? = try {
        val tls = dialTls(host, port) ?: return null
        tls.use {
            it.getOutputStream().apply {
                write(connectRequest(target, token, host, candidate).toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val statusLine = readHead(BufferedInputStream(it.getInputStream()))?.substringBefore("\r\n")
            statusLine
        }
    } catch (_: Exception) {
        null
    }

    /** Opens the connection to Fastly and asks it for the tunnel. */
    private fun openTunnel(target: String): Tunnel? {
        val viaCloudflare = cloudflareAddress
        if (viaCloudflare != null) {
            // The Worker makes the TLS connection and the CONNECT itself, so
            // what comes back is already a tunnel to the target.
            val pipe = FirefoxVpnCloudflare.openTunnel(
                address = viaCloudflare,
                context = appContext ?: return null,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
                target = target,
                token = bearerToken,
                hostHeader = if (shape.hostIsTarget) target else proxyHost,
                withAgent = shape.withAgent
            ) ?: return null
            return object : Tunnel {
                override val input: InputStream get() = pipe.input
                override val output: OutputStream get() = pipe.output
                override fun close() = pipe.close()
            }
        }

        val tls = dialTls(proxyHost, proxyPort) ?: return null
        val buffered = BufferedInputStream(tls.getInputStream())
        val statusLine = try {
            tls.getOutputStream().apply {
                write(connectRequest(target, bearerToken).toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            readHead(buffered)?.substringBefore("\r\n")
        } catch (e: Exception) {
            AppLog.error(AppLog.Section.Connection, "Firefox VPN tunnel: ${e.message ?: "closed"}")
            null
        }
        if (statusLine == null || !statusLine.contains(" 200")) {
            AppLog.error(
                AppLog.Section.Connection,
                "Firefox VPN proxy refused $target: ${statusLine ?: "no answer"}"
            )
            try {
                tls.close()
            } catch (_: Exception) {
            }
            return null
        }
        // The bytes already read past the head belong to the tunnel, so the
        // same buffered stream travels on instead of a fresh one.
        return object : Tunnel {
            override val input: InputStream get() = buffered
            override val output: OutputStream get() = tls.getOutputStream()
            override fun close() {
                try {
                    tls.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** The TLS connection to Fastly, dialled straight from the phone. */
    private fun dialTls(host: String, port: Int): SSLSocket? = try {
        val address = InetSocketAddress(host, port)
        val raw = Socket()
        raw.connect(address, CONNECT_TIMEOUT_MILLIS)
        raw.soTimeout = CONNECT_TIMEOUT_MILLIS

        val factory: SSLSocketFactory = SSLContext.getDefault().socketFactory
        val tls = factory.createSocket(raw, host, port, true) as SSLSocket
        val parameters = tls.sslParameters
        parameters.serverNames = listOf(SNIHostName(host))
        // Firefox offers both; offering one alone is what makes the proxy take
        // HTTP/1.1 for a plain CONNECT.
        parameters.applicationProtocols = arrayOf("http/1.1")
        tls.sslParameters = parameters
        tls.startHandshake()
        tls
    } catch (e: Exception) {
        AppLog.error(AppLog.Section.Connection, "Firefox VPN proxy unreachable: ${e.message ?: "no route"}")
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

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < 8192) {
            val next = input.read()
            if (next < 0) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) return line.toString().trimEnd('\r')
            line.append(next.toChar())
        }
        return null
    }

    internal fun authorityOf(absoluteUrl: String): String? {
        val withoutScheme = absoluteUrl.substringAfter("://", absoluteUrl)
        val authority = withoutScheme.substringBefore('/')
        if (authority.isEmpty()) return null
        return if (authority.contains(':')) authority else "$authority:80"
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
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
