package com.v2ray.ang.handler

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Finds DNS servers that work for an Xbox, which accepts nothing but a DNS address.
 *
 * The app carries every public DNS address the open lists know (about a hundred thousand, most
 * of them nameless) plus the Iranian anti-sanction services. A scan has two steps:
 *
 * 1. Sweep: two Xbox names are asked of every address, all through one socket, a few hundred
 *    a second. An ordinary resolver answers with Microsoft's own, different addresses. A
 *    service that gets around sanctions answers both with the address of its own relay; that
 *    is how such servers are told apart without knowing them beforehand.
 * 2. Proof: for those, the named services and the quickest ordinary ones, four Xbox names
 *    (site, sign-in, Xbox Live, store) are resolved and a real TLS connection is made to each
 *    answer and checked against the site's certificate. A server that points to a dead or
 *    forged address fails here.
 *
 * What cannot be proven from a phone: that the console itself signs in. The in-app sign-in
 * page, which resolves every name through the chosen server, is the closest check.
 */
object XboxDns {

    enum class Kind { Sanction, Isp, Global, Unknown }

    /** A service with a name, from the bundled list. */
    data class Named(val name: String, val primary: String, val secondary: String, val kind: Kind)

    /** What one server said during the sweep. */
    data class Heard(val address: Int, val millis: Long, val first: List<Int>, val second: List<Int>)

    data class Proven(
        val address: String,
        /** Empty for a nameless server. */
        val name: String,
        /** The other address of a named service, when it passed too; else empty. */
        val partner: String,
        val redirects: Boolean,
        val passed: Int,
        val millis: Long,
    )

    /** Asked in the sweep: two names that an honest resolver never answers with one address. */
    internal val SWEEP_NAMES = listOf("login.live.com", "www.xbox.com")

    /** Checked in the proof: the site, sign-in, Xbox Live and the store. */
    val PROOF_NAMES = listOf("www.xbox.com", "login.live.com", "user.auth.xboxlive.com", "displaycatalog.mp.microsoft.com")

    private const val SWEEP_BURST = 60
    private const val SWEEP_PAUSE_MILLIS = 70L
    private const val SWEEP_TAIL_MILLIS = 2500L
    private const val QUERY_TIMEOUT_MILLIS = 2000
    private const val CONNECT_TIMEOUT_MILLIS = 4000

    fun toText(address: Int): String =
        "${address ushr 24 and 0xFF}.${address ushr 16 and 0xFF}.${address ushr 8 and 0xFF}.${address and 0xFF}"

