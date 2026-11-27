package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkHandoverStateTest {

    @Test
    fun lossOfPreviousNetworkDoesNotClearTheNewUpstream() {
        val state = NetworkHandoverState<String>()

        assertFalse(state.onAvailable("cellular"))
        assertTrue(state.onAvailable("wifi"))

        assertFalse(state.onLost("cellular"))
        assertTrue(state.isCurrent("wifi"))
    }

    @Test
    fun aNetworkReturningAfterLossSchedulesAChange() {
        val state = NetworkHandoverState<String>()

        assertFalse(state.onAvailable("cellular"))
        assertTrue(state.onLost("cellular"))

        assertTrue(state.onAvailable("wifi"))
        assertTrue(state.isCurrent("wifi"))
    }

    @Test
    fun capabilitiesFromAStaleNetworkAreIgnored() {
        val state = NetworkHandoverState<String>()
        state.onAvailable("cellular")
        state.onAvailable("wifi")

        assertFalse(state.isCurrent("cellular"))
        assertTrue(state.isCurrent("wifi"))
    }
}
