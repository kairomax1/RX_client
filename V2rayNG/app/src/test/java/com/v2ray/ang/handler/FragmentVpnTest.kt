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
}
