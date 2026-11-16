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
 * Finds DNS servers that get the Xbox app past a sanction block.
 *
 * Xbox: finds DNS servers that get an Xbox, or the Xbox app, past the sanction block. The console
 * accepts nothing but a DNS address, so only a server that sends the Xbox Live names to a
 * relay of its own is of any use; an ordinary resolver, however quick, changes nothing.
 *
 * The site xbox.com opens from Iran without help, so it proves nothing and is not asked. The
 * names asked are the ones the Xbox app and the console talk to ([APP_NAMES]).
 *
 * 1. Sweep: two Xbox Live sign-in names are asked of every bundled address through one
 *    socket. Thousands of ordinary resolvers give the same Microsoft addresses; a server
 *    that gives an address hardly anyone else gives, outside Microsoft's own ranges, is a
 *    suspect.
 * 2. Examination of each suspect and each named anti-sanction service: all of [APP_NAMES]
 *    are asked. The server is a relay when it sends an Xbox Live name and at least one more
 *    name to one such address, and that address presents the real certificate of each name
 *    (so it passes the traffic on to Microsoft rather than faking it).
 * 3. For a relay, the country Microsoft sees is asked through the relay, where it lets
 *    that through.
 *
 * What cannot be proven from here: that the Xbox app signs in. Only the app itself, with the
 * server set as the DNS, shows that.
 */
object XboxDns {

    enum class Kind { Sanction, Isp, Global, Unknown }

    /** A service with a name, from the bundled list. */
    data class Named(val name: String, val primary: String, val secondary: String, val kind: Kind)

    /** What one server said during the sweep: one list of addresses per name asked. */
    data class Heard(val address: Int, val millis: Long, val answers: List<List<Int>>)

    data class Proven(
        val address: String,
        /** Empty for a nameless server. */
        val name: String,
        /** The other address of a named service, when it is a result of the same sort; else empty. */
        val partner: String,
        /** True for a relay; false for an ordinary resolver. */
        val redirects: Boolean,
        /** How many of [APP_NAMES] the relay carries; 0 for an ordinary resolver. */
        val passed: Int,
        val millis: Long,
        /** Two-letter country Microsoft sees through the relay; empty when unknown. */
        val country: String = "",
    )

    /** What the examination of one server found. */
    data class Examined(val relay: Int?, val carried: Int, val country: String, val answered: Boolean)

    /**
     * Names the Xbox app and the console use: sign-in of the app, user and service tokens,
     * title management, profile, the store catalogue, the Microsoft account, the app's own
     * content service. The first two are the ones asked in the sweep. At most eight.
     */
    val APP_NAMES = listOf(
        "sisu.xboxlive.com",
        "user.auth.xboxlive.com",
        "xsts.auth.xboxlive.com",
        "title.mgt.xboxlive.com",
        "profile.xboxlive.com",
        "displaycatalog.mp.microsoft.com",
        "login.live.com",
        "emerald.xboxservices.com",
    )

    internal const val SWEEP_NAMES = 2

    /** What the user wants a DNS server for. */
    enum class Purpose { Xbox }

    /** Lists of open DNS servers, renewed by their makers every day; read by [updateList]. */
    private val LIST_SOURCES = listOf(
        "https://raw.githubusercontent.com/proabiral/Fresh-Resolvers/master/resolvers.txt",
        "https://raw.githubusercontent.com/rix4uni/resolvers/main/resolvers.txt",
        "https://raw.githubusercontent.com/trickest/resolvers/main/resolvers.txt",
        "https://raw.githubusercontent.com/blechschmidt/massdns/master/lists/resolvers.txt",
        "https://public-dns.info/nameserver/ir.txt",
    )
    private const val EXTRA_FILE = "rx-dns-extra.txt"
    private const val KEY_LIST_CHECKED = "rx_dns_list_checked"
    private const val LIST_EVERY_MILLIS = 15 * 60 * 1000L

