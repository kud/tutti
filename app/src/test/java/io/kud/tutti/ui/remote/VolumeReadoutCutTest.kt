package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeReadoutCutTest {
    // A 1000 px rail, its readout 200 px down and 100 px tall.
    private fun cut(fraction: Float, railHeight: Float = 1000f) = readoutCut(railHeight, fraction, 200f, 100f)

    @Test
    fun `a low level leaves the whole number over the empty track`() {
        assertEquals(100f, cut(0.18f))
    }

    @Test
    fun `a level whose edge crosses the number cuts it there`() {
        assertEquals(50f, cut(0.75f))
    }

    @Test
    fun `a level above the number puts it all on the fill`() {
        assertEquals(0f, cut(0.9f))
    }

    @Test
    fun `a rail not yet measured cuts nowhere inside the readout rather than past it`() {
        assertEquals(0f, cut(0.18f, railHeight = 0f))
    }

    @Test
    fun `a level out of range is held to the rail`() {
        assertEquals(100f, cut(-0.5f))
        assertEquals(0f, cut(1.5f))
    }
}
