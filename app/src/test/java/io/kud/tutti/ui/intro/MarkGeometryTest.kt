package io.kud.tutti.ui.intro

import io.kud.tutti.ui.intro.MarkGeometry.BODY_BOTTOM
import io.kud.tutti.ui.intro.MarkGeometry.BODY_CORNER
import io.kud.tutti.ui.intro.MarkGeometry.BODY_LEFT
import io.kud.tutti.ui.intro.MarkGeometry.BODY_RIGHT
import io.kud.tutti.ui.intro.MarkGeometry.BODY_TOP
import io.kud.tutti.ui.intro.MarkGeometry.CENTRE
import io.kud.tutti.ui.intro.MarkGeometry.OK_RADIUS
import io.kud.tutti.ui.intro.MarkGeometry.OK_Y
import io.kud.tutti.ui.intro.MarkGeometry.PILL_BOTTOM
import io.kud.tutti.ui.intro.MarkGeometry.PILL_LEFT
import io.kud.tutti.ui.intro.MarkGeometry.PILL_RIGHT
import io.kud.tutti.ui.intro.MarkGeometry.PILL_TOP
import io.kud.tutti.ui.intro.MarkGeometry.SAFE_RADIUS
import io.kud.tutti.ui.intro.MarkGeometry.TOP_RADIUS
import io.kud.tutti.ui.intro.MarkGeometry.TOP_Y
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher mark survives every adaptive mask, and the vectors draw what the intro draws. */
class MarkGeometryTest {
    private fun f(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    @Test
    fun `the handset stays inside the adaptive icon's safe circle`() {
        // The farthest point of a rounded rectangle from the centre is on a corner's arc.
        val cx = maxOf(CENTRE - (BODY_LEFT + BODY_CORNER), (BODY_RIGHT - BODY_CORNER) - CENTRE)
        val cy = maxOf(CENTRE - (BODY_TOP + BODY_CORNER), (BODY_BOTTOM - BODY_CORNER) - CENTRE)
        val reach = hypot(cx, cy) + BODY_CORNER
        assertTrue("reach $reach", reach <= SAFE_RADIUS)
        assertTrue(CENTRE - BODY_TOP <= SAFE_RADIUS && BODY_BOTTOM - CENTRE <= SAFE_RADIUS)
    }

    @Test
    fun `the top button, OK and pill keep clear of each other and of the edge`() {
        val gap = 4f
        assertTrue(TOP_Y - TOP_RADIUS - BODY_TOP >= gap)
        assertTrue((OK_Y - OK_RADIUS) - (TOP_Y + TOP_RADIUS) >= gap)
        assertTrue(PILL_TOP - (OK_Y + OK_RADIUS) >= gap)
        assertTrue(BODY_BOTTOM - PILL_BOTTOM >= gap)
        assertTrue(CENTRE - OK_RADIUS - BODY_LEFT >= gap && PILL_LEFT - BODY_LEFT >= gap && BODY_RIGHT - PILL_RIGHT >= gap)
    }

    @Test
    fun `the launcher, themed and splash vectors spell the same handset and OK`() {
        val w = BODY_RIGHT - BODY_LEFT - 2 * BODY_CORNER
        val h = BODY_BOTTOM - BODY_TOP - 2 * BODY_CORNER
        val r = f(BODY_CORNER)
        val body = "M${f(BODY_LEFT + BODY_CORNER)},${f(BODY_TOP)}h${f(w)}a$r,$r 0,0 1,$r ${r}v${f(h)}"
        val ok = "M${f(CENTRE)},${f(OK_Y - OK_RADIUS)}a${f(OK_RADIUS)},${f(OK_RADIUS)}"
        val top = "M${f(CENTRE)},${f(TOP_Y - TOP_RADIUS)}a${f(TOP_RADIUS)},${f(TOP_RADIUS)}"
        val pill = "M${f(PILL_LEFT + (PILL_BOTTOM - PILL_TOP) / 2f)},${f(PILL_TOP)}h"
        for (name in listOf("ic_launcher_foreground", "ic_launcher_monochrome", "splash_icon")) {
            val xml = File("src/main/res/drawable/$name.xml").readText()
            for (part in listOf(body, ok, top, pill)) assertTrue("$name lacks $part", part in xml)
        }
    }
    @Test
    fun `the wordmark sits centred under the handset, clear of it, as wide as the lockup allows`() {
        val s = Wordmark.SCALE
        val top = MarkGeometry.WORDMARK_BASELINE + Wordmark.TOP * s
        val gap = top - BODY_BOTTOM
        assertTrue("clear of the handset: $gap", gap >= (BODY_RIGHT - BODY_LEFT) / 2f)
        assertTrue("near enough to read as one lockup: $gap", gap <= BODY_BOTTOM - BODY_TOP)
        val width = (Wordmark.RIGHT - Wordmark.LEFT) * s
        assertEquals(MarkGeometry.WORDMARK_WIDTH, width, 1e-3f)
        assertTrue("wider than the handset, narrower than the icon", width > BODY_RIGHT - BODY_LEFT && width < MarkGeometry.VIEWPORT)
        // The halo wraps the whole handset.
        assertTrue(MarkGeometry.HALO_RX > (BODY_RIGHT - BODY_LEFT) && MarkGeometry.HALO_RY > (BODY_BOTTOM - BODY_TOP))
    }

    @Test
    fun `the wordmark's outline stays inside its declared extent and leaves the dot to amber`() {
        val numbers = Regex("-?[0-9]+(?:\\.[0-9]+)?").findAll(Wordmark.PATH).map { it.value.toFloat() }.toList()
        assertTrue(numbers.size > 100 && numbers.size % 2 == 0)
        numbers.chunked(2).forEach { (x, y) ->
            assertTrue("x $x", x >= Wordmark.LEFT && x <= Wordmark.RIGHT)
            assertTrue("y $y", y >= Wordmark.TOP && y <= Wordmark.BOTTOM)
            // Nothing of the ink inside the dot's circle: the i's dot is a separate shape.
            assertTrue("($x, $y) in the dot", hypot(x - Wordmark.DOT_X, y - Wordmark.DOT_Y) > Wordmark.DOT_RADIUS)
        }
        assertEquals(5, Wordmark.PATH.count { it == 'M' })
    }
}
