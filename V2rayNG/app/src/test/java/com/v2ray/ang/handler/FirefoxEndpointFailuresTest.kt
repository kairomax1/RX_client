package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirefoxEndpointFailuresTest {

    @Test
    fun oneDroppedRequestDoesNotQuarantineARoute() {
        val failures = FirefoxEndpointFailures()
        val endpoint = FirefoxVpnRelay.Endpoint("proxy.example", 443)

        assertFalse(failures.recordFailure(endpoint))
        assertFalse(failures.isQuarantined(endpoint))
    }

    @Test
    fun twoConsecutiveFailuresQuarantineOnlyThatEndpoint() {
        val failures = FirefoxEndpointFailures()
        val failed = FirefoxVpnRelay.Endpoint("proxy.example", 443)
        val spare = FirefoxVpnRelay.Endpoint("spare.example", 443)

        assertFalse(failures.recordFailure(failed))
        assertTrue(failures.recordFailure(failed))
        assertTrue(failures.isQuarantined(failed))
        assertFalse(failures.isQuarantined(spare))
    }

    @Test
    fun aSuccessfulTunnelClearsTheFailureStreak() {
        val failures = FirefoxEndpointFailures()
        val endpoint = FirefoxVpnRelay.Endpoint("proxy.example", 443)

        failures.recordFailure(endpoint)
        failures.recordSuccess(endpoint)
        assertFalse(failures.recordFailure(endpoint))
        assertFalse(failures.isQuarantined(endpoint))
    }

    @Test
    fun quarantineExpiresUsingTheInjectedClock() {
        var now = 100L
        val failures = FirefoxEndpointFailures(
            quarantineMillis = 1_000L,
            nowMillis = { now },
        )
        val endpoint = FirefoxVpnRelay.Endpoint("proxy.example", 443)

        failures.recordFailure(endpoint)
        assertTrue(failures.recordFailure(endpoint))
        now += 1_001L

        assertFalse(failures.isQuarantined(endpoint))
        assertFalse(failures.recordFailure(endpoint))
    }
}
