package io.kud.tutti.protocol.atv

import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientCertificatesTest {
    @Test
    fun `generates a self-signed RSA 2048 certificate`() {
        val identity = ClientCertificates.generate("tutti test")
        val cert = identity.certificate
        val key = cert.publicKey as RSAPublicKey
        assertEquals(2048, key.modulus.bitLength())
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.verify(cert.publicKey)
        cert.checkValidity()
        assertTrue(cert.notAfter.time - cert.notBefore.time > 20L * 365 * 24 * 3600 * 1000)
    }
}
