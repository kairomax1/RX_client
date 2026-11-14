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
        val question = ConsoleDns.buildQuery(host, id)
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
        val question = ConsoleDns.buildQuery("login.live.com", 7)
        assertNull(XboxDns.parseAnswer(question, question.size, 7), "a question is not an answer")
        assertNull(XboxDns.parseAnswer(message, message.size - 3, 0x1234), "cut short")
        assertNull(XboxDns.parseAnswer(byteArrayOf(1, 2, 3), 3, null))
    }

    @Test
    fun aRelayAnswersBothNamesWithItsOwnAddress() {
        val relay = listOf(ip("178.22.122.110"))
        assertTrue(XboxDns.redirects(relay, relay))
        assertFalse(XboxDns.redirects(listOf(ip("20.190.1.1")), listOf(ip("23.50.1.1"))), "an ordinary resolver")
        assertFalse(XboxDns.redirects(listOf(ip("10.10.34.34")), listOf(ip("10.10.34.34"))), "the filtering page is no relay")
        assertFalse(XboxDns.redirects(listOf(ip("0.0.0.0")), listOf(ip("0.0.0.0"))))
    }

    @Test
    fun aServerMustAnswerBothNamesWithRealAddresses() {
        fun heard(first: List<String>, second: List<String>) = XboxDns.Heard(ip("1.1.1.1"), 10, first.map(::ip), second.map(::ip))
        assertTrue(XboxDns.answersBoth(heard(listOf("20.190.1.1"), listOf("23.50.1.1"))))
        assertFalse(XboxDns.answersBoth(heard(listOf("20.190.1.1"), emptyList())))
        assertFalse(XboxDns.answersBoth(heard(listOf("127.0.0.1"), listOf("23.50.1.1"))))
    }

    @Test
    fun sweepIdsFitTheFieldAndTellTheTwoNamesApart() {
        for (address in listOf(ip("1.1.1.1"), ip("255.255.255.255"), ip("10.202.10.202"), 0)) {
            val first = XboxDns.sweepId(address, 0)
            val second = XboxDns.sweepId(address, 1)
            assertTrue(first in 0..0xFFFF && second in 0..0xFFFF)
            assertEquals(0, first and 1)
            assertEquals(1, second and 1)
            assertEquals(first shr 1, second shr 1)
        }
    }

    @Test
    fun relaysComeFirstThenMoreProofThenNamesThenSpeed() {
        fun result(address: String, name: String = "", relay: Boolean, passed: Int, millis: Long) =
            XboxDns.Proven(address, name, "", relay, passed, millis)
        val ranked = XboxDns.rank(
            listOf(
                result("1.0.0.1", relay = false, passed = 4, millis = 5),
                result("2.0.0.1", relay = true, passed = 3, millis = 5),
                result("3.0.0.1", relay = true, passed = 4, millis = 90),
                result("4.0.0.1", name = "Known", relay = true, passed = 4, millis = 200),
                result("5.0.0.1", relay = true, passed = 4, millis = 40),
            )
        ).map { it.address }
        assertEquals(listOf("4.0.0.1", "5.0.0.1", "3.0.0.1", "2.0.0.1", "1.0.0.1"), ranked)
    }

    @Test
    fun proxyRequestsNameWhereTheyWantToGo() {
        assertEquals(DnsProxy.Target("login.live.com", 443, true), DnsProxy.parseTarget("CONNECT login.live.com:443 HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertEquals(DnsProxy.Target("example.com", 80, false), DnsProxy.parseTarget("GET http://example.com/a/b HTTP/1.1\r\n\r\n"))
        assertEquals(DnsProxy.Target("example.com", 8080, false), DnsProxy.parseTarget("GET http://example.com:8080/ HTTP/1.1\r\n\r\n"))
        assertNull(DnsProxy.parseTarget("GET /relative HTTP/1.1\r\n\r\n"))
        assertNull(DnsProxy.parseTarget("CONNECT nohost HTTP/1.1\r\n\r\n"))
        assertNull(DnsProxy.parseTarget(""))
    }
}
