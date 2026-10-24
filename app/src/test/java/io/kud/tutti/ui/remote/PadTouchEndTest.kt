package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** The touchpad's verdict at lift: a still tap in the switch's corner switches, never OK. */
class PadTouchEndTest {
    @Test
    fun `a still tap in the corner switches and never sends OK`() {
        assertEquals(PadTouchEnd.SWITCH, padTouchEnd(moved = false, longFired = false, pointers = 1, startedInCorner = true))
    }

    @Test
    fun `a touch that starts in the corner and moves is a swipe, already acted on`() {
        assertEquals(PadTouchEnd.NOTHING, padTouchEnd(moved = true, longFired = false, pointers = 1, startedInCorner = true))
    }

    @Test
    fun `a long hold in the corner does nothing at lift`() {
        assertEquals(PadTouchEnd.NOTHING, padTouchEnd(moved = false, longFired = true, pointers = 1, startedInCorner = true))
    }

    @Test
    fun `a still tap elsewhere is OK, and a swipe crossing the corner counts only where it started`() {
        assertEquals(PadTouchEnd.TAP, padTouchEnd(moved = false, longFired = false, pointers = 1, startedInCorner = false))
        assertEquals(PadTouchEnd.NOTHING, padTouchEnd(moved = true, longFired = false, pointers = 1, startedInCorner = false))
    }

    @Test
    fun `two still fingers are play-pause, wherever the first went down`() {
        assertEquals(PadTouchEnd.TWO_FINGER_TAP, padTouchEnd(moved = false, longFired = false, pointers = 2, startedInCorner = true))
        assertEquals(PadTouchEnd.TWO_FINGER_TAP, padTouchEnd(moved = false, longFired = false, pointers = 2, startedInCorner = false))
    }
}
