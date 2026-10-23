package io.kud.tutti.protocol.net

import io.kud.tutti.protocol.atv.AtvRemote
import io.kud.tutti.protocol.atv.ClientCertificates
import io.kud.tutti.protocol.atv.proto.remote.RemoteConfigure
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.atv.proto.remote.RemotePingRequest
import io.kud.tutti.protocol.theatre.TheatreClient
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a read ends on its own (the peer half-closed, or went silent past the read timeout), the
 * client must close its end: the bar keeps one control socket per client, and a leaked one sits
 * open until the GC finds it.
 */
class ConnectionLifecycleTest {
    private val client = ClientCertificates.generate("client")
    private val tv = ClientCertificates.generate("tv")
    private val pin = CertificatePin.of(tv.certificate)

    private fun tvConnector(conn: SocketLikeTlsConnection) = object : TlsConnector {
        override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?) = conn
    }

    @Test
    fun `remote closes its socket when the TV half-closes`() = runTest {
        val conn = SocketLikeTlsConnection(client.certificate, tv.certificate)
        val remote = AtvRemote(tvConnector(conn), backgroundScope)
        remote.connect("192.168.1.20", client, pin)
        conn.peerWritesFrame(RemoteMessage(remote_configure = RemoteConfigure(code1 = 622)).encode())
        runCurrent()
        assertTrue(remote.state.value.ready)

        conn.peerHalfClose()
        runCurrent()

        assertFalse(remote.state.value.ready)
        assertTrue(conn.closed, "the remote socket stayed open after EOF")
    }

    @Test
    fun `a quiet TV that pings stays up, a silent one is dropped and closed`() = runTest {
        val conn = SocketLikeTlsConnection(client.certificate, tv.certificate, readTimeoutMillis = 20_000)
        val remote = AtvRemote(tvConnector(conn), backgroundScope)
        remote.connect("192.168.1.20", client, pin)
        conn.peerWritesFrame(RemoteMessage(remote_configure = RemoteConfigure(code1 = 622)).encode())
        runCurrent()
        // The TV pings every ~5 s: a minute of that is a live link.
        repeat(12) {
            advanceTimeBy(5_000)
            conn.peerWritesFrame(RemoteMessage(remote_ping_request = RemotePingRequest(val1 = it)).encode())
            runCurrent()
        }
        assertTrue(remote.state.value.ready)
        assertEquals(12 + 1, conn.sent.size, "configure answer + one pong per ping")

        advanceTimeBy(20_001)
        runCurrent()

        assertFalse(remote.state.value.ready)
        assertTrue(conn.closed, "the timed-out socket was left open")
    }

    @Test
    fun `bar client closes its socket when the bar half-closes`() = runTest {
        val conn = SocketLikeConnection(readTimeoutMillis = 75_000)
        val bar = TheatreClient(
            object : TcpConnector {
                override suspend fun connect(host: String, port: Int): Connection = conn
            },
            backgroundScope,
            clock = { testScheduler.currentTime },
        )
        bar.connect("192.168.1.30")
        runCurrent()
        assertTrue(bar.state.value.connected)

        conn.peerHalfClose()
        runCurrent()

        assertFalse(bar.state.value.connected)
        assertTrue(conn.closed, "the bar socket stayed open after EOF")
    }
}
