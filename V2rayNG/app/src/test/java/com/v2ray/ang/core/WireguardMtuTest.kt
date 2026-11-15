package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WireguardMtuTest {
    @Test
    fun packetSizeFitsMobileAndPppoePaths() {
        assertEquals(1280, CoreOutboundBuilder.wireguardMtu(null))
        assertEquals(1280, CoreOutboundBuilder.wireguardMtu(1420))
        assertEquals(1280, CoreOutboundBuilder.wireguardMtu(1500))
        assertEquals(1360, CoreOutboundBuilder.wireguardMtu(1360))
        assertEquals(1280, CoreOutboundBuilder.wireguardMtu(576))
    }
}
