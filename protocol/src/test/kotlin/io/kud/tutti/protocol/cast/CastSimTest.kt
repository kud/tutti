package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.sim.FakeCastTv
import io.kud.tutti.protocol.sim.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-only Cast session over a real TLS socket against the simulated receiver: framing on
 * a live stream, device auth bound to the certificate of this very handshake, then the app in
 * front and its media. The PKI is [CastFixtures]', so [CastSession] is given its test root;
 * against the bundled Google roots the same device is refused.
 */
class CastSimTest {
    private val identity = ClientIdentity(CastFixtures.tls, CastFixtures.tlsKeys.private)
    private val tv = FakeCastTv(identity) { nonce -> CastFixtures.reply(signedNonce = nonce) }.apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() {
        scope.cancel()
        tv.close()
    }

    /**
     * A plain JVM client for the test. Trust is pinned to the simulator's certificate here only to
     * keep the test from trusting anything; the product defers that decision to device auth.
     */
    private class SocketCast(private val socket: SSLSocket) : CastConnection {
        override val peerCertificate: X509Certificate = socket.session.peerCertificates.first() as X509Certificate
        override val incoming: Flow<ByteArray> = flow {
            val input = socket.inputStream
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                emit(buffer.copyOf(read))
            }
        }.flowOn(Dispatchers.IO)

