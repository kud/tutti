package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.PadSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadLayoutTest {
    // What a portrait phone leaves the pad block (card room, tab, pad), from the remote's fixed
    // parts in dp: status bar 24, header 68, scene pills 48, round keys 52, 12, dock 84, 12, Son
    // peek 72, gesture bar 24. The pad's width is the screen less 2 × 16 margin, the 12 gap and
    // the 64 rail.
    private val chrome = 24f + 68f + 48f + 52f + 12f + 84f + 12f + 72f + 24f

    private fun phone(width: Float, height: Float, size: PadSize = PadSize.NORMAL): Triple<PadPlacement, Float, Float> {
        val available = height - chrome
        val padWidth = width - 32f - 12f - 64f
        return Triple(placePad(available, padWidth, size), available, padWidth)
    }

    private fun assertFits(place: PadPlacement, available: Float) {
        assertEquals(available, place.cardRoom + place.tab + place.height + place.below, 0.01f)
        assertTrue(place.cardRoom >= 0f)
        assertTrue(place.height >= 0f)
        // The card, when shown, keeps its margins inside its room.
        assertTrue(place.card.height + 2 * CARD_MARGIN <= place.cardRoom + 0.01f || place.card.size == CardSize.HIDDEN)
    }

    @Test
    fun `a short phone gives the pad all it can and hides the card, never the tab`() {
        val (place, available, padWidth) = phone(360f, 640f)
        assertFits(place, available)
        assertEquals(PAD_BELOW, place.below, 0.01f)
        assertEquals(PAD_MIN_ABOVE, place.cardRoom, 0.01f)
        assertEquals(SWITCH_GROUP, place.tab, 0.01f)
        assertTrue(place.height < padWidth * padRatio(PadSize.NORMAL))
        assertEquals(CardSize.HIDDEN, place.card.size)
    }

    @Test
    fun `a mid phone stops the pad growing and shows the card as one line`() {
        val (place, available, padWidth) = phone(390f, 844f)
        assertFits(place, available)
        assertEquals(padWidth * padRatio(PadSize.NORMAL), place.height, 0.01f)
        assertEquals(CardFit(CardSize.LINE, CARD_LINE), place.card)
    }

    @Test
    fun `a tall phone shows the full card`() {
        val (place, available, padWidth) = phone(412f, 915f)
        assertFits(place, available)
        assertEquals(padWidth * padRatio(PadSize.NORMAL), place.height, 0.01f)
        assertEquals(CardSize.FULL, place.card.size)
        assertTrue(place.card.height in CARD_FULL..CARD_MAX)
    }

    @Test
    fun `the card grows to its ceiling in Erwann's band and centres in it`() {
        // His 412x915 (dev.46): pills end at 165, the pad's top at 387, so 168 dp above the tab.
        val fit = cardFor(387f - 165f - SWITCH_GROUP)
        assertEquals(CardFit(CardSize.FULL, CARD_MAX), fit)
        val margin = (168f - fit.height) / 2f
        assertTrue("margin $margin either side", margin >= CARD_MARGIN)
    }

    @Test
    fun `card thresholds step full, line, hidden`() {
        assertEquals(CardSize.FULL, cardFor(CARD_FULL + 2 * CARD_MARGIN).size)
        assertEquals(CardSize.LINE, cardFor(CARD_FULL + 2 * CARD_MARGIN - 1f).size)
        assertEquals(CardSize.LINE, cardFor(CARD_LINE + 2 * CARD_MARGIN).size)
        assertEquals(CardSize.HIDDEN, cardFor(CARD_LINE + 2 * CARD_MARGIN - 1f).size)
        assertEquals(CARD_MAX, cardFor(1000f).height, 0.01f)
    }

    @Test
    fun `the pad's middle sits in the lower half on a tall phone`() {
        val (place, _, _) = phone(412f, 915f)
        // From the screen's top: the status bar, header and pills, then the card room and the tab.
        val middle = 24f + 68f + 48f + place.cardRoom + place.tab + place.height / 2f
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
        for (available in listOf(0f, 10f, 30f, 40f, 60f, 90f)) {
            val place = placePad(available, 250f, PadSize.LARGE)
            assertTrue(place.cardRoom >= 0f && place.height >= 0f && place.below >= 0f)
            assertEquals(CardSize.HIDDEN, place.card.size)
        }
    }
}
