package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarTest {

    @Test
    fun theThresholdIsFourHundredTwentyDp() {
        assertEquals(420, TOP_BAR_FOLD_DP)
        assertTrue(topBarFolds(419))
        assertFalse(topBarFolds(420))
    }

    @Test
    fun narrowPhonesFoldAndWideScreensKeepTheIcons() {
        for (width in listOf(320, 360, 393, 412)) assertTrue("$width dp", topBarFolds(width))
        for (width in listOf(480, 600, 840)) assertFalse("$width dp", topBarFolds(width))
    }

    @Test
    fun foldingGivesTheChipAtLeastWhatThreeIconsLeaveItAtTheThreshold() {
        // At 360 dp, folded (two icon buttons: scenes and ⋯), the chip is no narrower than ever unfolded.
        assertEquals(180, chipWidthDp(360, icons = 2))
        assertEquals(CHIP_MIN_DP, chipWidthDp(TOP_BAR_FOLD_DP, icons = 3))
    }
}
