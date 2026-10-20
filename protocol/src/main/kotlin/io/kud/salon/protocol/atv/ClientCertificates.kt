package io.kud.salon.protocol.atv

import io.kud.salon.protocol.net.ClientIdentity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * The TLS client certificate the TV binds the pairing to: RSA 2048, self-signed, long-lived.
 * Reconnects must present the exact same certificate, so it is generated once per install.
 *
 * On Android, prefer a key generated inside AndroidKeyStore (non-exportable) and pass its
 * handles to [selfSign]; [generate] is for tests and for devices where that fails.
 */
object ClientCertificates {
    const val KEY_SIZE = 2048

    fun generate(commonName: String, now: Date = Date()): ClientIdentity {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE, SecureRandom())
        val keyPair = generator.generateKeyPair()
        return ClientIdentity(selfSign(keyPair.public, keyPair.private, commonName, now), keyPair.private)
    }

    fun selfSign(publicKey: PublicKey, privateKey: PrivateKey, commonName: String, now: Date = Date()): X509Certificate {
        val name = X500Name("CN=${commonName.replace(Regex("[,=+<>#;\"\\\\]"), "")}")
        val notAfter = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = now
            add(Calendar.YEAR, 30)
        }.time
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val holder = JcaX509v3CertificateBuilder(name, serial, now, notAfter, name, publicKey)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(privateKey))
        return JcaX509CertificateConverter().getCertificate(holder)
    }
}
