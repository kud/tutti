package io.kud.tutti.protocol.sleep

import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import io.kud.tutti.protocol.sleep.EpisodeEnd.Why
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeEndTest {
    private val now = 1_000_000_000L

    private fun item(
        at: Double,
        duration: Double? = 2_700.0,
        state: CastPlayerState = CastPlayerState.PLAYING,
        rate: Double = 1.0,
        readAt: Long = now,
        title: String = "Episode",
    ) = CastNowPlaying(title, null, state, at, duration, rate, readAt)

    @Test fun `offered only for a known duration with room for the fade`() {
        assertTrue(EpisodeEnd.offered(item(600.0), now))
        assertTrue(EpisodeEnd.offered(item(600.0, state = CastPlayerState.PAUSED), now))
        assertFalse(EpisodeEnd.offered(null, now))
        assertFalse(EpisodeEnd.offered(item(600.0, duration = null), now), "live: no end to aim at")
        assertFalse(EpisodeEnd.offered(item(2_600.0), now), "100 s left: shorter than the fade")
        assertFalse(EpisodeEnd.offered(item(600.0, state = CastPlayerState.BUFFERING), now))
        assertFalse(EpisodeEnd.offered(item(600.0, readAt = now - 60_000), now), "stale reading: no time")
    }

    @Test fun `the end is the time left at the item's rate`() {
        assertEquals(now + 2_100_000, EpisodeEnd.start(item(600.0), now)!!.endsAt)
        assertEquals(now + 1_050_000, EpisodeEnd.start(item(600.0, rate = 2.0), now)!!.endsAt)
        // Read 10 s ago while playing: the position ran on.
        assertEquals(now + 2_090_000, EpisodeEnd.start(item(600.0, readAt = now - 10_000), now)!!.endsAt)
    }

    @Test fun `re-reads within the jitter leave the end alone`() {
        val plan = EpisodeEnd.start(item(600.0), now)!!
        val step = EpisodeEnd.replan(plan, item(615.0, readAt = now + 12_000), now + 12_000)
        assertNull(step.why)
        assertEquals(plan.endsAt, step.plan.endsAt)
    }

    @Test fun `a seek back moves the end later, a seek forward sooner`() {
        val plan = EpisodeEnd.start(item(600.0), now)!!
        val back = EpisodeEnd.replan(plan, item(300.0), now)
        assertEquals(Why.SEEK, back.why)
        assertEquals(plan.endsAt + 300_000, back.plan.endsAt)
        val forward = EpisodeEnd.replan(plan, item(900.0), now)
        assertEquals(plan.endsAt - 300_000, forward.plan.endsAt)
    }

    @Test fun `a rate change is told apart from a seek`() {
        val plan = EpisodeEnd.start(item(600.0), now)!!
        val step = EpisodeEnd.replan(plan, item(600.0, rate = 1.5), now)
        assertEquals(Why.RATE, step.why)
        assertEquals(now + 1_400_000, step.plan.endsAt)
    }

    @Test fun `a pause holds the end out of the fade's reach, and resuming re-aims it`() {
        val plan = EpisodeEnd.start(item(2_600.0 - 60.0), now)!! // 160 s left
        val paused = EpisodeEnd.replan(plan, item(2_540.0, state = CastPlayerState.PAUSED), now + 5_000)
        assertEquals(Why.PAUSED, paused.why)
        assertTrue(paused.plan.paused)
        assertEquals(SleepFade.Phase.WAITING, SleepFade.phase(now + 5_000, paused.plan.endsAt), "never fades a paused episode")
        // Still paused ten minutes later: no new line, and the end keeps its distance.
        val later = EpisodeEnd.replan(paused.plan, item(2_540.0, state = CastPlayerState.PAUSED, readAt = now + 600_000), now + 600_000)
        assertNull(later.why)
        assertEquals(now + 600_000 + 160_000 + EpisodeEnd.PAUSE_RECHECK_MS, later.plan.endsAt)
        val resumed = EpisodeEnd.replan(later.plan, item(2_540.0, readAt = now + 700_000), now + 700_000)
        assertEquals(Why.RESUMED, resumed.why)
        assertEquals(now + 700_000 + 160_000, resumed.plan.endsAt)
    }

    @Test fun `another item ends the one that played, within one fade, and is never followed`() {
        val plan = EpisodeEnd.start(item(600.0), now)!!
        val next = EpisodeEnd.replan(plan, item(5.0, duration = 2_640.0, title = "Next episode"), now)
        assertEquals(Why.ITEM_CHANGED, next.why)
        assertEquals(now + SleepFade.FADE_MS, next.plan.endsAt)
        assertTrue(next.plan.over)
        // Its own end is never aimed at, and a seek in it moves nothing.
        val after = EpisodeEnd.replan(next.plan, item(1_000.0, duration = 2_640.0, title = "Next episode"), now + 10_000)
        assertNull(after.why)
        assertEquals(next.plan.endsAt, after.plan.endsAt)
    }

    @Test fun `an autoplay inside the fade never pushes the end out`() {
        val plan = EpisodeEnd.start(item(2_550.0), now)!! // 150 s left
        val next = EpisodeEnd.replan(plan, item(1.0, title = "Next"), now + 60_000)
        assertEquals(plan.endsAt, next.plan.endsAt)
    }

    @Test fun `Cast going silent keeps the plan and says so once`() {
        val plan = EpisodeEnd.start(item(600.0), now)!!
        val silent = EpisodeEnd.replan(plan, null, now + 20_000)
        assertEquals(Why.SILENT, silent.why)
        assertEquals(plan.endsAt, silent.plan.endsAt)
        assertNull(EpisodeEnd.replan(silent.plan, null, now + 40_000).why)
        val back = EpisodeEnd.replan(silent.plan, item(620.0, readAt = now + 20_000), now + 20_000)
        assertNull(back.why)
        assertFalse(back.plan.silent)
    }

    @Test fun `the log line carries no title and no position`() {
        val line = EpisodeEnd.logLine(Why.SEEK, now + 100_000, now + 400_000, now)
        assertEquals("episode end: seek (end +300 s, 7 min left)", line)
        assertNotNull(EpisodeEnd.start(item(600.0, title = "Secret title"), now)).also {
            assertFalse(it.toString().contains("Secret"))
        }
    }
}
