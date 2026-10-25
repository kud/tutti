package io.kud.tutti.protocol.net

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** SHA-256 of a certificate's DER encoding, lowercase hex. Stored per device for TOFU. */
data class CertificatePin(val sha256Hex: String) {
    fun matches(certificate: X509Certificate): Boolean = of(certificate) == this

    override fun toString(): String = "sha256/${sha256Hex.take(12)}…"

    companion object {
        fun of(certificate: X509Certificate): CertificatePin = ofDer(certificate.encoded)

        fun ofDer(der: ByteArray): CertificatePin {
            val digest = MessageDigest.getInstance("SHA-256").digest(der)
            return CertificatePin(digest.joinToString("") { "%02x".format(it) })
        }
    }
}

/**
 * A transport's report that a pinned device presented another certificate. An `IOException`
 * so it travels as a network failure, but callers tell it apart: it means "re-pair".
 */
class CertificatePinMismatch(cause: Throwable? = null) : java.io.IOException("certificate does not match the recorded pin", cause)
