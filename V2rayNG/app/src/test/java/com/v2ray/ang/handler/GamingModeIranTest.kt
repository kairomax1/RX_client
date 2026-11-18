package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
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
        val rules = GamingMode.rulesets()
        val iranianAt = rules.indexOfFirst { it.remarks == GamingMode.IRAN_RULE }
        // The rule that carries the filtered and restricted lists, whichever words
        // it happens to use: it is the one that sends a name out on the tunnel.
        val tunnelledAt = rules.indexOfFirst { it.remarks?.contains("filtered and restricted") == true }
        assertTrue(iranianAt >= 0 && tunnelledAt >= 0)
        // The order is the decision: whichever rule a name reaches first owns it.
        // Iran first means a site cannot show one address to one of its names and
        // another to the next — which is the split that leaked a foreign address.
        assertTrue(iranianAt < tunnelledAt) { "Iranian names must be matched before filtered ones" }
    }

    @Test
    fun advertsAreStillBlockedBeforeEither() {
        val rules = GamingMode.rulesets()
        val adAt = rules.indexOfFirst { it.outboundTag == AppConfig.TAG_BLOCKED }
        val iranianAt = rules.indexOfFirst { it.remarks == GamingMode.IRAN_RULE }
        if (adAt >= 0 && iranianAt >= 0) assertTrue(adAt < iranianAt)
    }
}
