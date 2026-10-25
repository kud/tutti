package io.kud.tutti.ui.remote

import io.kud.tutti.R
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import io.kud.tutti.quick.notification.castPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented titles only. */
class CastLineTest {
    private fun cast(state: CastPlayerState, position: Double? = 723.0, duration: Double? = 2470.0) =
        CastNowPlaying("An invented film", null, state, position, duration, 1.0, readAtMillis = 1_000_000)

    private val words: (Int, Array<Any>) -> String = { id, args ->
        when (id) {
            R.string.cast_spoken_paused_at -> "${args[0]}, ${args[1]} of ${args[2]} minutes, paused"
            R.string.cast_spoken_paused -> "${args[0]}, paused"
            R.string.cast_spoken_live -> "${args[0]}, live"
            R.string.cast_spoken_loading -> "${args[0]}, loading"
            else -> error("unexpected string")
        }
    }

    @Test
    fun `time reads minutes and seconds, hours from an hour`() {
        assertEquals("12:03 / 41:10", castTime(cast(CastPlayerState.PAUSED), 9_999_999))
        assertEquals("1:02:03", clockOf(3723.9))
        assertEquals("0:00", clockOf(-3.0))
        assertNull(castTime(cast(CastPlayerState.PLAYING, duration = null), 1_000_000))
    }

    @Test
    fun `the time runs on while playing`() {
        assertEquals("12:13 / 41:10", castTime(cast(CastPlayerState.PLAYING), 1_010_000))
    }

    @Test
    fun `talkback hears the time when paused, never the tick while playing`() {
        assertEquals("An invented film, 12 of 41 minutes, paused", castSpoken(cast(CastPlayerState.PAUSED), 1_000_000, words))
        val a = castSpoken(cast(CastPlayerState.PLAYING), 1_000_000, words)
        val b = castSpoken(cast(CastPlayerState.PLAYING), 1_061_000, words)
        assertEquals("An invented film", a)
        assertEquals(a, b)
        assertEquals("An invented film, live", castSpoken(cast(CastPlayerState.PLAYING, duration = null), 0, words))
        assertEquals("An invented film, loading", castSpoken(cast(CastPlayerState.BUFFERING), 0, words))
    }

    @Test
    fun `cast shows on the tv's own screen only`() {
        assertTrue(castFits(CardShowing.Home))
        assertFalse(castFits(CardShowing.Standby(barOn = false)))
    }

    @Test
    fun `the session gets position, speed and an elapsed-clock update time`() {
        val p = castPlayback(cast(CastPlayerState.PLAYING), nowWallMs = 1_004_000, nowElapsedMs = 50_000)
        assertEquals(android.media.session.PlaybackState.STATE_PLAYING, p.state)
        assertEquals(723_000L, p.positionMs)
        assertEquals(1f, p.speed)
        assertEquals(46_000L, p.updateTime)
        assertEquals(2_470_000L, p.durationMs)
        val paused = castPlayback(cast(CastPlayerState.PAUSED, duration = null), 1_000_000, 0)
        assertEquals(android.media.session.PlaybackState.STATE_PAUSED, paused.state)
        assertEquals(0f, paused.speed)
        assertNull(paused.durationMs)
    }
}
