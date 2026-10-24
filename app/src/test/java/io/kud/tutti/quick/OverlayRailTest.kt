package io.kud.tutti.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRailTest {
    @Test
    fun `an unreported level is empty, never 0, and never at a limit`() {
        val rail = overlayRail(QuickState(volume = 0, volumeLimit = 100, level = null))
        assertNull(rail.level)
        assertEquals(100, rail.range)
        assertFalse(rail.atLimit(up = false))
        assertFalse(rail.atLimit(up = true))
    }

    @Test
    fun `the bar's level is on 0 to 100 and capped by the scene`() {
        val rail = overlayRail(QuickState(volumeLimit = 20, level = AudioLevel(VolumeTarget.BAR, 20, 20, muted = false)))
        assertEquals(20, rail.level)
        assertEquals(100, rail.range)
        assertEquals(20, rail.limit)
        assertTrue(rail.atLimit(up = true))
        assertFalse(rail.atLimit(up = false))
    }

    @Test
    fun `a reported 0 is a real 0`() {
        val rail = overlayRail(QuickState(level = AudioLevel(VolumeTarget.BAR, 0, 100, muted = false)))
        assertEquals(0, rail.level)
        assertTrue(rail.atLimit(up = false))
    }

    @Test
    fun `the TV's level keeps its own scale, whatever the bar's cap`() {
        val rail = overlayRail(QuickState(volumeLimit = 20, level = AudioLevel(VolumeTarget.TV, 30, 50, muted = false)))
        assertEquals(30, rail.level)
        assertEquals(50, rail.range)
        assertEquals(50, rail.limit)
        assertFalse(rail.atLimit(up = true))
    }
}
