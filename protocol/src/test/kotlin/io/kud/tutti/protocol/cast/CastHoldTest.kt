package io.kud.tutti.protocol.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invented titles and packages only. */
class CastHoldTest {
    private fun json(text: String) = CastStatusParser.parse(text)!!

    private fun item(title: String = "Sample Title", at: Long = 0) =
        CastNowPlaying(title, "Sample Channel", CastPlayerState.PLAYING, 100.0, 600.0, 1.0, at)

    private fun media(state: String, session: Int = 3, idleReason: String? = null, title: String? = "Sample Title") = json(
        """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":$session,"playbackRate":1,"playerState":"$state","currentTime":10
          ${if (idleReason != null) ""","idleReason":"$idleReason"""" else ""}
          ${if (title != null) ""","media":{"duration":600,"metadata":{"title":"$title"}}""" else ""}}]}""",
    )

    private val empty = json("""{"type":"MEDIA_STATUS","status":[]}""")

    @Test fun `an unknown report keeps the item for the grace window, then lets it go`() {
        val hold = CastHold()
        val shown = item()
        assertEquals(shown, hold.onReport(CastReport.Playing(shown), 0))
        assertEquals(shown, hold.onReport(CastReport.Unknown, 1_000))
        assertEquals(1_000 + CastHold.GRACE_MS, hold.expiresAtMillis)
        assertEquals(shown, hold.at(1_000 + CastHold.GRACE_MS - 1))
        assertNull(hold.at(1_000 + CastHold.GRACE_MS))
        assertNull(hold.expiresAtMillis)
    }

    @Test fun `more silence never stretches the window, so nothing stale outlives it`() {
        val hold = CastHold()
        hold.onReport(CastReport.Playing(item()), 0)
        hold.onReport(CastReport.Unknown, 1_000)
        hold.onReport(CastReport.Unknown, 9_000)
        assertNull(hold.at(1_000 + CastHold.GRACE_MS))
    }

    @Test fun `a fresh report ends the gap and replaces the item at once`() {
        val hold = CastHold()
        hold.onReport(CastReport.Playing(item()), 0)
        hold.onReport(CastReport.Unknown, 1_000)
        val next = item("Another Title", at = 5_000)
        assertEquals(next, hold.onReport(CastReport.Playing(next), 5_000))
        assertNull(hold.expiresAtMillis)
        assertEquals(next, hold.at(60_000))
    }

    @Test fun `a confirmed stop clears at once`() {
        val hold = CastHold()
        hold.onReport(CastReport.Playing(item()), 0)
        assertNull(hold.onReport(CastReport.Stopped, 1))
    }

    @Test fun `silence with nothing confirmed shows nothing`() {
        val hold = CastHold()
        assertNull(hold.onReport(CastReport.Unknown, 0))
        assertNull(hold.expiresAtMillis)
    }

    @Test fun `standby, another package, or a tuner or input clears at once`() {
        fun held(context: CastContext = CastContext(foreground = "com.example.player")) = CastHold().apply {
            onContext(context)
            onReport(CastReport.Playing(item()), 0)
            onReport(CastReport.Unknown, 1)
        }
        assertTrue(held().onContext(CastContext(standby = true)))
        assertTrue(held().onContext(CastContext(foreground = "com.example.launcher")))
        val switched = held()
        assertTrue(switched.onContext(CastContext(foreground = "com.example.player", source = "extInput:hdmi?port=1")))
        assertNull(switched.at(2))
    }

    @Test fun `a package or source going unreported proves nothing`() {
        val hold = CastHold()
        hold.onContext(CastContext(foreground = "com.example.player"))
        hold.onReport(CastReport.Playing(item()), 0)
        assertFalse(hold.onContext(CastContext(foreground = null)))
        assertFalse(hold.onContext(CastContext(foreground = "com.example.player")))
        assertEquals("Sample Title", hold.at(1)?.title)
    }

    @Test fun `a package first learnt after the item counts as the item's own`() {
        val hold = CastHold()
        hold.onReport(CastReport.Playing(item()), 0)
        assertFalse(hold.onContext(CastContext(foreground = "com.example.player")))
        assertTrue(hold.onContext(CastContext(foreground = "com.example.launcher")))
    }

    @Test fun `the position runs on from the last reading while held`() {
        val hold = CastHold()
        hold.onReport(CastReport.Playing(item(at = 0)), 0)
        val held = hold.onReport(CastReport.Unknown, 2_000)!!
        assertEquals(110.0, held.positionAt(10_000))
    }

    @Test fun `the reducer tells silence from a confirmed end`() {
        val reducer = CastStatusReducer()
        assertTrue(reducer.report(media("PLAYING"), 0) is CastReport.Playing)
        // An empty list (seen between items and around ads) and a new item without its title say nothing.
        assertEquals(CastReport.Unknown, reducer.report(empty, 1_000))
        assertEquals(CastReport.Unknown, reducer.report(media("LOADING", session = 4, title = null), 2_000))
        // IDLE on the item's own session between items says nothing either.
        val again = CastStatusReducer().apply { report(media("PLAYING"), 0) }
        assertEquals(CastReport.Unknown, again.report(media("IDLE", idleReason = "FINISHED", title = null), 1_000))
        // Stopped or failed, or another session idle: over.
        assertEquals(CastReport.Stopped, CastStatusReducer().apply { report(media("PLAYING"), 0) }.report(media("IDLE", idleReason = "CANCELLED"), 1))
        assertEquals(CastReport.Stopped, CastStatusReducer().apply { report(media("PLAYING"), 0) }.report(media("IDLE", idleReason = "ERROR"), 1))
        assertEquals(CastReport.Stopped, CastStatusReducer().apply { report(media("PLAYING"), 0) }.report(media("IDLE", session = 9), 1))
        // onMediaStatus keeps its plain view: nothing for anything but an item.
        assertNull(CastStatusReducer().onMediaStatus(empty, 0))
    }

    @Test fun `a flicker sequence never reaches the card`() {
        // PLAYING, an empty status, the same item again after a reconnect: the card never blinks.
        val hold = CastHold()
        val reducer = CastStatusReducer()
        val seen = mutableListOf<String?>()
        fun feed(report: CastReport, at: Long) { seen += hold.onReport(report, at)?.title }
        feed(reducer.report(media("PLAYING"), 0), 0)
        feed(reducer.report(empty, 4_000), 4_000)
        feed(CastReport.Unknown, 5_000) // the session drops
        val fresh = CastStatusReducer()
        feed(CastReport.Unknown, 11_000) // the new session's receiver status
        feed(fresh.report(media("PLAYING"), 11_500), 11_500)
        assertEquals(List(5) { "Sample Title" }, seen)
    }
}
