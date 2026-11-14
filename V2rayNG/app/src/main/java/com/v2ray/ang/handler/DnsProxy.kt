package com.v2ray.ang.handler

import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small local web proxy that looks every site name up at one chosen DNS server and then
 * connects straight to the answer, exactly as a console set to that DNS would. The in-app
 * Xbox page goes through it, so signing in there shows whether that DNS really works.
 *
 * It only finds the address and copies bytes; the pages stay encrypted end to end between
 * the page and Microsoft.
 */
class DnsProxy(private val servers: List<String>) {

    private var listening: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { task -> Thread(task, "dns-proxy").apply { isDaemon = true } }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val known = ConcurrentHashMap<String, String>()

    /** Names the chosen DNS answered, and names it did not. */
    val resolved = AtomicInteger(0)
    val failed = AtomicInteger(0)
    private val failedNames = ConcurrentHashMap.newKeySet<String>()

    /** Starts listening and returns the local port. */
    fun start(): Int {
        val server = ServerSocket()
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
        listening = server
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                pool.execute { serve(client) }
            }
        }, "dns-proxy-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    fun stop() {
        runCatching { listening?.close() }
        listening = null
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun lookUp(host: String): String? {
        if (XboxDns.toInt(host) != null) return host
        known[host]?.let { return it }
        for (server in servers) {
            val answer = XboxDns.resolve(server, host)?.firstOrNull { !XboxDns.isUnroutable(it) }
            if (answer != null) {
                val text = XboxDns.toText(answer)
                if (known.putIfAbsent(host, text) == null) resolved.incrementAndGet()
                return text
            }
        }
        if (failedNames.add(host)) failed.incrementAndGet()
        return null
    }

    private fun serve(client: Socket) {
        sockets.add(client)
        var origin: Socket? = null
        try {
            client.soTimeout = 30_000
            val head = readHead(client.getInputStream()) ?: return
            val target = parseTarget(String(head, Charsets.ISO_8859_1)) ?: return
            val address = lookUp(target.host)
            if (address == null) {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            origin = Socket().also {
                sockets.add(it)
                it.connect(InetSocketAddress(address, target.port), 10_000)
                it.tcpNoDelay = true
            }
            client.soTimeout = 0
            if (target.tunnel) {
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            } else {
                origin.getOutputStream().write(head)
            }
            val there = origin
            val back = pool.submit(Runnable { pump(there, client) })
            pump(client, there)
            runCatching { back.get() }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
            runCatching { origin?.close() }
            sockets.remove(client)
            origin?.let { sockets.remove(it) }
        }
    }

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
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    /** The request up to and including the empty line; null when it never comes. */
    private fun readHead(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var matched = 0
        while (out.size() < 32 * 1024) {
            val value = input.read()
            if (value < 0) return null
            out.write(value)
            matched = when {
                value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return out.toByteArray()
        }
        return null
    }

    data class Target(val host: String, val port: Int, val tunnel: Boolean)

    companion object {
        /** Where a proxy request wants to go: `CONNECT host:port` or `GET http://host[:port]/…`. */
        internal fun parseTarget(head: String): Target? {
            val parts = head.lineSequence().firstOrNull()?.trim()?.split(' ') ?: return null
            if (parts.size < 2) return null
            return if (parts[0].equals("CONNECT", ignoreCase = true)) {
                val at = parts[1].lastIndexOf(':')
                if (at <= 0) return null
                val port = parts[1].substring(at + 1).toIntOrNull() ?: return null
                Target(parts[1].substring(0, at).trim('[', ']'), port, tunnel = true)
            } else {
                if (!parts[1].startsWith("http://", ignoreCase = true)) return null
                val authority = parts[1].substring(7).substringBefore('/').substringAfterLast('@')
                val at = authority.lastIndexOf(':')
                val port = if (at > 0) authority.substring(at + 1).toIntOrNull() ?: return null else 80
                val host = if (at > 0) authority.substring(0, at) else authority
                if (host.isEmpty()) null else Target(host, port, tunnel = false)
            }
        }
    }
}
