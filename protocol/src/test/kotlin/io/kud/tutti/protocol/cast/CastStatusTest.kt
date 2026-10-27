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

    // A receiver that, playing, reports currentTime == duration frozen (YouTube on a BRAVIA, 2026-10-26).
    @Test fun `a frozen end while playing runs on from the last paused reading and the resume`() {
        val reducer = CastStatusReducer()
        assertEquals(341.0, reducer.onMediaStatus(media("PAUSED", 341.0, duration = "1500.0"), 0)!!.positionAt(0))
        val resumed = reducer.onMediaStatus(media("PLAYING", 1500.0, duration = "1500.0"), 10_000)!!
        assertTrue(resumed.inferred)
        assertEquals(341.0, resumed.positionAt(10_000))
        // Later statuses keep the frozen number; the time keeps counting from the resume.
        val later = reducer.onMediaStatus(media("PLAYING", 1500.0, withMedia = false), 25_000)!!
        assertEquals(356.0, later.positionSec)
        assertEquals(361.0, later.positionAt(30_000))
    }

    @Test fun `the inference never runs past the end and is dropped there`() {
        val reducer = CastStatusReducer()
        reducer.onMediaStatus(media("PAUSED", 590.0), 0)
        assertEquals(590.0, reducer.onMediaStatus(media("PLAYING", 600.0), 1_000)!!.positionSec)
        assertNull(reducer.onMediaStatus(media("PLAYING", 600.0, withMedia = false), 12_000)!!.positionAt(12_000))
        // Gone for good: even back short of the end, nothing to run on until a trusted reading.
        assertNull(reducer.onMediaStatus(media("PLAYING", 600.0, withMedia = false), 13_000)!!.positionAt(13_000))
    }

    @Test fun `buffering, a new item or no anchor means no inferred time`() {
        val reducer = CastStatusReducer()
        assertNull(reducer.onMediaStatus(media("PLAYING", 600.0), 0)!!.positionAt(0))
        reducer.onMediaStatus(media("PAUSED", 100.0), 0)
        reducer.onMediaStatus(media("BUFFERING", 600.0), 1_000)
        assertNull(reducer.onMediaStatus(media("PLAYING", 600.0), 2_000)!!.positionAt(2_000))
        reducer.onMediaStatus(media("PAUSED", 100.0), 3_000)
        assertNull(reducer.onMediaStatus(media("PLAYING", 900.0, duration = "900.0"), 4_000)!!.positionAt(4_000))
        reducer.onMediaStatus(media("PAUSED", 100.0), 5_000)
        reducer.reset()
        assertNull(reducer.onMediaStatus(media("PLAYING", 600.0), 6_000)!!.positionAt(6_000))
    }

    @Test fun `a trusted playing reading is shown as read, not inferred`() {
        val reducer = CastStatusReducer()
        reducer.onMediaStatus(media("PAUSED", 100.0), 0)
        val playing = reducer.onMediaStatus(media("PLAYING", 120.0), 5_000)!!
        assertFalse(playing.inferred)
        assertEquals(120.0, playing.positionSec)
        // Then frozen at the end: runs on from that trusted playing reading.
        assertEquals(130.0, reducer.onMediaStatus(media("PLAYING", 600.0, withMedia = false), 15_000)!!.positionSec)
    }

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
    }

    // The screenshot of 2026-10-26: "43:09 / 43:09" and a full bar while YouTube played. The old
    // positionAt clamped every overrun to the duration, so a reading run on too long, or a
    // currentTime at or past the duration, read as a confident end. Each case below did that.

    @Test fun `a playing reading never runs on to the end`() {
        val now = CastStatusReducer().onMediaStatus(media("PLAYING", 590.0), 0)!!
        assertEquals(599.0, now.positionAt(9_000))
        assertNull(now.positionAt(10_000), "claimed the end while playing")
        assertNull(now.progressAt(10_000))
    }

    @Test fun `a reading too old to run on shows no time`() {
        val now = CastStatusReducer().onMediaStatus(media("PLAYING", 60.0), 0)!!
        assertEquals(100.0, now.positionAt(CastNowPlaying.MAX_RUN_MS))
        assertNull(now.positionAt(CastNowPlaying.MAX_RUN_MS + 1))
        // The old maths, left alone 43 minutes, showed the duration.
        assertNull(now.positionAt(43 * 60_000L))
    }

    @Test fun `a receiver that reports currentTime at the duration while playing is not believed`() {
        val now = CastStatusReducer().onMediaStatus(media("PLAYING", 2589.0, duration = "2589.0"), 0)!!
        assertFalse(now.readingTrusted)
        assertNull(now.positionAt(0))
        assertNull(now.progressAt(0))
    }

    @Test fun `a currentTime in another unit (milliseconds) is not believed, playing or paused`() {
        assertNull(CastStatusReducer().onMediaStatus(media("PLAYING", 812_400.0, duration = "2589.0"), 0)!!.positionAt(0))
        assertNull(CastStatusReducer().onMediaStatus(media("PAUSED", 812_400.0, duration = "2589.0"), 0)!!.positionAt(0))
    }

    @Test fun `paused at the end may say so, and a paused reading never ages out`() {
        val end = CastStatusReducer().onMediaStatus(media("PAUSED", 600.4), 0)!!
        assertEquals(600.0, end.positionAt(10_000_000))
        assertEquals(1f, end.progressAt(0))
    }

    @Test fun `a new item in the same session takes its own duration`() {
        val reducer = CastStatusReducer()
        reducer.onMediaStatus(media("PLAYING", 10.0, duration = "2589.0"), 0)
        val next = reducer.onMediaStatus(media("PLAYING", 3.0, duration = "300.0"), 1_000)!!
        assertEquals(300.0, next.durationSec)
        assertEquals(3.0, next.positionAt(1_000))
    }

    @Test fun `each status leaves its raw numbers for the diagnostic, never a title`() {
        val reducer = CastStatusReducer()
        reducer.onMediaStatus(json("""{"type":"MEDIA_STATUS","requestId":7,"status":[{"mediaSessionId":3,"playbackRate":1,"playerState":"PLAYING",
          "currentTime":812.4,"media":{"duration":2589.0,"metadata":{"title":"Sample Title"}}}]}"""), 1_000)
        val first = reducer.lastReading!!
        assertEquals(CastReading(1_000, "PLAYING", 812.4, 2589.0, mediaIncluded = true, playbackRate = 1.0, answer = true, sameSession = true), first)
        reducer.onMediaStatus(json("""{"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":4,"playerState":"WEIRD","currentTime":5}]}"""), 16_000)
        val second = reducer.lastReading!!
        assertEquals(CastReading(16_000, "other", 5.0, null, mediaIncluded = false, playbackRate = null, answer = false, sameSession = false), second)

        val readings = CastReadings(capacity = 2)
        readings.record(first)
        readings.record(second)
        readings.record(second.copy(atMillis = 31_000))
        val report = readings.report(nowMillis = 41_000)
        assertEquals(listOf("cast reading 01", "cast reading 02"), report.map { it.first })
        assertEquals("age 25.0 s, first, other, currentTime 5.0, no media, rate none, unasked, new session", report[0].second)
        assertEquals("age 10.0 s, gap 15.0 s, other, currentTime 5.0, no media, rate none, unasked, new session", report[1].second)
        assertFalse(report.any { "Sample" in it.second })
        assertEquals(listOf("cast readings" to "none this session"), CastReadings().report(0))
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
