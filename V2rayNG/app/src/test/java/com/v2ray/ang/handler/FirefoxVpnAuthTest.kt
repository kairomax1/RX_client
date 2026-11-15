package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirefoxVpnAuthTest {

    /** RFC 7914 vector: scrypt("", "", N=16, r=1, p=1, dkLen=64). */
    @Test
    fun scryptMatchesRfc7914Vector() {
        val result = FirefoxVpnAuth.scrypt(
            password = ByteArray(0),
            salt = ByteArray(0),
            N = 16, r = 1, p = 1, dkLen = 64
        )
        val expected = "77 d6 57 62 38 65 7b 20 3b 19 ca 42 c1 8a 04 97 " +
            "f1 6b 48 44 e3 07 4a e8 df df fa 3f ed e2 14 42 " +
            "fc d0 06 9d ed 09 48 f8 32 6a 75 3a 0f c8 1f 17 " +
            "e8 d3 e0 fb 2e 0d 36 28 cf 35 e2 0c 38 d1 89 06"
        assertEquals(expected, result.joinToString(" ") { "%02x".format(it) })
    }

    /** The parameters Firefox Accounts uses, checked against a reference implementation. */
    @Test
    fun scryptMatchesFirefoxAccountParameters() {
        val result = FirefoxVpnAuth.scrypt(
            password = "testpassword".toByteArray(),
            salt = "testsalt".toByteArray(),
            N = 32768, r = 8, p = 1, dkLen = 32
        )
        val expected = "c8bd4f0495664cfee04d145f4798e072fee5aed5e9f36b80f0e1aefcbed2f51f"
        assertEquals(expected, result.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun scryptIsDeterministic() {
        val a = FirefoxVpnAuth.scrypt("x".toByteArray(), "y".toByteArray(), 1024, 8, 1, 32)
        val b = FirefoxVpnAuth.scrypt("x".toByteArray(), "y".toByteArray(), 1024, 8, 1, 32)
        assertEquals(a.joinToString("") { "%02x".format(it) }, b.joinToString("") { "%02x".format(it) })
        assertTrue(a.size == 32)
    }
}