    fun toInt(address: String): Int? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            value = (value shl 8) or number
        }
        return value
    }

    internal fun parseNamed(text: String): List<Named> = text.lines().mapNotNull { line ->
        val parts = line.trim().split('|')
        if (line.startsWith("#") || parts.size < 4 || toInt(parts[1]) == null) return@mapNotNull null
        val kind = when (parts[3]) {
            "sanction" -> Kind.Sanction
            "isp" -> Kind.Isp
            "global" -> Kind.Global
            else -> Kind.Unknown
        }
        Named(parts[0], parts[1], parts[2].takeIf { toInt(it) != null }.orEmpty(), kind)
    }

    internal fun parseAddresses(text: String): List<Int> =
        text.lines().mapNotNull { if (it.startsWith("#")) null else toInt(it) }

    /** Four bytes per address, as the bundled list stores them. */
    internal fun parsePacked(bytes: ByteArray): IntArray = IntArray(bytes.size / 4) { index ->
        val at = index * 4
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
    }

    fun loadNamed(context: Context): List<Named> =
        parseNamed(context.assets.open("rx_dns_named.txt").bufferedReader().use { it.readText() })

    /**
     * Every address to sweep, in the order to ask them: named services, then the addresses
     * Iranian tools list, then everything else.
     */
    fun loadAll(context: Context, named: List<Named>): IntArray {
        val first = named.flatMap { listOfNotNull(toInt(it.primary), toInt(it.secondary)) }
        val second = parseAddresses(context.assets.open("rx_dns_priority.txt").bufferedReader().use { it.readText() })
        val rest = parsePacked(context.assets.open("rx_dns_all.bin").use { it.readBytes() })
        val seen = HashSet<Int>(rest.size * 2)
        val ordered = ArrayList<Int>(rest.size + 256)
        for (address in first + second) if (seen.add(address)) ordered.add(address)
        for (address in rest) if (seen.add(address)) ordered.add(address)
        return ordered.toIntArray()
    }

    /** IPv4 addresses in the answer to question [id]; null when it is not a clean answer to it. */
    internal fun parseAnswer(data: ByteArray, length: Int, id: Int?): List<Int>? = try {
        fun u16(at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
        if (length < 12 || (id != null && u16(0) != id)) {
            null
        } else if ((data[2].toInt() and 0x80) == 0 || (data[3].toInt() and 0x0F) != 0) {
            null
        } else {
            fun skipName(start: Int): Int {
                var at = start
                while (true) {
                    val size = data[at].toInt() and 0xFF
                    if (size == 0) return at + 1
                    if ((size and 0xC0) == 0xC0) return at + 2
                    at += 1 + size
                }
            }
            var at = 12
            repeat(u16(4)) { at = skipName(at) + 4 }
            val found = ArrayList<Int>()
            repeat(u16(6)) {
                at = skipName(at)
                val type = u16(at)
                val size = u16(at + 8)
                val value = at + 10
                at = value + size
                if (at > length) return null
                if (type == 1 && size == 4) {
                    found.add(
                        ((data[value].toInt() and 0xFF) shl 24) or ((data[value + 1].toInt() and 0xFF) shl 16) or
                            ((data[value + 2].toInt() and 0xFF) shl 8) or (data[value + 3].toInt() and 0xFF)
                    )
                }
            }
            found
        }
    } catch (e: Exception) {
        null
    }

    /** Addresses no real site lives at: what filters and broken resolvers answer with. */
    internal fun isUnroutable(address: Int): Boolean {
        val a = address ushr 24 and 0xFF
        val b = address ushr 16 and 0xFF
        return a == 0 || a == 10 || a == 127 || a >= 224 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    /**
     * True when the two sweep answers share an address that could be a relay: the mark of a
     * server that sends Xbox traffic through its own machine.
     */
    internal fun redirects(first: List<Int>, second: List<Int>): Boolean =
        first.any { it in second && !isUnroutable(it) }

    /** True when the server gave a usable answer for both names. */
    internal fun answersBoth(heard: Heard): Boolean =
        heard.first.any { !isUnroutable(it) } && heard.second.any { !isUnroutable(it) }

    /** Question id for a server and one of the two sweep names; the low bit says which name. */
    internal fun sweepId(address: Int, name: Int): Int = (((address * 31 + (address ushr 13)) and 0x7FFF) shl 1) or name

    /**
     * Asks every address the two sweep names. [onHeard] is called (from another thread) as
     * soon as a server has answered both; [onSent] with how many were asked so far. Returns
     * when all were asked and the last answers had time to arrive, or [cancelled] was set.
     */
    fun sweep(addresses: IntArray, cancelled: AtomicBoolean, onSent: (Int) -> Unit, onHeard: (Heard) -> Unit) {
        val index = HashMap<Int, Int>(addresses.size * 2)
        addresses.forEachIndexed { position, address -> index[address] = position }
        val sentAt = LongArray(addresses.size)
        val answers = arrayOfNulls<List<Int>>(addresses.size * 2)
        val queries = SWEEP_NAMES.map { ConsoleDns.buildQuery(it, 0) }
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            runCatching { socket.receiveBufferSize = 4 * 1024 * 1024 }
            val sending = AtomicBoolean(true)
            val listener = Thread({
                val buffer = ByteArray(1500)
                val packet = DatagramPacket(buffer, buffer.size)
                var quietSince = 0L
                while (!cancelled.get()) {
                    try {
                        packet.length = buffer.size
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        if (!sending.get()) {
                            if (quietSince == 0L) quietSince = System.currentTimeMillis()
                            if (System.currentTimeMillis() - quietSince > SWEEP_TAIL_MILLIS) break
                        }
                        continue
                    } catch (e: Exception) {
                        break
                    }
                    if (!sending.get()) quietSince = 0L
                    val raw = packet.address.address
                    if (raw.size != 4 || packet.length < 12) continue
                    val from = ((raw[0].toInt() and 0xFF) shl 24) or ((raw[1].toInt() and 0xFF) shl 16) or
                        ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
                    val position = index[from] ?: continue
                    val id = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
                    val name = id and 1
                    if (id != sweepId(from, name)) continue
                    val slot = position * 2 + name
                    if (answers[slot] != null) continue
                    answers[slot] = parseAnswer(buffer, packet.length, id) ?: emptyList()
                    val first = answers[position * 2]
                    val second = answers[position * 2 + 1]
                    if (first != null && second != null) {
                        onHeard(Heard(from, (System.nanoTime() - sentAt[position]) / 1_000_000, first, second))
                    }
                }
            }, "xbox-dns-listen").apply { isDaemon = true }
            listener.start()
            var position = 0
            while (position < addresses.size && !cancelled.get()) {
                val end = minOf(position + SWEEP_BURST, addresses.size)
                while (position < end) {
                    val address = addresses[position]
                    val target = InetAddress.getByAddress(
                        byteArrayOf((address ushr 24).toByte(), (address ushr 16).toByte(), (address ushr 8).toByte(), address.toByte())
                    )
                    sentAt[position] = System.nanoTime()
                    for (name in queries.indices) {
                        val query = queries[name].clone()
                        val id = sweepId(address, name)
                        query[0] = (id shr 8).toByte()
                        query[1] = id.toByte()
                        // An address the phone has no route to throws; that server is simply silent.
                        runCatching { socket.send(DatagramPacket(query, query.size, target, 53)) }
                    }
                    position++
                }
                onSent(position)
                Thread.sleep(SWEEP_PAUSE_MILLIS)
            }
            sending.set(false)
            listener.join(SWEEP_TAIL_MILLIS + 1500)
        }
    }

    /** The IPv4 addresses [server] gives for [host]; null when it does not answer. */
    fun resolve(server: String, host: String): List<Int>? {
        repeat(2) {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = QUERY_TIMEOUT_MILLIS
                    val id = (System.nanoTime() and 0xFFFF).toInt()
                    val query = ConsoleDns.buildQuery(host, id)
                    socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
                    val buffer = ByteArray(1500)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseAnswer(buffer, packet.length, id)?.let { return it }
                }
            } catch (e: Exception) {
                // Asked once more below.
            }
        }
        return null
    }

    /**
     * How many of [PROOF_NAMES] the server resolves to an address that really is that site: a
     * TLS connection is made to the answer and the certificate must belong to the name.
     */
    fun prove(server: String, cancelled: AtomicBoolean): Int = PROOF_NAMES.count { host ->
        if (cancelled.get()) return@count false
        val answer = resolve(server, host)?.firstOrNull { !isUnroutable(it) } ?: return@count false
        isGenuine(toText(answer), host)
    }

    /** True when [address] presents a valid certificate for [host]. */
    internal fun isGenuine(address: String, host: String): Boolean = try {
        Socket().use { plain ->
            plain.connect(InetSocketAddress(address, 443), CONNECT_TIMEOUT_MILLIS)
            plain.soTimeout = CONNECT_TIMEOUT_MILLIS
            val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, 443, true) as SSLSocket
            secure.use {
                it.startHandshake()
                HttpsURLConnection.getDefaultHostnameVerifier().verify(host, it.session)
            }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Best first: servers that relay Xbox traffic before ordinary ones, then more names
     * proven, then named services, then the quicker answer.
     */
    internal fun rank(results: List<Proven>): List<Proven> = results.sortedWith(
        compareByDescending<Proven> { it.redirects }
            .thenByDescending { it.passed }
            .thenByDescending { it.name.isNotEmpty() }
            .thenBy { it.millis }
    )
}
