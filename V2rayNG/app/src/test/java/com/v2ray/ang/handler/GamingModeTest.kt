package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever

class GamingModeTest {

    private val settingsValues = mutableMapOf<String, String>()

    /**
     * The choice the owner picked is kept in the settings store, which no unit test can open.
     * A plain map behind the same handle stands in for it, so what [GamingMode.setGameExit]
     * writes is what [GamingMode.gameExit] reads back.
     */
    @AfterEach
    fun prepareSettingsStorage() {
        reset(settings)
        settingsValues.clear()
        whenever(settings.decodeString(any())).thenAnswer { settingsValues[it.getArgument<String>(0)] }
        whenever(settings.encode(any<String>(), anyOrNull<String>())).thenAnswer {
            val key = it.getArgument<String>(0)
            val value: String? = it.getArgument(1)
            if (value == null) settingsValues.remove(key) else settingsValues[key] = value
            true
        }
    }

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
        val rules = GamingMode.rulesets(listOf("mysite.net"))
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
        val tunnelled = GamingMode.rulesets(bundled = parsed).first().domain!!
        assertTrue("domain:ea.com" in tunnelled)
        assertTrue("full:api.example.org" in tunnelled)
        assertTrue("domain:tunnelbear.com" in tunnelled)
        assertTrue("domain:google" in tunnelled)
    }

    @Test
    fun tunnelledSitesAreMatchedBeforeTheLocalNetworkRule() {
        val rules = GamingMode.rulesets()
        val tunnelled = rules.first { it.outboundTag == AppConfig.TAG_PROXY && it.domain != null }
        assertEquals(tunnelled, rules.first())
        assertTrue(rules.indexOfFirst { it.ip == listOf(AppConfig.GEOIP_PRIVATE) } > 0)
    }

    @Test
    fun telegramIsTunnelledByAddress() {
        val rule = GamingMode.rulesets().first { it.ip == GamingMode.TELEGRAM_NETWORKS }
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
        // Only the phone's own network is left outside; everything else leaves through the config,
        // which is what gives a game the config's exit address.
        val local = rules.indexOfFirst { it.ip == listOf(AppConfig.GEOIP_PRIVATE) }
        assertEquals(0, local)
        assertEquals(AppConfig.TAG_PROXY, rules.last().outboundTag)
        assertEquals("0-65535", rules.last().port)
        assertTrue(rules.drop(1).all { it.outboundTag == AppConfig.TAG_PROXY })
    }

    @Test
    fun theChoiceRemembersWhatTheOwnerPicked() {
        GamingMode.setGameExit(GamingMode.EXIT_CONFIG)
        assertEquals(GamingMode.EXIT_CONFIG, GamingMode.gameExit())
        GamingMode.setGameExit(GamingMode.EXIT_OWN)
        assertEquals(GamingMode.EXIT_OWN, GamingMode.gameExit())
    }

    companion object {
        private val settings: MMKV = mock()

        @BeforeAll
        @JvmStatic
        fun initializeHandle() {
            mockStatic(MMKV::class.java).use {
                it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                MmkvManager.decodeSettingsString("test-initialize")
            }
        }
    }
}
