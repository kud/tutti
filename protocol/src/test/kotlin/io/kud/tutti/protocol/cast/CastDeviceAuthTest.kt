package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.CastFixtures.NOW
import io.kud.tutti.protocol.cast.proto.DeviceAuthMessage
import io.kud.tutti.protocol.cast.proto.HashAlgorithm
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class CastDeviceAuthTest {
    private val roots = listOf(CastFixtures.root)

    private fun verify(reply: ByteArray, peer: java.security.cert.X509Certificate = CastFixtures.tls, nonce: ByteArray = CastFixtures.nonce(), now: Long = NOW) =
        CastDeviceAuth.verify(reply, peer, nonce, now, roots)

    @Test fun `a device signing our nonce and its own tls certificate is verified`() {
        assertEquals(CastAuthResult.Verified, verify(CastFixtures.reply()))
    }

    @Test fun `sha1 replies from older devices still verify`() {
        assertEquals(CastAuthResult.Verified, verify(CastFixtures.reply(hash = HashAlgorithm.SHA1)))
    }

    @Test fun `a signature over another tls certificate fails, so a man in the middle cannot replay it`() {
        val other = CastFixtures.cert("CN=Other", CastFixtures.rootKeys, "CN=Other", CastFixtures.rootKeys, false, NOW - 1000, NOW + 1000)
        assertEquals(CastAuthResult.Failed(CastAuthFailure.BAD_SIGNATURE), verify(CastFixtures.reply(peer = other)))
    }

    @Test fun `a signature by a key outside the chain fails`() {
        assertEquals(CastAuthResult.Failed(CastAuthFailure.BAD_SIGNATURE), verify(CastFixtures.reply(key = CastFixtures.tlsKeys)))
    }

    @Test fun `another nonce fails`() {
        assertEquals(CastAuthResult.Failed(CastAuthFailure.NONCE_MISMATCH), verify(CastFixtures.reply(), nonce = CastFixtures.nonce(seed = 99)))
    }

    @Test fun `a chain to an unknown root fails, and test chains never pass the bundled roots`() {
        assertEquals(CastAuthResult.Failed(CastAuthFailure.UNTRUSTED_CHAIN), CastDeviceAuth.verify(CastFixtures.reply(), CastFixtures.tls, CastFixtures.nonce(), NOW))
        assertEquals(CastAuthResult.Failed(CastAuthFailure.UNTRUSTED_CHAIN), verify(CastFixtures.reply(chain = listOf(CastFixtures.device))))
    }

    @Test fun `a self-signed device certificate is not a chain`() {
        val selfSigned = CastFixtures.cert("CN=Test Cast ICA", CastFixtures.deviceKeys, "CN=Test Device", CastFixtures.deviceKeys, false, NOW - 1000, NOW + 100_000)
        assertEquals(CastAuthResult.Failed(CastAuthFailure.UNTRUSTED_CHAIN), verify(CastFixtures.reply(chain = listOf(selfSigned, CastFixtures.intermediate))))
    }

    @Test fun `a tls certificate living longer than four days, or out of its dates, fails`() {
        val long = CastFixtures.cert("CN=Long", CastFixtures.tlsKeys, "CN=Long", CastFixtures.tlsKeys, false, NOW - 1000, NOW + 10L * 24 * 3600 * 1000)
        assertEquals(CastAuthResult.Failed(CastAuthFailure.PEER_CERT_DATES), verify(CastFixtures.reply(peer = long), peer = long))
        assertEquals(CastAuthResult.Failed(CastAuthFailure.PEER_CERT_DATES), verify(CastFixtures.reply(), now = NOW + 3L * 24 * 3600 * 1000))
    }

    @Test fun `garbage and device errors fail closed`() {
        assertEquals(CastAuthResult.Failed(CastAuthFailure.MALFORMED), verify(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(CastAuthResult.Failed(CastAuthFailure.MALFORMED), verify(DeviceAuthMessage().encode()))
    }

    @Test fun `the challenge asks for sha256 with our nonce`() {
        val sent = DeviceAuthMessage.ADAPTER.decode(CastDeviceAuth.challenge(CastFixtures.nonce()))
        assertEquals(HashAlgorithm.SHA256, sent.challenge?.hash_algorithm)
        assertEquals(CastFixtures.nonce().toList(), sent.challenge?.sender_nonce?.toByteArray()?.toList())
    }

    @Test fun `the bundled roots are google's cast and eureka roots`() {
        val fingerprints = CastRoots.all.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        }
        assertEquals(
            listOf(
                "80:9A:F1:47:00:B3:FE:26:11:AD:59:7E:B1:58:4D:63:54:31:3B:64:CC:DB:33:90:F0:97:FA:3E:58:26:D6:CA",
                "CA:F6:D1:E3:7B:53:22:03:E0:1A:76:BF:07:18:7B:B7:31:CC:D3:88:01:56:5A:B2:21:1A:2C:0A:B7:F3:BC:46",
            ),
            fingerprints,
        )
        assertEquals(listOf(true, true), CastRoots.all.map { it.basicConstraints >= 0 })
    }
}
