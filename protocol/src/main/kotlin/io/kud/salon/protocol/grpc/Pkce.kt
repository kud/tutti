package io.kud.salon.protocol.grpc

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth 2.0 PKCE (RFC 7636), S256. The sign-in runs in a Custom Tab, never a WebView. */
object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 43 characters from 32 random bytes. */
    fun verifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Random `state` parameter, checked on the redirect. */
    fun state(random: SecureRandom = SecureRandom()): String = verifier(random)
}
