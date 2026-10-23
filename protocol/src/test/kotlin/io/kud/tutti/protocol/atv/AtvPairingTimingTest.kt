package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.pairing.PairingConfigurationAck
import io.kud.tutti.protocol.atv.proto.pairing.PairingMessage
import io.kud.tutti.protocol.atv.proto.pairing.PairingOption
import io.kud.tutti.protocol.atv.proto.pairing.PairingRequestAck
import io.kud.tutti.protocol.atv.proto.pairing.PairingSecretAck
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.SocketLikeTlsConnection
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pairing against a socket with the real connector's 20 s read timeout. The reader runs ahead
 * of the collector, so it keeps reading while the person types the code the TV shows.
 */
class AtvPairingTimingTest {
    private val client = ClientCertificates.generate("client")
    private val server = ClientCertificates.generate("tv")

    private fun displayedCode(): String {
        val c = client.certificate.publicKey as RSAPublicKey
        val s = server.certificate.publicKey as RSAPublicKey
        return (0..255).map { "%02x0000".format(it) }.first { PairingHash.compute(c, s, it) != null }
    }

    /** Answers each step like the TV, unless [silent]; keeps the socket open afterwards. */
    private inner class ScriptedTv(private val silent: Boolean = false) : TlsConnector {
        lateinit var conn: SocketLikeTlsConnection

        override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?): TlsConnection {
            val decoder = FrameDecoder()
            val ok = PairingMessage.Status.STATUS_OK
            conn = SocketLikeTlsConnection(client.certificate, server.certificate, readTimeoutMillis = 20_000)
            conn.onSend = { bytes ->
                for (frame in decoder.feed(bytes)) {
                    if (silent) continue
                    val message = PairingMessage.ADAPTER.decode(frame)
                    val reply = when {
                        message.pairing_request != null -> PairingMessage(status = ok, pairing_request_ack = PairingRequestAck(server_name = "BRAVIA"))
                        message.pairing_option != null -> PairingMessage(status = ok, pairing_option = PairingOption())
                        message.pairing_configuration != null -> PairingMessage(status = ok, pairing_configuration_ack = PairingConfigurationAck())
                        message.pairing_secret != null -> PairingMessage(status = ok, pairing_secret_ack = PairingSecretAck())
                        else -> null
                    }
                    reply?.let { conn.peerWritesFrame(it.encode()) }
                }
            }
            return conn
        }
    }

    @Test
    fun `a person who takes 45 s to type the code still pairs`() = runTest {
        val code = displayedCode()
        val tv = ScriptedTv()

        val result = AtvPairing(tv).pair("192.168.1.10", client) {
            delay(45_000)
            code
        }

        assertEquals(PairingResult.Paired(CertificatePin.of(server.certificate)), result)
        assertTrue(tv.conn.closed)
    }

    @Test
    fun `a TV that never answers fails well before the read timeout and closes the socket`() = runTest {
        val tv = ScriptedTv(silent = true)
        var asked = false

        val result = AtvPairing(tv).pair("192.168.1.10", client) { asked = true; null }

        assertIs<PairingResult.Failed>(result)
        assertTrue(!asked)
        assertTrue(testScheduler.currentTime <= AtvPairing.TV_ANSWER_TIMEOUT_MS + 1, "took ${testScheduler.currentTime} ms")
        assertTrue(tv.conn.closed)
    }
}