    /**
     * Adds the servers of the public lists to the ones the app carries. Looks at most every
     * fifteen minutes; a list that did not change costs one short request. Returns how many
     * servers the phone now knows beyond the bundled ones.
     */
    fun updateList(context: Context): Int {
        val file = java.io.File(context.filesDir, EXTRA_FILE)
        val known = LinkedHashSet<Int>()
        if (file.exists()) runCatching { known.addAll(parseAddresses(file.readText())) }
        val now = System.currentTimeMillis()
        if (now - MmkvManager.decodeSettingsLong(KEY_LIST_CHECKED, 0L) < LIST_EVERY_MILLIS) return known.size
        MmkvManager.encodeSettings(KEY_LIST_CHECKED, now)
        val before = known.size
        var answered = 0
        for (source in LIST_SOURCES) {
            val tagKey = "rx_dns_tag_" + source.hashCode().toUInt().toString(16)
            val answer = PublicConfigs.fetch(source, MmkvManager.decodeSettingsString(tagKey).takeIf { file.exists() })
            if (answer.code == 200 || answer.code == 304) answered++
            val body = answer.body ?: continue
            known.addAll(parseAddresses(body).filterNot { isUnroutable(it) })
            MmkvManager.encodeSettings(tagKey, answer.tag)
        }
        if (known.size != before) runCatching { file.writeText(known.joinToString("\n") { toText(it) }) }
        AppLog.info(AppLog.Section.Other, "DNS scanner: $answered of ${LIST_SOURCES.size} server lists answered, ${known.size - before} new servers, ${known.size} beyond the bundled ones")
        return known.size
    }

    /** A Microsoft service that answers with the country it sees the caller in. */
    private const val GEO_HOST = "geo.prod.do.dsp.mp.microsoft.com"

    private const val SWEEP_BURST = 60
    private const val SWEEP_PAUSE_MILLIS = 70L
    private const val SWEEP_TAIL_MILLIS = 2500L
    private const val QUERY_TIMEOUT_MILLIS = 2000
    private const val CONNECT_TIMEOUT_MILLIS = 4000

    /**
     * Microsoft's own address space, coarsely: first address and prefix length. Only used to
     * avoid taking a far-away Microsoft data centre for a relay.
     */
    private val MICROSOFT = listOf(
        "4.144.0.0" to 12, "4.160.0.0" to 11, "4.192.0.0" to 10, "13.64.0.0" to 11, "13.96.0.0" to 13,
        "13.104.0.0" to 14, "20.0.0.0" to 8, "23.96.0.0" to 13, "40.64.0.0" to 10, "51.4.0.0" to 15,
        "51.8.0.0" to 16, "51.10.0.0" to 15, "51.103.0.0" to 16, "51.104.0.0" to 15, "51.116.0.0" to 16,
        "51.120.0.0" to 16, "51.124.0.0" to 16, "51.132.0.0" to 16, "51.136.0.0" to 13, "51.144.0.0" to 15,
        "52.96.0.0" to 12, "52.112.0.0" to 14, "52.120.0.0" to 14, "52.136.0.0" to 13, "52.144.0.0" to 12,
        "52.160.0.0" to 11, "52.224.0.0" to 11, "65.52.0.0" to 14, "70.37.0.0" to 17, "104.40.0.0" to 13,
        "104.208.0.0" to 13, "131.253.0.0" to 16, "134.170.0.0" to 16, "137.116.0.0" to 15,
        "137.135.0.0" to 16, "138.91.0.0" to 16, "150.171.0.0" to 16, "157.54.0.0" to 15, "157.56.0.0" to 14,
        "168.61.0.0" to 16, "168.62.0.0" to 15, "172.160.0.0" to 11, "191.232.0.0" to 13, "204.79.195.0" to 24,
        "204.79.196.0" to 23,
    ).map { (first, bits) -> toInt(first)!! to (-1 shl (32 - bits)) }

    internal fun isMicrosoft(address: Int): Boolean = MICROSOFT.any { (first, mask) -> address and mask == first }

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
     * Iranian tools list, then everything else, then what [updateList] brought.
     */
    fun loadAll(context: Context, named: List<Named>): IntArray {
        val first = named.flatMap { listOfNotNull(toInt(it.primary), toInt(it.secondary)) }
        val second = parseAddresses(context.assets.open("rx_dns_priority.txt").bufferedReader().use { it.readText() })
        val rest = parsePacked(context.assets.open("rx_dns_all.bin").use { it.readBytes() })
        val extra = runCatching { parseAddresses(java.io.File(context.filesDir, EXTRA_FILE).readText()) }.getOrDefault(emptyList())
        val seen = HashSet<Int>(rest.size * 2)
        val ordered = ArrayList<Int>(rest.size + extra.size + 256)
        for (address in first + second) if (seen.add(address)) ordered.add(address)
        for (address in rest) if (seen.add(address)) ordered.add(address)
        for (address in extra) if (seen.add(address)) ordered.add(address)
        return ordered.toIntArray()
    }

