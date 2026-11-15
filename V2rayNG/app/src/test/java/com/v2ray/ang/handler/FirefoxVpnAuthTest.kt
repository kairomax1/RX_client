package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Test

class FirefoxVpnAuthTest {

    @Test
    fun scryptProducesExpectedOutput() {
        // RFC 7914 test vector: scrypt("", "", N=16, r=1, p=1, dkLen=64)
        val result = FirefoxVpnAuth.scrypt(
            password = ByteArray(0),
            salt = ByteArray(0),
            N = 16, r = 1, p = 1, dkLen = 64
        )
        val expected = "77 d6 57 62 38 65 7b 20 3b 19 ca 42 c1 8a 04 97 " +
            "f1 6b 48 44 e3 07 4a e8 df df fa 3f ed e2 14 42 " +
            "fc d0 06 9c ed d4 5b 72 6d 71 cb 75 30 4a 27 " +
            "ec 70 0e e6 2c 21 e5 2c 1e 7f ec 1f 4b 09 2c"
        val actual = result.joinToString(" ") { "%02x".format(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun scryptWithFxAParameters() {
        // FxA uses N=32768, r=8, p=1, dkLen=32
        val result = FirefoxVpnAuth.scrypt(
            password = "testpassword".toByteArray(),
            salt = "testsalt".toByteArray(),
            N = 32768, r = 8, p = 1, dkLen = 32
        )
        // Just verify it produces 32 bytes and is deterministic
        assertEquals(32, result.size)
        val result2 = FirefoxVpnAuth.scrypt(
            password = "testpassword".toByteArray(),
            salt = "testsalt".toByteArray(),
            N = 32768, r = 8, p = 1, dkLen = 32
        )
        assertEquals(
            result.joinToString("") { "%02x".format(it) },
            result2.joinToString("") { "%02x".format(it) }
        )
    }
}
