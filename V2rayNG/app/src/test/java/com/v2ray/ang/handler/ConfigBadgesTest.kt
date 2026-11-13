package com.v2ray.ang.handler

import com.v2ray.ang.handler.ConfigBadges.Score
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigBadgesTest {

    @Test
    fun winnerOfBothIsRecommendedAndRunnersUpTakeTheTitles() {
        val badges = ConfigBadges.assign(
            listOf(
                Score("a", pingMs = 80, speedKbps = 9000),
                Score("b", pingMs = 120, speedKbps = 4000),
                Score("c", pingMs = 200, speedKbps = 7000),
            )
        )
        assertEquals(ConfigBadge.Recommended, badges["a"])
        assertEquals(ConfigBadge.LowestPing, badges["b"])
        assertEquals(ConfigBadge.Fastest, badges["c"])
    }

    @Test
    fun oneRunnerUpCannotHoldBothTitles() {
        val badges = ConfigBadges.assign(
            listOf(
                Score("a", pingMs = 80, speedKbps = 9000),
                Score("b", pingMs = 120, speedKbps = 7000),
                Score("c", pingMs = 200, speedKbps = 4000),
            )
        )
        assertEquals(ConfigBadge.Recommended, badges["a"])
        assertEquals(ConfigBadge.LowestPing, badges["b"])
        assertEquals(ConfigBadge.Fastest, badges["c"])
    }

    @Test
    fun differentWinnersKeepTheirTitlesAndTheBestOfTheRestIsRecommended() {
        val badges = ConfigBadges.assign(
            listOf(
                Score("ping", pingMs = 50, speedKbps = 1000),
                Score("speed", pingMs = 400, speedKbps = 20000),
                Score("balanced", pingMs = 90, speedKbps = 12000),
                Score("poor", pingMs = 300, speedKbps = 500),
            )
        )
        assertEquals(ConfigBadge.LowestPing, badges["ping"])
        assertEquals(ConfigBadge.Fastest, badges["speed"])
        assertEquals(ConfigBadge.Recommended, badges["balanced"])
        assertEquals(3, badges.size)
    }

    @Test
    fun unreachableConfigsNeverGetABadge() {
        val badges = ConfigBadges.assign(
            listOf(Score("dead", pingMs = -1, speedKbps = 99999), Score("alive", pingMs = 300))
        )
        assertEquals(mapOf("alive" to ConfigBadge.LowestPing), badges)
        assertTrue(ConfigBadges.assign(listOf(Score("dead", -1))).isEmpty())
    }

    @Test
    fun aLoneWinnerOfBothIsOnlyRecommended() {
        assertEquals(
            mapOf("a" to ConfigBadge.Recommended),
            ConfigBadges.assign(listOf(Score("a", pingMs = 80, speedKbps = 9000))),
        )
    }

    @Test
    fun speedIsMeasuredForTheFiveLowestReachablePings() {
        val pings = mapOf("a" to 500L, "b" to 100L, "c" to -1L, "d" to 300L, "e" to 200L, "f" to 400L, "g" to 600L)
        assertEquals(listOf("b", "e", "d", "f", "a"), ConfigBadges.speedCandidates(pings))
    }

    @Test
    fun listIsOrderedByBadgeThenPingWithUnreachableLast() {
        val badges = mapOf("f" to ConfigBadge.Fastest, "r" to ConfigBadge.Recommended, "p" to ConfigBadge.LowestPing)
        val pings = mapOf("dead" to -1L, "slow" to 900L, "f" to 400L, "r" to 90L, "p" to 50L, "mid" to 200L, "new" to 0L)
        assertEquals(
            listOf("r", "p", "f", "mid", "slow", "dead", "new"),
            ConfigBadges.order(listOf("dead", "slow", "f", "r", "p", "mid", "new"), badges) { pings.getValue(it) },
        )
    }
}