    /** A plain DNS question for the A record of [host]. */
    internal fun buildQuery(host: String, id: Int): ByteArray {
        val name = host.split('.').flatMap { label -> listOf(label.length.toByte()) + label.toByteArray(Charsets.US_ASCII).toList() }
        val header = byteArrayOf(
            (id shr 8).toByte(), id.toByte(),
            0x01, 0x00, // recursion desired
            0x00, 0x01, // one question
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        return header + name.toByteArray() + byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x01)
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

    /** True when the server gave a usable answer for at least one sweep name. */
    internal fun answered(heard: Heard): Boolean = heard.answers.any { list -> list.any { !isUnroutable(it) } }

    /**
     * Addresses so many servers give that they can only be Microsoft's own: given by more
     * than [limit] of the servers in [heard].
     */
    internal fun commonAddresses(heard: List<Heard>, limit: Int): Set<Int> {
        val givenBy = HashMap<Int, Int>()
        for (reply in heard) for (address in reply.answers.flatten().toSet()) givenBy[address] = (givenBy[address] ?: 0) + 1
        return givenBy.filterValues { it > limit }.keys
    }

    /** True for an address that could be a relay: reachable, not Microsoft's, not what everyone gives. */
    internal fun isUnusual(address: Int, common: Set<Int>, own: (Int) -> Boolean = ::isMicrosoft): Boolean =
        !isUnroutable(address) && address !in common && !own(address)

    /** True when a sweep reply holds an address worth examining. */
    fun isSuspect(heard: Heard, common: Set<Int>, own: (Int) -> Boolean = ::isMicrosoft): Boolean =
        heard.answers.any { list -> list.any { isUnusual(it, common, own) } }

    /**
     * The relay a server points to, from its answers to [APP_NAMES] in that order: an unusual
     * address given for an Xbox Live name and for at least one more name. Null when there is
     * none, which is what an ordinary resolver looks like.
     */
    internal fun relayAddress(answers: List<List<Int>>, common: Set<Int>): Int? {
        val names = HashMap<Int, MutableList<Int>>()
        answers.forEachIndexed { name, list ->
            for (address in list.toSet()) if (isUnusual(address, common)) names.getOrPut(address) { ArrayList() }.add(name)
        }
        return names.filterValues { held -> held.size >= 2 && held.any { APP_NAMES[it].endsWith(".xboxlive.com") } }
            .maxByOrNull { it.value.size }?.key
    }

    /** Question id for a server and the index of a name (0 to 7), carried in the low three bits. */
    internal fun sweepId(address: Int, name: Int): Int = (((address * 31 + (address ushr 13)) and 0x1FFF) shl 3) or name

    /**
     * Asks every address the first [SWEEP_NAMES] of [names]. [onHeard] is called (from another
     * thread) as soon as a server has answered them all; [onSent] with how many were asked so
     * far. Returns when all were asked and the last answers had time to arrive, or
     * [cancelled] was set.
     */
    fun sweep(
        addresses: IntArray,
        cancelled: AtomicBoolean,
        onSent: (Int) -> Unit,
        onHeard: (Heard) -> Unit,
        names: List<String> = APP_NAMES.take(SWEEP_NAMES),
    ) {
        val index = HashMap<Int, Int>(addresses.size * 2)
        addresses.forEachIndexed { position, address -> index[address] = position }
        val sentAt = LongArray(addresses.size)
        val answers = arrayOfNulls<List<Int>>(addresses.size * SWEEP_NAMES)
        val queries = names.take(SWEEP_NAMES).map { buildQuery(it, 0) }
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
                    val name = id and 7
                    if (name >= SWEEP_NAMES || id != sweepId(from, name)) continue
                    val slot = position * SWEEP_NAMES + name
                    if (answers[slot] != null) continue
                    answers[slot] = parseAnswer(buffer, packet.length, id) ?: emptyList()
                    val all = (0 until SWEEP_NAMES).map { answers[position * SWEEP_NAMES + it] }
                    if (all.none { it == null }) {
                        onHeard(Heard(from, (System.nanoTime() - sentAt[position]) / 1_000_000, all.map { it.orEmpty() }))
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
                    val query = buildQuery(host, id)
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
     * Asks [server] every name the Xbox app uses and decides whether it is a relay: see the
     * description of the class. [common] is what [commonAddresses] found in the sweep.
     */
    fun examine(server: String, common: Set<Int>, cancelled: AtomicBoolean): Examined {
        val answers = APP_NAMES.map { host -> if (cancelled.get()) emptyList() else resolve(server, host).orEmpty() }
        val answered = answers.any { it.isNotEmpty() }
        val relay = relayAddress(answers, common) ?: return Examined(null, 0, "", answered)
        val relayText = toText(relay)
        var carried = 0
        var live = false
        answers.forEachIndexed { name, list ->
            if (relay in list && !cancelled.get() && isGenuine(relayText, APP_NAMES[name])) {
                carried++
                if (APP_NAMES[name].endsWith(".xboxlive.com")) live = true
            }
        }
        // A relay that carries no Xbox Live name, or only one name, does nothing for the app.
        if (!live || carried < 2) return Examined(null, 0, "", answered)
        return Examined(relay, carried, if (cancelled.get()) "" else seenCountry(relayText), answered)
    }

    /** The host of an entry of a service: the name before an optional path. */
    internal fun hostOf(entry: String): String = entry.substringBefore('/')

    /**
     * The HTTP status the service at [entry] gives when reached at [address] with its real
     * name and certificate; -1 when the connection, the certificate or the answer fails.
     */
    internal fun probe(address: String, entry: String): Int = try {
        val host = hostOf(entry)
        secure(address, host) { socket ->
            socket.outputStream.write(
                "GET /${entry.substringAfter('/', "")} HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.outputStream.flush()
            val buffer = ByteArray(64)
            var size = 0
            while (size < 16) {
                val read = socket.inputStream.read(buffer, size, buffer.size - size)
                if (read <= 0) break
                size += read
            }
            parseStatus(String(buffer, 0, size, Charsets.ISO_8859_1))
        }
    } catch (e: Exception) {
        -1
    }

    /** The status of an HTTP answer's first line; -1 when it is none. */
    internal fun parseStatus(head: String): Int =
        Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})").find(head)?.groupValues?.get(1)?.toInt() ?: -1

    /**
     * True when a status means the service let the caller in. 403 and 451 are what sanction
     * and geo blocks answer with; -1 is a connection the filter cut.
     */
    internal fun isOpen(status: Int): Boolean = status > 0 && status != 403 && status != 451

    /** True when [address] presents a valid certificate for [host]. */
    internal fun isGenuine(address: String, host: String): Boolean = try {
        secure(address, host) { true }
    } catch (e: Exception) {
        false
    }

    private fun <T> secure(address: String, host: String, block: (SSLSocket) -> T): T = Socket().use { plain ->
        plain.connect(InetSocketAddress(address, 443), CONNECT_TIMEOUT_MILLIS)
        plain.soTimeout = CONNECT_TIMEOUT_MILLIS
        val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, 443, true) as SSLSocket
        secure.use {
            it.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, it.session)) throw SecurityException("wrong certificate")
            block(it)
        }
    }

    /**
     * The country Microsoft sees for a caller that comes through the relay at [relay]; empty
     * when the relay does not pass this service on or the answer cannot be read.
     */
    internal fun seenCountry(relay: String): String = try {
        secure(relay, GEO_HOST) { socket ->
            socket.outputStream.write(
                "GET /geo?doClientVersion=10.0.19041.1266 HTTP/1.1\r\nHost: $GEO_HOST\r\nAccept: */*\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.outputStream.flush()
            val buffer = ByteArray(8192)
            var size = 0
            while (size < buffer.size) {
                val read = socket.inputStream.read(buffer, size, buffer.size - size)
                if (read <= 0) break
                size += read
                if (parseCountry(String(buffer, 0, size, Charsets.ISO_8859_1)).isNotEmpty()) break
            }
            parseCountry(String(buffer, 0, size, Charsets.ISO_8859_1))
        }
    } catch (e: Exception) {
        ""
    }

    /** The two-letter country in the answer of the geo service; empty when there is none. */
    internal fun parseCountry(text: String): String =
        Regex("\"CountryCode\"\\s*:\\s*\"([A-Za-z]{2})\"").find(text)?.groupValues?.get(1)?.uppercase().orEmpty()

    /**
     * Best first. Relays before ordinary resolvers; among relays those Microsoft does not see
     * in Iran, then more names carried, then named services, then the quicker answer.
     */
    internal fun rank(results: List<Proven>): List<Proven> = results.sortedWith(
        compareByDescending<Proven> { it.redirects }
            .thenBy { it.country == "IR" }
            .thenByDescending { it.passed }
            .thenByDescending { it.name.isNotEmpty() }
            .thenBy { it.millis }
    )
}
