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
    private const val MAX_HEADER_BYTES = 16 * 1024

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val openSockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var running = false

    private var proxyHost: String = ""
    private var proxyPort: Int = 443
    private var bearerToken: String = ""

    /** Starts the relay and returns the loopback port the core should use. */
    @Synchronized
    fun start(host: String, port: Int, token: String): Int {
        stop()
        proxyHost = host
        proxyPort = port
        bearerToken = token

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
        for (socket in openSockets) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        openSockets.clear()
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
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
                upstream.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }

            client.soTimeout = 0
            pump(fromClient, upstream.getOutputStream())
            pump(upstream.getInputStream(), toClient)
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

    /** Opens the TLS connection to Fastly and asks it for the tunnel. */
    private fun openTunnel(target: String): Socket? {
        val address = InetSocketAddress(proxyHost, proxyPort)
        val raw = Socket()
        raw.connect(address, CONNECT_TIMEOUT_MILLIS)
        raw.soTimeout = CONNECT_TIMEOUT_MILLIS

        val factory: SSLSocketFactory = SSLContext.getDefault().socketFactory
        val tls = factory.createSocket(raw, proxyHost, proxyPort, true) as SSLSocket
        val parameters = tls.sslParameters
        parameters.serverNames = listOf(SNIHostName(proxyHost))
        tls.sslParameters = parameters
        tls.startHandshake()

        // Firefox's own CONNECT: Host names the destination, not the proxy, and
        // nothing else but the bearer travels with it. The proxy answers 400 to
        // anything else.
        val request = buildString {
            append("CONNECT $target HTTP/1.1\r\n")
            append("Host: $target\r\n")
            append("Proxy-Authorization: Bearer $bearerToken\r\n")
            append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0\r\n")
            append("\r\n")
        }
        tls.outputStream.apply {
            write(request.toByteArray(Charsets.ISO_8859_1))
            flush()
        }

        val statusLine = readLine(BufferedInputStream(tls.inputStream))
        val accepted = statusLine != null && statusLine.contains(" 200")
        if (!accepted) {
            AppLog.error(AppLog.Section.Connection, "Firefox VPN proxy refused $target: ${statusLine ?: "no answer"}")
            try {
                tls.close()
            } catch (_: Exception) {
            }
            return null
        }
        return tls
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
