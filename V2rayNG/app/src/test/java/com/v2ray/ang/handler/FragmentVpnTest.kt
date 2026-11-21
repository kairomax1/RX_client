package com.v2ray.ang.handler

import com.v2ray.ang.ui.main.MainDestination
import com.v2ray.ang.ui.main.quickSheetItems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertTrue(FragmentVpn.Preset.measurablePresets.all { it.maxSplit == "0" })
        assertTrue(MainDestination.FragmentVpn in quickSheetItems)
        assertTrue(FragmentVpn.isFragmentProfile(FragmentVpn.PROFILE_GUID))
        assertTrue(PublicConfigs.isInternalGroup(FragmentVpn.GROUP_ID))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".ui.fragmentvpn.FragmentVpnActivity"))

        val faStrings = File("src/main/res/values-fa/strings.xml").readText()
        assertTrue(faStrings.contains(">فرگمنت</string>"))
    }

    @Test
    fun fragmentVpnUsesDirectUserIpWithoutAetherOrUdpNoise() {
        val fragSrc = File("src/main/java/com/v2ray/ang/handler/FragmentVpn.kt").readText()
        val coreSvcSrc = File("src/main/java/com/v2ray/ang/core/CoreServiceManager.kt").readText()
        val actSrc = File("src/main/java/com/v2ray/ang/ui/fragmentvpn/FragmentVpnActivity.kt").readText()

        // Must never attach Aether / Cloudflare tunnel to Fragment VPN.
        assertFalse(fragSrc.contains("AetherEngine"))
        assertFalse(fragSrc.contains("shouldUseAetherTunnel"))
        assertFalse(coreSvcSrc.contains("FragmentVpn.shouldUseAetherTunnel"))
        assertFalse(actSrc.contains("AetherEngine"))

        // Direct freedom mode must not inject UDP noise (which breaks games/WhatsApp/STUN).
        assertFalse(fragSrc.contains("type = \"noise\""))
        assertFalse(fragSrc.contains("NoiseMaskBean"))

        // Must include tun inbound support when hev-socks5-tunnel is disabled.
        assertTrue(fragSrc.contains("v2ray_config_with_tun.json"))

        // Must route domestic DNS cleanly and avoid plain UDP 1.1.1.1 / 8.8.8.8 fallbacks.
        assertTrue(fragSrc.contains("TAG_DOMESTIC_DNS"))
        assertTrue(fragSrc.contains("enableParallelQuery = true"))
        val dohSection = fragSrc.substringAfter("val dohList").substringBefore(".distinct()")
        assertFalse(Regex("\"1\\.1\\.1\\.1\"(?![/:])").containsMatchIn(dohSection))
        assertFalse(Regex("\"8\\.8\\.8\\.8\"(?![/:])").containsMatchIn(dohSection))

        // Activity must verify via CoreServiceManager / OkHttp SOCKS5 remote DNS rather than Proxy.Type.SOCKS URLConnection.
        assertTrue(actSrc.contains("measureDelayDirect"))
        assertTrue(actSrc.contains("HttpUtil.getUrlContent"))
        assertFalse(actSrc.contains("Proxy.Type.SOCKS"))
    }
}
