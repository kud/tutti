package io.kud.tutti.protocol.atv

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture computed independently (Python hashlib) over the same byte layout:
 * moduli as minimal big-endian bytes, exponent 65537 as 01 00 01, then the last two code bytes.
 */
class PairingHashTest {
    private val clientModulus = BigInteger("c0ffee".repeat(10), 16) // top bit set: toByteArray() adds a 0x00 to strip
    private val serverModulus = BigInteger("0badcafe".repeat(8), 16)
    private val exponent = BigInteger.valueOf(65537)

    @Test
    fun `secret matches the fixture`() {
        val secret = PairingHash.compute(clientModulus, exponent, serverModulus, exponent, "7ba1b2")
        assertNotNull(secret)
        assertContentEquals(hex("7b659b1038e7e31d17bc0a8a40a1899a65bceee9df3b0eaf34293c1503667ab5"), secret)
    }

    @Test
    fun `code is case-insensitive`() {
        assertNotNull(PairingHash.compute(clientModulus, exponent, serverModulus, exponent, "7BA1B2"))
    }

    @Test
    fun `a mistyped first byte is caught locally`() {
        assertNull(PairingHash.compute(clientModulus, exponent, serverModulus, exponent, "7ca1b2"))
    }

    @Test
    fun `code shape is validated`() {
        assertTrue(PairingHash.isWellFormed("0a1B2c"))
        assertFalse(PairingHash.isWellFormed("12345"))
        assertFalse(PairingHash.isWellFormed("12345g"))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
