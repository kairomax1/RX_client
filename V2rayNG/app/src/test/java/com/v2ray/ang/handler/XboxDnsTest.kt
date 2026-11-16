package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XboxDnsTest {

    private fun ip(text: String) = XboxDns.toInt(text)!!

    /** A DNS answer to [id] with one A record per address, each named by a pointer to the question. */
    private fun answer(id: Int, host: String, addresses: List<String>, rcode: Int = 0): ByteArray {
        val question = XboxDns.buildQuery(host, id)
        question[2] = 0x81.toByte()
        question[3] = (0x80 or rcode).toByte()
        question[7] = addresses.size.toByte()
        var message = question
        for (address in addresses) {
            val value = ip(address)
            message += byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) +
                byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        }
        return message
    }

    @Test
    fun addressesSurviveTheTripToNumbersAndBack() {
        assertEquals("178.22.122.100", XboxDns.toText(ip("178.22.122.100")))
        assertEquals("255.0.0.1", XboxDns.toText(ip("255.0.0.1")))
        assertNull(XboxDns.toInt("1.2.3"))
        assertNull(XboxDns.toInt("1.2.3.256"))
        assertNull(XboxDns.toInt("a.b.c.d"))
        assertEquals(listOf(ip("1.2.3.4"), ip("200.100.50.25")), XboxDns.parsePacked(byteArrayOf(1, 2, 3, 4, 200.toByte(), 100, 50, 25)).toList())
    }

    @Test
    fun theBundledListsAreRead() {
        val named = XboxDns.parseNamed("# comment\nShecan|178.22.122.100|185.51.200.2|sanction\nSolo|9.9.9.9||global\nbroken|x|y|isp\n")
        assertEquals(2, named.size)
        assertEquals(XboxDns.Named("Shecan", "178.22.122.100", "185.51.200.2", XboxDns.Kind.Sanction), named[0])
        assertEquals("", named[1].secondary)
        assertEquals(listOf(ip("5.200.200.200")), XboxDns.parseAddresses("# note\n5.200.200.200\nnot an address\n"))
    }

    @Test
    fun anAnswerIsReadAndOnlyWhenItBelongsToTheQuestion() {
        val message = answer(0x1234, "login.live.com", listOf("20.190.1.1", "40.126.2.2"))
        assertEquals(listOf(ip("20.190.1.1"), ip("40.126.2.2")), XboxDns.parseAnswer(message, message.size, 0x1234))
        assertNull(XboxDns.parseAnswer(message, message.size, 0x4321), "another question's answer")
        val refused = answer(7, "login.live.com", emptyList(), rcode = 5)
        assertNull(XboxDns.parseAnswer(refused, refused.size, 7))
        val question = XboxDns.buildQuery("login.live.com", 7)
        assertNull(XboxDns.parseAnswer(question, question.size, 7), "a question is not an answer")
        assertNull(XboxDns.parseAnswer(message, message.size - 3, 0x1234), "cut short")
        assertNull(XboxDns.parseAnswer(byteArrayOf(1, 2, 3), 3, null))
    }

    private fun heard(server: String, vararg answers: List<String>) =
        XboxDns.Heard(ip(server), 10, answers.map { list -> list.map(::ip) })

    /** Answers to all the app's names: the given ones first, Microsoft's own for the rest. */
    private fun answers(vararg first: List<String>): List<List<Int>> =
        List(XboxDns.APP_NAMES.size) { index -> (first.getOrNull(index) ?: listOf("20.190.1.${index + 1}")).map(::ip) }

    @Test
    fun theNamesAskedAreTheAppsAndNotTheSite() {
        assertTrue(XboxDns.APP_NAMES.size <= 8, "the question id has three bits for the name")
        assertFalse("www.xbox.com" in XboxDns.APP_NAMES)
        assertTrue(XboxDns.APP_NAMES.take(XboxDns.SWEEP_NAMES).all { it.endsWith(".xboxlive.com") })
    }

    @Test
    fun whatManyServersGiveIsMicrosoftsOwn() {
        val crowd = (1..30).map { heard("9.9.9.$it", listOf("93.184.1.1"), listOf("93.184.1.2")) }
        val odd = heard("178.22.122.100", listOf("178.22.122.110"), listOf("178.22.122.110"))
        val common = XboxDns.commonAddresses(crowd + odd, 10)
        assertEquals(setOf(ip("93.184.1.1"), ip("93.184.1.2")), common)
        assertTrue(XboxDns.isSuspect(odd, common))
        assertFalse(XboxDns.isSuspect(crowd[0], common))
        assertFalse(XboxDns.isSuspect(heard("1.2.3.4", listOf("20.190.5.5"), listOf("13.107.4.4")), common), "Microsoft's ranges")
        assertFalse(XboxDns.isSuspect(heard("1.2.3.4", listOf("10.10.34.34"), listOf("0.0.0.0")), common), "the filtering page")
        assertTrue(XboxDns.answered(odd))
        assertFalse(XboxDns.answered(heard("1.2.3.4", emptyList(), listOf("127.0.0.1"))))
    }

    @Test
    fun aRelaySendsAnXboxLiveNameAndOneMoreToItsOwnAddress() {
        val relay = listOf("178.22.122.110")
        assertEquals(ip("178.22.122.110"), XboxDns.relayAddress(answers(relay, relay), emptySet()))
        assertNull(XboxDns.relayAddress(answers(), emptySet()), "an ordinary resolver")
        assertNull(XboxDns.relayAddress(answers(relay), emptySet()), "one name alone may be a far-away data centre")
        assertNull(XboxDns.relayAddress(answers(relay, relay), setOf(ip("178.22.122.110"))), "what everyone gives")
        assertNull(XboxDns.relayAddress(answers(listOf("10.10.34.34"), listOf("10.10.34.34")), emptySet()), "the filtering page")
        // The store and the account alone, without an Xbox Live name, do nothing for the app.
        val storeOnly = List(XboxDns.APP_NAMES.size) { index ->
            listOf(ip(if (XboxDns.APP_NAMES[index].endsWith(".xboxlive.com")) "20.190.1.1" else "178.22.122.110"))
        }
        assertNull(XboxDns.relayAddress(storeOnly, emptySet()))
    }

    @Test
    fun sweepIdsFitTheFieldAndTellTheNamesApart() {
        for (address in listOf(ip("1.1.1.1"), ip("255.255.255.255"), ip("10.202.10.202"), 0)) {
            val ids = (0..7).map { XboxDns.sweepId(address, it) }
            assertTrue(ids.all { it in 0..0xFFFF })
            assertEquals((0..7).toList(), ids.map { it and 7 })
            assertEquals(1, ids.map { it shr 3 }.toSet().size)
        }
    }

    @Test
    fun theCountryIsReadFromTheGeoAnswer() {
        val body = "HTTP/1.1 200 OK\r\n\r\n{\"ExternalIpAddress\":\"1.2.3.4\",\"CountryCode\":\"de\",\"Version\":\"x\"}"
        assertEquals("DE", XboxDns.parseCountry(body))
        assertEquals("", XboxDns.parseCountry("HTTP/1.1 403 Forbidden\r\n\r\n"))
    }

    @Test
    fun relaysComeFirstThenOutsideIranThenMoreNamesThenKnownThenSpeed() {
        fun result(address: String, name: String = "", relay: Boolean, passed: Int, millis: Long, country: String = "") =
            XboxDns.Proven(address, name, "", relay, passed, millis, country)
        val ranked = XboxDns.rank(
            listOf(
                result("1.0.0.1", relay = false, passed = 0, millis = 5),
                result("2.0.0.1", relay = true, passed = 3, millis = 5),
                result("3.0.0.1", relay = true, passed = 8, millis = 90),
                result("4.0.0.1", name = "Known", relay = true, passed = 8, millis = 200),
                result("5.0.0.1", relay = true, passed = 8, millis = 40),
                result("6.0.0.1", relay = true, passed = 8, millis = 1, country = "IR"),
            )
        ).map { it.address }
        assertEquals(listOf("4.0.0.1", "5.0.0.1", "3.0.0.1", "2.0.0.1", "6.0.0.1", "1.0.0.1"), ranked)
    }

    @Test
    fun onlyARealAnswerThatIsNotARefusalCountsAsOpen() {
        assertEquals(200, XboxDns.parseStatus("HTTP/1.1 200 OK\r\n"))
        assertEquals(403, XboxDns.parseStatus("HTTP/1.1 403 Forbidden"))
        assertEquals(404, XboxDns.parseStatus("HTTP/2 404"))
        assertEquals(-1, XboxDns.parseStatus("<html>blocked"))
        assertEquals(-1, XboxDns.parseStatus(""))
        assertTrue(XboxDns.isOpen(200))
        assertTrue(XboxDns.isOpen(404), "the service answered; it only has nothing at that path")
        assertTrue(XboxDns.isOpen(302))
        assertFalse(XboxDns.isOpen(403), "what a sanction block answers")
        assertFalse(XboxDns.isOpen(451))
        assertFalse(XboxDns.isOpen(-1), "a connection the filter cut")
    }

    @Test
    fun questionIsAWellFormedDnsPacket() {
        val expected = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 'a'.code.toByte(), 0x02, 'i'.code.toByte(), 'r'.code.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01,
        )
        assertEquals(expected.toList(), XboxDns.buildQuery("a.ir", 0x1234).toList())
    }
}
