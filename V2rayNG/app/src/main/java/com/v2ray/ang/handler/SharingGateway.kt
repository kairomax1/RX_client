package com.v2ray.ang.handler

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The one door other devices come through.
 *
 * It listens for ordinary proxy requests (HTTP, the kind a console types in,
 * and SOCKS5) and carries each one to the core, which listens on this phone
 * alone. Because every device comes through here, the phone can see which
 * device is asking, ask its owner before letting it through, and cut one
 * device off without touching another.
 */
object SharingGateway {

    private const val READ_TIMEOUT_MILLIS = 30_000
    private const val UPSTREAM_TIMEOUT_MILLIS = 10_000
    private const val ASK_TIMEOUT_MILLIS = 60_000
    private const val PUMP_BUFFER_BYTES = 32 * 1024
    private const val MAX_HEADER_BYTES = 32 * 1024

    /** Told when a device the owner has not decided about asks to come in. */
    fun interface OnAsk {
        fun onAsk(ip: String, target: String)
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var port = 0

    @Volatile
    private var listener: OnAsk? = null

    /** Devices waiting for the owner's word, and when they started waiting. */
    private val asking = ConcurrentHashMap<String, Long>()

    private class Count {
        val active = AtomicInteger(0)
        val bytes = AtomicLong(0L)
        val since = AtomicLong(0L)
        val lastSeen = AtomicLong(0L)
        @Volatile
        var target: String = ""
        @Volatile
        var reaching: Boolean = true
    }

    private val counts = ConcurrentHashMap<String, Count>()

    /**
     * Opens the door, starting at [preferredPort] and taking the next free one
     * if that is taken. [onAsk] is called for a device the owner has not
     * decided about yet. Returns the port that was opened, or 0 if none was
     * free.
     */
    @Synchronized
    fun start(preferredPort: Int, onAsk: OnAsk? = null): Int {
        stop()
        this.listener = onAsk
        var server: ServerSocket? = null
        for (port in preferredPort..(preferredPort + 4)) {
            server = try {
                ServerSocket(port, 64, InetAddress.getByName("0.0.0.0"))
            } catch (e: Exception) {
                AppLog.debug(AppLog.Section.Connection, "Sharing: port $port is taken (${e.message})")
                null
            }
            if (server != null) {
                this.port = port
                break
            }
        }
        if (server == null) {
            AppLog.error(AppLog.Section.Connection, "Sharing: no port was free, so sharing stayed off")
            return 0
        }
        serverSocket = server
        running = true
        val opened = this.port
        thread(name = "rx-sharing-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(name = "rx-sharing-client", isDaemon = true) { handle(client) }
            }
        }
        AppLog.info(AppLog.Section.Connection, "Sharing: other devices can use this phone on port $opened")
        return opened
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        listener = null
        asking.clear()
        counts.clear()
    }

    fun isRunning(): Boolean = running

    /** Devices waiting for the owner to allow or refuse them. */
    fun asking(): Map<String, Long> = asking.toMap()

    /**
     * What every device that has been seen is doing, joined with what the
     * owner has decided about it.
     */
    fun snapshot(): List<Sharing.Device> {
        val stored = Sharing.devices()
        val seen = counts.entries.mapNotNull { (ip, count) ->
            Sharing.Device(
                ip = ip,
                waiting = asking.containsKey(ip),
                bytes = count.bytes.get(),
                active = count.active.get(),
                since = count.since.get(),
                lastSeen = count.lastSeen.get(),
                target = count.target,
                reaching = count.reaching,
            )
        }
        val byIp = seen.associateBy { it.ip }.toMutableMap()
        for (device in stored) {
            val current = byIp[device.ip]
            byIp[device.ip] = (current ?: Sharing.Device(ip = device.ip)).copy(
                name = device.name,
                verdict = device.verdict,
                addedAt = device.addedAt,
            )
        }
        return byIp.values.sortedWith(
            compareByDescending<Sharing.Device> { it.lastSeen }.thenBy { it.ip }
        )
    }

