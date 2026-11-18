package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GamingModeTest {

    @Test
    fun whatTheUserTypesBecomesAPlainSiteName() {
        assertEquals("example.com", GamingMode.normalizeDomain("https://www.Example.com/page?x=1"))
        assertEquals("sub.example.co.uk", GamingMode.normalizeDomain("  sub.example.co.uk:8443 "))
        assertEquals("example.com", GamingMode.normalizeDomain("*.example.com"))
    }

    @Test
    fun thingsThatAreNotSiteNamesAreRefused() {
        assertNull(GamingMode.normalizeDomain(""))
        assertNull(GamingMode.normalizeDomain("localhost"))
        assertNull(GamingMode.normalizeDomain("two words.com"))
        assertNull(GamingMode.normalizeDomain("192.168.1.1"))
        assertNull(GamingMode.normalizeDomain("سایت.com"))
        assertNull(GamingMode.normalizeDomain("a..b"))
    }

    @Test
    fun listedSitesAreTunnelledAndEverythingElseIsDirect() {
        val rules = GamingMode.rulesets(listOf("mysite.net"), gameExit = GamingMode.EXIT_OWN)
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
        (GamingMode.FILTERED + GamingMode.SANCTIONED + GamingMode.VPN_SERVICES).forEach { name ->
            assertEquals(name, GamingMode.normalizeDomain(name), name)
        }
    }

    @Test
    fun theBigListIsAddedAsItIsAndBadLinesAreDropped() {
        val parsed = GamingMode.parseSiteList("# note\ndomain:ea.com\n full:api.example.org \nregexp:.*\ndomain:\nbad line\ndomain:a b.com\n")
        assertEquals(listOf("domain:ea.com", "full:api.example.org"), parsed)
        val tunnelled = GamingMode.rulesets(bundled = parsed, gameExit = GamingMode.EXIT_OWN).first().domain!!
        assertTrue("domain:ea.com" in tunnelled)
        assertTrue("full:api.example.org" in tunnelled)
        assertTrue("domain:tunnelbear.com" in tunnelled)
        assertTrue("domain:google" in tunnelled)
    }

    @Test
    fun tunnelledSitesAreMatchedBeforeTheLocalNetworkRule() {
        val rules = GamingMode.rulesets(gameExit = GamingMode.EXIT_OWN)
        val tunnelled = rules.first { it.outboundTag == AppConfig.TAG_PROXY && it.domain != null }
        assertEquals(tunnelled, rules.first())
        assertTrue(rules.indexOfFirst { it.ip == listOf(AppConfig.GEOIP_PRIVATE) } > 0)
    }

    @Test
    fun telegramIsTunnelledByAddress() {
        val rule = GamingMode.rulesets(gameExit = GamingMode.EXIT_OWN)
            .first { it.ip == GamingMode.TELEGRAM_NETWORKS }
        assertEquals(AppConfig.TAG_PROXY, rule.outboundTag)
    }

    @Test
    fun withTheOwnLineEveryLastRuleSendsTrafficOutDirectly() {
        val rules = GamingMode.rulesets(gameExit = GamingMode.EXIT_OWN)
        assertEquals(AppConfig.TAG_DIRECT, rules.last().outboundTag)
        assertEquals("0-65535", rules.last().port)
    }

    @Test
    fun withTheConfigExitAddressTheWholeConnectionGoesThroughTheTunnel() {
        val rules = GamingMode.rulesets(gameExit = GamingMode.EXIT_CONFIG)
        // One rule and nothing else: no local-network exception and no games exception, because
        // either one is a second exit, and a second exit is what an address check notices.
        assertEquals(1, rules.size)
        assertEquals(AppConfig.TAG_PROXY, rules.last().outboundTag)
        assertEquals("0-65535", rules.last().port)
        assertNull(rules.last().domain)
        assertNull(rules.last().ip)
        assertTrue(rules.all { it.outboundTag == AppConfig.TAG_PROXY })
    }

    @Test
    fun theDefaultIsEverythingThroughTheConfig() {
        // A mode that is switched on without an answer must still keep one exit; the phone's own
        // line is what "IP ایران" must never fall back to by accident.
        val rules = GamingMode.rulesets()
        assertEquals(1, rules.size)
        assertEquals(AppConfig.TAG_PROXY, rules.single().outboundTag)
    }

    @Test
    fun theChoiceRemembersWhatTheOwnerPicked() {
        assertEquals(GamingMode.EXIT_CONFIG, GamingMode.chosenExit(GamingMode.EXIT_CONFIG))
        assertEquals(GamingMode.EXIT_OWN, GamingMode.chosenExit(GamingMode.EXIT_OWN))
    }

    @Test
    fun switchingTheModeOffForgetsTheChoice() {
        // Switching the mode off stores empty text, so the question is asked again. Until it is
        // answered the exit is the config, never the owner's own line.
        assertEquals(GamingMode.EXIT_CONFIG, GamingMode.chosenExit(""))
        assertEquals(GamingMode.EXIT_CONFIG, GamingMode.chosenExit("   "))
        // A phone that has never answered the question has nothing stored at all.
        assertEquals(GamingMode.EXIT_CONFIG, GamingMode.chosenExit(null))
    }
}
