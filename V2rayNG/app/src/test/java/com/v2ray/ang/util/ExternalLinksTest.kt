package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalLinksTest {

    @Test
    fun channelLinkOpensTelegramDirectly() {
        assertEquals("tg://resolve?domain=aimobix", ExternalLinks.telegramDeepLink("https://t.me/aimobix"))
        assertEquals("tg://resolve?domain=aimobix", ExternalLinks.telegramDeepLink(" http://telegram.me/aimobix/ "))
        assertEquals("tg://resolve?domain=aimobix", ExternalLinks.telegramDeepLink("https://t.me/aimobix?start=1"))
    }

    @Test
    fun postAndInviteLinksAreTranslated() {
        assertEquals("tg://resolve?domain=aimobix&post=42", ExternalLinks.telegramDeepLink("https://t.me/aimobix/42"))
        assertEquals("tg://join?invite=AbCd_12", ExternalLinks.telegramDeepLink("https://t.me/+AbCd_12"))
        assertEquals("tg://join?invite=AbCd_12", ExternalLinks.telegramDeepLink("https://t.me/joinchat/AbCd_12"))
    }

    @Test
    fun otherLinksAreLeftToTheBrowser() {
        assertNull(ExternalLinks.telegramDeepLink("https://github.com/kairomax1"))
        assertNull(ExternalLinks.telegramDeepLink("https://t.me/"))
        assertNull(ExternalLinks.telegramDeepLink("https://t.me/a/b/c"))
        assertNull(ExternalLinks.telegramDeepLink("https://t.me/ab"))
    }

    @Test
    fun youtubeLinksAreRecognised() {
        assertTrue(ExternalLinks.isYoutubeLink("https://youtube.com/@aimobixnet"))
        assertTrue(ExternalLinks.isYoutubeLink("https://www.youtube.com/watch?v=x"))
        assertFalse(ExternalLinks.isYoutubeLink("https://t.me/aimobix"))
    }
}
