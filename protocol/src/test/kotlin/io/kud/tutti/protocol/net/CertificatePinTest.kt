package io.kud.tutti.protocol.net

import io.kud.tutti.protocol.atv.ClientCertificates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePinTest {
    @Test
    fun `public key pin survives certificate reissue while certificate pin does not`() {
        val first = ClientCertificates.generate("first")
        val reissued = ClientCertificates.selfSign(
            first.certificate.publicKey,
            first.privateKey,
            "reissued",
        )

        assertFalse(CertificatePin.of(first.certificate).matches(reissued))
        assertTrue(PublicKeyPin.of(first.certificate).matches(reissued))
    }

    @Test
    fun `public key pin refuses another key`() {
        val first = ClientCertificates.generate("first")
        val other = ClientCertificates.generate("other")

        assertFalse(PublicKeyPin.of(first.certificate).matches(other.certificate))
    }
}
