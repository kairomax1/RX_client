package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class FirefoxVpnRelayTest {

    @Test
    fun readsAConnectHeadWhole() {
        val head = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\n"
        assertEquals(head, FirefoxVpnRelay.readHead(ByteArrayInputStream(head.toByteArray())))
    }

    @Test
    fun stopsAtTheEndOfTheHeaders() {
        val head = "GET http://a.b/x HTTP/1.1\r\nHost: a.b\r\n\r\nBODY"
        assertEquals(
            "GET http://a.b/x HTTP/1.1\r\nHost: a.b\r\n\r\n",
            FirefoxVpnRelay.readHead(ByteArrayInputStream(head.toByteArray()))
        )
    }

    @Test
    fun authorityCarriesTheDefaultPort() {
        assertEquals("example.com:80", FirefoxVpnRelay.authorityOf("http://example.com/path"))
        assertEquals("example.com:8080", FirefoxVpnRelay.authorityOf("http://example.com:8080/path"))
        assertEquals("example.com:80", FirefoxVpnRelay.authorityOf("http://example.com"))
    }

    @Test
    fun aHeadThatNeverEndsIsNotAHead() {
        assertNull(FirefoxVpnRelay.readHead(ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray())))
    }
}
