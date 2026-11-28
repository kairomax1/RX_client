package com.v2ray.ang.service

import android.net.NetworkCapabilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkHandoverStateTest {

    @Test
    fun underlayRequestExcludesVpnOverlays() {
        assertTrue(NETWORK_MONITOR_REQUIRED_CAPABILITIES.contains(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertTrue(NETWORK_MONITOR_REQUIRED_CAPABILITIES.contains(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(NETWORK_MONITOR_REQUIRED_CAPABILITIES.contains(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
    }

    @Test
    fun onlyAnUnrestrictedPhysicalInternetNetworkCanBeAnUpstream() {
        assertTrue(isEligibleUpstream(hasNotVpn = true, hasInternet = true, hasNotRestricted = true))
        assertFalse(isEligibleUpstream(hasNotVpn = false, hasInternet = true, hasNotRestricted = true))
        assertFalse(isEligibleUpstream(hasNotVpn = true, hasInternet = false, hasNotRestricted = true))
        assertFalse(isEligibleUpstream(hasNotVpn = true, hasInternet = true, hasNotRestricted = false))
    }

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
