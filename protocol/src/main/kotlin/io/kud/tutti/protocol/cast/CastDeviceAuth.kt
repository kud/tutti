package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.proto.AuthChallenge
import io.kud.tutti.protocol.cast.proto.DeviceAuthMessage
import io.kud.tutti.protocol.cast.proto.HashAlgorithm
import io.kud.tutti.protocol.cast.proto.SignatureAlgorithm
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Why a Cast device was not trusted. A class, never a value from the device: safe to log. */
enum class CastAuthFailure {
    /** The reply was not a DeviceAuthMessage with a response. */
    MALFORMED,
    /** The device answered with an AuthError. */
    DEVICE_ERROR,
    /** The TLS certificate is not yet valid, expired, or lives longer than [CastDeviceAuth.MAX_PEER_LIFETIME_MS]. */
    PEER_CERT_DATES,
    /** The signed nonce is not the one we sent. */
    NONCE_MISMATCH,
    /** RSASSA-PSS or an unknown hash: not supported. */
    UNSUPPORTED_ALGORITHM,
    /** The device certificate does not chain to a bundled Cast root. */
    UNTRUSTED_CHAIN,
    /** The signature over nonce + TLS certificate does not verify with the device key. */
    BAD_SIGNATURE,
}

sealed interface CastAuthResult {
    data object Verified : CastAuthResult
    data class Failed(val reason: CastAuthFailure) : CastAuthResult
}

/**
 * Cast device authentication, as Chromium and Open Screen do it (cast_auth_util.cc,
 * cast_cert_validator.cc). The TLS certificate of a Cast device is self-signed and rotates
 * within days, so it is not pinned. Instead the device proves it holds a key certified by
 * Google: it signs our random nonce followed by the DER of the very TLS certificate it
 * presented, with a device key whose certificate chains to a bundled [CastRoots] anchor.
 * A man in the middle has its own TLS certificate, so it cannot reuse a real device's
 * signature, and cannot sign without a Google-certified key.
 *
 * CRLs are not checked (Chromium treats them as optional as well). Pure: no I/O, the clock is passed in.
 */
object CastDeviceAuth {
    const val NAMESPACE = "urn:x-cast:com.google.cast.tp.deviceauth"
    const val NONCE_BYTES = 16
    const val MAX_PEER_LIFETIME_MS = 4L * 24 * 60 * 60 * 1000
    private const val MAX_CHAIN = 5

    /** The challenge payload: SHA-256 and PKCS#1 v1.5 asked, with our nonce. */
    fun challenge(nonce: ByteArray): ByteArray = DeviceAuthMessage(
        challenge = AuthChallenge(
            signature_algorithm = SignatureAlgorithm.RSASSA_PKCS1v15,
            sender_nonce = nonce.toByteString(),
            hash_algorithm = HashAlgorithm.SHA256,
        ),
    ).encode()

    fun verify(
        reply: ByteArray,
        peerCertificate: X509Certificate,
        nonce: ByteArray,
        nowMillis: Long,
        roots: List<X509Certificate> = CastRoots.all,
    ): CastAuthResult {
        val message = runCatching { DeviceAuthMessage.ADAPTER.decode(reply) }.getOrNull()
            ?: return fail(CastAuthFailure.MALFORMED)
        if (message.error != null) return fail(CastAuthFailure.DEVICE_ERROR)
        val response = message.response ?: return fail(CastAuthFailure.MALFORMED)

        val notBefore = peerCertificate.notBefore.time
        val notAfter = peerCertificate.notAfter.time
        if (nowMillis < notBefore || nowMillis > notAfter || notAfter > nowMillis + MAX_PEER_LIFETIME_MS) {
            return fail(CastAuthFailure.PEER_CERT_DATES)
        }

        val signedNonce = response.sender_nonce?.toByteArray()
        if (signedNonce != null && !signedNonce.contentEquals(nonce)) return fail(CastAuthFailure.NONCE_MISMATCH)

        if ((response.signature_algorithm ?: SignatureAlgorithm.RSASSA_PKCS1v15) != SignatureAlgorithm.RSASSA_PKCS1v15) {
            return fail(CastAuthFailure.UNSUPPORTED_ALGORITHM)
        }
        val algorithm = when (response.hash_algorithm ?: HashAlgorithm.SHA1) {
            HashAlgorithm.SHA1 -> "SHA1withRSA"
            HashAlgorithm.SHA256 -> "SHA256withRSA"
        }

        val leaf = parse(response.client_auth_certificate.toByteArray()) ?: return fail(CastAuthFailure.MALFORMED)
        val intermediates = response.intermediate_certificate.map { parse(it.toByteArray()) ?: return fail(CastAuthFailure.MALFORMED) }
        if (!chains(leaf, intermediates, roots, nowMillis)) return fail(CastAuthFailure.UNTRUSTED_CHAIN)
        // keyUsage, when present, must allow digitalSignature (index 0).
        leaf.keyUsage?.let { if (it.isEmpty() || !it[0]) return fail(CastAuthFailure.UNTRUSTED_CHAIN) }

        val signed = (signedNonce ?: ByteArray(0)) + peerCertificate.encoded
        val ok = runCatching {
            Signature.getInstance(algorithm).run {
                initVerify(leaf.publicKey)
                update(signed)
                verify(response.signature.toByteArray())
            }
        }.getOrDefault(false)
        return if (ok) CastAuthResult.Verified else fail(CastAuthFailure.BAD_SIGNATURE)
    }

    /**
     * Walks leaf → intermediates → a root by issuer name, checking each signature, each
     * validity window at [now], and that every issuer is a CA. Hand-written rather than
     * PKIX so that the JVM tests and Android's provider behave the same.
     */
    internal fun chains(leaf: X509Certificate, intermediates: List<X509Certificate>, roots: List<X509Certificate>, now: Long): Boolean {
        var current = leaf
        if (!validAt(current, now)) return false
        repeat(MAX_CHAIN) {
            roots.firstOrNull { it.subjectX500Principal == current.issuerX500Principal && signedBy(current, it) }
                ?.let { root -> return validAt(root, now) }
            val next = intermediates.firstOrNull {
                it !== current && it.subjectX500Principal == current.issuerX500Principal && signedBy(current, it)
            } ?: return false
            if (next.basicConstraints < 0 || !validAt(next, now)) return false
            current = next
        }
        return false
    }

    private fun signedBy(cert: X509Certificate, issuer: X509Certificate): Boolean =
        runCatching { cert.verify(issuer.publicKey); true }.getOrDefault(false)

    private fun validAt(cert: X509Certificate, now: Long): Boolean =
        now >= cert.notBefore.time && now <= cert.notAfter.time

    private fun parse(der: ByteArray): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }.getOrNull()

    private fun fail(reason: CastAuthFailure) = CastAuthResult.Failed(reason)
}
