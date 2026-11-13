package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MainSubscriptionNamingTest {

    private val isLink: (String) -> Boolean = { it.startsWith("https://") }

    @Test
    fun onlyASingleSubscriptionLinkIsAskedForAName() {
        assertEquals("https://x.test/sub", singleSubscriptionLink("  https://x.test/sub \n", isLink))
        assertNull(singleSubscriptionLink("https://a.test\nhttps://b.test", isLink))
        assertNull(singleSubscriptionLink("vless://abc", isLink))
        assertNull(singleSubscriptionLink("https://a.test\nvless://abc", isLink))
        assertNull(singleSubscriptionLink(null, isLink))
    }

    @Test
    fun unnamedSubscriptionsAreNumberedInPersian() {
        val base = "اشتراک ها"
        assertEquals(base, nextSubscriptionName(base, emptyList()))
        assertEquals("$base ۲", nextSubscriptionName(base, listOf(base)))
        assertEquals("$base ۳", nextSubscriptionName(base, listOf(base, "$base ۲")))
        assertEquals("$base ۲", nextSubscriptionName(base, listOf(base, "$base ۳")))
        assertEquals("۱۲", persianDigits(12))
    }

    @Test
    fun typedNameWinsAndBlankFallsBackToTheAutomaticOne() {
        val base = "اشتراک ها"
        assertEquals("خونه", resolveSubscriptionName("  خونه ", base, listOf(base)))
        assertEquals("$base ۲", resolveSubscriptionName("   ", base, listOf(base)))
        assertEquals(base, resolveSubscriptionName(null, base, emptyList()))
    }
}