    // ── One device ────────────────────────────────────────────────

    private fun handle(client: Socket) {
        val ip = client.inetAddress?.hostAddress.orEmpty()
        var upstream: Socket? = null
        var opened = false
        val count = counts.computeIfAbsent(ip) { Count() }
        try {
            client.soTimeout = READ_TIMEOUT_MILLIS
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            input.mark(1)
            val first = input.read()
            input.reset()
            val socks = first == 0x05

            val request = if (socks) readSocksRequest(input, output) else readHttpRequest(input, output)
            if (request == null) return
            if (count.since.get() == 0L) count.since.set(System.currentTimeMillis())
            count.lastSeen.set(System.currentTimeMillis())
            count.target = request.target

            val verdict = decide(ip, request.target)
            if (verdict != Sharing.ALLOW) {
                AppLog.info(AppLog.Section.Connection, "Sharing: $ip was not allowed through")
                refuse(socks, output, request.refusable)
                return
            }

            val core = Socket()
            try {
                core.tcpNoDelay = true
                core.connect(
                    InetSocketAddress("127.0.0.1", SettingsManager.getSocksPort()),
                    UPSTREAM_TIMEOUT_MILLIS
                )
                core.soTimeout = READ_TIMEOUT_MILLIS
                socksConnect(core, request.host, request.port)
            } catch (e: Exception) {
                // Nothing reached the tunnel: either the phone's own connection
                // is down, or the core is not listening. Both are worth saying.
                count.reaching = false
                AppLog.error(
                    AppLog.Section.Connection,
                    "Sharing: the phone's own connection did not carry $ip (${e.message ?: "no answer"})"
                )
                core.close()
                refuse(socks, output, request.refusable, unavailable = true)
                return
            }
            count.reaching = true
            upstream = core

            if (!agree(socks, output, request)) return
            if (!request.isConnect) {
                core.getOutputStream().apply {
                    write(request.head)
                    flush()
                }
            }

            client.soTimeout = 0
            core.soTimeout = 0
            count.active.incrementAndGet()
            opened = true
            val back = thread(name = "rx-sharing-down", isDaemon = true) {
                pump(core.getInputStream(), output, count)
            }
            pump(input, core.getOutputStream(), count)
            try {
                back.join(2000)
            } catch (_: Exception) {
            }
            count.lastSeen.set(System.currentTimeMillis())
        } catch (e: Exception) {
            AppLog.debug(AppLog.Section.Connection, "Sharing: ${e.message ?: "a device left"}")
        } finally {
            if (opened) count.active.decrementAndGet()
            try {
                upstream?.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    /** What a device asked for, in the words both proxy kinds share. */
    private class Request(
        val host: String,
        val port: Int,
        val target: String,
        val isConnect: Boolean,
        val head: ByteArray,
        /** True when the request can be answered with a refusal of its own kind. */
        val refusable: Boolean,
    )

    private fun readHttpRequest(input: BufferedInputStream, output: OutputStream): Request? {
        val head = readUntilBlankLine(input) ?: return null
        val text = String(head, Charsets.ISO_8859_1)
        val line = text.substringBefore("\r\n")
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val connect = parts[0].equals("CONNECT", ignoreCase = true)
        val raw = parts[1]
        // Plain requests keep the full form they were written in; that is what
        // a site expects to see once it is reached through the tunnel.
        val withoutScheme = raw.substringAfter("://", raw)
        val authority = if (connect) raw else withoutScheme.substringBefore('/')
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "").toIntOrNull() ?: if (connect) 443 else 80
        if (host.isEmpty()) return null
        return Request(host, port, "$host:$port", connect, head, refusable = true)
    }

    private fun readSocksRequest(input: InputStream, output: OutputStream): Request? = try {
        val version = input.read()
        if (version != 0x05) return null
        val methods = input.read()
        repeat(methods.coerceAtLeast(0)) { input.read() }
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val head = ByteArray(4)
        if (readFully(input, head) < head.size) return null
        if (head[0] != 0x05.toByte()) return null
        val command = head[1].toInt() and 0xFF
        val addressType = head[3].toInt() and 0xFF
        val host = when (addressType) {
            0x01 -> {
                val bytes = ByteArray(4)
                if (readFully(input, bytes) < 4) return null
                InetAddress.getByAddress(bytes).hostAddress.orEmpty()
            }
            0x03 -> {
                val length = input.read()
                if (length <= 0) return null
                val bytes = ByteArray(length)
                if (readFully(input, bytes) < length) return null
                String(bytes, Charsets.ISO_8859_1)
            }
            0x04 -> {
                val bytes = ByteArray(16)
                if (readFully(input, bytes) < 16) return null
                InetAddress.getByAddress(bytes).hostAddress.orEmpty()
            }
            else -> return null
        }
        val portBytes = ByteArray(2)
        if (readFully(input, portBytes) < 2) return null
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        // Only a tunnel is offered; carrying loose packets is another matter.
        if (command != 0x01 || host.isEmpty()) {
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return null
        }
        Request(host, port, "$host:$port", isConnect = true, head = ByteArray(0), refusable = false)
    } catch (_: Exception) {
        null
    }

    /** Asks the owner about a device it has not decided on, and waits. */
    private fun decide(ip: String, target: String): String {
        val stored = Sharing.verdictOf(ip)
        if (stored != Sharing.ASK) return stored
        // Sharing that waits to be approved one device at a time is sharing that
        // does not work: the notice is missed, the question goes unanswered, and
        // an unanswered question is a refusal. A device is let through and shown
        // on the screen instead, where the owner can shut it out by hand.
        if (Sharing.letsNewDevicesIn()) {
            Sharing.allow(ip, "")
            return Sharing.ALLOW
        }
        asking[ip] = System.currentTimeMillis()
        return try {
            try {
                listener?.onAsk(ip, target)
            } catch (_: Exception) {
            }
            val deadline = System.currentTimeMillis() + ASK_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline && running) {
                val verdict = Sharing.verdictOf(ip)
                if (verdict != Sharing.ASK) return verdict
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                }
            }
            // No answer is the same as no. Nothing is stored, so the owner is
            // asked again the next time the device tries.
            Sharing.BLOCK
        } finally {
            asking.remove(ip)
        }
    }