        override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
            synchronized(socket) {
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
            }
        }

        override fun close() = socket.close()
    }

    private fun connect(port: Int = tv.port): SocketCast {
        val expected = CastFixtures.tls
        val onlyTheSimulator = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = throw CertificateException("client side only")

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (chain.firstOrNull() != expected) throw CertificateException("not the simulated receiver")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(onlyTheSimulator), SecureRandom()) }
        val socket = context.socketFactory.createSocket(InetAddress.getLoopbackAddress(), port) as SSLSocket
        socket.startHandshake()
        return SocketCast(socket)
    }

    private fun session(
        connection: CastConnection,
        seen: MutableList<CastNowPlaying?>,
        roots: List<X509Certificate> = listOf(CastFixtures.root),
        nonce: ByteArray = ByteArray(CastDeviceAuth.NONCE_BYTES).also { SecureRandom().nextBytes(it) },
    ) = CastSession(connection, { seen += it.nowPlaying }, clock = { CastFixtures.NOW }, nonce = nonce, roots = roots, heartbeatMillis = 200, refreshMillis = 400)

    @Test
    fun `verifies the receiver, follows the app in front and reports its media over a live socket`() = runBlocking {
        val seen = CopyOnWriteArrayList<CastNowPlaying?>()
        val connection = connect()
        val run = scope.async { session(connection, seen).run() }
        assertTrue(eventually { seen.lastOrNull()?.title == "Sample Title" }, "no title reported: $seen")
        val playing = seen.last()!!
        assertEquals("Sample Channel", playing.secondary)
        assertEquals(CastPlayerState.PLAYING, playing.state)
        assertEquals(90.0, playing.durationSec)
        assertEquals("deviceauth", tv.received.first())
        assertTrue(tv.received.any { it == "${CastNamespaces.MEDIA} GET_STATUS" })

        tv.ping()
        assertTrue(eventually { tv.received.count { it == "${CastNamespaces.HEARTBEAT} PONG" } >= 1 }, "the receiver's ping went unanswered")
        assertTrue(eventually { tv.received.count { it == "${CastNamespaces.HEARTBEAT} PING" } >= 1 }, "no heartbeat of our own")
        assertTrue(eventually { tv.received.count { it == "${CastNamespaces.MEDIA} GET_STATUS" } >= 2 }, "media status never re-read")

        tv.play("""{"mediaSessionId":1,"playerState":"PAUSED","currentTime":40,"media":{"duration":90,"metadata":{"title":"Sample Title"}}}""")
        assertTrue(eventually { seen.lastOrNull()?.state == CastPlayerState.PAUSED })

        tv.closeApp()
        assertTrue(eventually { seen.lastOrNull() == null }, "the card still shows a closed app")
        assertTrue(tv.received.none { it.startsWith(CastNamespaces.MEDIA) && !it.endsWith("GET_STATUS") }, "a media command was sent: ${tv.received}")

        tv.dropConnections()
        assertEquals(CastEnd.Closed::class, withTimeout(3_000) { run.await() }::class)
        assertNull(seen.last())
    }

    @Test
    fun `an app that reports no media shows nothing`() = runBlocking {
        tv.media = null
        val seen = CopyOnWriteArrayList<CastNowPlaying?>()
        val connection = connect()
        val run = scope.async { session(connection, seen).run() }
        assertTrue(eventually { tv.received.any { it == "${CastNamespaces.MEDIA} GET_STATUS" } })
        Thread.sleep(200)
        assertTrue(seen.all { it == null }, "$seen")
        connection.close()
        withTimeout(3_000) { run.await() }
        Unit
    }

    @Test
    fun `against the bundled Google roots the simulated receiver is refused and asked nothing more`() = runBlocking {
        val seen = CopyOnWriteArrayList<CastNowPlaying?>()
        val end = withTimeout(3_000) { session(connect(), seen, roots = CastRoots.all).run() }
        assertEquals(CastEnd.Untrusted(CastAuthFailure.UNTRUSTED_CHAIN), end)
        assertEquals(listOf("deviceauth"), tv.received.toList())
        assertTrue(seen.all { it == null })
    }

    @Test
    fun `a reply signed over another TLS certificate is refused, as a man in the middle's would be`() = runBlocking {
        tv.close()
        val forged = FakeCastTv(identity) { nonce -> CastFixtures.reply(signedNonce = nonce, peer = CastFixtures.device) }.apply { start() }
        try {
            val end = withTimeout(3_000) { session(connect(forged.port), mutableListOf()).run() }
            assertEquals(CastEnd.Untrusted(CastAuthFailure.BAD_SIGNATURE), end)
            assertEquals(listOf("deviceauth"), forged.received.toList())
        } finally {
            forged.close()
        }
    }

    /** A socket as the JVM's: once closed, a blocked read and any write throw instead of ending quietly. */
    private class ThrowingWhenClosed : CastConnection {
        private val inbox = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        @Volatile var closed = false
        override val peerCertificate: X509Certificate = CastFixtures.tls
        override val incoming: Flow<ByteArray> = flow { for (chunk in inbox) emit(chunk) }
        override suspend fun send(bytes: ByteArray) {
            if (closed) throw java.net.SocketException("Socket is closed")
        }
        override fun close() {
            closed = true
            inbox.close(java.net.SocketException("Socket closed"))
        }
        fun deliver(message: io.kud.tutti.protocol.cast.proto.CastMessage) {
            inbox.trySend(CastFraming.encode(message))
        }
    }

    @Test
    fun `a refused device is reported as refused even when closing the socket fails the read`() = runBlocking {
        val tv = ThrowingWhenClosed()
        val run = scope.async { session(tv, mutableListOf(), roots = CastRoots.all, nonce = CastFixtures.nonce()).run() }
        tv.deliver(CastFraming.binary(CastSession.RECEIVER, CastSession.SENDER, CastDeviceAuth.NAMESPACE, CastFixtures.reply()))
        assertEquals(CastEnd.Untrusted(CastAuthFailure.UNTRUSTED_CHAIN), withTimeout(3_000) { run.await() })
    }

    @Test
    fun `a heartbeat on a socket that just closed ends the session as closed instead of throwing`() = runBlocking {
        val tv = ThrowingWhenClosed()
        val seen = CopyOnWriteArrayList<CastNowPlaying?>()
        val run = scope.async { session(tv, seen, nonce = CastFixtures.nonce()).run() }
        tv.deliver(CastFraming.binary(CastSession.RECEIVER, CastSession.SENDER, CastDeviceAuth.NAMESPACE, CastFixtures.reply()))
        Thread.sleep(100)
        // Closed between two heartbeats (200 ms apart here): the next one fails to write.
        tv.closed = true
        val end = withTimeout(3_000) { runCatching { run.await() } }
        assertTrue(end.isSuccess, "run() threw ${end.exceptionOrNull()}")
        assertTrue(end.getOrNull() is CastEnd.Closed)
        assertNull(seen.lastOrNull())
    }
}
