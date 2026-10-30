package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rail's gauge sits between the readout and −, never under a key (Iris, 2026-10-30). */
class VolumeGaugeTest {
    @Test
    fun runsFromUnderTheReadoutToAboveMinus() {
        val gauge = gaugeBounds(height = 600f, top = 108f, bottom = 108f, width = 12f)!!
        assertEquals(108f, gauge.start)
        assertEquals(492f, gauge.endInclusive)
    }

    @Test
    fun aSqueezedRailHasNoGauge() {
        assertNull(gaugeBounds(height = 220f, top = 108f, bottom = 108f, width = 12f))
    }
}