    private fun refuse(socks: Boolean, output: OutputStream, refusable: Boolean, unavailable: Boolean = false) {
        val answer = when {
            !socks && refusable && unavailable ->
                "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray()
            !socks && refusable ->
                "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray()
            socks ->
                byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
            else -> ByteArray(0)
        }
        try {
            output.write(answer)
            output.flush()
        } catch (_: Exception) {
        }
    }

    /** Tells the device the tunnel is open. */
    private fun agree(socks: Boolean, output: OutputStream, request: Request): Boolean = try {
        if (socks) {
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        } else if (request.isConnect) {
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
        }
        output.flush()
        true
    } catch (_: Exception) {
        false
    }

    /** Opens a tunnel through the core's SOCKS door on this phone. */
    private fun socksConnect(socket: Socket, host: String, port: Int) {
        val output = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())
        val user = SettingsManager.getSocksUsername()
        val methods = if (user == null) byteArrayOf(0x00) else byteArrayOf(0x00, 0x02)
        output.write(0x05)
        output.write(methods.size)
        output.write(methods)
        output.flush()
        if (input.readUnsignedByte() != 0x05) error("the core did not answer as SOCKS5")
        when (val method = input.readUnsignedByte()) {
            0x00 -> Unit
            0x02 -> authenticate(output, input, user.orEmpty())
            else -> error("the core asks for a kind of login this phone does not do ($method)")
        }
        val name = host.toByteArray(Charsets.ISO_8859_1)
        val request = java.io.ByteArrayOutputStream().apply {
            write(0x05)
            write(0x01)
            write(0x00)
            // A name is sent as a name: the core resolves it, as a browser's
            // proxy would, so an address this phone cannot resolve still works.
            write(0x03)
            write(name.size.coerceAtMost(255))
            write(name, 0, name.size.coerceAtMost(255))
            write((port shr 8) and 0xFF)
            write(port and 0xFF)
        }.toByteArray()
        output.write(request)
        output.flush()

