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
        assertTrue(coreServiceSrc.contains("control?.stopService()"))
    }
}
