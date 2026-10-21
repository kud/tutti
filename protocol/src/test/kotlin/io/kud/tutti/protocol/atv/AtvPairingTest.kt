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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** An in-memory TV that answers each pairing step; the secret it receives is recorded. */
private class FakeTv(private val client: ClientIdentity, private val server: ClientIdentity) : TlsConnector {
    var receivedSecret: ByteArray? = null

    override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?): TlsConnection {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val decoder = FrameDecoder()
        val ok = PairingMessage.Status.STATUS_OK
        return object : TlsConnection {
            override val localCertificate: X509Certificate = client.certificate
            override val peerCertificate: X509Certificate = server.certificate
            override val incoming: Flow<ByteArray> = inbox.consumeAsFlow()

            override suspend fun send(bytes: ByteArray) {
                for (frame in decoder.feed(bytes)) {
                    val message = PairingMessage.ADAPTER.decode(frame)
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
                    reply?.let { inbox.send(Framing.frame(it.encode())) }
                }
            }

            override fun close() {
                inbox.close()
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
    fun `cancelling at the code prompt fails cleanly`() = runTest {
        val result = AtvPairing(FakeTv(client, server)).pair("192.168.1.10", client) { null }
        assertEquals(PairingResult.Failed(PairingFailure.Cancelled), result)
    }
}
