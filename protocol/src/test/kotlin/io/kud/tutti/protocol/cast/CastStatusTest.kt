package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.proto.CastMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invented titles only: "Sample Title", "Sample Channel". */
class CastStatusTest {
    private fun json(text: String) = CastStatusParser.parse(text)!!

    private val receiver = json(
        """{"type":"RECEIVER_STATUS","requestId":1,"status":{"applications":[
          {"appId":"BACKDROP","displayName":"Backdrop","transportId":"t-0","namespaces":[{"name":"urn:x-cast:com.google.cast.debugoverlay"}]},
          {"appId":"APP1","displayName":"Sample App","transportId":"t-1","sessionId":"s-1","namespaces":[{"name":"urn:x-cast:com.google.cast.media"}]}
        ],"volume":{"level":0.5}}}""",
    )

    private fun media(state: String, time: Double, withMedia: Boolean = true, duration: String = "600.0") = json(
        """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playbackRate":1,"playerState":"$state","currentTime":$time
          ${if (withMedia) ""","media":{"duration":$duration,"metadata":{"metadataType":0,"title":"Sample Title","artist":"Sample Channel"}}""" else ""}}]}""",
    )

    @Test fun `the app in front is the first one speaking the media namespace`() {
        assertEquals(CastApp("APP1", "t-1", "s-1"), CastStatusParser.mediaApp(receiver))
        assertNull(CastStatusParser.mediaApp(json("""{"type":"RECEIVER_STATUS","status":{"applications":[]}}""")))
        assertNull(CastStatusParser.mediaApp(json("""{"type":"RECEIVER_STATUS"}""")))
    }

    @Test fun `a media status gives title, secondary, state and progress`() {
        val now = CastStatusReducer().onMediaStatus(media("PLAYING", 60.0), 1_000)!!
        assertEquals("Sample Title", now.title)
        assertEquals("Sample Channel", now.secondary)
        assertEquals(CastPlayerState.PLAYING, now.state)
        assertEquals(70.0, now.positionAt(11_000))
        assertEquals(0.1f, now.progressAt(1_000))
        assertEquals(600.0, now.positionAt(10_000_000))
    }

    @Test fun `a paused position does not run`() {
        val now = CastStatusReducer().onMediaStatus(media("PAUSED", 60.0), 1_000)!!
        assertEquals(60.0, now.positionAt(50_000))
    }

    @Test fun `a later status without media keeps the title and duration of the same session`() {
        val reducer = CastStatusReducer()
        reducer.onMediaStatus(media("PLAYING", 1.0), 0)
        val later = reducer.onMediaStatus(media("PAUSED", 30.0, withMedia = false), 0)!!
        assertEquals("Sample Title", later.title)
        assertEquals(600.0, later.durationSec)
        assertEquals(CastPlayerState.PAUSED, later.state)
    }

    @Test fun `live content has no duration and no progress`() {
        val now = CastStatusReducer().onMediaStatus(media("PLAYING", 5.0, duration = "null"), 0)!!
        assertNull(now.durationSec)
        assertNull(now.progressAt(0))
    }

    @Test fun `idle, no title or an empty status shows nothing`() {
        assertNull(CastStatusReducer().onMediaStatus(media("IDLE", 0.0), 0))
        assertNull(CastStatusReducer().onMediaStatus(media("PLAYING", 0.0, withMedia = false), 0))
        assertNull(CastStatusReducer().onMediaStatus(json("""{"type":"MEDIA_STATUS","status":[]}"""), 0))
        assertNull(CastStatusParser.parse("not json"))
    }

    @Test fun `toString never prints viewing data`() {
        val text = CastStatusReducer().onMediaStatus(media("PLAYING", 1.0), 0).toString()
        assertFalse("Sample" in text)
        assertTrue("<redacted>" in text)
    }

    @Test fun `frames survive split and merged reads, and oversize lengths are refused`() {
        val a = CastFraming.encode(CastFraming.text("a", "b", CastNamespaces.RECEIVER, """{"type":"PING"}"""))
        val b = CastFraming.encode(CastFraming.binary("a", "b", CastDeviceAuth.NAMESPACE, byteArrayOf(1, 2, 3)))
        val all = a + b
        val decoder = CastFrameDecoder()
        val out = mutableListOf<CastMessage>()
        out += decoder.feed(all.copyOfRange(0, 3))
        out += decoder.feed(all.copyOfRange(3, a.size + 5))
        out += decoder.feed(all.copyOfRange(a.size + 5, all.size))
        assertEquals(listOf(CastMessage.PayloadType.STRING, CastMessage.PayloadType.BINARY), out.map { it.payload_type })
        assertFailsWith<CastFrameException> { CastFrameDecoder().feed(byteArrayOf(0x7f, 0, 0, 0)) }
    }
}
