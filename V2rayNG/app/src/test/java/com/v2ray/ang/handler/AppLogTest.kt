package com.v2ray.ang.handler

import com.v2ray.ang.handler.AppLog.Entry
import com.v2ray.ang.handler.AppLog.Level
import com.v2ray.ang.handler.AppLog.Mode
import com.v2ray.ang.handler.AppLog.Section
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppLogTest {

    private val entries = listOf(
        Entry(1, Section.Connection, Level.Info, "connect asked"),
        Entry(2, Section.Aether, Level.Debug, "engine line"),
        Entry(3, Section.Aether, Level.Error, "no route"),
        Entry(4, Section.Core, Level.Info, "core up"),
    )

    @Test
    fun aLineSurvivesBeingStoredEvenWithBarsInIt() {
        val entry = Entry(1700000000000, Section.Aether, Level.Error, "a|b|c")
        assertEquals(entry, AppLog.parse(AppLog.format(entry)))
        assertNull(AppLog.parse("broken"))
        assertNull(AppLog.parse("1|Nowhere|Info|x"))
    }

    @Test
    fun eachModeShowsWhatItPromises() {
        assertEquals(listOf(1L, 3L, 4L), AppLog.filter(entries, null, Mode.Normal).map { it.timeMillis })
        assertEquals(listOf(3L), AppLog.filter(entries, null, Mode.Errors).map { it.timeMillis })
        assertEquals(4, AppLog.filter(entries, null, Mode.Debug).size)
        assertEquals(listOf(2L, 3L), AppLog.filter(entries, Section.Aether, Mode.Debug).map { it.timeMillis })
    }

    @Test
    fun wholeConfigsAndShareLinksNeverReachTheLog() {
        assertNull(AppLog.sanitize("{\"outbounds\":[{\"settings\":{}}]}"))
        assertNull(AppLog.sanitize("imported vless://uuid@host:443?security=tls"))
        assertNull(AppLog.sanitize("   \n "))
        assertEquals("two lines", AppLog.sanitize("two\nlines"))
        assertTrue(AppLog.sanitize("x".repeat(5000))!!.length < 700)
    }

    @Test
    fun oldStyleLinesLandInTheRightSection() {
        assertEquals(Section.Aether, AppLog.sectionOf("Aether: trying 1/8"))
        assertEquals(Section.Core, AppLog.sectionOf("StartCore-Manager: Starting core loop"))
        assertEquals(Section.Connection, AppLog.sectionOf("LauncherManager: startService"))
        assertEquals(Section.Other, AppLog.sectionOf("something else"))
    }

    @Test
    fun theFileForSendingMarksErrors() {
        val text = AppLog.export(entries, "RX VPN 1.0.1")
        assertTrue(text.startsWith("RX VPN 1.0.1"))
        assertTrue(text.contains("[Aether] ERROR no route"))
        assertTrue(text.contains("[Core] core up"))
    }
}
