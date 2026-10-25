package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeWallTest {
    @Test
    fun theRailStopsAtTheGuardWhileAtOrUnderIt() {
        assertEquals(30, wallFor(ceiling = null, guard = 30, from = 12))
        assertEquals(30, wallFor(ceiling = 100, guard = 30, from = 30))
        // Above it already (confirmed, or the bar's own remote): only the ceiling.
        assertEquals(100, wallFor(ceiling = null, guard = 30, from = 31))
        // The cap wins when it is lower.
        assertEquals(20, wallFor(ceiling = 20, guard = 30, from = 10))
        assertEquals(100, wallFor(ceiling = null, guard = null, from = 10))
    }
}
