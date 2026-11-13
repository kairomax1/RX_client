package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleDnsTest {

    @Test
    fun questionIsAWellFormedDnsPacket() {
        val expected = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            1, 'a'.code.toByte(), 2, 'i'.code.toByte(), 'r'.code.toByte(), 0,
            0x00, 0x01, 0x00, 0x01,
        )
        assertArrayEquals(expected, ConsoleDns.buildQuery("a.ir", 0x1234))
    }

    @Test
    fun onlyAMatchingErrorFreeAnswerCounts() {
        fun packet(id: Int, flags2: Int, flags3: Int, answers: Int) = byteArrayOf(
            (id shr 8).toByte(), id.toByte(), flags2.toByte(), flags3.toByte(), 0, 1, 0, answers.toByte(), 0, 0, 0, 0,
        )
        assertTrue(ConsoleDns.isAnswer(packet(0xBEEF, 0x81, 0x80, 1), 12, 0xBEEF))
        assertFalse(ConsoleDns.isAnswer(packet(0xBEEF, 0x81, 0x80, 1), 12, 0x0001), "other question")
        assertFalse(ConsoleDns.isAnswer(packet(0xBEEF, 0x01, 0x00, 1), 12, 0xBEEF), "not a response")
        assertFalse(ConsoleDns.isAnswer(packet(0xBEEF, 0x81, 0x83, 1), 12, 0xBEEF), "name error")
        assertFalse(ConsoleDns.isAnswer(packet(0xBEEF, 0x81, 0x80, 0), 12, 0xBEEF), "empty answer")
        assertFalse(ConsoleDns.isAnswer(ByteArray(4), 4, 0), "too short")
    }

    @Test
    fun everyServiceHasTwoDifferentAddresses() {
        ConsoleDns.SERVICES.forEach { assertTrue(it.primary != it.secondary, it.name) }
    }
}
