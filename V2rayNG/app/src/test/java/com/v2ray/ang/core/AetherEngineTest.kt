package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun theRouteThatWorkedLastIsTriedFirstAndNothingIsSkipped() {
        assertEquals(listOf(0, 1, 2, 3), AetherEngine.routeOrder(4, null))
        assertEquals(listOf(2, 0, 1, 3), AetherEngine.routeOrder(4, 2))
        assertEquals(listOf(0, 1, 2, 3), AetherEngine.routeOrder(4, 9), "a stale winner is ignored")
    }

    @Test
    fun statusSurvivesTheTripBetweenProcesses() {
        val status = AetherEngine.Status(AetherEngine.Phase.Searching, 2, 8, "MASQUE + ECH")
        assertEquals(status, AetherEngine.decodeStatus(AetherEngine.encodeStatus(status)))
        assertNull(AetherEngine.decodeStatus(null))
        assertNull(AetherEngine.decodeStatus("garbage"))
        assertNull(AetherEngine.decodeStatus("Nope|1|2|x"))
    }

    @Test
    fun everyRouteNamesItsProtocolAndAddressFamilySoTheEngineAsksNothing() {
        AetherEngine.ROUTES.forEach { route ->
            assertTrue(route.args.any { it in setOf("--masque", "--wg", "--gool") }, route.label)
            assertTrue(route.args.any { it in setOf("-4", "-6") }, route.label)
            assertTrue(route.args.any { it in setOf("--turbo", "--balanced", "--thorough") }, route.label)
        }
        assertEquals(AetherEngine.ROUTES.size, AetherEngine.ROUTES.map { it.label }.toSet().size)
    }
}
