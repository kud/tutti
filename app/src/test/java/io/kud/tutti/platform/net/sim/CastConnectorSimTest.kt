package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.AndroidCastConnector
import io.kud.tutti.protocol.atv.ClientCertificates
import io.kud.tutti.protocol.cast.CastFrameDecoder
import io.kud.tutti.protocol.cast.CastFraming
import io.kud.tutti.protocol.cast.CastNamespaces
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.sim.FakeCastTv
import io.kud.tutti.protocol.sim.eventually
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date
import javax.net.ssl.SSLException

/**
 * The app's Cast connector over a real TLS socket against the simulated receiver: the handshake
 * records the receiver's own leaf (what device auth will be bound to) and the socket carries Cast
 * frames both ways. Device auth itself is CastSimTest's, in :protocol. Invented identities only.
 */
class CastConnectorSimTest {
    private var tv: FakeCastTv? = null

    @After
    fun stop() {
        tv?.close()
    }

    private fun receiver(identity: ClientIdentity) = FakeCastTv(identity) { ByteArray(0) }.apply { start() }.also { tv = it }

    private fun connector() = AndroidCastConnector(::loopbackOnly, 2_000)

    @Test
    fun `records the receiver's leaf and carries cast frames both ways`() = runBlocking {
        val identity = ClientCertificates.generate("simulated cast receiver")
        val sim = receiver(identity)
        val connection = connector().connect(HOST, sim.port)
        try {
            assertEquals(identity.certificate, connection.peerCertificate)
            connection.send(CastFraming.encode(CastFraming.text("sender-0", "receiver-0", CastNamespaces.RECEIVER, """{"type":"GET_STATUS","requestId":1}""")))
            val decoder = CastFrameDecoder()
            val reply = withTimeout(3_000) {
                connection.incoming.map { chunk -> decoder.feed(chunk) }.first { it.isNotEmpty() }.first()
            }
            assertEquals(CastNamespaces.RECEIVER, reply.namespace)
            assertTrue(reply.payload_utf8.orEmpty().contains("RECEIVER_STATUS"))
            assertTrue(eventually { sim.received.contains("${CastNamespaces.RECEIVER} GET_STATUS") })
        } finally {
            connection.close()
        }
    }

    @Test
    fun `an out-of-date receiver certificate ends the handshake`() = runBlocking {
        val keys = ClientCertificates.generate("expired cast receiver")
        // Valid for 30 years from 1980: long out of date.
        val expired = ClientCertificates.selfSign(keys.certificate.publicKey, keys.privateKey, "expired cast receiver", Date(315_532_800_000L))
        val sim = receiver(ClientIdentity(expired, keys.privateKey))
        try {
            connector().connect(HOST, sim.port).close()
            fail("an expired leaf was accepted")
        } catch (e: SSLException) {
            // Refused by PeerRecorder.
        }
    }

    @Test
    fun `refuses a host the lan check refuses, before dialling`() = runBlocking {
        val sim = receiver(ClientCertificates.generate("simulated cast receiver"))
        try {
            connector().connect("192.0.2.1", sim.port).close()
            fail("dialled a host the check refused")
        } catch (e: IllegalArgumentException) {
            assertEquals(0, sim.accepted.get())
        }
    }
}
