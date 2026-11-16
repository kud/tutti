package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteConfigure
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.atv.proto.remote.RemotePingRequest
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.SocketLikeTlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A phone that sleeps stops the clock its read timeout runs on, so a session the TV closed
 * meanwhile still reads as ready on waking. The remote judges silence on a clock that counts
 * sleep: here [now], which the tests move without moving virtual time, as a sleep does.
 */
class AtvRemoteStaleTest {
    private val client = ClientCertificates.generate("client")
    private val tv = ClientCertificates.generate("tv")
    private val pin = CertificatePin.of(tv.certificate)
    private var now = 1_000_000L

    private fun connector(conn: SocketLikeTlsConnection) = object : TlsConnector {
        override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?) = conn
    }

    private fun SocketLikeTlsConnection.tvSays(message: RemoteMessage) = peerWritesFrame(message.encode())

    private fun configure() = RemoteMessage(remote_configure = RemoteConfigure(code1 = 622))

    private fun ping(value: Int) = RemoteMessage(remote_ping_request = RemotePingRequest(val1 = value))

    @Test
    fun `a key after a long sleep is refused and drops the session instead of vanishing`() = runTest {
        val conn = SocketLikeTlsConnection(client.certificate, tv.certificate)
        val remote = AtvRemote(connector(conn), backgroundScope, clock = { now })
        remote.connect("192.168.1.20", client, pin)
        conn.tvSays(configure())
        runCurrent()
        assertTrue(remote.state.value.ready)
        val written = conn.sent.size

        now += 10 * 60_000L // the phone slept ten minutes; virtual time, the read timeout's, did not move
        assertTrue(remote.isStale())

        assertFalse(remote.sendKey(V2Key.HOME), "a key into a stale session reported success")
        assertEquals(written, conn.sent.size, "the key was written into the dead socket")
        assertFalse(remote.state.value.ready, "the stale session still reads ready")
        assertTrue(conn.closed, "the stale socket was left open")
    }

    @Test
    fun `the TV's pings keep a session fresh across any length of use`() = runTest {
        val conn = SocketLikeTlsConnection(client.certificate, tv.certificate)
        val remote = AtvRemote(connector(conn), backgroundScope, clock = { now })
        remote.connect("192.168.1.20", client, pin)
        conn.tvSays(configure())
        runCurrent()
        repeat(20) { i ->
            now += 5_000L
            conn.tvSays(ping(i))
            runCurrent()
        }
        now += AtvRemote.STALE_MS - 1
        assertFalse(remote.isStale())
        assertFalse(remote.dropIfStale())
        assertTrue(remote.sendKey(V2Key.DPAD_CENTER))
        assertTrue(remote.state.value.ready)
    }

    @Test
    fun `coming back drops a silent session, and nothing when there is none`() = runTest {
        val conn = SocketLikeTlsConnection(client.certificate, tv.certificate)
        val remote = AtvRemote(connector(conn), backgroundScope, clock = { now })
        assertFalse(remote.dropIfStale(), "no session, nothing to drop")

        remote.connect("192.168.1.20", client, pin)
        conn.tvSays(configure())
        runCurrent()
        now += AtvRemote.STALE_MS
        assertTrue(remote.dropIfStale())
        assertFalse(remote.state.value.ready)
        assertTrue(conn.closed)
        assertFalse(remote.dropIfStale(), "dropped once is enough")
    }
}
