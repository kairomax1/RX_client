package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

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

    /** The password stretch Firefox Accounts performs, checked against a reference implementation. */
    @Test
    fun deriveAuthPwMatchesFirefoxAccounts() {
        val authPw = FirefoxVpnAuth.deriveAuthPw("test@example.com", "password")
        assertEquals(
            "a624b128b0e54c3377fe16ca819c09d867827328bd8c740e620bffe3d1208475",
            FirefoxVpnAuth.toHex(authPw)
        )
    }

    /** A session token turns into the Hawk id and key the API expects. */
    @Test
    fun deriveHawkMatchesReference() {
        val token = "a1b2c3d4e5f6".repeat(10)
        val (id, key) = FirefoxVpnAuth.deriveHawk(token)
        assertEquals("0f721872645a2c87bac6d0629ca577c195397f8b8fc210fc47f7b0acc11d241a", id)
        assertEquals(
            "add141ab70931c450eebbadd9d33fdca0e438b7c8ac5dab81df896be66c134f0",
            FirefoxVpnAuth.toHex(key)
        )
    }

    /** The Hawk string covers the body hash and the request line, as the API checks it. */
    @Test
    fun hawkNormalizedAndPayloadHashMatchReference() {
        val payload = """{"email":"a@b.c","authPW":"00"}"""
        val contentType = "application/json; charset=utf-8"
        val payloadHash = FirefoxVpnAuth.hawkPayloadHash(payload, contentType)
        assertEquals(
            "e31a1d6163aa7376c5a2b3d0c687500128e6d70b479c3e4ea13d05d10ddda64c",
            payloadHash
        )

        val normalized = FirefoxVpnAuth.hawkNormalized(
            method = "POST",
            url = "https://api.accounts.firefox.com/v1/account/login",
            timestamp = 1_700_000_000L,
            nonce = "abcdef123456",
            payloadHash = payloadHash
        )
        assertEquals(
            "hawk.1.header\n1700000000\nabcdef123456\nPOST\n/v1/account/login" +
                "\napi.accounts.firefox.com\n443\n$payloadHash\n\n",
            normalized
        )
    }

    @Test
    fun anEmptyPayloadLeavesTheHashEmpty() {
        assertEquals("", FirefoxVpnAuth.hawkPayloadHash("", "application/json"))
    }

    @Test
    fun scryptIsDeterministic() {
        val a = FirefoxVpnAuth.scrypt("x".toByteArray(), "y".toByteArray(), 1024, 8, 1, 32)
        val b = FirefoxVpnAuth.scrypt("x".toByteArray(), "y".toByteArray(), 1024, 8, 1, 32)
        assertEquals(FirefoxVpnAuth.toHex(a), FirefoxVpnAuth.toHex(b))
        assertTrue(a.size == 32)
    }

    /** The proof of work the edge asks for: two characters whose hash is given. */
    @Test
    fun proofOfWorkIsSolved() {
        val base = "Zm9vYmFy"
        val answer = "aa"
        val target = MessageDigest.getInstance("SHA-256")
            .digest((base + answer).toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(answer, FirefoxVpnAuth.solvePow(base, target))
    }

    @Test
    fun aProofOfWorkWithoutAnAnswerIsReported() {
        val impossible = "00".repeat(32)
        assertNull(FirefoxVpnAuth.solvePow("base", impossible))
    }

    /** The script's own call names the tasks, the token and the prefix it belongs to. */
    @Test
    fun challengeInitIsReadFromTheScript() {
        val script = """var x = 1; init([{"ty":"pow","data":{"base":"abc"}},{"ty":"captcha"}], "TOKEN123", "/_fs-ch-9x"); more();"""
        val parsed = FirefoxVpnAuth.parseChallengeInit(script, "/_fs-ch-9x")
        assertNotNull(parsed)
        assertEquals(2, parsed!!.first.size())
        assertEquals("pow", parsed.first[0].asJsonObject.get("ty").asString)
        assertEquals("TOKEN123", parsed.second)
    }

    @Test
    fun aScriptForAnotherPrefixIsIgnored() {
        val script = """init([{"ty":"pow"}], "TOKEN", "/_fs-ch-aaaa")"""
        assertNull(FirefoxVpnAuth.parseChallengeInit(script, "/_fs-ch-bbbb"))
    }

    @Test
    fun onlyAPageWithTheChallengeMarksCounts() {
        assertTrue(FirefoxVpnAuth.isChallengePage("<html>Client Challenge<script src=\"/_fs-ch-1a/x\">"))
        assertTrue(!FirefoxVpnAuth.isChallengePage("<html>Firefox Accounts</html>"))
    }
}
