package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
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
        // The last rule catches whatever no earlier rule did, and sends it out on
        // the phone's own line — the owner's Iranian address.
        assertEquals(AppConfig.TAG_DIRECT, rules.last().outboundTag)
        assertEquals("0-65535", rules.last().port)
        // The tunnelled list is the first thing consulted, so a blocked name can
        // never reach the rule that would send it out directly.
        assertEquals(0, rules.indexOf(tunnelled))
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
    fun theIranianLineTunnelsExactlyWhatCannotWorkFromHere() {
        val rules = GamingMode.rulesets(gameExit = GamingMode.EXIT_OWN)
        // What is filtered, sanctioned, a blocked game or an AI site goes through
        // the config; everything else keeps the owner's own Iranian address.
        val tunnelled = rules.first { it.remarks == "Gaming: filtered and restricted sites" }
        assertEquals(AppConfig.TAG_PROXY, tunnelled.outboundTag)
        val names = tunnelled.domain!!
        listOf("chatgpt.com", "openai.com", "claude.ai", "youtube.com", "instagram.com", "telegram.org")
            .forEach { assertTrue("domain:$it" in names, it) }
        // Telegram is reached by bare address, so its networks are tunnelled too.
        assertTrue(rules.any { it.outboundTag == AppConfig.TAG_PROXY && it.ip == GamingMode.TELEGRAM_NETWORKS })
        // And nothing that leaves directly is a name that should have been tunnelled.
        val directDomains = rules.filter { it.outboundTag == AppConfig.TAG_DIRECT }.flatMap { it.domain.orEmpty() }
        assertTrue(directDomains.none { it in names })
    }

    @Test
    fun outsideTheModeYouTubeStillGoesThroughTheConfigBeforeTheOwnersRules() {
        // The owner's rules are whatever the preset or they themselves wrote: a rule that sends
        // a whole TLD out directly, say, or nothing that names YouTube at all.
        val owners = listOf(
            RulesetItem(remarks = "direct ending", outboundTag = AppConfig.TAG_DIRECT, domain = listOf("domain:com")),
            RulesetItem(remarks = "everything else", outboundTag = AppConfig.TAG_DIRECT, port = "0-65535"),
        )
        val rules = GamingMode.aroundOwnerRules(
            base = owners,
            adverts = listOf("domain:ads.example.com"),
            remoteProxy = emptyList(),
            remoteDirect = listOf("domain:example.ir"),
        )
        // Adverts are first, then the services that can only be answered from outside, and
        // only then anything of the owner's that could send them out directly.
        assertEquals(AppConfig.TAG_BLOCKED, rules.first().outboundTag)
        val tunnelled = rules.first { it.remarks == "Gaming: filtered and restricted sites" }
        assertEquals(AppConfig.TAG_PROXY, tunnelled.outboundTag)
        assertTrue(rules.indexOf(tunnelled) < rules.indexOfFirst { it.outboundTag == AppConfig.TAG_DIRECT })
        // The names that open YouTube, video and all.
        val names = tunnelled.domain!!
        listOf(
            "domain:youtube.com", "domain:youtu.be", "domain:ytimg.com",
            "domain:googlevideo.com", "domain:ggpht.com", "domain:googleapis.com",
        ).forEach { assertTrue(it in names, it) }
        // The remote policy's own direct rule is not allowed to get in front of them.
        assertTrue(rules.indexOf(tunnelled) < rules.indexOfFirst { it.remarks == "RemoteControl: direct domains" })
        // And the owner's rules are all still there, in their own order, after it.
        assertEquals(owners, rules.takeLast(owners.size))
        // Nothing is duplicated.
        assertEquals(rules.size, rules.map { it.remarks }.distinct().size)
    }

    @Test
    fun theServicesTunnelledOutsideTheModeAreTheOnesTheModeTunnels() {
        // One list, so YouTube is never opened in the Gaming mode and closed outside it.
        val inTheMode = GamingMode.rulesets(gameExit = GamingMode.EXIT_OWN)
            .first { it.remarks == "Gaming: filtered and restricted sites" }.domain!!
        val outside = GamingMode.tunnelledServicesRule().domain!!
        assertTrue(outside.all { it in inTheMode })
        assertEquals(outline(outside), outline(inTheMode.filter { it in outside }))
    }

    /** Order and duplicates dropped: what the two lists must agree on. */
    private fun outline(names: List<String>) = names.distinct().sorted()

    @Test
    fun theIranianLineIsTheDefaultAndTheConfigIsOneChoiceAway() {
        // A phone that has never answered, and a phone whose answer was cleared:
        // both are the Iranian line, which is the mode as it is meant to be used.
        assertEquals(GamingMode.EXIT_OWN, GamingMode.chosenExit(null))
        assertEquals(GamingMode.EXIT_OWN, GamingMode.chosenExit(""))
        // The other way round is still there, and keeps one exit and nothing else.
        val all = GamingMode.rulesets(gameExit = GamingMode.EXIT_CONFIG)
        assertTrue(all.none { it.outboundTag == AppConfig.TAG_DIRECT })
    }

    @Test
    fun withTheConfigExitAddressTheWholeConnectionGoesThroughTheTunnel() {
        val rules = GamingMode.rulesets(gameExit = GamingMode.EXIT_CONFIG)
        // The last rule carries the whole connection: every name, every bare address, every port.
        // Nothing after it sends anything out directly — no local-network exception and no games
        // exception — because either one is a second exit, and a second exit is what an address
        // check notices.
        val all = rules.last()
        assertEquals(AppConfig.TAG_PROXY, all.outboundTag)
        assertEquals("0-65535", all.port)
        assertNull(all.domain)
        assertNull(all.ip)
        // The only other rule is not a way out: it drops QUIC on UDP 443 so the app falls back to
        // TCP inside the tunnel instead of half loading over a UDP nobody carries.
        assertEquals(2, rules.size)
        assertEquals(AppConfig.TAG_BLOCKED, rules.first().outboundTag)
        assertEquals("udp", rules.first().network)
        assertEquals("443", rules.first().port)
        assertNull(rules.first().domain)
        assertNull(rules.first().ip)
        // Nothing is sent out directly under any name or address.
        assertTrue(rules.none { it.outboundTag == AppConfig.TAG_DIRECT })
    }

    @Test
    fun theDefaultIsTheIranianLine() {
        // "IP ایران": what works here stays here, and only what cannot work here
        // is carried by the config.
        val rules = GamingMode.rulesets()
        assertEquals(AppConfig.TAG_DIRECT, rules.last().outboundTag)
        assertEquals("0-65535", rules.last().port)
        assertTrue(rules.any { it.outboundTag == AppConfig.TAG_PROXY && it.domain != null })
    }

    @Test
    fun theChoiceRemembersWhatTheOwnerPicked() {
        assertEquals(GamingMode.EXIT_CONFIG, GamingMode.chosenExit(GamingMode.EXIT_CONFIG))
        assertEquals(GamingMode.EXIT_OWN, GamingMode.chosenExit(GamingMode.EXIT_OWN))
    }

    @Test
    fun switchingTheModeOffForgetsTheChoice() {
        // Switching the mode off stores empty text, so nothing is carried over into
        // another session; with nothing stored the mode is the Iranian line.
        assertEquals(GamingMode.EXIT_OWN, GamingMode.chosenExit(""))
        assertEquals(GamingMode.EXIT_OWN, GamingMode.chosenExit("   "))
        // A phone that has never answered the question has nothing stored at all.
        assertEquals(GamingMode.EXIT_OWN, GamingMode.chosenExit(null))
    }
}
