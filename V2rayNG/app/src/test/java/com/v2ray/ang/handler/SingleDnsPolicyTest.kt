package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SingleDnsPolicyTest {

    private val supported: (String) -> Boolean = { it == "9.9.9.9" || it == "1.1.1.1" || it.startsWith("https") }

    private fun pick(enabled: Boolean, value: String?) =
        SingleDnsPolicy.pick(enabled = enabled, value = value, default = "1.1.1.1", isSupported = supported)

    @Test
    fun theModeIsOffUnlessEnabled() {
        assertNull(pick(enabled = false, value = "9.9.9.9"))
    }

    @Test
    fun theStoredAddressIsUsedTrimmed() {
        assertEquals("9.9.9.9", pick(enabled = true, value = " 9.9.9.9 "))
        assertEquals("https://dns.example/dns-query", pick(enabled = true, value = "https://dns.example/dns-query"))
    }

    @Test
    fun anEmptySettingMeansTheDefault() {
        assertEquals("1.1.1.1", pick(enabled = true, value = null))
        assertEquals("1.1.1.1", pick(enabled = true, value = "   "))
    }

    @Test
    fun severalServersOrAnUnsupportedAddressDoNotApply() {
        assertNull(pick(enabled = true, value = "9.9.9.9,1.1.1.1"))
        assertNull(pick(enabled = true, value = "9.9.9.9 1.1.1.1"))
        assertNull(pick(enabled = true, value = "not-a-dns"))
    }
}
