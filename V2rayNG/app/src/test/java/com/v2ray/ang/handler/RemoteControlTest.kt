package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteControlTest {

    @Test
    fun bundledControlManifestParsesCleanly() {
        val manifest = java.io.File("../../rx-control.json").readText()
        val parsed = RemoteControl.parse(manifest)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.schemaVersion)
        // Background list syncs are opt-in: the bundled manifest keeps them off so the
        // app never downloads on its own (lag/freeze/self-download fix).
        assertFalse(parsed.features.publicConfigs)
        assertFalse(parsed.features.mozillaVpn)
        assertTrue(parsed.features.gamingMode)
        assertFalse(parsed.features.dnsScanner)
        assertTrue(parsed.features.aether)
        assertTrue("youtubei.googleapis.com" in parsed.routing.unblockDomains)
        assertEquals(true, parsed.routing.blockUdp443)
        assertFalse(parsed.core.silentGeoUpdate)
    }

    @Test
    fun retiredMozillaProbeTargetIsIgnoredByRemotePolicy() {
        val parsed = RemoteControl.parse(
            """{"schemaVersion":1,"mozilla":{"probeTarget":"https://cp.cloudflare.com"}}"""
        )
        assertNotNull(parsed)
        assertEquals(RemoteControl.MozillaPolicy(), parsed!!.mozilla)
    }

    @Test
    fun customOverridesAreParsedWithoutErrors() {
        val raw = """
            {
              "schemaVersion": 1,
              "revision": 42,
              "features": { "aether": false, "autoFailover": true },
              "publicConfigs": {
                "extraGithubSources": ["owner/repo@main:list.txt"],
                "extraUrlSources": ["https://t.me/s/example"],
                "disabledSources": ["old/repo"],
                "emergencyConfigs": ["vless://id@1.2.3.4:443?security=tls"],
                "allowCloudflare": true
              },
              "routing": {
                "unblockDomains": ["domain:youtube.com"],
                "blockDomains": ["ads.bad.example"],
                "blockUdp443": false
              }
            }
        """.trimIndent()
        val cfg = RemoteControl.parse(raw)!!
        assertEquals(42L, cfg.revision)
        assertFalse(cfg.features.aether)
        assertEquals(true, cfg.features.autoFailover)
        assertEquals(listOf("owner/repo@main:list.txt"), cfg.publicConfigs.extraGithubSources)
        assertEquals(listOf("https://t.me/s/example"), cfg.publicConfigs.extraUrlSources)
        assertTrue(cfg.publicConfigs.allowCloudflare)
        assertEquals(false, cfg.routing.blockUdp443)
        assertEquals(setOf("youtube.com"), RemoteControl.normalizedDomainKeys(cfg.routing.unblockDomains))
        assertNull(RemoteControl.parse("not-json"))
    }
}
