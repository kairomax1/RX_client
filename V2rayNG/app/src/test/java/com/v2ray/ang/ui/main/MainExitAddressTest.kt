package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainExitAddressTest {

    @Test
    fun addressIsTypedOneCharacterAtATime() {
        assertEquals(listOf("1", ".", "2", ".", "3", ".", "4"), MainExitAddress.typingUnits("1.2.3.4"))
    }

    @Test
    fun flagIsOnePiece() {
        val label = MainExitAddress.countryLabel("de") { "Germany" }
        val units = MainExitAddress.typingUnits(label)
        assertEquals(MainExitAddress.flag("DE"), units.first())
        assertEquals(" ", units[1])
        assertEquals("Germany".length + 2, units.size)
    }

    @Test
    fun codeBecomesFlagAndName() {
        assertEquals(MainExitAddress.flag("FR") + " France", MainExitAddress.countryLabel(" fr ") { "France" })
    }

    @Test
    fun unknownNameFallsBackToTheCode() {
        assertEquals(MainExitAddress.flag("ZZ") + " ZZ", MainExitAddress.countryLabel("zz") { "" })
    }

    @Test
    fun nonCodeIsShownAsSent() {
        assertEquals("Germany", MainExitAddress.countryLabel("Germany") { "unused" })
        assertEquals("", MainExitAddress.countryLabel(null) { "unused" })
        assertEquals("", MainExitAddress.flag("D1"))
    }

    @Test
    fun pacingIsCalm() {
        assertEquals(MainExitAddress.CHAR_MS * 13, MainExitAddress.typingMillis("213.156.152.9".length))
        assertEquals(0, MainExitAddress.typingMillis(-1))
    }

    @Test
    fun readingTheAddressIsNotHeldUp() {
        // The line is read on every connection and changed by any config whose
        // exit address moves, so typing it out must be over quickly.
        assertTrue(MainExitAddress.typingMillis("213.156.152.9".length) <= 600)
        assertTrue(MainExitAddress.COUNTRY_DELAY_MS <= 300L)
    }
}
