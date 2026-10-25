package io.kud.tutti.protocol.sleep

import io.kud.tutti.protocol.sleep.SleepFade.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SleepFadeTest {
    private val now = 1_000_000_000L
    private val end = SleepFade.endsAt(now, 30)

    @Test
    fun `phases run waiting, fading for the last two minutes, then due`() {
        assertEquals(now + 30 * 60_000L, end)
        assertEquals(Phase.WAITING, SleepFade.phase(end - 120_001, end))
        assertEquals(Phase.FADING, SleepFade.phase(end - 120_000, end))
        assertEquals(Phase.FADING, SleepFade.phase(end - 1, end))
        assertEquals(Phase.DUE, SleepFade.phase(end, end))
    }

    @Test
    fun `minutes left round up and stop at zero`() {
        assertEquals(30, SleepFade.minutesLeft(now, end))
        assertEquals(1, SleepFade.minutesLeft(end - 1, end))
        assertEquals(0, SleepFade.minutesLeft(end + 5, end))
    }

    @Test
    fun `the fade steps down evenly to a whisper, never silence before the end`() {
        val start = SleepFade.fadeStartsAt(end)
        assertNull(SleepFade.volumeAt(start - 1, end, 24))
        assertEquals(22, SleepFade.volumeAt(start, end, 24))
        assertEquals(20, SleepFade.volumeAt(start + SleepFade.STEP_MS, end, 24))
        assertEquals(1, SleepFade.volumeAt(end - 1, end, 24))
        assertEquals(1, SleepFade.volumeAt(end, end, 24))
        val levels = (0 until SleepFade.STEPS).map { SleepFade.volumeAt(start + it * SleepFade.STEP_MS, end, 24)!! }
        assertEquals(levels.sortedDescending(), levels)
        assertEquals(0, SleepFade.volumeAt(end, end, 0))
    }

    @Test
    fun `a hand on the rail wins: the fade only ever lowers`() {
        val start = SleepFade.fadeStartsAt(end)
        assertEquals(22, SleepFade.nextLevel(start, end, 24, current = 24))
        assertNull(SleepFade.nextLevel(start, end, 24, current = 15))
        assertNull(SleepFade.nextLevel(start - 1, end, 24, current = 24))
    }

    @Test
    fun `wakes at the fade, then each step, then the end`() {
        val start = SleepFade.fadeStartsAt(end)
        assertEquals(start, SleepFade.nextWake(now, end))
        assertEquals(start + SleepFade.STEP_MS, SleepFade.nextWake(start, end))
        assertEquals(end, SleepFade.nextWake(end - 1, end))
        assertNull(SleepFade.nextWake(end, end))
    }

    @Test
    fun `cancel mid-fade puts the level back unless it is already higher`() {
        assertEquals(24, SleepFade.restoreOnCancel(24, 10))
        assertNull(SleepFade.restoreOnCancel(24, 30))
        assertNull(SleepFade.restoreOnCancel(null, 10))
    }
}
