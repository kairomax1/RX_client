package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UpdateReleaseTest {

    private val names = listOf(
        "RXVPN_1.0.2_arm64-v8a.apk",
        "RXVPN_1.0.2_armeabi-v7a.apk",
        "RXVPN_1.0.2_universal.apk",
        "RXVPN_1.0.2_x86.apk",
        "RXVPN_1.0.2_x86_64.apk",
        "SHA256SUMS.txt",
    )

    @Test
    fun appSeesOnlyItsOwnBlock() {
        val body = "Full text for GitHub.\n<!--app\nخلاصه برای برنامه\nخط دوم\n-->\nMore GitHub text."
        assertEquals("خلاصه برای برنامه\nخط دوم", UpdateRelease.appNotes(body))
    }

    @Test
    fun withoutABlockTheWholeDescriptionIsShown() {
        assertEquals("Plain notes", UpdateRelease.appNotes("Plain notes <!-- hidden -->"))
        assertEquals("", UpdateRelease.appNotes(null))
    }

    @Test
    fun apkMatchesTheProcessorExactly() {
        assertEquals("RXVPN_1.0.2_x86.apk", UpdateRelease.pickApk(names, listOf("x86")) { it })
        assertEquals("RXVPN_1.0.2_x86_64.apk", UpdateRelease.pickApk(names, listOf("x86_64", "x86")) { it })
        assertEquals("RXVPN_1.0.2_arm64-v8a.apk", UpdateRelease.pickApk(names, listOf("arm64-v8a", "armeabi-v7a")) { it })
    }

    @Test
    fun unknownProcessorGetsTheUniversalBuild() {
        assertEquals("RXVPN_1.0.2_universal.apk", UpdateRelease.pickApk(names, listOf("riscv64")) { it })
        assertNull(UpdateRelease.pickApk(listOf("SHA256SUMS.txt"), listOf("arm64-v8a")) { it })
    }

    @Test
    fun sizesAreShortAndLatin() {
        assertEquals("24.6 MB", UpdateRelease.formatSize(25_800_000))
        assertEquals("512 KB", UpdateRelease.formatSize(512 * 1024))
        assertEquals("", UpdateRelease.formatSize(0))
    }

    @Test
    fun checksumIsReadFromTheGithubDigest() {
        val hex = "a".repeat(64)
        assertEquals(hex, UpdateRelease.sha256Of("sha256:$hex"))
        assertEquals(hex, UpdateRelease.sha256Of("SHA256:" + "A".repeat(64)))
        assertNull(UpdateRelease.sha256Of("md5:abc"))
        assertNull(UpdateRelease.sha256Of(null))
    }

    @Test
    fun segmentsCoverTheFileWithoutGapsOrOverlap() {
        val ranges = UpdateRelease.segments(10_000_003, 4)
        assertEquals(4, ranges.size)
        assertEquals(0L, ranges.first().first)
        assertEquals(10_000_002L, ranges.last().last)
        for (i in 1 until ranges.size) assertEquals(ranges[i - 1].last + 1, ranges[i].first)
        assertEquals(10_000_003L, ranges.sumOf { it.last - it.first + 1 })
    }

    @Test
    fun testBuildsAreNeverOffered() {
        val names = listOf("RXVPN_1.0.2_test-debug_arm64-v8a.apk", "RXVPN_1.0.2_arm64-v8a.apk")
        assertEquals("RXVPN_1.0.2_arm64-v8a.apk", UpdateRelease.pickApk(names, listOf("arm64-v8a")) { it })
        assertEquals(null, UpdateRelease.pickApk(names.take(1), listOf("arm64-v8a")) { it })
    }
}
