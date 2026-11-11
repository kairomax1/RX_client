package com.v2ray.ang.core

import com.v2ray.ang.core.FailoverPolicy.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FailoverPolicyTest {

    @Test
    fun aSingleLostProbeDoesNotStartASearch() {
        var failures = 0
        failures = FailoverPolicy.nextFailureCount(failures, probeSucceeded = false)
        assertFalse(FailoverPolicy.shouldSearch(failures))
        failures = FailoverPolicy.nextFailureCount(failures, probeSucceeded = false)
        assertFalse(FailoverPolicy.shouldSearch(failures))
        failures = FailoverPolicy.nextFailureCount(failures, probeSucceeded = false)
        assertTrue(FailoverPolicy.shouldSearch(failures))
    }

    @Test
    fun aSuccessfulProbeResetsTheCount() {
        var failures = FailoverPolicy.nextFailureCount(2, probeSucceeded = true)
        assertEquals(0, failures)
        failures = FailoverPolicy.nextFailureCount(failures, probeSucceeded = false)
        assertFalse(FailoverPolicy.shouldSearch(failures))
    }

    @Test
    fun candidatesAreOrderedWorkingThenUntestedThenFailed() {
        val group = listOf(
            Candidate("failed", -1L),
            Candidate("untested", 0L),
            Candidate("slow", 900L),
            Candidate("current", 50L),
            Candidate("fast", 120L),
        )

        assertEquals(
            listOf("fast", "slow", "untested", "failed"),
            FailoverPolicy.orderCandidates("current", group),
        )
    }

    @Test
    fun candidatesRespectTheLimitAndSkipDuplicatesAndBlanks() {
        val group = listOf(
            Candidate("a", 10L),
            Candidate("a", 10L),
            Candidate("", 5L),
            Candidate("b", 20L),
            Candidate("c", 30L),
        )

        assertEquals(listOf("a", "b"), FailoverPolicy.orderCandidates("current", group, limit = 2))
        assertTrue(FailoverPolicy.orderCandidates("current", group, limit = 0).isEmpty())
    }

    @Test
    fun aGroupHoldingOnlyTheRunningConfigHasNoCandidates() {
        assertTrue(FailoverPolicy.orderCandidates("only", listOf(Candidate("only", 80L))).isEmpty())
    }

    @Test
    fun theFastestAnsweringConfigIsPicked() {
        val results = mapOf("dead" to -1L, "slow" to 700L, "fast" to 140L, "zero" to 0L)

        assertEquals("fast", FailoverPolicy.pickBest(results))
    }

    @Test
    fun nothingIsPickedWhenNoConfigAnswers() {
        assertNull(FailoverPolicy.pickBest(mapOf("a" to -1L, "b" to -1L)))
        assertNull(FailoverPolicy.pickBest(emptyMap()))
    }

    @Test
    fun probeDelayDependsOnTheScreenAndBacksOffAfterEmptySearches() {
        assertEquals(FailoverPolicy.PROBE_INTERVAL_MS, FailoverPolicy.nextProbeDelayMs(true, 0))
        assertEquals(FailoverPolicy.PROBE_INTERVAL_SCREEN_OFF_MS, FailoverPolicy.nextProbeDelayMs(false, 0))
        assertEquals(FailoverPolicy.PROBE_INTERVAL_MS * 2, FailoverPolicy.nextProbeDelayMs(true, 1))
        assertEquals(FailoverPolicy.PROBE_INTERVAL_MS * 4, FailoverPolicy.nextProbeDelayMs(true, 2))
        assertEquals(FailoverPolicy.MAX_BACKOFF_MS, FailoverPolicy.nextProbeDelayMs(true, 10))
        assertEquals(FailoverPolicy.MAX_BACKOFF_MS, FailoverPolicy.nextProbeDelayMs(false, Int.MAX_VALUE))
    }
}
