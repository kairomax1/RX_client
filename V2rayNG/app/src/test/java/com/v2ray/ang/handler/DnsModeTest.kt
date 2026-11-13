package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsModeTest {

    @Test
    fun whatTheUserTypesBecomesAPlainSiteName() {
        assertEquals("example.com", DnsMode.normalizeDomain("https://www.Example.com/page?x=1"))
        assertEquals("sub.example.co.uk", DnsMode.normalizeDomain("  sub.example.co.uk:8443 "))
        assertEquals("example.com", DnsMode.normalizeDomain("*.example.com"))
    }

    @Test
    fun thingsThatAreNotSiteNamesAreRefused() {
        assertNull(DnsMode.normalizeDomain(""))
        assertNull(DnsMode.normalizeDomain("localhost"))
        assertNull(DnsMode.normalizeDomain("two words.com"))
        assertNull(DnsMode.normalizeDomain("192.168.1.1"))
        assertNull(DnsMode.normalizeDomain("سایت.com"))
        assertNull(DnsMode.normalizeDomain("a..b"))
    }

    @Test
    fun listedSitesAreTunnelledAndEverythingElseIsDirect() {
        val rules = DnsMode.rulesets(listOf("mysite.net"))
        val tunnelled = rules.first { it.outboundTag == AppConfig.TAG_PROXY && it.domain != null }
        assertTrue("domain:youtube.com" in tunnelled.domain!!)
        assertTrue("domain:openai.com" in tunnelled.domain!!)
        assertTrue("domain:mysite.net" in tunnelled.domain!!)
        // The last rule catches whatever no earlier rule did, and sends it out directly.
        assertEquals(AppConfig.TAG_DIRECT, rules.last().outboundTag)
        assertEquals("0-65535", rules.last().port)
        // The tunnelled list must be consulted before the catch-all name rule.
        val catchAll = rules.indexOfFirst { it.domain == listOf("regexp:.+") }
        assertTrue(rules.indexOf(tunnelled) < catchAll)
    }

    @Test
    fun listsHoldOnlyPlainNames() {
        (DnsMode.FILTERED + DnsMode.SANCTIONED).forEach { name ->
            assertEquals(name, DnsMode.normalizeDomain(name), name)
        }
    }

    @Test
    fun telegramIsTunnelledByAddress() {
        val rule = DnsMode.rulesets().first { it.ip == DnsMode.TELEGRAM_NETWORKS }
        assertEquals(AppConfig.TAG_PROXY, rule.outboundTag)
    }
}
