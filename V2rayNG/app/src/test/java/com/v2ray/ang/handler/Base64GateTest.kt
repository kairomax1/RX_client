package com.v2ray.ang.handler

import android.util.Base64
import android.util.Log
import com.v2ray.ang.util.Utils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import java.util.Base64 as JavaBase64

/**
 * The decoder is given every imported line twice — once to read an encoded list,
 * once to read the plain one — and a plain line can only fail. Each failure wrote
 * two lines to the log: seven hundred and eighty-six in a single day, all of them
 * the same sentence, and the real faults could not be found among them.
 *
 * Deciding before the attempt is made changes nothing about what is read: the
 * gate accepts everything that could decode and refuses only what could not.
 */
class Base64GateTest {

    private lateinit var mockBase64: MockedStatic<Base64>
    private lateinit var mockLog: MockedStatic<Log>

    @BeforeEach
    fun setUp() {
        // android.util.Base64 is a stub in a local unit test; the real encoder is
        // put in its place so the answers mean something.
        mockBase64 = mockStatic(Base64::class.java)
        mockBase64.`when`<String> { Base64.encodeToString(Mockito.any(ByteArray::class.java), Mockito.anyInt()) }
            .thenAnswer { JavaBase64.getEncoder().encodeToString(it.getArgument(0) as ByteArray) }
        mockBase64.`when`<ByteArray> { Base64.decode(Mockito.anyString(), Mockito.anyInt()) }
            .thenAnswer { JavaBase64.getDecoder().decode(it.getArgument(0) as String) }
        mockLog = mockStatic(Log::class.java)
    }

    @AfterEach
    fun tearDown() {
        mockBase64.close()
        mockLog.close()
    }

    @Test
    fun aShareLinkIsNeverHandedToTheDecoder() {
        // ':' and '#' are not in the alphabet, so both are settled without a decode.
        assertFalse(Utils.isPossiblyBase64("vless://uuid@host:443?x=1#name"))
        assertFalse(Utils.isPossiblyBase64("https://example.com/sub"))
        assertFalse(Utils.isPossiblyBase64("trojan://pass@1.2.3.4:443"))
    }

    @Test
    fun realBase64IsStillAccepted() {
        assertTrue(Utils.isPossiblyBase64("dmxlc3M6Ly91dWlkQGhvc3Q6NDQzI25hbWU="))
        assertTrue(Utils.isPossiblyBase64("dmxlc3M6Ly91dWlkQGhvc3Q6NDQzI25hbWU"))
        assertTrue(Utils.isPossiblyBase64("-_8AAAAAAAAAAAAAAAAAAA"))
    }

    @Test
    fun whatTheDecoderGivesBackIsTheSameAsBefore() {
        val encoded = JavaBase64.getEncoder().encodeToString("vless://a@b:443".toByteArray())
        assertEquals("vless://a@b:443", Utils.decode(encoded))
        // A plain link is not base64 and never was: it comes back empty, exactly as
        // it did before the gate, and the caller falls back to reading it as itself.
        assertEquals("", Utils.decode("vless://a@b:443"))
    }
}
