package com.v2ray.ang.core

import com.v2ray.ang.fmt.WireguardFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun ipv6WireguardEndpointAndAntiDpiModesWorkCleanly() {
        val conf = """
            [Interface]
            PrivateKey = secret
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = pub
            Endpoint = [2606:4700:d0::a29f:c001]:2408
        """.trimIndent()
        val parsed = WireguardFmt.parseWireguardConfFile(conf)
        assertEquals("2606:4700:d0::a29f:c001", parsed.server)
        assertEquals("2408", parsed.serverPort)

        assertFalse(CoreOutboundBuilder.WireguardMode.STANDARD.noise)
        assertEquals(1280, CoreOutboundBuilder.WireguardMode.MTU_1280.maxMtu)
        assertTrue(CoreOutboundBuilder.WireguardMode.NOISE_1280.noise)
        assertEquals(1280, CoreOutboundBuilder.WireguardMode.NOISE_1280.maxMtu)
    }
}
