package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpScanInputTest {

    @Test
    fun preparesCloudflareAndCustomScanInputsAndNoPortMode() {
        val cloudflare = IpScanInput.prepareScan(
            cloudflarePool = true,
            customRange = "",
            portEnabled = true,
            portText = "443",
            sniText = "Example.COM",
            cloudflareAddresses = listOf("104.16.0.1", "104.16.0.1"),
        ).request
        assertEquals(listOf("104.16.0.1"), cloudflare?.addresses)
        assertEquals(443, cloudflare?.port)
        assertEquals("example.com", cloudflare?.sni)

        val custom = IpScanInput.prepareScan(
            cloudflarePool = false,
            customRange = "192.0.2.1-192.0.2.2",
            portEnabled = false,
            portText = "invalid",
            sniText = "also ignored",
            cloudflareAddresses = emptyList(),
        ).request
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), custom?.addresses)
        assertEquals(null, custom?.port)
        assertEquals(null, custom?.sni)
    }

    @Test
    fun preparationReportsEveryInvalidInputWithoutStartingAProbe() {
        assertEquals(
            IpScanInput.ScanError.InvalidRange,
            IpScanInput.prepareScan(false, "", true, "443", "", emptyList()).error,
        )
        assertEquals(
            IpScanInput.ScanError.TooManyAddresses,
            IpScanInput.prepareScan(false, "192.0.2.0/23", true, "443", "", emptyList()).error,
        )
        assertEquals(
            IpScanInput.ScanError.InvalidPort,
            IpScanInput.prepareScan(false, "192.0.2.1", true, "0", "", emptyList()).error,
        )
        assertEquals(
            IpScanInput.ScanError.InvalidSni,
            IpScanInput.prepareScan(false, "192.0.2.1", true, "443", "bad name", emptyList()).error,
        )
    }

    @Test
    fun parsesOneAddressCidrAndInclusiveRange() {
        assertEquals(
            listOf("192.0.2.7"),
            IpScanInput.parseIpv4Range(" 192.0.2.7 ").addresses,
        )
        assertEquals(
            listOf("192.0.2.4", "192.0.2.5", "192.0.2.6", "192.0.2.7"),
            IpScanInput.parseIpv4Range("192.0.2.5/30").addresses,
        )
        assertEquals(
            listOf("198.51.100.2", "198.51.100.3", "198.51.100.4"),
            IpScanInput.parseIpv4Range("198.51.100.2-198.51.100.4").addresses,
        )
    }

    @Test
    fun rejectsInvalidAndReversedRanges() {
        listOf(
            "",
            "example.com",
            "256.1.1.1",
            "1.2.3",
            "1.2.3.4/33",
            "1.2.3.4/24/25",
            "1.2.3.5-1.2.3.4",
        ).forEach { input ->
            assertEquals(IpScanInput.RangeError.Invalid, IpScanInput.parseIpv4Range(input).error, input)
        }
    }

    @Test
    fun boundsLargeRangesBeforeBuildingAddressList() {
        assertEquals(IpScanInput.RangeError.TooMany, IpScanInput.parseIpv4Range("192.0.2.0/23").error)
        assertEquals(256, IpScanInput.parseIpv4Range("192.0.2.0/24").addresses.size)
    }

    @Test
    fun normalizesDnsNamesAndRejectsIpLiteralsAndInvalidLabels() {
        assertEquals("example.com", IpScanInput.normalizeSni(" Example.COM. "))
        assertEquals("xn--bcher-kva.example", IpScanInput.normalizeSni("bücher.example"))
        assertNull(IpScanInput.normalizeSni(""))
        assertNull(IpScanInput.normalizeSni("192.0.2.1"))
        assertNull(IpScanInput.normalizeSni("bad name.example"))
        assertFalse(IpScanInput.isValidIpv4("192.0.2.256"))
    }

    @Test
    fun ipCopyChangesOnlyTheCopyAndKeepsProtocolAndHostSettings() {
        val source = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "Personal"
            server = "node.example"
            serverPort = "8443"
            sni = "old.example"
            host = "ws.example"
        }

        val copy = IpScanner.copyProfileForAddress(
            source = source,
            address = "203.0.113.7",
            port = 443,
            sni = " New.Example ",
            groupId = "ip-scan-group",
            copySuffix = "(IP scan)",
        )

        assertEquals(EConfigType.VLESS, copy?.configType)
        assertEquals("203.0.113.7", copy?.server)
        assertEquals("443", copy?.serverPort)
        assertEquals("new.example", copy?.sni)
        assertEquals("ws.example", copy?.host)
        assertEquals("ip-scan-group", copy?.subscriptionId)
        assertEquals("Personal (IP scan)", copy?.remarks)
        assertEquals("node.example", source.server)
        assertEquals("8443", source.serverPort)
        assertEquals("old.example", source.sni)
    }

    @Test
    fun ipCopyPreservesImplicitHostnameWhenReplacingTheServerWithAnIp() {
        val source = ProfileItem.create(EConfigType.VLESS).apply {
            server = "node.example"
            serverPort = "443"
        }
        val copy = IpScanner.copyProfileForAddress(
            source = source,
            address = "203.0.113.8",
            port = null,
            sni = null,
            groupId = "ip-scan-group",
            copySuffix = "(IP scan)",
        )

        assertEquals("node.example", copy?.sni)
        assertEquals("node.example", copy?.host)
        assertNull(source.sni)
        assertNull(source.host)
        assertEquals("node.example", source.server)
    }

    @Test
    fun ipCopyPreservesUnsetPortAndSniAndRejectsInvalidAddress() {
        val source = ProfileItem.create(EConfigType.VMESS).apply {
            server = "node.example"
            serverPort = "443"
            sni = "node.example"
        }
        val copy = IpScanner.copyProfileForAddress(
            source = source,
            address = "203.0.113.8",
            port = null,
            sni = null,
            groupId = "ip-scan-group",
            copySuffix = "(IP scan)",
        )

        assertEquals("443", copy?.serverPort)
        assertEquals("node.example", copy?.sni)
        assertEquals(null, IpScanner.copyProfileForAddress(
            source = source,
            address = "203.0.113.256",
            port = 443,
            sni = null,
            groupId = "ip-scan-group",
            copySuffix = "(IP scan)",
        ))
    }

    @Test
    fun proxyChainSpecPlacesTheScannedIpAsTheEntryHop() {
        val exit = ProfileItem.create(EConfigType.VLESS).apply { remarks = "Exit" }
        val current = ProfileItem.create(EConfigType.VMESS).apply { remarks = "Current" }
        val existingEntry = ProfileItem.create(EConfigType.TROJAN).apply { remarks = "Entry" }
        val scannedIp = ProfileItem.create(EConfigType.SOCKS).apply { remarks = "Scanned IP" }

        assertEquals(
            "Scanned IP,Entry,Current,Exit",
            IpScanner.buildProxyChainSpec(listOf(exit, current, existingEntry), scannedIp),
        )
        assertNull(
            IpScanner.buildProxyChainSpec(
                listOf(ProfileItem.create(EConfigType.VLESS).apply { remarks = "Exit, alternate" }),
                scannedIp,
            ),
        )
    }

    @Test
    fun optimizationPrefersBalancedSpeedThenEdgeResponse() {
        val bestBySpeed = IpScanner.EdgeCandidate("192.0.2.2", 80, 20.0, 10.0)
        val bestByLatency = IpScanner.EdgeCandidate("192.0.2.1", 15, 12.0, 12.0)
        assertEquals(bestBySpeed, IpScanner.bestEdgeCandidate(listOf(bestByLatency, bestBySpeed)))

        val noSpeedFast = IpScanner.EdgeCandidate("192.0.2.3", 10)
        val noSpeedSlow = IpScanner.EdgeCandidate("192.0.2.4", 40)
        assertEquals(noSpeedFast, IpScanner.bestEdgeCandidate(listOf(noSpeedSlow, noSpeedFast)))
        assertTrue(IpScanner.bestEdgeCandidate(emptyList()) == null)
    }
}
