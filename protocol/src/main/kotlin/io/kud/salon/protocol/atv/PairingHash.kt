package io.kud.salon.protocol.atv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * The pairing secret. The TV shows a 6-hex-digit code; the client proves it saw it with
 *
 *     SHA-256(clientModulus ‖ clientExponent ‖ serverModulus ‖ serverExponent ‖ code[1..2])
 *
 * where each number is its unsigned big-endian bytes (no leading zero byte) and code[1..2]
 * are the bytes of the last four hex digits. The first code byte must equal the first hash
 * byte: that check catches a mistyped code before anything is sent.
 * Ported from kud/androidtv-remote (MIT), src/pairing/pairing-manager.ts.
 */
object PairingHash {
    const val CODE_LENGTH = 6

    fun isWellFormed(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** The secret to send, or null if [code] does not match these certificates (typo). */
    fun compute(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? =
        compute(client.modulus, client.publicExponent, server.modulus, server.publicExponent, code)

    fun compute(
        clientModulus: BigInteger,
        clientExponent: BigInteger,
        serverModulus: BigInteger,
        serverExponent: BigInteger,
        code: String,
    ): ByteArray? {
        require(isWellFormed(code)) { "pairing code must be $CODE_LENGTH hex digits" }
        val codeBytes = ByteArray(3) { i -> code.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(clientModulus.unsignedBytes())
        digest.update(clientExponent.unsignedBytes())
        digest.update(serverModulus.unsignedBytes())
        digest.update(serverExponent.unsignedBytes())
        digest.update(codeBytes, 1, 2)
        val hash = digest.digest()
        return if (hash[0] == codeBytes[0]) hash else null
    }

    internal fun BigInteger.unsignedBytes(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}
