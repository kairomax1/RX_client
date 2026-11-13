package com.v2ray.ang.handler

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Public anti-sanction DNS services, for devices that accept nothing but a DNS address (Xbox).
 * They are run by third parties; the app only checks from this phone which of them answer now,
 * and how fast, so the user can type the best one into the console.
 */
object ConsoleDns {

    data class Service(val name: String, val primary: String, val secondary: String)

    /** [millis] is the answer time of the faster of the two addresses. */
    data class Result(val service: Service, val millis: Long)

    val SERVICES = listOf(
        Service("Shecan", "178.22.122.100", "185.51.200.2"),
        Service("Electro", "78.157.42.100", "78.157.42.101"),
        Service("403", "10.202.10.202", "10.202.10.102"),
        Service("Radar Game", "10.202.10.10", "10.202.10.11"),
        Service("Begzar", "185.55.226.26", "185.55.225.25"),
    )

    private const val PROBE_HOST = "xboxlive.com"
    private const val TIMEOUT_MILLIS = 2500

    /** Services that answered, fastest first. Blocking; call it off the main thread. */
    fun probeAll(): List<Result> = SERVICES.map { service ->
        val times = listOfNotNull(probe(service.primary), probe(service.secondary))
        // Both addresses must work: a console falls back to the second one at any time.
        if (times.size == 2) Result(service, times.min()) else null
    }.filterNotNull().sortedBy { it.millis }

    /** Milliseconds one DNS question took, or null when no valid answer came. */
    fun probe(address: String): Long? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            val id = (System.nanoTime() and 0xFFFF).toInt()
            val query = buildQuery(PROBE_HOST, id)
            val start = System.nanoTime()
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(address), 53))
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            if (isAnswer(buffer, packet.length, id)) (System.nanoTime() - start) / 1_000_000 else null
        }
    } catch (e: Exception) {
        null
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

    /** True for a response to question [id] that reports no error and carries an answer. */
    internal fun isAnswer(data: ByteArray, length: Int, id: Int): Boolean {
        if (length < 12) return false
        val sameId = (data[0].toInt() and 0xFF) == (id shr 8 and 0xFF) && (data[1].toInt() and 0xFF) == (id and 0xFF)
        val isResponse = data[2].toInt() and 0x80 != 0
        val noError = data[3].toInt() and 0x0F == 0
        val answers = (data[6].toInt() and 0xFF shl 8) or (data[7].toInt() and 0xFF)
        return sameId && isResponse && noError && answers > 0
    }
}
