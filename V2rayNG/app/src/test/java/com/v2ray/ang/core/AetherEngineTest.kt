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
    fun theRouteThatWorkedLastComesFirstAndFailedOnesGoLast() {
        val routes = listOf("a", "b", "c", "d").map { AetherEngine.Route(it, listOf("--masque", "-4")) }
        fun plan(winner: String?, failures: Map<String, Int> = emptyMap()) =
            AetherEngine.planOrder(routes, winner, failures, hasIpv6 = true).map { it.label }
        assertEquals(listOf("a", "b", "c", "d"), plan(null))
        assertEquals(listOf("c", "a", "b", "d"), plan("c"))
        assertEquals(listOf("a", "b", "c", "d"), plan("gone"), "a winner that no longer exists is ignored")
        assertEquals(listOf("b", "d", "c", "a"), plan(null, mapOf("a" to 2, "c" to 1)))
        assertEquals(listOf("a", "b", "d", "c"), plan("a", mapOf("a" to 3, "c" to 1)), "the winner stays first")
    }

    @Test
    fun ipv6OnlyRoutesAreLeftOutWithoutAnIpv6Address() {
        val all = AetherEngine.planOrder(AetherEngine.ROUTES, null, emptyMap(), hasIpv6 = true)
        val without = AetherEngine.planOrder(AetherEngine.ROUTES, null, emptyMap(), hasIpv6 = false)
        assertEquals(AetherEngine.ROUTES, all)
        assertTrue(without.none { "-6" in it.args })
        assertTrue(without.any { "--dual" in it.args })
        assertEquals(AetherEngine.ROUTES.count { "-6" in it.args }, all.size - without.size)
    }

    @Test
    fun failuresSurviveBeingWrittenDown() {
        val failures = mapOf("MASQUE + ECH" to 2, "WireGuard" to 1)
        assertEquals(failures, AetherEngine.decodeFailures(AetherEngine.encodeFailures(failures + ("gone" to 0))))
        assertTrue(AetherEngine.decodeFailures(null).isEmpty())
        assertTrue(AetherEngine.decodeFailures("10").isEmpty(), "the old file format is ignored")
    }

    @Test
    fun aRouteGetsExtraTimeOnlyWhileItMakesProgress() {
        assertTrue(AetherEngine.isProgressing(ageMillis = 45_000, sinceProgressMillis = 3_000))
        assertFalse(AetherEngine.isProgressing(ageMillis = 45_000, sinceProgressMillis = 20_000))
        assertFalse(AetherEngine.isProgressing(ageMillis = 80_000, sinceProgressMillis = 1_000))
    }

    @Test
    fun onlyPublicIpv6AddressesCount() {
        fun bytes(first: Int, second: Int) = ByteArray(16).also { it[0] = first.toByte(); it[1] = second.toByte() }
        assertTrue(AetherEngine.isGlobalIpv6(bytes(0x2a, 0x01)))
        assertFalse(AetherEngine.isGlobalIpv6(bytes(0xfe, 0x80)), "link-local")
        assertFalse(AetherEngine.isGlobalIpv6(bytes(0xfc, 0x00)), "private, like the VPN's own address")
        assertFalse(AetherEngine.isGlobalIpv6(ByteArray(4)))
    }

    @Test
    fun theEchKeyIsReadFromADnsAnswerAndForgedAnswersAreRefused() {
        val key = byteArrayOf(0, 8, 0xFE.toByte(), 0x0D, 0, 4, 1, 2, 3, 4)
        fun answer(value: ByteArray): ByteArray {
            val question = AetherEngine.EchKey.httpsQuery("a.bc", 7)
            val record = byteArrayOf(0xC0.toByte(), 12, 0, 65, 0, 1, 0, 0, 0, 60) +
                byteArrayOf(0, (3 + 4 + 4 + value.size).toByte()) +
                byteArrayOf(0, 1, 0) + // priority 1, target "."
                byteArrayOf(0, 1, 0, 0) + // alpn, empty: skipped
                byteArrayOf(0, 5, 0, value.size.toByte()) + value
            return question.also { it[7] = 1 } + record
        }
        assertEquals(key.toList(), AetherEngine.EchKey.echFromAnswer(answer(key))?.toList())
        assertNull(AetherEngine.EchKey.echFromAnswer(answer(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))))
        assertNull(AetherEngine.EchKey.echFromAnswer(AetherEngine.EchKey.httpsQuery("a.bc", 7)))
        assertNull(AetherEngine.EchKey.echFromAnswer(byteArrayOf(1, 2, 3)))
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
            assertTrue(route.args.any { it in setOf("--masque", "--wg", "--gool", "--mim") }, route.label)
            assertTrue(route.args.any { it in setOf("-4", "-6", "--dual") }, route.label)
            assertTrue(route.args.any { it in setOf("--turbo", "--balanced", "--thorough") }, route.label)
            assertFalse("--ech" in route.args, "the key is added when the route starts")
        }
        assertEquals(AetherEngine.ROUTES.size, AetherEngine.ROUTES.map { it.label }.toSet().size)
    }

    @Test
    fun socksConnectRequestNamesHostAndPort() {
        val request = AetherEngine.socksConnectRequest("a.bc", 80)
        assertEquals(listOf<Byte>(5, 1, 0, 3, 4, 'a'.code.toByte(), '.'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0, 80), request.toList())
    }
}
