package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TunnelWatchCounterTest {
    @Test
    fun failedChecksAfterAnInPlaceReloadCanStillMoveToAnotherConfig() {
        val watch = TunnelWatchCounter(reloadAfter = 3, moveAfter = 5)

        assertEquals(TunnelWatchCounter.Decision.WAIT, watch.observe(carries = false, reachableToAsk = true))
        assertEquals(TunnelWatchCounter.Decision.WAIT, watch.observe(carries = false, reachableToAsk = true))
        assertEquals(TunnelWatchCounter.Decision.RELOAD, watch.observe(carries = false, reachableToAsk = true))
        // A successful in-place reload does not create a new watch round.
        assertEquals(3, watch.misses)
        assertEquals(TunnelWatchCounter.Decision.WAIT, watch.observe(carries = false, reachableToAsk = true))
        assertEquals(TunnelWatchCounter.Decision.MOVE, watch.observe(carries = false, reachableToAsk = true))
    }

    @Test
    fun aHealthyTunnelResetsTheFailureStreak() {
        val watch = TunnelWatchCounter(reloadAfter = 3, moveAfter = 5)
        repeat(4) { watch.observe(carries = false, reachableToAsk = true) }

        assertEquals(TunnelWatchCounter.Decision.CARRIES, watch.observe(carries = true, reachableToAsk = true))
        assertEquals(0, watch.misses)
        assertEquals(TunnelWatchCounter.Decision.WAIT, watch.observe(carries = false, reachableToAsk = true))
    }

    @Test
    fun anOfflinePhoneDoesNotCountOrClearAConfigFailure() {
        val watch = TunnelWatchCounter(reloadAfter = 3, moveAfter = 5)
        watch.observe(carries = false, reachableToAsk = true)

        assertEquals(TunnelWatchCounter.Decision.WAIT, watch.observe(carries = false, reachableToAsk = false))
        assertEquals(1, watch.misses)
        assertEquals(TunnelWatchCounter.Decision.WAIT, watch.observe(carries = false, reachableToAsk = true))
        assertEquals(2, watch.misses)
    }
}
