package com.v2ray.ang.handler

import com.v2ray.ang.ui.main.MainDestination
import com.v2ray.ang.ui.main.quickSheetItems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FragmentVpnTest {

    @Test
    fun presetsAndEntryPointsAreRegistered() {
        assertEquals(
            listOf(FragmentVpn.Preset.BALANCED, FragmentVpn.Preset.STRICT_ISP, FragmentVpn.Preset.LOW_SPLIT),
            FragmentVpn.Preset.measurablePresets,
        )
        assertTrue(MainDestination.FragmentVpn in quickSheetItems)
        assertTrue(FragmentVpn.isFragmentProfile(FragmentVpn.PROFILE_GUID))
        assertTrue(PublicConfigs.isInternalGroup(FragmentVpn.GROUP_ID))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".ui.fragmentvpn.FragmentVpnActivity"))

        val faStrings = File("src/main/res/values-fa/strings.xml").readText()
        assertTrue(faStrings.contains(">فرگمنت</string>"))
    }

    @Test
    fun fragmentVpnSupportsTunnelAndDoesNotCorruptUdpWithNoise() {
        val fragSrc = File("src/main/java/com/v2ray/ang/handler/FragmentVpn.kt").readText()
        val coreSvcSrc = File("src/main/java/com/v2ray/ang/core/CoreServiceManager.kt").readText()
        val actSrc = File("src/main/java/com/v2ray/ang/ui/fragmentvpn/FragmentVpnActivity.kt").readText()

        // Direct freedom mode must not inject UDP noise (which breaks games/WhatsApp/STUN).
        assertTrue(!fragSrc.contains("type = \"noise\""))
        assertTrue(!fragSrc.contains("NoiseMaskBean"))
        // Must include tun inbound support when hev-socks5-tunnel is disabled.
        assertTrue(fragSrc.contains("v2ray_config_with_tun.json"))
        // Must support Aether server-less tunnel outbound and start AetherEngine in CoreServiceManager.
        assertTrue(fragSrc.contains("shouldUseAetherTunnel"))
        assertTrue(coreSvcSrc.contains("FragmentVpn.shouldUseAetherTunnel"))
        assertTrue(actSrc.contains("probeSocks204"))
    }
}
