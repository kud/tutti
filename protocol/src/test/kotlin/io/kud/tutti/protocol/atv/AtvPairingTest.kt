package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.pairing.PairingConfigurationAck
import io.kud.tutti.protocol.atv.proto.pairing.PairingMessage
import io.kud.tutti.protocol.atv.proto.pairing.PairingOption
import io.kud.tutti.protocol.atv.proto.pairing.PairingRequestAck
import io.kud.tutti.protocol.atv.proto.pairing.PairingSecretAck
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An in-memory TV that answers each pairing step; the secret it receives is recorded. */
private class FakeTv(
    private val client: ClientIdentity,
    private val server: ClientIdentity,
    private val blockingRead: Boolean = false,
) : TlsConnector {
    var receivedSecret: ByteArray? = null
    var requestedClientName: String? = null

    override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?): TlsConnection {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        // Like a socket: a blocking read on its own thread that only a close can end. The TV keeps
        // the pairing socket open after its secret ack, so nothing else ever arrives.
        val wire = PipedInputStream(64 * 1024)
        val wireIn = PipedOutputStream(wire)
        val decoder = FrameDecoder()
        val ok = PairingMessage.Status.STATUS_OK
        return object : TlsConnection {
            override val localCertificate: X509Certificate = client.certificate
            override val peerCertificate: X509Certificate = server.certificate
            override val incoming: Flow<ByteArray> = if (blockingRead) {
                flow {
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = try { wire.read(buffer) } catch (e: IOException) { -1 }
                        if (read < 0) break
                        emit(buffer.copyOf(read))
                    }
                }.flowOn(Dispatchers.IO)
            } else {
                inbox.consumeAsFlow()
            }

            override suspend fun send(bytes: ByteArray) {
                for (frame in decoder.feed(bytes)) {
                    val message = PairingMessage.ADAPTER.decode(frame)
                    message.pairing_request?.let { requestedClientName = it.client_name }
                    val reply = when {
                        message.pairing_request != null -> PairingMessage(status = ok, pairing_request_ack = PairingRequestAck(server_name = "BRAVIA"))
                        message.pairing_option != null -> PairingMessage(status = ok, pairing_option = PairingOption())
                        message.pairing_configuration != null -> PairingMessage(status = ok, pairing_configuration_ack = PairingConfigurationAck())
                        message.pairing_secret != null -> {
                            receivedSecret = message.pairing_secret?.secret?.toByteArray()
                            PairingMessage(status = ok, pairing_secret_ack = PairingSecretAck())
                        }
                        else -> null
                    }
                    reply?.let {
                        val bytes = Framing.frame(it.encode())
                        if (blockingRead) {
                            wireIn.write(bytes)
                            wireIn.flush()
                        } else {
                            inbox.send(bytes)
                        }
                    }
                }
            }

            override fun close() {
                inbox.close()
                // A blocked pipe read ignores its own close; closing the writer ends it, as closing a socket would.
                wireIn.close()
            }
        }
    }
}

class AtvPairingTest {
    private val client = ClientCertificates.generate("client")
    private val server = ClientCertificates.generate("tv")

    /** The code the TV would show: any first byte that matches the hash of "…0000". */
    private fun displayedCode(): String {
        val c = client.certificate.publicKey as RSAPublicKey
        val s = server.certificate.publicKey as RSAPublicKey
        return (0..255).map { "%02x0000".format(it) }.first { PairingHash.compute(c, s, it) != null }
    }

    @Test
    fun `pairs, retries a mistyped code locally, and returns the server pin`() = runTest {
        val tv = FakeTv(client, server)
        val code = displayedCode()
        val wrong = "%02x0000".format((code.substring(0, 2).toInt(16) + 1) % 256)
        val attempts = mutableListOf<Int>()

        val result = AtvPairing(tv).pair("192.168.1.10", client) { attempt ->
            attempts += attempt
            if (attempt == 1) wrong else code
        }

        assertEquals(PairingResult.Paired(CertificatePin.of(server.certificate)), result)
        assertEquals(listOf(1, 2), attempts)
        assertEquals(32, tv.receivedSecret?.size)
    }

    @Test
    fun `the pairing request names the client as its certificate does`() = runTest {
        val named = ClientCertificates.generate(ClientNames.forInstall("Phone X", kotlin.random.Random(7)))
        val tv = FakeTv(named, server)
        AtvPairing(tv).pair("192.168.1.10", named) { null }
        assertEquals(ClientNames.of(named.certificate), tv.requestedClientName)
        assertTrue(tv.requestedClientName!!.startsWith("tutti (Phone X "))
    }

    @Test
    fun `an identity made before per-install names still pairs as tutti`() = runTest {
        val legacy = ClientCertificates.generate(ClientNames.LEGACY)
        val tv = FakeTv(legacy, server)
        AtvPairing(tv).pair("192.168.1.10", legacy) { null }
        assertEquals("tutti", tv.requestedClientName)
    }

    @Test
    fun `cancelling at the code prompt fails cleanly`() = runTest {
        val result = AtvPairing(FakeTv(client, server)).pair("192.168.1.10", client) { null }
        assertEquals(PairingResult.Failed(PairingFailure.Cancelled), result)
    }

    @Test
    fun `returns as soon as the TV acks the secret, though it keeps the socket open`() {
        val code = displayedCode()
        var result: PairingResult? = null
        val session = AtvPairing(FakeTv(client, server, blockingRead = true))
        val pairing = Thread {
            result = runBlocking { session.pair("192.168.1.10", client) { code } }
        }
        pairing.isDaemon = true
        pairing.start()
        pairing.join(5_000)
        assertTrue(!pairing.isAlive, "pair() still waiting on the open socket after the secret ack: ${session.trace}")
        assertEquals(PairingResult.Paired(CertificatePin.of(server.certificate)), result)
    }
}
