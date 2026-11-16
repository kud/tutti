package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarTest {

    @Test
    fun powerStandsApartFromTheMenu() {
        // Half a target of nothing between the ⋮ and Power (Erwann, 2026-10-25).
        assertTrue(POWER_GAP_DP >= 24)
    }

    @Test
    fun theChipKeepsItsWidthOnEveryPhone() {
        // One bar at every width: from 360 dp the chip has what three icons once left it at 420.
        for (width in listOf(360, 393, 412, 480, 600, 840)) assertTrue("$width dp", chipWidthDp(width) >= CHIP_MIN_DP)
        assertEquals(204, chipWidthDp(360))
    }

    @Test
    fun theChipIsNeverNarrowerThanTheFoldedBarLeftIt() {
        // The folded bar of dev.81 (scenes and ⋯ beside the chip) left it 180 dp at 360.
        assertTrue(chipWidthDp(360) >= 180)
    }
}
