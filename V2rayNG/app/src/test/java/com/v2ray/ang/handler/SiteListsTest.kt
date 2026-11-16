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
}
