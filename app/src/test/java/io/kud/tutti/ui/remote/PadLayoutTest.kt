package io.kud.tutti.ui.remote

import androidx.compose.ui.geometry.Offset
import io.kud.tutti.platform.data.Hand
import io.kud.tutti.platform.data.PadSize
import io.kud.tutti.protocol.atv.V2Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadLayoutTest {
    // What a portrait phone leaves the pad block (card room, pad), from the remote's fixed
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
        assertEquals(available, place.cardRoom + place.height + place.below, 0.01f)
        assertTrue(place.cardRoom >= 0f)
        assertTrue(place.height >= 0f)
        // The card, when shown, keeps its margins inside its room.
        assertTrue(place.card.height + 2 * CARD_MARGIN <= place.cardRoom + 0.01f || place.card.size == CardSize.HIDDEN)
    }

    @Test
    fun `a short phone gives the pad all it can and hides the card`() {
        val (place, available, padWidth) = phone(360f, 640f)
        assertFits(place, available)
        assertEquals(PAD_BELOW, place.below, 0.01f)
        assertEquals(PAD_MIN_ABOVE, place.cardRoom, 0.01f)
        assertTrue(place.height < padWidth * padRatio(PadSize.NORMAL))
        assertEquals(CardSize.HIDDEN, place.card.size)
    }

    @Test
    fun `a mid phone stops the pad growing and, with the tab gone, shows the full card`() {
        val (place, available, padWidth) = phone(390f, 844f)
        assertFits(place, available)
        assertEquals(padWidth * padRatio(PadSize.NORMAL), place.height, 0.01f)
        assertEquals(CardSize.FULL, place.card.size)
    }

    @Test
    fun `a phone between short and mid shows the card as one line`() {
        val (place, available, _) = phone(360f, 760f)
        assertFits(place, available)
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
        // His 412x915 (dev.46): pills end at 165, the pad's top at 387, and the tab's 54 dp is the card's now.
        val room = 387f - 165f
        val fit = cardFor(room)
        assertEquals(CardFit(CardSize.FULL, CARD_MAX), fit)
        val margin = (room - fit.height) / 2f
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
    fun `large text steps the card down instead of clipping it`() {
        val room = CARD_FULL + 2 * CARD_MARGIN
        assertEquals(CardSize.FULL, cardFor(room, fontScale = 1f).size)
        assertEquals(CardSize.LINE, cardFor(room, fontScale = 2f).size)
        assertEquals(CARD_LINE + CARD_TEXT_LINE, cardFor(room, fontScale = 2f).height, 0.01f)
        assertEquals(CardSize.HIDDEN, cardFor(CARD_LINE + 2 * CARD_MARGIN, fontScale = 2f).size)
        assertEquals(0f, cardGrowth(0.85f), 0.01f)
        assertEquals(CARD_TEXT_LINE, cardGrowth(3f), 0.01f)
    }

    @Test
    fun `the pad's middle sits in the lower half on a tall phone`() {
        val (place, _, _) = phone(412f, 915f)
        // From the screen's top: the status bar, header and pills, then the card room.
        val middle = 24f + 68f + 48f + place.cardRoom + place.height / 2f
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

    @Test
    fun `the d-pad never reaches the switch's corner, on any phone, pad size or hand`() {
        for (width in listOf(320f, 360f, 390f, 412f, 480f)) {
            for (height in listOf(600f, 640f, 740f, 844f, 915f, 1000f)) {
                for (size in PadSize.entries) for (onLeft in listOf(false, true)) {
                    val (place, _, padWidth) = phone(width, height, size)
                    if (place.height <= 0f) continue
                    val r = dpadFaceRadius(padWidth, place.height, PAD_SWITCH_ZONE, onLeft)
                    val reach = r * DPAD_REACH
                    // The corner square's nearest point to the disc's centre.
                    val cx = padWidth / 2f
                    val cy = place.height / 2f
                    val nx = if (onLeft) cx.coerceIn(0f, PAD_SWITCH_ZONE) else cx.coerceIn(padWidth - PAD_SWITCH_ZONE, padWidth)
                    val ny = cy.coerceIn(0f, PAD_SWITCH_ZONE)
                    val gap = kotlin.math.hypot(cx - nx, cy - ny)
                    assertTrue("${width}x$height $size left=$onLeft: reach $reach, corner at $gap", reach <= gap + 0.01f)
                    // The face never outgrows the pad.
                    assertTrue(2f * r <= minOf(padWidth, place.height) + 0.01f)
                }
            }
        }
    }

    @Test
    fun `a tall pad keeps the full disc, a square one gives up only a little`() {
        assertEquals(304f / 2f, dpadFaceRadius(304f, 365f, PAD_SWITCH_ZONE, onLeft = false), 0.01f)
        val square = dpadFaceRadius(304f, 304f, PAD_SWITCH_ZONE, onLeft = false)
        assertTrue("square face $square", square < 152f && square > 152f * 0.9f)
        // The disc is centred, so the mirrored corner costs it exactly the same.
        assertEquals(square, dpadFaceRadius(304f, 304f, PAD_SWITCH_ZONE, onLeft = true), 0.001f)
    }

    @Test
    fun `the switch's corner is the pad's top-right 52 dp, nowhere else`() {
        val w = 304f
        assertTrue(inPadSwitchCorner(w - 1f, 1f, w, PAD_SWITCH_ZONE, onLeft = false))
        assertTrue(inPadSwitchCorner(w - 52f, 52f, w, PAD_SWITCH_ZONE, onLeft = false))
        assertTrue(!inPadSwitchCorner(w - 53f, 10f, w, PAD_SWITCH_ZONE, onLeft = false))
        assertTrue(!inPadSwitchCorner(w - 10f, 53f, w, PAD_SWITCH_ZONE, onLeft = false))
        assertTrue(!inPadSwitchCorner(10f, 10f, w, PAD_SWITCH_ZONE, onLeft = false))
    }

    @Test
    fun `mirrored, the switch's corner is the pad's top-left 52 dp, nowhere else`() {
        val w = 304f
        assertTrue(inPadSwitchCorner(1f, 1f, w, PAD_SWITCH_ZONE, onLeft = true))
        assertTrue(inPadSwitchCorner(52f, 52f, w, PAD_SWITCH_ZONE, onLeft = true))
        assertTrue(!inPadSwitchCorner(53f, 10f, w, PAD_SWITCH_ZONE, onLeft = true))
        assertTrue(!inPadSwitchCorner(10f, 53f, w, PAD_SWITCH_ZONE, onLeft = true))
        assertTrue(!inPadSwitchCorner(w - 10f, 10f, w, PAD_SWITCH_ZONE, onLeft = true))
        assertTrue(!inPadSwitchCorner(10f, 10f, w, 0f, onLeft = true))
    }

    @Test
    fun `the right hand puts the pad on the right and the switch top-left, facing the rail`() {
        assertTrue(Hand.RIGHT.padOnRight && Hand.RIGHT.switchOnLeft)
        assertTrue(!Hand.LEFT.padOnRight && !Hand.LEFT.switchOnLeft)
    }

    @Test
    fun `the back-gesture band hugs the pad's outer side and stays under Android's 200 dp`() {
        val right = padEdgeExclusion(304f, 365f, edgeOnRight = true, strip = PAD_EXCLUSION_STRIP, tall = PAD_EXCLUSION_TALL)
        assertEquals(304f, right.right, 0.01f)
        assertEquals(304f - PAD_EXCLUSION_STRIP, right.left, 0.01f)
        assertEquals(365f / 2f, right.center.y, 0.01f)
        assertTrue(right.height <= 200f)
        val left = padEdgeExclusion(304f, 365f, edgeOnRight = false, strip = PAD_EXCLUSION_STRIP, tall = PAD_EXCLUSION_TALL)
        assertEquals(0f, left.left, 0.01f)
        assertEquals(PAD_EXCLUSION_STRIP, left.right, 0.01f)
        // A short pad keeps the band inside itself.
        val short = padEdgeExclusion(304f, 120f, edgeOnRight = true, strip = PAD_EXCLUSION_STRIP, tall = PAD_EXCLUSION_TALL)
        assertEquals(0f, short.top, 0.01f)
        assertEquals(120f, short.bottom, 0.01f)
    }

    @Test
    fun `the D-pad answers only on its drawn disc, the surface round it is inert`() {
        val corner = PAD_SWITCH_ZONE
        // Compact, Normal and Large shapes on a 390 dp phone, and a tall one.
        for ((w, h) in listOf(282f to 240f, 282f to 282f, 282f to 338f, 300f to 360f)) {
            for (onLeft in listOf(true, false)) {
                fun zone(x: Float, y: Float) = dpadZone(Offset(x, y), w, h, corner, onLeft)
                val r = dpadFaceRadius(w, h, corner, onLeft)
                val cx = w / 2f
                val cy = h / 2f
                assertEquals(V2Key.DPAD_CENTER, zone(cx, cy))
                // Just inside the disc's edge, each arrow answers.
                assertEquals(V2Key.DPAD_UP, zone(cx, cy - r + 2f))
                assertEquals(V2Key.DPAD_DOWN, zone(cx, cy + r - 2f))
                assertEquals(V2Key.DPAD_LEFT, zone(cx - r + 2f, cy))
                assertEquals(V2Key.DPAD_RIGHT, zone(cx + r - 2f, cy))
                // Outside it, on the plate, nothing: the corners and just past the edge.
                assertEquals(null, zone(cx, cy - r - 2f))
                assertEquals(null, zone(2f, h - 2f))
                assertEquals(null, zone(w - 2f, h - 2f))
                // The switch's corner never answers as a direction.
                val switchX = if (onLeft) 20f else w - 20f
                for (y in listOf(1f, 20f, 51f)) assertEquals(null, zone(switchX, y))
            }
        }
    }

    @Test
    fun `the drawn disc never reaches the switch's glyph`() {
        // The glyph is 24 dp, 16 dp in from the top and side: its inner corner is at 40, 40.
        for ((w, h) in listOf(282f to 240f, 282f to 282f, 282f to 338f, 304f to 304f)) {
            for (onLeft in listOf(true, false)) {
                val side = 2f * dpadFaceRadius(w, h, PAD_SWITCH_ZONE, onLeft)
                val edge = dpadEdgeRadius(side, side)
                val gx = if (onLeft) 40f else w - 40f
                val gap = kotlin.math.hypot(gx - w / 2f, 40f - h / 2f)
                assertTrue("$w x $h left=$onLeft: disc $edge, glyph at $gap", edge < gap)
            }
        }
    }
}
