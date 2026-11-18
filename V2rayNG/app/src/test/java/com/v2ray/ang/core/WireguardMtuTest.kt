package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WireguardMtuTest {
    @Test
    fun packetSizeFitsMobileAndPppoePaths() {
        // A config that names no size, or one that only fits plain Ethernet, gets
        // the largest size a mobile and a PPPoE path still carry — not the
        // smallest that fits anywhere, which was throwing away a tenth of the
        // payload of every packet.
        assertEquals(1360, CoreOutboundBuilder.wireguardMtu(null))
        assertEquals(1360, CoreOutboundBuilder.wireguardMtu(1420))
        assertEquals(1360, CoreOutboundBuilder.wireguardMtu(1500))
        assertEquals(1360, CoreOutboundBuilder.wireguardMtu(1360))
        // Below the IPv6 minimum is brought up to it: nothing smaller travels everywhere.
        assertEquals(1280, CoreOutboundBuilder.wireguardMtu(576))
    }
}
