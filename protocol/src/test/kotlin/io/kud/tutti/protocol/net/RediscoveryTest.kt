package io.kud.tutti.protocol.net

import io.kud.tutti.protocol.atv.ClientCertificates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RediscoveryTest {
    private val tv = ClientCertificates.generate("tv")
    private val stranger = ClientCertificates.generate("another google tv")

    @Test
    fun `browses only after three failures in a row`() {
        assertFalse(Rediscovery.due(failures = 2, lastAt = null, now = 0))
        assertTrue(Rediscovery.due(failures = 3, lastAt = null, now = 0))
        assertTrue(Rediscovery.due(failures = 7, lastAt = null, now = 0))
    }

    @Test
    fun `browses at most once in ten minutes`() {
        val at = 1_000_000L
        assertFalse(Rediscovery.due(failures = 5, lastAt = at, now = at + 60_000))
        assertFalse(Rediscovery.due(failures = 5, lastAt = at, now = at + Rediscovery.COOLDOWN_MS - 1))
        assertTrue(Rediscovery.due(failures = 5, lastAt = at, now = at + Rediscovery.COOLDOWN_MS))
        // A clock that went back (a reboot of the monotonic clock) is not a reason to wait forever.
        assertTrue(Rediscovery.due(failures = 5, lastAt = at, now = at - 1))
    }

    @Test
    fun `candidates are private, new and listed once`() {
        val found = listOf("192.168.1.20", "192.168.1.31", " 192.168.1.31 ", "8.8.8.8", "192.168.1.40", "")
        assertEquals(
            listOf("192.168.1.31"),
            Rediscovery.candidates(found, current = "192.168.1.20", others = listOf("192.168.1.40")),
        )
    }

    @Test
    fun `the tv moves only when it presents the pinned remote certificate`() {
        val pin = CertificatePin.of(tv.certificate)
        assertTrue(Rediscovery.tvConfirmed(CertificatePin.of(tv.certificate), null, pin, null))
        assertFalse(Rediscovery.tvConfirmed(CertificatePin.of(stranger.certificate), null, pin, null))
        assertFalse(Rediscovery.tvConfirmed(null, null, pin, null))
    }

    @Test
    fun `or the pinned rest public key, reissued certificate included`() {
        val restPin = PublicKeyPin.of(tv.certificate)
        val reissued = ClientCertificates.selfSign(tv.certificate.publicKey, tv.privateKey, "reissued")
        assertTrue(Rediscovery.tvConfirmed(null, PublicKeyPin.of(reissued), null, restPin))
        assertFalse(Rediscovery.tvConfirmed(null, PublicKeyPin.of(stranger.certificate), null, restPin))
    }

    @Test
    fun `nothing pinned proves nothing, and a legacy rest pin is left to the remote pin`() {
        assertFalse(Rediscovery.tvConfirmed(CertificatePin.of(tv.certificate), PublicKeyPin.of(tv.certificate), null, null))
        val legacy = CertificatePin.of(tv.certificate)
        assertFalse(Rediscovery.tvConfirmed(null, PublicKeyPin.of(tv.certificate), null, legacy))
    }

    @Test
    fun `a bar is offered only when it names a model, the same one when it is known`() {
        assertEquals(BarAddressOffer("192.168.1.31", "HT-A9000"), Rediscovery.barOffer("192.168.1.31", "HT-A9000", null))
        assertEquals(BarAddressOffer("192.168.1.31", "HT-A9000"), Rediscovery.barOffer("192.168.1.31", " HT-A9000 ", "ht-a9000"))
        assertNull(Rediscovery.barOffer("192.168.1.31", "HT-A8000", "HT-A9000"))
        assertNull(Rediscovery.barOffer("192.168.1.31", null, null))
        assertNull(Rediscovery.barOffer("192.168.1.31", "  ", null))
    }
}
