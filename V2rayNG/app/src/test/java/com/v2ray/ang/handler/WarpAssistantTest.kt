package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.WarpAssistant.ChainOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Date

class WarpAssistantTest {

    private val answer = """
        {
          "id": "abc",
          "config": {
            "client_id": "AQID",
            "peers": [
              {
                "public_key": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
                "endpoint": {"v4": "162.159.192.7:0", "host": "engage.cloudflareclient.com:2408"}
              }
            ],
            "interface": {"addresses": {"v4": "172.16.0.2", "v6": "2606:4700:110:8a36::1"}}
          }
        }
    """.trimIndent()

    @Test
    fun aFullAnswerIsReadIntoTheProfileFields() {
        val registration = WarpAssistant.parseRegistration(answer)

        assertNotNull(registration)
        registration!!
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", registration.peerPublicKey)
        assertEquals("162.159.192.7", registration.endpointHost)
        assertEquals(2408, registration.endpointPort)
        assertEquals("172.16.0.2/32,2606:4700:110:8a36::1/128", registration.localAddress)
        assertEquals("1,2,3", registration.reserved)
    }

    @Test
    fun missingOptionalPartsFallBackToTheSharedValues() {
        val registration = WarpAssistant.parseRegistration(
            """{"config": {"peers": [{"public_key": "key"}]}}"""
        )

        assertNotNull(registration)
        registration!!
        assertEquals("162.159.192.1", registration.endpointHost)
        assertEquals("172.16.0.2/32", registration.localAddress)
        assertEquals("0,0,0", registration.reserved)
    }

    @Test
    fun anAnswerWithoutAPeerKeyIsRejected() {
        assertNull(WarpAssistant.parseRegistration("""{"config": {"peers": []}}"""))
        assertNull(WarpAssistant.parseRegistration("""{"error": "blocked"}"""))
        assertNull(WarpAssistant.parseRegistration("not json"))
        assertNull(WarpAssistant.parseRegistration("[]"))
    }

    @Test
    fun anEndpointOutsideCloudflareIsNotTrusted() {
        val registration = WarpAssistant.parseRegistration(
            """{"config": {"peers": [{"public_key": "key", "endpoint": {"v4": "10.0.0.1:0"}}]}}"""
        )

        assertEquals("162.159.192.1", registration?.endpointHost)
    }

    @Test
    fun reservedBytesComeFromTheClientId() {
        assertEquals("255,0,128", WarpAssistant.reservedFromClientId("/wCA"))
        assertEquals("0,0,0", WarpAssistant.reservedFromClientId(null))
        assertEquals("0,0,0", WarpAssistant.reservedFromClientId("AQIDBA=="))
        assertEquals("0,0,0", WarpAssistant.reservedFromClientId("***"))
    }

    @Test
    fun theProfileIsAWireGuardConfigNamedWarp() {
        val registration = WarpAssistant.parseRegistration(answer)!!
        val profile = WarpAssistant.buildProfile(registration, privateKey = "private", groupId = "group")

        assertEquals(EConfigType.WIREGUARD, profile.configType)
        assertEquals(WarpAssistant.PROFILE_NAME, profile.remarks)
        assertEquals("group", profile.subscriptionId)
        assertEquals("162.159.192.7", profile.server)
        assertEquals("2408", profile.serverPort)
        assertEquals("private", profile.secretKey)
        assertEquals("1,2,3", profile.reserved)
        assertEquals(1280, profile.mtu)
    }

    @Test
    fun theRegistrationBodyCarriesTheKeyAndAUtcTimestamp() {
        val body = WarpAssistant.registrationBody("public", Date(0L))

        assertTrue(body.contains("\"key\":\"public\""))
        assertTrue(body.contains("\"tos\":\"1970-01-01T00:00:00.000Z\""))
    }

    @Test
    fun chainMembersFollowTheTrafficOrder() {
        val other = ProfileItem.create(EConfigType.VLESS).apply { remarks = "My server" }
        val warp = ProfileItem.create(EConfigType.WIREGUARD).apply { remarks = "WARP" }

        assertEquals(listOf("My server", "WARP"), WarpAssistant.chainMembers(other, warp, ChainOrder.ExitThroughWarp))
        assertEquals(listOf("WARP", "My server"), WarpAssistant.chainMembers(other, warp, ChainOrder.EnterThroughWarp))
    }

    @Test
    fun configsThatCannotBeNamedInAChainAreRejected() {
        val warp = ProfileItem.create(EConfigType.WIREGUARD).apply { remarks = "WARP" }
        val comma = ProfileItem.create(EConfigType.VLESS).apply { remarks = "a,b" }
        val blank = ProfileItem.create(EConfigType.VLESS).apply { remarks = " " }
        val sameName = ProfileItem.create(EConfigType.VLESS).apply { remarks = "WARP" }
        val chain = ProfileItem.create(EConfigType.PROXYCHAIN).apply { remarks = "chain" }

        assertNull(WarpAssistant.chainMembers(comma, warp, ChainOrder.ExitThroughWarp))
        assertNull(WarpAssistant.chainMembers(blank, warp, ChainOrder.ExitThroughWarp))
        assertNull(WarpAssistant.chainMembers(sameName, warp, ChainOrder.ExitThroughWarp))
        assertNull(WarpAssistant.chainMembers(chain, warp, ChainOrder.ExitThroughWarp))
    }
}
