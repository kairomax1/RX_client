package com.v2ray.ang.util

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 (RFC 7748), used only to create a WireGuard key pair on the phone.
 *
 * Written on [BigInteger] because the platform offers the curve from Android 13 (API 33) only and
 * the app supports older versions. It is not constant time; that is acceptable here because the
 * single use is generating the user's own key locally, never a handshake with a remote party.
 * Removal condition: `minSdk` reaches 33, then `KeyPairGenerator.getInstance("XDH")` replaces it.
 */
object Curve25519 {
    const val KEY_SIZE = 32

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT = ByteArray(KEY_SIZE).also { it[0] = 9 }

    /** A fresh private key, already clamped the way WireGuard stores it. */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(KEY_SIZE)
        random.nextBytes(key)
        return clamp(key)
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE_POINT)

    /** The X25519 function: [scalar] times the point with u-coordinate [u], both 32 bytes. */
    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == KEY_SIZE && u.size == KEY_SIZE) { "X25519 needs 32 byte inputs" }

        val k = fromLittleEndian(clamp(scalar))
        val x1 = fromLittleEndian(u.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() }).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false

        for (t in 254 downTo 0) {
            val bit = k.testBit(t)
            if (swap != bit) {
                val x = x2; x2 = x3; x3 = x
                val z = z2; z2 = z3; z3 = z
            }
            swap = bit

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val sum = da.add(cb).mod(P)
            val difference = da.subtract(cb).mod(P)
            x3 = sum.multiply(sum).mod(P)
            z3 = x1.multiply(difference.multiply(difference).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap) {
            x2 = x3
            z2 = z3
        }

        val result = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
        return toLittleEndian(result)
    }

    private fun clamp(key: ByteArray): ByteArray = key.copyOf().also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = ((it[31].toInt() and 127) or 64).toByte()
    }

    private fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun toLittleEndian(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(KEY_SIZE)
        // toByteArray may carry a leading sign byte or be shorter than 32 bytes.
        var written = 0
        var index = bigEndian.size - 1
        while (index >= 0 && written < KEY_SIZE) {
            out[written++] = bigEndian[index--]
        }
        return out
    }
}
