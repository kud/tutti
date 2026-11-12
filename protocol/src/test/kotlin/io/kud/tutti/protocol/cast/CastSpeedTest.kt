package io.kud.tutti.protocol.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastSpeedTest {
    private fun json(text: String) = CastStatusParser.parse(text)!!

    @Test fun `the playback rate flag decides, and a missing mask is worth a try`() {
        assertEquals(CastRateSupport.UNKNOWN, CastSpeed.support(null))
        // ALL_BASIC_MEDIA on a Web Receiver: pause, seek, volume, mute, edit tracks, playback rate.
        assertEquals(CastRateSupport.ADVERTISED, CastSpeed.support(1L or 2 or 4 or 8 or 4096 or 8192))
        assertEquals(CastRateSupport.NOT_ADVERTISED, CastSpeed.support(1L or 2 or 4 or 8 or 4096))
        assertEquals(CastRateSupport.NOT_ADVERTISED, CastSpeed.support(0L))
    }

    @Test fun `the reducer carries the app's own mask onto what is playing`() {
        val reducer = CastStatusReducer()
        fun playing(mask: String) = reducer.onMediaStatus(
            json("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":4,"playerState":"PLAYING","currentTime":3$mask,"media":{"duration":90,"metadata":{"title":"Sample"}}}]}"""),
            0L,
        )
        assertEquals(CastRateSupport.ADVERTISED, playing(""","supportedMediaCommands":12303""")?.rateSupport)
        assertEquals(CastRateSupport.NOT_ADVERTISED, playing(""","supportedMediaCommands":15""")?.rateSupport)
        assertEquals(CastRateSupport.UNKNOWN, playing("")?.rateSupport)
    }

    @Test fun `the request names the media session and the rate, in any locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals(
                """{"type":"SET_PLAYBACK_RATE","requestId":7,"mediaSessionId":3,"playbackRate":1.25}""",
                CastSpeed.request(3, 7, 1.25),
            )
            assertEquals(listOf("0.5", "0.75", "1", "1.25", "1.5", "2"), CastSpeed.RATES.map(CastSpeed::format))
        } finally {
            java.util.Locale.setDefault(default)
        }
        assertEquals(3L, CastSpeed.mediaSessionId(json("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3}]}""")))
    }

    @Test fun `a reply is judged on its own request and on the rate it reports`() {
        val other = json("""{"type":"MEDIA_STATUS","requestId":6,"status":[{"playbackRate":1.5}]}""")
        assertNull(CastSpeed.classify(other, 7, 1.5))
        val unasked = json("""{"type":"MEDIA_STATUS","requestId":0,"status":[{"playbackRate":1.5}]}""")
        assertNull(CastSpeed.classify(unasked, 7, 1.5))

        assertEquals(CastSpeedResult.Accepted, CastSpeed.classify(json("""{"type":"MEDIA_STATUS","requestId":7,"status":[{"playbackRate":1.5}]}"""), 7, 1.5))
        assertEquals(CastSpeedResult.Ignored(1.0), CastSpeed.classify(json("""{"type":"MEDIA_STATUS","requestId":7,"status":[{"playbackRate":1}]}"""), 7, 1.5))
        assertEquals(CastSpeedResult.Ignored(null), CastSpeed.classify(json("""{"type":"MEDIA_STATUS","requestId":7,"status":[{}]}"""), 7, 2.0))
        assertEquals(CastSpeedResult.Accepted, CastSpeed.classify(json("""{"type":"MEDIA_STATUS","requestId":7,"status":[{}]}"""), 7, 1.0))
        assertEquals(
            CastSpeedResult.Refused("INVALID_REQUEST", "NOT_SUPPORTED"),
            CastSpeed.classify(json("""{"type":"INVALID_REQUEST","requestId":7,"reason":"NOT_SUPPORTED"}"""), 7, 2.0),
        )
        assertEquals(
            CastSpeedResult.Refused("INVALID_PLAYER_STATE", null),
            CastSpeed.classify(json("""{"type":"INVALID_PLAYER_STATE","requestId":7}"""), 7, 2.0),
        )
    }

    @Test fun `the picker ticks an offered rate only`() {
        assertEquals(1.5, CastSpeed.offered(1.5))
        assertEquals(1.0, CastSpeed.offered(1.004))
        assertNull(CastSpeed.offered(1.1))
    }

    @Test fun `the log line says app, package, rate and outcome, nothing else`() {
        assertEquals(
            "speed 1.5x for 233637DE (com.google.android.youtube.tv): refused (INVALID_REQUEST, NOT_SUPPORTED)",
            CastSpeed.logLine("233637DE", "com.google.android.youtube.tv", 1.5, CastSpeedResult.Refused("INVALID_REQUEST", "NOT_SUPPORTED")),
        )
        assertEquals("speed 2x for no app (package unknown): no media session", CastSpeed.logLine(null, null, 2.0, CastSpeedResult.NoSession))
    }
}
