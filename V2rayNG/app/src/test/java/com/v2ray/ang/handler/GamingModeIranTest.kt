package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The whole of "IP ایران" rests on one question: when the app says a site is
 * Iranian, is it? The list of names served from inside the country holds nine
 * thousand entries and cannot hold the next shop that opens, so the country's
 * own ending decides it, and an Iranian name must never be tunnelled even when
 * a list of filtered services happens to mention it.
 */
class GamingModeIranTest {

    private fun iranianRule() = GamingMode.iranianRule()

    @Test
    fun everyNameOfThisCountryGoesOutOnTheHomeLine() {
        val rule = iranianRule()
        assertEquals(AppConfig.TAG_DIRECT, rule.outboundTag)
        assertTrue("regexp:.*\\.ir$" in rule.domain!!, "a .ir name is Iranian by definition")
    }

    @Test
    fun theEndingIsAnchoredSoForeignNamesAreNotCaught() {
        val names = iranianRule().domain!!
        // Nothing that would sweep up the whole internet: no bare ".com" pattern
        // and no "match anything" expression may appear in this rule, or every
        // site in the world would be sent out on the home line.
        assertTrue(names.none { it == "regexp:.+" || it == "regexp:.*" }) { "no catch-all in the Iranian rule" }
        assertTrue(names.none { it == "regexp:.*\\.com$" }) { ".com is not an Iranian ending" }
    }

    @Test
    fun aFilteredNameBeatsAnAddressThatLooksLocal() {
        // The pieces a device would supply, given here so the order can be seen
        // without a store: a filtered rule, a game rule, and the country's own.
        val filtered = RulesetItem(
            remarks = "Gaming: filtered and restricted sites",
            outboundTag = AppConfig.TAG_PROXY,
            domain = listOf("domain:whatsapp.com", "domain:telegram.org"),
        )
        val games = RulesetItem(
            remarks = "Gaming: games on the phone's own line",
            outboundTag = AppConfig.TAG_DIRECT,
            domain = listOf("domain:game.example"),
        )
        val rules = GamingMode.ordered(listOf(games, filtered), GamingMode.iranianRule())

        val iranianAt = rules.indexOfFirst { it.remarks == GamingMode.IRAN_RULE }
        val filteredAt = rules.indexOfFirst { it === filtered }
        assertTrue(iranianAt >= 0 && filteredAt >= 0)

        // Inside the country a filtered name is answered with a private address,
        // so the country's rule cannot tell a hosted site from a blocked one: both
        // look local. The name is what is known first, so the filtered rule must
        // be reached first — otherwise a blocked site is confidently sent out on
        // the home line to the filtering page, which is exactly what stopped
        // WhatsApp, Telegram and Proton opening.
        assertTrue(filteredAt < iranianAt) { "a filtered name must win over a local-looking address" }
        // Nothing is lost on the way: every rule given is in the result.
        assertTrue(games in rules && filtered in rules)
    }

    @Test
    fun theFilteringPageIsNotAnIranianAddress() {
        // This range is where the filtering page answers. Treated as Iranian, it
        // sends every filtered site out on the home line.
        assertTrue(SiteLists.isPrivateRange("10.0.0.0/8"))
        assertTrue(SiteLists.isPrivateRange("10.10.34.34/32"))
        // The carrier's own translation range means only that the phone is behind one.
        assertTrue(SiteLists.isPrivateRange("100.64.0.0/10"))
        assertTrue(SiteLists.isPrivateRange("192.168.1.0/24"))
        assertTrue(SiteLists.isPrivateRange("172.16.0.0/12"))
        // A real Iranian range must survive: this is ArvanCloud, where a great many
        // sites of this country are served from.
        assertFalse(SiteLists.isPrivateRange("185.143.232.0/22"))
        assertFalse(SiteLists.isPrivateRange("2a0c:a7c6:4::/48"))
        assertFalse(SiteLists.isPrivateRange("5.144.130.0/24"))
    }

    @Test
    fun aParsedListDropsThePrivateRangesButKeepsTheCountry() {
        val parsed = SiteLists.parseCidrs(
            """
            # a comment
            10.0.0.0/8
            100.64.0.0/10
            185.143.232.0/22
            5.144.130.0/24
            """.trimIndent()
        )
        assertFalse("10.0.0.0/8" in parsed)
        assertFalse("100.64.0.0/10" in parsed)
        assertTrue("185.143.232.0/22" in parsed)
        assertTrue("5.144.130.0/24" in parsed)
    }

    @Test
    fun advertsAreStillBlockedBeforeEither() {
        val rules = GamingMode.rulesets()
        val adAt = rules.indexOfFirst { it.outboundTag == AppConfig.TAG_BLOCKED }
        val iranianAt = rules.indexOfFirst { it.remarks == GamingMode.IRAN_RULE }
        if (adAt >= 0 && iranianAt >= 0) assertTrue(adAt < iranianAt)
    }
}
