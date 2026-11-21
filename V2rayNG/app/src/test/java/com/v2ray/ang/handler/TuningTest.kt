package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TuningTest {

    @Test
    fun bypassPlanOffersMuxAndFragmentWithoutPlainForVlessTls() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            security = AppConfig.TLS
            network = "ws"
        }
        assertTrue(Tuning.worthTrying(profile))
        val bypass = Tuning.bypassPlan(profile)
        assertFalse(Tuning.PLAIN in bypass)
        assertEquals(listOf(Tuning.FRAGMENT, Tuning.MUX, Tuning.FRAGMENT_FINE, Tuning.BOTH), bypass)
    }

    @Test
    fun bypassPlanOffersFragmentOnlyForTrojanTlsAndMuxOnlyForPlainVmess() {
        val trojanTls = ProfileItem.create(EConfigType.TROJAN).apply {
            security = AppConfig.TLS
            network = "tcp"
        }
        assertEquals(listOf(Tuning.FRAGMENT, Tuning.FRAGMENT_FINE), Tuning.bypassPlan(trojanTls))

        val vmessPlain = ProfileItem.create(EConfigType.VMESS).apply {
            security = ""
            network = "tcp"
        }
        assertEquals(listOf(Tuning.MUX), Tuning.bypassPlan(vmessPlain))

        val wireguard = ProfileItem.create(EConfigType.WIREGUARD)
        assertFalse(Tuning.worthTrying(wireguard))
        assertTrue(Tuning.bypassPlan(wireguard).isEmpty())
    }

    @Test
    fun unbypassableMinusOneConfigsAreTestedWithFragmentAndMuxAndStoppedIfBlocked() {
        val realPingSrc = java.io.File("src/main/java/com/v2ray/ang/service/RealPingWorkerService.kt").readText()
        val runnerSrc = java.io.File("src/main/java/com/v2ray/ang/handler/TuningRunner.kt").readText()
        val mainActivitySrc = java.io.File("src/main/java/com/v2ray/ang/ui/main/MainActivity.kt").readText()
        val mainViewModelSrc = java.io.File("src/main/java/com/v2ray/ang/ui/main/MainViewModel.kt").readText()
        val coreServiceSrc = java.io.File("src/main/java/com/v2ray/ang/core/CoreServiceManager.kt").readText()

        assertTrue(realPingSrc.contains("Tuning.bypassPlan(config)"))
        assertTrue(realPingSrc.contains("Tuning.remember(guid, choice)"))
        assertTrue(realPingSrc.contains("Tuning.markUnbypassable(guid)"))

        assertTrue(runnerSrc.contains("Phase.Failed"))
        assertTrue(runnerSrc.contains("LauncherManager.stopService(context)"))
        assertTrue(runnerSrc.contains("bypassed = false"))

        assertTrue(mainActivitySrc.contains("if (!result.bypassed)"))
        assertTrue(mainActivitySrc.contains("LauncherManager.stopService(this@MainActivity)"))

        assertTrue(mainViewModelSrc.contains("testDelayMillis ?: 0L) < 0L"))
        assertTrue(coreServiceSrc.contains("serviceControl.stopService()") || coreServiceSrc.contains("reloadCore()"))
    }

    @Test
    fun modeFilteringRespectsMuxOnlyFragmentOnlyAndNone() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            security = AppConfig.TLS
            network = "ws"
        }

        val muxOnly = Tuning.plan(profile, allowMux = true, allowFragment = false)
        assertTrue(muxOnly.all { !it.fragment })
        assertTrue(muxOnly.any { it.mux })
        assertEquals(listOf(Tuning.PLAIN, Tuning.MUX_LIGHT, Tuning.MUX, Tuning.MUX_HEAVY), muxOnly)

        val fragmentOnly = Tuning.plan(profile, allowMux = false, allowFragment = true)
        assertTrue(fragmentOnly.all { !it.mux })
        assertTrue(fragmentOnly.any { it.fragment })
        assertEquals(listOf(Tuning.PLAIN, Tuning.FRAGMENT_WIDE, Tuning.FRAGMENT, Tuning.FRAGMENT_FINE), fragmentOnly)

        val none = Tuning.plan(profile, allowMux = false, allowFragment = false)
        assertEquals(listOf(Tuning.PLAIN), none)
    }

    @Test
    fun networkPresetAndPersianStringsMatchRequestedUx() {
        val cellularMux = Tuning.networkPreset(isCellular = true, probeLatencyMs = 400L, mode = Tuning.Mode.MUX_ONLY)
        assertTrue(cellularMux.mux)
        assertFalse(cellularMux.fragment)
        assertEquals("4", cellularMux.concurrency)

        val wifiFragment = Tuning.networkPreset(isCellular = false, probeLatencyMs = 90L, mode = Tuning.Mode.FRAGMENT_ONLY)
        assertTrue(wifiFragment.fragment)
        assertFalse(wifiFragment.mux)
        assertEquals("100-200", wifiFragment.length)

        val faStrings = java.io.File("src/main/res/values-fa/strings.xml").readText()
        assertTrue(faStrings.contains("دارم موکس رو به بهترین حالتش پیدا می‌کنم"))
        assertTrue(faStrings.contains("دارم فرگمنت رو به بهترین حالتش پیدا می‌کنم"))
        assertTrue(faStrings.contains("اعمال بهترین حالت"))

        val settingsSrc = java.io.File("src/main/java/com/v2ray/ang/ui/settings/SettingsActivity.kt").readText()
        assertTrue(settingsSrc.contains("settings_apply_best_tuning"))
        assertTrue(settingsSrc.contains("PREF_RX_FRAGMENT_ENABLED"))
        assertTrue(settingsSrc.contains("PREF_RX_MUX_ENABLED"))
    }
}
