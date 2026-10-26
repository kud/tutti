package io.kud.tutti.protocol.net

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** A persisted identity check for a TLS server certificate. */
interface ServerIdentityPin {
    fun matches(certificate: X509Certificate): Boolean
}

/** SHA-256 of a certificate's full DER encoding. Remote v2 keeps this exact pairing identity. */
data class CertificatePin(val sha256Hex: String) : ServerIdentityPin {
    override fun matches(certificate: X509Certificate): Boolean = of(certificate) == this

    override fun toString(): String = "cert-sha256/${sha256Hex.take(12)}…"

    companion object {
        fun of(certificate: X509Certificate): CertificatePin = ofDer(certificate.encoded)

        fun ofDer(der: ByteArray): CertificatePin = CertificatePin(sha256Hex(der))
    }
}

/**
 * SHA-256 of SubjectPublicKeyInfo (X.509 [X509Certificate.getPublicKey] encoding).
 *
 * BRAVIA REST uses this rather than the whole self-signed certificate: Sony may reissue the
 * certificate while retaining the same key. A new key still fails closed and requires explicit
 * pairing before tutti trusts it.
 */
data class PublicKeyPin(val sha256Hex: String) : ServerIdentityPin {
    override fun matches(certificate: X509Certificate): Boolean = of(certificate) == this

    override fun toString(): String = "spki-sha256/${sha256Hex.take(12)}…"

    companion object {
        fun of(certificate: X509Certificate): PublicKeyPin =
            PublicKeyPin(sha256Hex(certificate.publicKey.encoded))
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * A transport's report that a pinned device presented another trusted identity. An [java.io.IOException]
 * so it travels as a network failure, but callers tell it apart: it means "pair again".
 */
class CertificatePinMismatch(cause: Throwable? = null) :
    java.io.IOException("server identity does not match the recorded pin", cause)
