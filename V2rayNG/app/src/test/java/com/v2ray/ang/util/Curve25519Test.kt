package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Curve25519Test {

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun rfc7748ScalarMultiplicationVector() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")

        assertArrayEquals(
            hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            Curve25519.scalarMult(scalar, u),
        )
    }

    @Test
    fun rfc7748PublicKeysAndSharedSecret() {
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertArrayEquals(alicePublic, Curve25519.publicKey(alicePrivate))
        assertArrayEquals(bobPublic, Curve25519.publicKey(bobPrivate))
        assertArrayEquals(shared, Curve25519.scalarMult(alicePrivate, bobPublic))
        assertArrayEquals(shared, Curve25519.scalarMult(bobPrivate, alicePublic))
    }

    @Test
    fun generatedKeysAreClampedAndHaveAPublicKey() {
        val privateKey = Curve25519.generatePrivateKey()

        assertEquals(32, privateKey.size)
        assertEquals(0, privateKey[0].toInt() and 7)
        assertEquals(64, privateKey[31].toInt() and 0xC0)
        assertEquals(32, Curve25519.publicKey(privateKey).size)
    }
}
