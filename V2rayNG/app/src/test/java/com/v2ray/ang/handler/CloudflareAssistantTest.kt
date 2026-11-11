package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CloudflareAssistantTest {

    @Test
    fun announcedBlocksAreRecognised() {
        assertTrue(CloudflareAssistant.isCloudflareIpv4("104.16.0.1"))
        assertTrue(CloudflareAssistant.isCloudflareIpv4("104.27.255.255"))
        assertTrue(CloudflareAssistant.isCloudflareIpv4("172.67.1.1"))
        assertTrue(CloudflareAssistant.isCloudflareIpv4("162.159.36.1"))
        assertTrue(CloudflareAssistant.isCloudflareIpv4("188.114.99.5"))
        assertTrue(CloudflareAssistant.isCloudflareIpv4("198.41.200.1"))
        assertTrue(CloudflareAssistant.isCloudflareIpv4("131.0.72.7"))
    }

    @Test
    fun neighboursOfTheBlocksAreNotCloudflare() {
        assertFalse(CloudflareAssistant.isCloudflareIpv4("104.15.0.1"))
        assertFalse(CloudflareAssistant.isCloudflareIpv4("104.28.0.1"))
        assertFalse(CloudflareAssistant.isCloudflareIpv4("172.72.0.1"))
        assertFalse(CloudflareAssistant.isCloudflareIpv4("188.114.112.1"))
        assertFalse(CloudflareAssistant.isCloudflareIpv4("198.41.127.1"))
    }

    @Test
    fun nonIpValuesAreRejected() {
        assertFalse(CloudflareAssistant.isCloudflareIpv4(null))
        assertFalse(CloudflareAssistant.isCloudflareIpv4(""))
        assertFalse(CloudflareAssistant.isCloudflareIpv4("104.16.0.1:443"))
        assertFalse(CloudflareAssistant.isCloudflareIpv4("104.16.0"))
        assertFalse(CloudflareAssistant.isCloudflareIpv4("example.com"))
        assertFalse(CloudflareAssistant.isCloudflareIpv4("104.16.0.999"))
    }

    @Test
    fun sampledAddressesAreDistinctAndInsideTheAnnouncedBlocks() {
        val sample = CloudflareAssistant.sampleEdgeAddresses(200, Random(7))

        assertEquals(200, sample.size)
        assertEquals(200, sample.toSet().size)
        assertTrue(sample.all { CloudflareAssistant.isCloudflareIpv4(it) })
        assertTrue(sample.none { it.endsWith(".0") || it.endsWith(".255") })
    }

    @Test
    fun samplingIsRepeatableForTheSameSeedAndEmptyForNoCount() {
        assertEquals(
            CloudflareAssistant.sampleEdgeAddresses(40, Random(3)),
            CloudflareAssistant.sampleEdgeAddresses(40, Random(3)),
        )
        assertTrue(CloudflareAssistant.sampleEdgeAddresses(0, Random(3)).isEmpty())
    }

    @Test
    fun deepScanKeepsTheFixedSampleAndAddsRandomAddresses() {
        val addresses = CloudflareAssistant.deepScanAddresses(Random(11))

        assertTrue(addresses.contains("104.16.0.1"))
        assertEquals(addresses.size, addresses.toSet().size)
        assertTrue(addresses.size > 150)
        assertTrue(addresses.all { CloudflareAssistant.isCloudflareIpv4(it) })
    }
}
