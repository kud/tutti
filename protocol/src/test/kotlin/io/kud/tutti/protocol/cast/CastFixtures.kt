package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.proto.AuthResponse
import io.kud.tutti.protocol.cast.proto.DeviceAuthMessage
import io.kud.tutti.protocol.cast.proto.HashAlgorithm
import okio.ByteString.Companion.toByteString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date

/** An invented Cast PKI: a test root, an intermediate, a device key and a TLS certificate. Nothing real. */
object CastFixtures {
    const val NOW = 1_790_000_000_000L
    private const val DAY = 24L * 60 * 60 * 1000

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val rootKeys = keys()
    val intermediateKeys = keys()
    val deviceKeys = keys()
    val tlsKeys = keys()

    val root = cert("CN=Test Cast Root", rootKeys, "CN=Test Cast Root", rootKeys, ca = true, from = NOW - 400 * DAY, to = NOW + 4000 * DAY)
    val intermediate = cert("CN=Test Cast Root", rootKeys, "CN=Test Cast ICA", intermediateKeys, ca = true, from = NOW - 300 * DAY, to = NOW + 3000 * DAY)
    val device = cert("CN=Test Cast ICA", intermediateKeys, "CN=Test Device", deviceKeys, ca = false, from = NOW - 30 * DAY, to = NOW + 3000 * DAY)
    val tls = cert("CN=Test TLS", tlsKeys, "CN=Test TLS", tlsKeys, ca = false, from = NOW - DAY, to = NOW + 2 * DAY)

    fun cert(issuer: String, issuerKeys: KeyPair, subject: String, subjectKeys: KeyPair, ca: Boolean, from: Long, to: Long): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuer), BigInteger.valueOf(from / 1000), Date(from), Date(to), X500Name(subject), subjectKeys.public,
        )
        if (ca) {
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        } else {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    fun nonce(seed: Int = 7): ByteArray = ByteArray(CastDeviceAuth.NONCE_BYTES) { (it * 31 + seed).toByte() }

    /** The device's AuthResponse, signing [signedNonce] + [peer]'s DER with [key]. */
    fun reply(
        signedNonce: ByteArray? = nonce(),
        peer: X509Certificate = tls,
        key: KeyPair = deviceKeys,
        chain: List<X509Certificate> = listOf(device, intermediate),
        hash: HashAlgorithm = HashAlgorithm.SHA256,
    ): ByteArray {
        val algorithm = if (hash == HashAlgorithm.SHA256) "SHA256withRSA" else "SHA1withRSA"
        val signature = Signature.getInstance(algorithm).run {
            initSign(key.private)
            update((signedNonce ?: ByteArray(0)) + peer.encoded)
            sign()
        }
        return DeviceAuthMessage(
            response = AuthResponse(
                signature = signature.toByteString(),
                client_auth_certificate = chain.first().encoded.toByteString(),
                intermediate_certificate = chain.drop(1).map { it.encoded.toByteString() },
                sender_nonce = signedNonce?.toByteString(),
                hash_algorithm = hash,
            ),
        ).encode()
    }
}
