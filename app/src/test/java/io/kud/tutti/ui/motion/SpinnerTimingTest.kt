package io.kud.tutti.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpinnerTimingTest {
    @Test
    fun `no spinner before 150 ms`() {
        assertFalse(SpinnerTiming.visibleAt(0))
        assertFalse(SpinnerTiming.visibleAt(149))
        assertTrue(SpinnerTiming.visibleAt(150))
        assertTrue(SpinnerTiming.visibleAt(5_000))
    }

    @Test
    fun `a quick action resolves at once, never flashing a spinner`() {
        assertEquals(0L, SpinnerTiming.holdAfter(0))
        assertEquals(0L, SpinnerTiming.holdAfter(149))
    }

    @Test
    fun `a shown spinner stays at least 400 ms`() {
        // Shown at 150 ms: it must last until 550 ms.
        assertEquals(400L, SpinnerTiming.holdAfter(150))
        assertEquals(250L, SpinnerTiming.holdAfter(300))
        assertEquals(1L, SpinnerTiming.holdAfter(549))
    }

    @Test
    fun `a long wait resolves at once`() {
        assertEquals(0L, SpinnerTiming.holdAfter(550))
        assertEquals(0L, SpinnerTiming.holdAfter(8_000))
    }

    @Test
    fun `tokens match the motion spec`() {
        assertEquals(listOf(0, 150, 250, 400, 900), listOf(Motion.INSTANT, Motion.SHORT, Motion.MEDIUM, Motion.LONG, Motion.HERO))
    }
}
