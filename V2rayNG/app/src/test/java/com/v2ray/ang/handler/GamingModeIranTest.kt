package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun anIranianNameIsSettledBeforeTheTunnelledListIsConsulted() {
        // The pieces a device would supply, given here so the order can be seen
        // without a store: a filtered rule, a game rule, and the country's own.
        val filtered = RulesetItem(
            remarks = "Gaming: filtered and restricted sites",
            outboundTag = AppConfig.TAG_PROXY,
            domain = listOf("domain:example.org"),
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
        // Whichever a name reaches first owns it. Iran first means a site cannot
        // show one address to one of its names and another to the next.
        assertTrue(iranianAt < filteredAt) { "Iranian names must be matched before filtered ones" }
        // And the owner's choice about a game still beats both, because it is a
        // choice about latency rather than about a name.
        assertTrue(rules.indexOfFirst { it === games } < iranianAt)
        // Nothing is lost on the way: every rule given is in the result.
        assertTrue(games in rules && filtered in rules)
    }

    @Test
    fun advertsAreStillBlockedBeforeEither() {
        val rules = GamingMode.rulesets()
        val adAt = rules.indexOfFirst { it.outboundTag == AppConfig.TAG_BLOCKED }
        val iranianAt = rules.indexOfFirst { it.remarks == GamingMode.IRAN_RULE }
        if (adAt >= 0 && iranianAt >= 0) assertTrue(adAt < iranianAt)
    }
}
