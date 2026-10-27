package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.SocketLikeTlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import javax.net.ssl.SSLProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Under TLS 1.3 the TV refuses a forgotten client certificate after the handshake: connect()
 * returns, and the alert ends the first read. The remote must keep that error for its caller,
 * or a forgotten pairing reads as a TV that never got ready.
 */
class AtvRemoteFailureTest {
    private val client = ClientCertificates.generate("client")
    private val tv = ClientCertificates.generate("tv")
    private val pin = CertificatePin.of(tv.certificate)
    private val alert = SSLProtocolException(
        "Read error: ssl=0x1: Failure in SSL library, usually a protocol error\n" +
            "error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN",
    )

    private fun connector(vararg conns: SocketLikeTlsConnection): TlsConnector {
        val queue = ArrayDeque(conns.toList())
        return object : TlsConnector {
            override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?) = queue.removeFirst()
        }
    }

    @Test
    fun `a certificate alert on the first read is kept as the session's failure`() = runTest {
        val conn = SocketLikeTlsConnection(client.certificate, tv.certificate)
        val remote = AtvRemote(connector(conn), backgroundScope)
        remote.connect("192.168.1.20", client, pin)
        runCurrent()
        assertNull(remote.failure, "an open session has no failure")

        conn.peerFails(alert)
        runCurrent()

        assertFalse(remote.state.value.ready)
        val failure = assertNotNull(remote.failure)
        assertTrue(failure is SSLProtocolException, "kept: $failure")
        assertEquals(alert.message, failure.message)
        assertTrue(conn.closed, "the failed socket was left open")
    }

    @Test
    fun `a clean end has no failure, and the next connect starts clear`() = runTest {
        val failed = SocketLikeTlsConnection(client.certificate, tv.certificate)
        val quiet = SocketLikeTlsConnection(client.certificate, tv.certificate)
        val remote = AtvRemote(connector(failed, quiet), backgroundScope)
        remote.connect("192.168.1.20", client, pin)
        failed.peerFails(alert)
        runCurrent()
        assertNotNull(remote.failure)

        remote.connect("192.168.1.20", client, pin)
        runCurrent()
        assertNull(remote.failure, "a new session inherited the last one's failure")

        quiet.peerHalfClose()
        runCurrent()
        assertNull(remote.failure, "EOF is not a failure")
    }
}
