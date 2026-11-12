package io.kud.tutti.protocol.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastSayAgainTest {
    private fun json(text: String) = CastStatusParser.parse(text)!!
    private val now = 50_000L

    private fun item(seek: CastRateSupport = CastRateSupport.ADVERTISED, state: CastPlayerState = CastPlayerState.PLAYING, appId: String? = "APP") =
        CastNowPlaying("Title", null, state, 120.0, 2_400.0, 1.0, now, seekSupport = seek, appId = appId)

    @Test fun `the seek and edit-tracks flags decide`() {
        assertEquals(CastRateSupport.UNKNOWN, CastSayAgain.seekSupport(null))
        assertEquals(CastRateSupport.ADVERTISED, CastSayAgain.seekSupport(1L or 2 or 4))
        assertEquals(CastRateSupport.NOT_ADVERTISED, CastSayAgain.seekSupport(1L or 4))
        assertEquals(CastRateSupport.ADVERTISED, CastSayAgain.tracksSupport(4096L))
    }

    @Test fun `the reducer carries the seek flag`() {
        val np = CastStatusReducer().onMediaStatus(
            json("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":4,"playerState":"PLAYING","currentTime":3,"supportedMediaCommands":1,"media":{"duration":90,"metadata":{"title":"Sample"}}}]}"""),
            0L,
        )
        assertEquals(CastRateSupport.NOT_ADVERTISED, np?.seekSupport)
    }

    @Test fun `offered unless the app says no, refused once, or has no time`() {
        assertTrue(CastSayAgain.offered(item(), emptySet(), now))
        assertTrue(CastSayAgain.offered(item(CastRateSupport.UNKNOWN), emptySet(), now))
        assertFalse(CastSayAgain.offered(item(CastRateSupport.NOT_ADVERTISED), emptySet(), now))
        assertFalse(CastSayAgain.offered(item(), setOf("APP"), now))
        assertFalse(CastSayAgain.offered(item(state = CastPlayerState.IDLE), emptySet(), now))
        assertFalse(CastSayAgain.offered(null, emptySet(), now))
    }

    @Test fun `seconds fall back to ten and the target never goes before the start`() {
        assertEquals(10, CastSayAgain.seconds(null))
        assertEquals(10, CastSayAgain.seconds(12))
        assertEquals(30, CastSayAgain.seconds(30))
        assertEquals(110.0, CastSayAgain.target(120.0, 10))
        assertEquals(0.0, CastSayAgain.target(4.0, 10))
    }

    @Test fun `the help lasts thirty seconds or the replayed span`() {
        assertEquals(30_000L, CastSayAgain.holdMs(10, 1.0))
        assertEquals(63_000L, CastSayAgain.holdMs(30, 0.5))
        assertEquals(30_000L, CastSayAgain.holdMs(30, 0.0))
    }

    @Test fun `requests name the session and use a dot in every locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("""{"type":"SEEK","requestId":3,"mediaSessionId":9,"currentTime":110.500}""", CastSayAgain.seekRequest(9, 3, 110.5))
        } finally {
            java.util.Locale.setDefault(default)
        }
        assertEquals("""{"type":"EDIT_TRACKS_INFO","requestId":4,"mediaSessionId":9,"activeTrackIds":[1,3]}""", CastSayAgain.tracksRequest(9, 4, listOf(1, 3)))
        assertEquals("""{"type":"EDIT_TRACKS_INFO","requestId":5,"mediaSessionId":9,"activeTrackIds":[]}""", CastSayAgain.tracksRequest(9, 5, emptyList()))
    }

    @Test fun `replies settle by request id`() {
        assertEquals(CastSpeedResult.Accepted, CastSayAgain.classify(json("""{"type":"MEDIA_STATUS","requestId":3,"status":[]}"""), 3))
        assertEquals(CastSpeedResult.Refused("INVALID_REQUEST", "INVALID_COMMAND"), CastSayAgain.classify(json("""{"type":"INVALID_REQUEST","requestId":3,"reason":"INVALID_COMMAND"}"""), 3))
        assertNull(CastSayAgain.classify(json("""{"type":"MEDIA_STATUS","requestId":2,"status":[]}"""), 3))
    }

    @Test fun `text tracks are read when listed and kept when media is left out`() {
        val full = CastSayAgain.tracks(
            json("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":1,"activeTrackIds":[1],"media":{"tracks":[{"trackId":1,"type":"AUDIO"},{"trackId":2,"type":"TEXT","language":"en"},{"trackId":3,"type":"TEXT","language":"fr-FR"}]}}]}"""),
            CastTracks(),
        )
        assertEquals(listOf(CastTextTrack(2, "en"), CastTextTrack(3, "fr-FR")), full.text)
        assertEquals(listOf(1L), full.active)
        assertTrue(full.known)
        assertFalse(full.captionsOn)
        val partial = CastSayAgain.tracks(json("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":1,"activeTrackIds":[1,3]}]}"""), full)
        assertEquals(full.text, partial.text)
        assertTrue(partial.captionsOn)
        val none = CastSayAgain.tracks(json("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":1}]}"""), partial)
        assertEquals(emptyList(), none.active)
    }

    @Test fun `captions pick the phone's language and keep the audio track`() {
        val tracks = CastTracks(listOf(CastTextTrack(2, "en"), CastTextTrack(3, "fr-FR")), listOf(1), known = true)
        assertEquals(listOf(1L, 3L), CastSayAgain.captionsOn(tracks, "fr"))
        assertEquals(listOf(1L, 2L), CastSayAgain.captionsOn(tracks, "de"))
        assertNull(CastSayAgain.captionsOn(tracks.copy(active = listOf(1, 2)), "fr"), "already on")
        assertNull(CastSayAgain.captionsOn(CastTracks(), "fr"), "nothing listed")
    }

    @Test fun `a tap turns on what was off and remembers exactly that`() {
        val tracks = CastTracks(listOf(CastTextTrack(2, "en")), listOf(1), known = true)
        val plan = CastSayAgain.plan(120.0, 15, 1.0, tracks, voiceOn = false, language = "en", holding = null)
        assertEquals(105.0, plan.seekTo)
        assertEquals(listOf(1L, 2L), plan.captions)
        assertTrue(plan.voice)
        assertEquals(CastSayAgain.Restore(listOf(1), listOf(1, 2), false), plan.restore)
        // Restored only while they still read what we set.
        assertEquals(listOf(1L), CastSayAgain.restoreTracks(plan.restore, tracks.copy(active = listOf(2, 1))))
        assertNull(CastSayAgain.restoreTracks(plan.restore, tracks.copy(active = listOf(1))), "he turned them off himself")
        assertEquals(false, CastSayAgain.restoreVoice(plan.restore, true))
        assertNull(CastSayAgain.restoreVoice(plan.restore, false))
    }

    @Test fun `what was already on is left on, and a second tap keeps the first before`() {
        val on = CastTracks(listOf(CastTextTrack(2, "en")), listOf(1, 2), known = true)
        val plan = CastSayAgain.plan(120.0, 10, 1.0, on, voiceOn = true, language = "en", holding = null)
        assertNull(plan.captions)
        assertFalse(plan.voice)
        assertNull(CastSayAgain.restoreTracks(plan.restore, on))
        assertNull(CastSayAgain.restoreVoice(plan.restore, true))

        val off = CastTracks(listOf(CastTextTrack(2, "en")), listOf(1), known = true)
        val first = CastSayAgain.plan(120.0, 10, 1.0, off, voiceOn = false, language = "en", holding = null)
        val second = CastSayAgain.plan(115.0, 10, 1.0, on, voiceOn = true, language = "en", holding = first.restore)
        assertEquals(first.restore, second.restore)
    }

    @Test fun `voice unknown is left alone`() {
        val plan = CastSayAgain.plan(120.0, 10, 1.0, CastTracks(), voiceOn = null, language = null, holding = null)
        assertFalse(plan.voice)
        assertNull(CastSayAgain.restoreVoice(plan.restore, true))
    }

    @Test fun `the log line has no title or position`() {
        assertEquals(
            "say again 10s for APP (com.example): seek accepted; captions on; voice on",
            CastSayAgain.logLine("APP", "com.example", 10, CastSpeedResult.Accepted, "on", "on"),
        )
    }
}
