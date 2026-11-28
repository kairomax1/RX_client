package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreOutboundBuilderTest {

    @Test
    fun fragmentAppliesToTcpCarriedTransports() {
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork("tcp"))
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork("ws"))
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork("httpupgrade"))
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork("http"))
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork("h2"))
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork("grpc"))
    }

    @Test
    fun fragmentIsCaseInsensitiveAndDefaultsToTcp() {
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork("WS"))
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork(null))
        assertTrue(CoreOutboundBuilder.fragmentAppliesToNetwork(""))
    }

    @Test
    fun fragmentIsSkippedWhereItWouldBreakTheTransport() {
        // UDP-dialing transports: the noise mask corrupts the QUIC/kcp handshake.
        assertFalse(CoreOutboundBuilder.fragmentAppliesToNetwork("kcp"))
        assertFalse(CoreOutboundBuilder.fragmentAppliesToNetwork("quic"))
        assertFalse(CoreOutboundBuilder.fragmentAppliesToNetwork("hysteria"))
        // XHTTP already hides the handshake; fragmenting under it breaks mobile streams.
        assertFalse(CoreOutboundBuilder.fragmentAppliesToNetwork("xhttp"))
        assertFalse(CoreOutboundBuilder.fragmentAppliesToNetwork("XHTTP"))
    }
}
