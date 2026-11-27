package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirefoxProxyFailurePolicyTest {

    @Test
    fun destinationIpPolicyDenialDoesNotMarkTheRouteDeadOrTriggerWorkerFallback() {
        val reason = "HTTP/1.1 502 Destination IP Prohibited"

        assertTrue(FirefoxProxyFailurePolicy.isTargetPolicyRejection(reason))
        assertFalse(FirefoxProxyFailurePolicy.isEndpointFailure(reason))
        assertFalse(FirefoxProxyFailurePolicy.shouldTryWorker(reason))
    }

    @Test
    fun genericBadGatewayStillCountsAsAnEndpointFailure() {
        val reason = "HTTP/1.1 502 Bad Gateway"

        assertFalse(FirefoxProxyFailurePolicy.isTargetPolicyRejection(reason))
        assertTrue(FirefoxProxyFailurePolicy.isEndpointFailure(reason))
        assertTrue(FirefoxProxyFailurePolicy.shouldTryWorker(reason))
    }

    @Test
    fun transportFailureStillCountsAsAnEndpointFailure() {
        val reason = "proxy handshake timed out"

        assertTrue(FirefoxProxyFailurePolicy.isEndpointFailure(reason))
        assertTrue(FirefoxProxyFailurePolicy.shouldTryWorker(reason))
    }
}
