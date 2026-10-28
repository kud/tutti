package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.CastFixtures.NOW
import io.kud.tutti.protocol.cast.proto.CastMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastSessionTest {
    private class FakeCast : CastConnection {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = mutableListOf<CastMessage>()
        override val peerCertificate: X509Certificate = CastFixtures.tls
        override val incoming: Flow<ByteArray> = inbox.consumeAsFlow()
        override suspend fun send(bytes: ByteArray) { sent += CastFrameDecoder().feed(bytes) }
        override fun close() { inbox.close() }
        fun deliver(m: CastMessage) { inbox.trySend(CastFraming.encode(m)) }
    }

    private fun fromTv(source: String, namespace: String, json: String) = CastFraming.text(source, CastSession.SENDER, namespace, json)

    @Test fun `verifies, then follows the app in front, and reports its media`() = runTest {
        val tv = FakeCast()
        val seen = mutableListOf<CastNowPlaying?>()
        val session = CastSession(tv, { seen += it.nowPlaying }, clock = { NOW }, nonce = CastFixtures.nonce(), roots = listOf(CastFixtures.root))
        val run = async { session.run() }
        runCurrent()
        assertEquals(listOf(CastDeviceAuth.NAMESPACE), tv.sent.map { it.namespace })

        tv.deliver(CastFraming.binary(CastSession.RECEIVER, CastSession.SENDER, CastDeviceAuth.NAMESPACE, CastFixtures.reply()))
        runCurrent()
        assertEquals(listOf(CastNamespaces.CONNECTION, CastNamespaces.RECEIVER), tv.sent.drop(1).map { it.namespace })

        tv.deliver(fromTv(CastSession.RECEIVER, CastNamespaces.HEARTBEAT, """{"type":"PING"}"""))
        tv.deliver(fromTv(CastSession.RECEIVER, CastNamespaces.RECEIVER,
            """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"APP1","transportId":"t-1","namespaces":[{"name":"${CastNamespaces.MEDIA}"}]}]}}"""))
        runCurrent()
        val after = tv.sent.drop(3)
        assertEquals("""{"type":"PONG"}""", after[0].payload_utf8)
        assertEquals(listOf("t-1", "t-1"), after.drop(1).map { it.destination_id })
        assertEquals(listOf(CastNamespaces.CONNECTION, CastNamespaces.MEDIA), after.drop(1).map { it.namespace })

        tv.deliver(fromTv("t-1", CastNamespaces.MEDIA,
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":1,"playerState":"PLAYING","currentTime":3,"media":{"duration":90,"metadata":{"title":"Sample Title"}}}]}"""))
        runCurrent()
        assertEquals("Sample Title", seen.last()?.title)

        tv.close()
        assertEquals(CastEnd.Closed("eof"), run.await())
        assertNull(seen.last())
        run.cancel()
    }

    @Test fun `the media status is re-read on the refresh tick and every reading reaches the diagnostic`() = runTest {
        val tv = FakeCast()
        val seen = mutableListOf<CastNowPlaying?>()
        val readings = mutableListOf<CastReading>()
        val session = CastSession(
            tv, { seen += it.nowPlaying }, clock = { NOW + testScheduler.currentTime }, nonce = CastFixtures.nonce(), roots = listOf(CastFixtures.root),
            onReading = { readings += it },
        )
        val run = async { session.run() }
        runCurrent()
        tv.deliver(CastFraming.binary(CastSession.RECEIVER, CastSession.SENDER, CastDeviceAuth.NAMESPACE, CastFixtures.reply()))
        tv.deliver(fromTv(CastSession.RECEIVER, CastNamespaces.RECEIVER,
            """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"APP1","transportId":"t-1","namespaces":[{"name":"${CastNamespaces.MEDIA}"}]}]}}"""))
        runCurrent()
        fun mediaGets() = tv.sent.count { it.namespace == CastNamespaces.MEDIA && it.payload_utf8?.contains("GET_STATUS") == true }
        assertEquals(1, mediaGets())
        tv.deliver(fromTv("t-1", CastNamespaces.MEDIA,
            """{"type":"MEDIA_STATUS","requestId":3,"status":[{"mediaSessionId":1,"playerState":"PLAYING","currentTime":100,"media":{"duration":2589,"metadata":{"title":"Sample Title"}}}]}"""))
        runCurrent()

        testScheduler.advanceTimeBy(CastSession.REFRESH_MS + 1)
        runCurrent()
        assertEquals(2, mediaGets(), "the media status was not re-read")
        tv.deliver(fromTv("t-1", CastNamespaces.MEDIA,
            """{"type":"MEDIA_STATUS","requestId":5,"status":[{"mediaSessionId":1,"playerState":"PLAYING","currentTime":115}]}"""))
        runCurrent()
        assertEquals(listOf(100.0, 115.0), readings.map { it.currentTime })
        assertEquals(listOf(true, true), readings.map { it.answer })
        val latest = seen.last()!!
        assertEquals(2589.0, latest.durationSec)
        assertEquals(125.0, latest.positionAt(latest.readAtMillis + 10_000))

        tv.close()
        run.await()
        run.cancel()
    }

    @Test fun `an unverified device gets nothing but the challenge, and its status is ignored`() = runTest {
        val tv = FakeCast()
        val seen = mutableListOf<CastNowPlaying?>()
        val session = CastSession(tv, { seen += it.nowPlaying }, clock = { NOW }, nonce = CastFixtures.nonce())
        val run = async { session.run() }
        runCurrent()
        tv.deliver(fromTv("t-1", CastNamespaces.MEDIA,
            """{"type":"MEDIA_STATUS","status":[{"playerState":"PLAYING","media":{"metadata":{"title":"Sample Title"}}}]}"""))
        // Signed by the test PKI, which the bundled Google roots do not trust.
        tv.deliver(CastFraming.binary(CastSession.RECEIVER, CastSession.SENDER, CastDeviceAuth.NAMESPACE, CastFixtures.reply()))
        assertEquals(CastEnd.Untrusted(CastAuthFailure.UNTRUSTED_CHAIN), run.await())
        assertEquals(1, tv.sent.size)
        assertEquals(listOf<CastNowPlaying?>(null), seen)
    }

    @Test fun `silence is unknown, another app is a stop, and an empty status is asked again on the next heartbeat`() = runTest {
        val tv = FakeCast()
        val reports = mutableListOf<CastReport>()
        val session = CastSession(tv, { reports += it }, clock = { NOW + testScheduler.currentTime }, nonce = CastFixtures.nonce(), roots = listOf(CastFixtures.root))
        val run = async { session.run() }
        runCurrent()
        tv.deliver(CastFraming.binary(CastSession.RECEIVER, CastSession.SENDER, CastDeviceAuth.NAMESPACE, CastFixtures.reply()))
        fun app(id: String, transport: String) =
            """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"$id","transportId":"$transport","namespaces":[{"name":"${CastNamespaces.MEDIA}"}]}]}}"""
        tv.deliver(fromTv(CastSession.RECEIVER, CastNamespaces.RECEIVER, app("APP1", "t-1")))
        runCurrent()
        assertEquals(listOf<CastReport>(CastReport.Unknown), reports)
        fun mediaGets() = tv.sent.count { it.namespace == CastNamespaces.MEDIA && it.payload_utf8?.contains("GET_STATUS") == true }
        tv.deliver(fromTv("t-1", CastNamespaces.MEDIA,
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":1,"playerState":"PLAYING","currentTime":3,"media":{"duration":90,"metadata":{"title":"Sample Title"}}}]}"""))
        tv.deliver(fromTv("t-1", CastNamespaces.MEDIA, """{"type":"MEDIA_STATUS","status":[]}"""))
        runCurrent()
        assertEquals(CastReport.Unknown, reports.last())
        val before = mediaGets()
        testScheduler.advanceTimeBy(CastSession.HEARTBEAT_MS + 1)
        runCurrent()
        assertEquals(before + 1, mediaGets(), "an empty status waits for the refresh instead of the next heartbeat")

        // The same app on a new transport says nothing; another app is a confirmed end.
        tv.deliver(fromTv(CastSession.RECEIVER, CastNamespaces.RECEIVER, app("APP1", "t-2")))
        runCurrent()
        assertEquals(CastReport.Unknown, reports.last())
        tv.deliver(fromTv(CastSession.RECEIVER, CastNamespaces.RECEIVER, app("APP2", "t-3")))
        runCurrent()
        assertEquals(CastReport.Stopped, reports.last())

        tv.close()
        run.await()
        assertEquals(CastReport.Unknown, reports.last(), "the end of a session is silence, not a stop")
        run.cancel()
    }
}
