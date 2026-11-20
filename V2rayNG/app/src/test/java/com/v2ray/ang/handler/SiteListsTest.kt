package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SiteListsTest {

    @Test
    fun namesAreReadFromEitherShapeTheListsAreWrittenIn() {
        val text = """
            # Clash
            # A note

            +.ads.example.com
            banner.example.net
            .tracker.example.org
        """.trimIndent()
        assertEquals(
            listOf("domain:ads.example.com", "domain:banner.example.net", "domain:tracker.example.org"),
            SiteLists.parseNames(text),
        )
    }

    @Test
    fun aNameThatWouldStopTheCoreIsLeftOut() {
        val text = """
            +.good.example.com
            not a host name
            http://example.com/path
            +.fine.example.net # and a note after it
        """.trimIndent()
        assertEquals(
            listOf("domain:good.example.com", "domain:fine.example.net"),
            SiteLists.parseNames(text),
        )
    }

    @Test
    fun theSameNameTwiceIsKeptOnce() {
        assertEquals(
            listOf("domain:example.com"),
            SiteLists.parseNames("+.example.com\nexample.com\n+.example.com"),
        )
    }

    @Test
    fun anEmptyAnswerIsEmpty() {
        assertTrue(SiteLists.parseNames("").isEmpty())
    }

    @Test
    fun youtubeAndEssentialDomainsAreNeverBlocked() {
        val text = """
            +.ads.example.com
            +.youtubei.googleapis.com
            +.youtube.googleapis.com
            +.s.youtube.com
            +.googlevideo.com
            +.ytimg.com
            +.fbcdn.net
        """.trimIndent()
        assertEquals(listOf("domain:ads.example.com"), SiteLists.parseNames(text))

        val bundledAds = java.io.File("src/main/assets/rx_ad_domains.txt").readLines()
        assertTrue("domain:youtubei.googleapis.com" !in bundledAds)
        assertTrue("domain:youtube.googleapis.com" !in bundledAds)
        assertTrue("full:s.youtube.com" !in bundledAds)
        assertTrue("domain:fbcdn.net" !in bundledAds)
    }
}
