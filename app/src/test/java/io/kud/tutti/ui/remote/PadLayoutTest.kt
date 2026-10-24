package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.PadSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadLayoutTest {
    // What a portrait phone leaves the pad block, from the remote's fixed parts in dp: status bar
    // 24, header 68, scene pills 48, switch 6 + 48, round keys 52, 12, dock 84, 12, Son handle 48,
    // gesture bar 24. The pad's width is the screen less 2 × 16 margin, the 12 gap and the 64 rail.
    private val chrome = 24f + 68f + 48f + 54f + 52f + 12f + 84f + 12f + 48f + 24f

    private fun phone(width: Float, height: Float, size: PadSize = PadSize.NORMAL): Triple<PadPlacement, Float, Float> {
        val available = height - chrome
        val padWidth = width - 32f - 12f - 64f
        return Triple(placePad(available, padWidth, size), available, padWidth)
    }

    private fun assertFits(place: PadPlacement, available: Float) {
        assertEquals(available, place.above + place.height + place.below, 0.01f)
        assertTrue(place.above >= PAD_MIN_ABOVE - 0.01f)
        assertTrue(place.height >= 0f)
    }

    @Test
    fun `a short phone gives the pad all it can, nothing overlapping`() {
        val (place, available, padWidth) = phone(360f, 640f)
        assertFits(place, available)
        assertEquals(PAD_BELOW, place.below, 0.01f)
        assertEquals(PAD_MIN_ABOVE, place.above, 0.01f)
        assertTrue(place.height < padWidth * padRatio(PadSize.NORMAL))
    }

    @Test
    fun `taller phones stop the pad growing and put the room above it`() {
        for ((width, height) in listOf(390f to 844f, 412f to 915f)) {
            val (place, available, padWidth) = phone(width, height)
            assertFits(place, available)
            assertEquals(padWidth * padRatio(PadSize.NORMAL), place.height, 0.01f)
            assertEquals(PAD_BELOW, place.below, 0.01f)
            assertTrue("free room goes above at ${width}x$height", place.above > PAD_MIN_ABOVE)
        }
    }

    @Test
    fun `the pad's middle sits in the lower half on a tall phone`() {
        val (place, _, _) = phone(412f, 915f)
        // From the screen's top: everything above the pad block, then the room above the pad.
        val middle = 24f + 68f + 48f + 54f + place.above + place.height / 2f
        assertTrue("pad middle at $middle dp", middle > 915f / 2f)
    }

    @Test
    fun `compact is shorter than normal and large taller, on a roomy screen`() {
        val compact = phone(412f, 915f, PadSize.COMPACT).first.height
        val normal = phone(412f, 915f, PadSize.NORMAL).first.height
        val large = phone(412f, 915f, PadSize.LARGE).first.height
        assertTrue(compact < normal)
        assertTrue(large >= normal)
    }

    @Test
    fun `no room at all collapses without going negative`() {
        for (available in listOf(0f, 10f, 30f, 40f)) {
            val place = placePad(available, 250f, PadSize.LARGE)
            assertEquals(available, place.above + place.height + place.below, 0.01f)
            assertTrue(place.above >= 0f && place.height >= 0f && place.below >= 0f)
        }
    }
}
