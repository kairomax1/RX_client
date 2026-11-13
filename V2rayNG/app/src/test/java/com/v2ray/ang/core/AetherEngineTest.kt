package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AetherEngineTest {

    @Test
    fun onlyTheLocalSocksProfileOnTheEnginePortIsAether() {
        assertTrue(AetherEngine.isAetherEndpoint(isSocks = true, server = "127.0.0.1", port = "1819"))
        assertFalse(AetherEngine.isAetherEndpoint(isSocks = false, server = "127.0.0.1", port = "1819"))
        assertFalse(AetherEngine.isAetherEndpoint(isSocks = true, server = "10.0.0.2", port = "1819"))
        assertFalse(AetherEngine.isAetherEndpoint(isSocks = true, server = "127.0.0.1", port = "1080"))
        assertFalse(AetherEngine.isAetherEndpoint(isSocks = true, server = null, port = null))
    }
}
