package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FailoverTest {

    @BeforeEach
    fun cleanSlate() {
        Failover.reset()
    }

    private val pings = mutableMapOf<String, Long>()

    private fun next(current: String?, guids: List<String> = listOf("a", "b", "c", "d")) =
        Failover.next(current, guids) { pings[it] ?: 0L }

    @Test
    fun aConfigThatJustFailedIsNotOfferedAgain() {
        assertEquals("b", next("a"))
    }

    @Test
    fun aConfigTriedInThisRoundIsNotOfferedTwice() {
        val first = next("a")!!
        Failover.moved(first)
        val second = next("a")!!
        Failover.moved(second)
        val third = next("a")!!
        Failover.moved(third)
        assertEquals(listOf("b", "c", "d"), listOf(first, second, third))
        // The one that failed and the three that were taken: nothing is left.
        assertNull(next("a"))
    }

    @Test
    fun aMeasuredConfigIsOfferedBeforeAnUnmeasuredOne() {
        pings["d"] = 180L
        pings["c"] = 90L
        pings["b"] = -1L // failed its test: unmeasured, as far as choosing goes
        assertEquals("c", next("a"))
        Failover.moved("c")
        assertEquals("d", next("a"))
    }

    @Test
    fun theRoundEndsAfterThreeMovesAndNothingIsOfferedAfterwards() {
        assertTrue(Failover.canMove())
        repeat(3) {
            val picked = next("a")
            assertTrue(picked != null)
            Failover.moved(picked!!)
        }
        assertFalse(Failover.canMove())
        assertNull(next("a"))
    }

    @Test
    fun nothingIsOfferedWhenTheGroupHoldsOnlyTheConfigThatFailed() {
        assertNull(next("only", listOf("only")))
        assertNull(next("a", emptyList()))
    }

    @Test
    fun aFreshRequestForgetsTheRound() {
        Failover.moved(next("a")!!)
        Failover.moved(next("b")!!)
        Failover.reset()
        assertEquals("b", next("a"))
        assertTrue(Failover.canMove())
    }
}
