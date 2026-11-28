package com.v2ray.ang.handler

import com.v2ray.ang.ui.ipscanner.IpScannerViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpScanInputTest {

    @Test
    fun parsesIndividualIpv4AddressesAndDeduplicatesOverlappingRanges() {
        val parsed = IpScanInput.parseTargets(
            "192.0.2.1, 192.0.2.2\n192.0.2.1",
            requestedCount = 2,
        )

        assertNull(parsed.error)
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), parsed.addresses)
    }

    @Test
    fun expandsIpv4CidrWithoutNetworkAndBroadcastAddresses() {
        val parsed = IpScanInput.parseTargets("192.0.2.0/29", requestedCount = 6)

        assertNull(parsed.error)
        assertEquals(
            listOf("192.0.2.1", "192.0.2.2", "192.0.2.3", "192.0.2.4", "192.0.2.5", "192.0.2.6"),
            parsed.addresses,
        )
    }

    @Test
    fun supportsIpv4StartEndRangesAndWhitespaceAroundSeparator() {
        val parsed = IpScanInput.parseTargets("198.51.100.3 - 198.51.100.5", requestedCount = 3)

        assertNull(parsed.error)
        assertEquals(listOf("198.51.100.3", "198.51.100.4", "198.51.100.5"), parsed.addresses)
    }

    @Test
    fun supportsIpv6AddressesRangesAndCidrs() {
        val cidr = IpScanInput.parseTargets("2001:db8::/126", requestedCount = 4)
        val range = IpScanInput.parseTargets("2001:db8::5-2001:db8::7", requestedCount = 3)

        assertNull(cidr.error)
        assertEquals(4, cidr.addresses.size)
        assertTrue(cidr.addresses.all { IpScanInput.addressOf(it)?.address?.size == 16 })
        assertEquals(4, cidr.addresses.toSet().size)
        assertNull(range.error)
        assertEquals(3, range.addresses.size)
        assertEquals(3, range.addresses.toSet().size)
    }

    @Test
    fun supportsMixedIpv4AndIpv6Targets() {
        val parsed = IpScanInput.parseTargets("192.0.2.10, 2001:db8::10", requestedCount = 2)

        assertNull(parsed.error)
        assertEquals(2, parsed.addresses.size)
        assertTrue(parsed.addresses.any { ':' !in it })
        assertTrue(parsed.addresses.any { ':' in it })
    }

    @Test
    fun rejectsHostnamesMalformedAddressesAndInvalidRanges() {
        listOf(
            "example.com",
            "256.0.0.1",
            "192.0.2.1/33",
            "2001:db8::/129",
            "192.0.2.5-192.0.2.1",
            "192.0.2.1-2001:db8::1",
        ).forEach { invalid ->
            assertEquals(
                IpScanInput.ScanError.InvalidTargets,
                IpScanInput.parseTargets(invalid, requestedCount = 1).error,
                invalid,
            )
        }
    }

    @Test
    fun reportsWhenThereAreNotEnoughUniqueAddresses() {
        val parsed = IpScanInput.parseTargets("192.0.2.1, 192.0.2.1", requestedCount = 2)
        assertEquals(IpScanInput.ScanError.NotEnoughAddresses, parsed.error)
    }

    @Test
    fun parsesAndDeduplicatesCustomPorts() {
        val parsed = IpScanInput.parsePorts(
            selectedPorts = listOf(443, 80),
            customPortsText = "2053, 443; 53",
        )

        assertNull(parsed.error)
        assertEquals(listOf(443, 80, 2053, 53), parsed.ports)
    }

    @Test
    fun rejectsInvalidOrExcessivePorts() {
        assertEquals(
            IpScanInput.ScanError.InvalidPort,
            IpScanInput.parsePorts(emptyList(), "0").error,
        )
        assertEquals(
            IpScanInput.ScanError.InvalidPort,
            IpScanInput.parsePorts(emptyList(), "65536").error,
        )
        assertEquals(
            IpScanInput.ScanError.TooManyPorts,
            IpScanInput.parsePorts((1..12).toList(), "13").error,
        )
    }

    @Test
    fun remoteTargetListsAllowCommentsButRejectHostnames() {
        assertEquals(
            "8.8.8.8\n2001:4860:4860:0:0:0:0:8888",
            IpScanInput.normalizeRemoteList("# public targets\n8.8.8.8 # v4\n2001:4860:4860::8888\n"),
        )
        assertNull(IpScanInput.normalizeRemoteList("example.com\n"))
        assertNull(IpScanInput.normalizeRemoteList("# comment only\n"))
    }

    @Test
    fun remoteListUrlsRequireHttpsAndRejectEmbeddedCredentials() {
        assertTrue(IpScanner.isValidRemoteListUrl("https://example.org/targets.txt"))
        assertFalse(IpScanner.isValidRemoteListUrl("http://example.org/targets.txt"))
        assertFalse(IpScanner.isValidRemoteListUrl("https://user:secret@example.org/targets.txt"))
        assertFalse(IpScanner.isValidRemoteListUrl("file:///tmp/targets.txt"))
        assertFalse(IpScanner.isValidRemoteListUrl("https://"))
    }

    @Test
    fun builtinTargetSetsAreNumericAndContainBothIpFamilies() {
        val addresses = IpScanner.BUILT_IN_TARGETS.flatMap { it.addresses }
        assertEquals(16, addresses.size)
        assertTrue(addresses.all(IpScanInput::isValidAddress))
        assertTrue(addresses.any { ':' !in it })
        assertTrue(addresses.any { ':' in it })
        assertEquals(addresses.size, addresses.toSet().size)
    }

    @Test
    fun prepareScanEnforcesCountPortsAndProbeLimits() {
        assertEquals(
            IpScanInput.ScanError.InvalidCount,
            IpScanner.prepareScan("192.0.2.1", "not-a-number", IpScanner.Mode.NONE, emptyList(), "").error,
        )
        assertEquals(
            IpScanInput.ScanError.CountOutOfRange,
            IpScanner.prepareScan("192.0.2.1", "257", IpScanner.Mode.NONE, emptyList(), "").error,
        )
        assertEquals(
            IpScanInput.ScanError.NoPorts,
            IpScanner.prepareScan("192.0.2.1", "1", IpScanner.Mode.TCP, emptyList(), "").error,
        )
        assertEquals(
            IpScanInput.ScanError.TooManyProbes,
            IpScanner.prepareScan(
                "2001:db8::/120",
                "256",
                IpScanner.Mode.TCP,
                IpScanner.PRESET_PORTS,
                "",
            ).error,
        )
        val noPortRequest = IpScanner.prepareScan("192.0.2.1", "1", IpScanner.Mode.NONE, emptyList(), "")
        assertNull(noPortRequest.error)
        assertEquals(emptyList<Int>(), noPortRequest.request?.ports)
    }

    @Test
    fun viewModelInitialStateUsesManualTargetsAndBoundedDefaults() {
        val state = IpScannerViewModel.UiState()

        assertEquals(IpScanner.TargetSource.MANUAL, state.targetSource)
        assertEquals("", state.addressText)
        assertEquals("16", state.countText)
        assertEquals(IpScanner.Mode.TCP, state.mode)
        assertEquals(IpScanner.DEFAULT_PORTS, state.selectedPorts)
        assertEquals(IpScanner.BUILT_IN_TARGETS.map { it.id }.toSet(), state.selectedBuiltInTargetIds)
        assertTrue(state.loadingNetworkInfo)
        assertFalse(state.scanning)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun resolvesManualBuiltinAndRemoteTargetSources() {
        val manual = IpScanner.resolveTargetInput(
            IpScanner.TargetSource.MANUAL,
            "192.0.2.1",
            emptySet(),
            "ignored remote list",
        )
        val builtin = IpScanner.resolveTargetInput(
            IpScanner.TargetSource.BUILT_IN,
            "ignored manual targets",
            setOf("quad9-dns"),
            "ignored remote list",
        )
        val remote = IpScanner.resolveTargetInput(
            IpScanner.TargetSource.REMOTE,
            "ignored manual targets",
            emptySet(),
            "198.51.100.1\n2001:db8::1",
        )

        assertEquals("192.0.2.1", manual.addressText)
        assertNull(manual.error)
        assertEquals(IpScanner.BUILT_IN_TARGETS.single { it.id == "quad9-dns" }.addresses.joinToString("\n"), builtin.addressText)
        assertNull(builtin.error)
        assertEquals("198.51.100.1\n2001:db8::1", remote.addressText)
        assertNull(remote.error)
    }

    @Test
    fun sourceAndInputSelectionHelpersHandleValidEmptyAndUnknownChoices() {
        assertEquals(
            IpScanInput.ScanError.NoBuiltInTargets,
            IpScanner.resolveTargetInput(IpScanner.TargetSource.BUILT_IN, "", emptySet(), "").error,
        )
        assertEquals(
            IpScanInput.ScanError.RemoteListNotLoaded,
            IpScanner.resolveTargetInput(IpScanner.TargetSource.REMOTE, "", emptySet(), " ").error,
        )
        assertEquals(
            emptySet<String>(),
            IpScanner.toggleBuiltInTarget(setOf("google-dns"), "google-dns"),
        )
        assertEquals(
            setOf("quad9-dns"),
            IpScanner.toggleBuiltInTarget(emptySet(), "quad9-dns"),
        )
        assertEquals(
            emptySet<String>(),
            IpScanner.toggleBuiltInTarget(emptySet(), "unknown-target"),
        )
        assertEquals(listOf(443, 80, 2053), IpScanner.togglePresetPort(listOf(443, 80), 2053))
        assertEquals(listOf(80), IpScanner.togglePresetPort(listOf(443, 80), 443))
        assertEquals(listOf(443), IpScanner.togglePresetPort(listOf(443), 22))
    }

    @Test
    fun inputSanitizersBoundCountAndPortText() {
        assertEquals("123", IpScanner.sanitizeCountText("1a2c3456"))
        assertEquals("", IpScanner.sanitizeCountText("letters"))
        assertEquals("53, 2053\n8443", IpScanner.sanitizeCustomPortsText("53, 2053\n8443"))
        assertEquals("123", IpScanner.sanitizeCustomPortsText("a1b2c3"))
    }

    @Test
    fun closedTcpPortStillCountsAsAnAddressThatResponded() {
        val result = IpScanner.AddressResult(
            address = "192.0.2.1",
            probes = listOf(
                IpScanner.ProbeResult(
                    address = "192.0.2.1",
                    mode = IpScanner.Mode.TCP,
                    port = 443,
                    status = IpScanner.Status.CLOSED,
                    latencyMs = 2,
                ),
            ),
        )

        assertTrue(result.found)
        assertFalse(result.probes.any { it.status == IpScanner.Status.OPEN })
    }
}
