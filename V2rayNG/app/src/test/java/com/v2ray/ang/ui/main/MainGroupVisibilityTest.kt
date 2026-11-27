package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherEngine
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainGroupVisibilityTest {

    @Test
    fun hidesAetherAndMozillaRowsButKeepsOrdinaryConfigs() {
        val aether = ProfileItem.create(EConfigType.SOCKS).apply {
            server = AetherEngine.HOST
            serverPort = AetherEngine.PORT.toString()
        }
        val mozilla = ProfileItem.create(EConfigType.HTTP).apply {
            server = "127.0.0.1"
            serverPort = "45678"
        }
        val regular = ProfileItem.create(EConfigType.VLESS).apply {
            server = "edge.example"
            serverPort = "443"
        }
        val profiles = mapOf("aether" to aether, "ordinary" to regular)

        val stored = listOf("aether", AppConfig.FIREFOX_VPN_PROFILE_ID, "ordinary")
        val visible = MainGroupVisibility.visibleGuids(stored) { guid ->
            profiles[guid] ?: mozilla.takeIf { guid == AppConfig.FIREFOX_VPN_PROFILE_ID }
        }

        assertEquals(listOf("ordinary"), visible)
        // Filtering is a display-only projection; records remain available for explicit use.
        assertEquals(listOf("aether", AppConfig.FIREFOX_VPN_PROFILE_ID, "ordinary"), stored)
    }

    @Test
    fun omitsOnlyGroupsPopulatedExclusivelyByBuiltInProfiles() {
        assertFalse(MainGroupVisibility.shouldExposeGroup(listOf("service"), emptyList()))
        assertTrue(MainGroupVisibility.shouldExposeGroup(listOf("service", "user"), listOf("user")))
        assertTrue(MainGroupVisibility.shouldExposeGroup(emptyList(), emptyList()))
    }
}
