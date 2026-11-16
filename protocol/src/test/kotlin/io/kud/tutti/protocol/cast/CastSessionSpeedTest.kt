package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.CastFixtures.NOW
import io.kud.tutti.protocol.cast.proto.CastMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals

class CastSessionSpeedTest {
    private class FakeCast : CastConnection {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = mutableListOf<CastMessage>()
        override val peerCertificate: X509Certificate = CastFixtures.tls
        override val incoming: Flow<ByteArray> = inbox.consumeAsFlow()
        override suspend fun send(bytes: ByteArray) { sent += CastFrameDecoder().feed(bytes) }
        override fun close() { inbox.close() }
        fun deliver(m: CastMessage) { inbox.trySend(CastFraming.encode(m)) }
        fun media(json: String) = deliver(CastFraming.text("t-1", CastSession.SENDER, CastNamespaces.MEDIA, json))
    }

    private fun TestScope.playing(tv: FakeCast, seen: MutableList<CastNowPlaying?>): CastSession {
        val session = CastSession(tv, { seen += it.nowPlaying }, clock = { NOW }, nonce = CastFixtures.nonce(), roots = listOf(CastFixtures.root))
        backgroundScope.async { session.run() }
        runCurrent()
        tv.deliver(CastFraming.binary(CastSession.RECEIVER, CastSession.SENDER, CastDeviceAuth.NAMESPACE, CastFixtures.reply()))
        tv.deliver(CastFraming.text(CastSession.RECEIVER, CastSession.SENDER, CastNamespaces.RECEIVER,
            """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"APP1","transportId":"t-1","namespaces":[{"name":"${CastNamespaces.MEDIA}"}]}]}}"""))
        tv.media("""{"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":9,"playerState":"PLAYING","currentTime":3,"playbackRate":1,"supportedMediaCommands":12303,"media":{"duration":90,"metadata":{"title":"Sample"}}}]}""")
        runCurrent()
        return session
    }

    @Test fun `a picked rate goes to the app's transport naming its media session, and its status settles it`() = runTest {
        val tv = FakeCast()
        val seen = mutableListOf<CastNowPlaying?>()
        val session = playing(tv, seen)
        assertEquals("APP1", seen.last()?.appId)
        assertEquals(CastRateSupport.ADVERTISED, seen.last()?.rateSupport)

        val answer = async { session.setPlaybackRate(1.5) }
        runCurrent()
        val request = tv.sent.last()
        assertEquals("t-1", request.destination_id)
        assertEquals(CastNamespaces.MEDIA, request.namespace)
        val id = Regex("\"requestId\":(\\d+)").find(request.payload_utf8!!)!!.groupValues[1]
        assertEquals(CastSpeed.request(9, id.toInt(), 1.5), request.payload_utf8)

        tv.media("""{"type":"MEDIA_STATUS","requestId":$id,"status":[{"mediaSessionId":9,"playerState":"PLAYING","currentTime":4,"playbackRate":1.5}]}""")
        runCurrent()
        assertEquals(CastSpeedResult.Accepted, answer.await())
        assertEquals(1.5, seen.last()?.playbackRate)
    }

    @Test fun `an error reply refuses, and silence is no answer`() = runTest {
        val tv = FakeCast()
        val session = playing(tv, mutableListOf())
        val refused = async { session.setPlaybackRate(2.0) }
        runCurrent()
        val id = Regex("\"requestId\":(\\d+)").find(tv.sent.last().payload_utf8!!)!!.groupValues[1]
        tv.media("""{"type":"INVALID_REQUEST","requestId":$id,"reason":"INVALID_COMMAND"}""")
        runCurrent()
        assertEquals(CastSpeedResult.Refused("INVALID_REQUEST", "INVALID_COMMAND"), refused.await())

        val silent = async { session.setPlaybackRate(2.0) }
        advanceTimeBy(CastSpeed.ANSWER_MS + 1)
        runCurrent()
        assertEquals(CastSpeedResult.NoAnswer, silent.await())
    }

    @Test fun `with no media session there is nothing to send`() = runTest {
        val tv = FakeCast()
        val session = CastSession(tv, {}, clock = { NOW }, nonce = CastFixtures.nonce(), roots = listOf(CastFixtures.root))
        assertEquals(CastSpeedResult.NoSession, session.setPlaybackRate(1.5))
    }
}