        val reply = ByteArray(4)
        readFully(input, reply)
        if (reply[1] != 0x00.toByte()) error("the core refused the tunnel (${reply[1]})")
        val bound = when (reply[3].toInt() and 0xFF) {
            0x01 -> 4
            0x03 -> input.readUnsignedByte()
            0x04 -> 16
            else -> 0
        }
        readFully(input, ByteArray(bound + 2))
    }

    /** Says the proxy name and password when the core asks for them. */
    private fun authenticate(output: DataOutputStream, input: DataInputStream, user: String) {
        val password = SettingsManager.getSocksPassword().orEmpty()
        val userBytes = user.toByteArray(Charsets.ISO_8859_1)
        val passwordBytes = password.toByteArray(Charsets.ISO_8859_1)
        output.write(0x01)
        output.write(userBytes.size)
        output.write(userBytes)
        output.write(passwordBytes.size)
        output.write(passwordBytes)
        output.flush()
        if (input.readUnsignedByte() != 0x01) error("the core refused the login answer")
        if (input.readUnsignedByte() != 0x00) error("the core refused the proxy name and password")
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return read
    }

    private fun readUntilBlankLine(input: BufferedInputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return if (buffer.size() == 0) null else buffer.toByteArray()
            buffer.write(next)
            val c = next.toChar()
            matched = when {
                (matched == 0 || matched == 2) && c == '\r' -> matched + 1
                (matched == 1 || matched == 3) && c == '\n' -> matched + 1
                else -> 0
            }
            if (matched == 4) return buffer.toByteArray()
        }
        return null
    }

    private fun pump(input: InputStream, output: OutputStream, count: Count) {
        val buffer = ByteArray(PUMP_BUFFER_BYTES)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: Exception) {
                -1
            }
            if (read <= 0) break
            count.bytes.addAndGet(read.toLong())
            count.lastSeen.set(System.currentTimeMillis())
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

    /**
     * The interfaces a mobile network uses. Their address is the carrier's own,
     * not one another device can reach this phone on: sharing it produces a
     * door nobody can knock at, which is worse than saying there is no door.
     */
    private val MOBILE_INTERFACES = listOf("rmnet", "ccmni", "wwan", "pdp", "clat")

    /** The tunnel this phone itself is wearing; never a way in for somebody else. */
    private val TUNNEL_INTERFACES = listOf("tun")

    /**
     * The addresses of this phone other devices can reach it on, and whether
     * the phone is hosting a network of its own (a hotspot, or sharing by
     * cable) rather than only sitting on someone else's Wi-Fi.
     */
    fun network(): Pair<List<String>, Boolean> {
        val addresses = mutableListOf<String>()
        val hostingAddresses = mutableListOf<String>()
        var hosting = false
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (MOBILE_INTERFACES.any { name.startsWith(it) } || TUNNEL_INTERFACES.any { name.startsWith(it) }) continue
                val local = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .mapNotNull { it.hostAddress }
                if (local.isEmpty()) continue
                val isHosting = name.startsWith("ap") ||
                    name.startsWith("swlan") ||
                    name.startsWith("softap") ||
                    name.startsWith("rndis") ||
                    name.startsWith("bnep") ||
                    local.any { it.endsWith(".1") }
                if (isHosting) {
                    hosting = true
                    hostingAddresses += local
                } else {
                    addresses += local
                }
            }
        } catch (_: Exception) {
        }
        // The network this phone is giving out is the one another device is on:
        // its address is named first, before any network the phone merely sits on.
        val ordered = (hostingAddresses.sortedByDescending { it.endsWith(".1") } + addresses).distinct()
        return Pair(ordered, hosting)
    }
}
