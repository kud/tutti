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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
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
    fun `the wordmark sits centred under the handset, close enough to read as one lockup`() {
        val s = Wordmark.SCALE
        val top = MarkGeometry.WORDMARK_BASELINE + Wordmark.TOP * s
        val gap = top - BODY_BOTTOM
        val handset = BODY_RIGHT - BODY_LEFT
        assertTrue("clear of the handset: $gap", gap >= handset / 4f)
        assertTrue("near enough to read as one lockup: $gap", gap <= handset / 2f)
        val width = (Wordmark.RIGHT - Wordmark.LEFT) * s
        assertEquals(MarkGeometry.WORDMARK_WIDTH, width, 1e-3f)
        assertTrue("wider than the handset, no wider than it is tall", width > handset && width <= BODY_BOTTOM - BODY_TOP)
        // Centred: the outline's extent sits evenly either side of the mark's centre.
        assertEquals(CENTRE, Wordmark.ORIGIN_X + (Wordmark.LEFT + Wordmark.RIGHT) / 2f * s, 1e-3f)
        // The lockup's middle is the middle of its extent, handset top to the u's overshoot.
        assertEquals((BODY_TOP + MarkGeometry.WORDMARK_BASELINE + Wordmark.BOTTOM * s) / 2f, MarkGeometry.LOCKUP_CENTRE_Y, 0.05f)
        // The halo wraps the whole lockup.
        assertTrue(MarkGeometry.HALO_RX > width && MarkGeometry.HALO_RY > MarkGeometry.WORDMARK_BASELINE - BODY_TOP)
    }

    /** The lockup's outline points in the mark's units: the handset's corner arcs, the word's path (control points too) and the dot. */
    private fun lockupPoints(): List<Pair<Float, Float>> {
        val points = mutableListOf<Pair<Float, Float>>()
        val corners = listOf(
            Triple(BODY_LEFT + BODY_CORNER, BODY_TOP + BODY_CORNER, 180),
            Triple(BODY_RIGHT - BODY_CORNER, BODY_TOP + BODY_CORNER, 270),
            Triple(BODY_RIGHT - BODY_CORNER, BODY_BOTTOM - BODY_CORNER, 0),
            Triple(BODY_LEFT + BODY_CORNER, BODY_BOTTOM - BODY_CORNER, 90),
        )
        for ((cx, cy, from) in corners) {
            for (deg in from..from + 90 step 2) {
                val r = Math.toRadians(deg.toDouble())
                points += (cx + BODY_CORNER * cos(r).toFloat()) to (cy + BODY_CORNER * sin(r).toFloat())
            }
        }
        val s = Wordmark.SCALE
        val word = Regex("-?[0-9]+(?:\\.[0-9]+)?").findAll(Wordmark.PATH).map { it.value.toFloat() }.toList()
        word.chunked(2).forEach { (x, y) -> points += (Wordmark.ORIGIN_X + x * s) to (MarkGeometry.WORDMARK_BASELINE + y * s) }
        for (deg in 0 until 360 step 5) {
            val r = Math.toRadians(deg.toDouble())
            val x = Wordmark.DOT_X + Wordmark.DOT_RADIUS * cos(r).toFloat()
            val y = Wordmark.DOT_Y + Wordmark.DOT_RADIUS * sin(r).toFloat()
            points += (Wordmark.ORIGIN_X + x * s) to (MarkGeometry.WORDMARK_BASELINE + y * s)
        }
        return points
    }

    @Test
    fun `the splash lockup stays inside the splash's visible circle, as large as it can`() {
        val k = MarkGeometry.LOCKUP_SCALE
        val reach = lockupPoints().maxOf { (x, y) ->
            hypot(MarkGeometry.LOCKUP_DX + x * k - CENTRE, MarkGeometry.LOCKUP_DY + y * k - CENTRE)
        }
        assertTrue("reach $reach", reach <= MarkGeometry.SPLASH_RADIUS - 0.5f)
        // Not timid: within 1.5 units of the edge, so the word stays as large as the circle allows.
        assertTrue("reach $reach", reach >= MarkGeometry.SPLASH_RADIUS - 1.5f)
        // The lockup's middle is the icon's centre.
        assertEquals(CENTRE, MarkGeometry.LOCKUP_DY + MarkGeometry.LOCKUP_CENTRE_Y * k, 1e-4f)
        assertEquals(CENTRE, MarkGeometry.LOCKUP_DX + CENTRE * k, 1e-4f)
    }

    @Test
    fun `on screen the word is about 100 dp wide and the handset about 100 dp tall`() {
        val dp = MarkGeometry.SPLASH_ICON_DP / MarkGeometry.VIEWPORT * MarkGeometry.LOCKUP_SCALE
        val word = MarkGeometry.WORDMARK_WIDTH * dp
        val handset = (BODY_BOTTOM - BODY_TOP) * dp
        // The x-height: the u's top (114.469 outline units) above the baseline.
        val xHeight = 114.469f * Wordmark.SCALE * dp
        assertTrue("word $word dp", word in 95f..105f)
        assertTrue("handset $handset dp", handset in 95f..105f)
        assertTrue("x-height $xHeight dp", xHeight >= 24f)
    }

    @Test
    fun `the splash icon draws the lockup the intro draws`() {
        val xml = File("src/main/res/drawable/splash_icon.xml").readText()
        fun group(name: String): Map<String, Float> {
            val tag = Regex("<group[^>]*android:name=\"$name\"[^>]*>").find(xml)?.value ?: error("no group $name")
            return Regex("android:(scaleX|scaleY|translateX|translateY)=\"([-0-9.]+)\"").findAll(tag)
                .associate { it.groupValues[1] to it.groupValues[2].toFloat() }
        }
        val lockup = group("lockup")
        assertEquals(MarkGeometry.LOCKUP_SCALE, lockup.getValue("scaleX"), 1e-4f)
        assertEquals(MarkGeometry.LOCKUP_SCALE, lockup.getValue("scaleY"), 1e-4f)
        assertEquals(MarkGeometry.LOCKUP_DX, lockup.getValue("translateX"), 1e-3f)
        assertEquals(MarkGeometry.LOCKUP_DY, lockup.getValue("translateY"), 1e-3f)
        val word = group("wordmark")
        assertEquals(Wordmark.SCALE, word.getValue("scaleX"), 1e-5f)
        assertEquals(Wordmark.SCALE, word.getValue("scaleY"), 1e-5f)
        assertEquals(Wordmark.ORIGIN_X, word.getValue("translateX"), 1e-3f)
        assertEquals(MarkGeometry.WORDMARK_BASELINE, word.getValue("translateY"), 1e-3f)
        assertTrue("the word's outline", "android:pathData=\"${Wordmark.PATH}\"" in xml)
        // The i's dot: a circle of DOT_RADIUS round (DOT_X, DOT_Y), drawn from its top.
        val (dx, dy, dr) = Regex("""splash_line"\s*android:pathData="M([-0-9.]+),([-0-9.]+)a([0-9.]+),""").findAll(xml).last().destructured
        assertEquals(Wordmark.DOT_X, dx.toFloat(), 1e-3f)
        assertEquals(Wordmark.DOT_Y - Wordmark.DOT_RADIUS, dy.toFloat(), 1e-3f)
        assertEquals(Wordmark.DOT_RADIUS, dr.toFloat(), 1e-3f)
        // The word in the mark's ink, the dot in the pill's amber, as drawWordmark paints them.
        val tail = xml.substringAfter("android:name=\"wordmark\"")
        assertTrue(Regex("""splash_body"\s*android:pathData="M77""").containsMatchIn(tail))
        assertTrue(Regex("""splash_line"\s*android:pathData="M402""").containsMatchIn(tail))
    }

    @Test
    fun `the intro's mark box is where the splash icon's group puts the mark`() {
        // A 288 dp icon at 2.625x (the 6T's density), centred on a 1080 x 2340 window.
        val side = MarkGeometry.SPLASH_ICON_DP * 2.625f
        val splash = Rect(540f - side / 2f, 1170f - side / 2f, 540f + side / 2f, 1170f + side / 2f)
        val box = lockupMarkBox(splash)
        val u = splash.width / MarkGeometry.VIEWPORT
        // Any mark point p lands at splash + (DX + p x k) units, as the vector's group draws it.
        for ((x, y) in listOf(CENTRE to OK_Y, BODY_LEFT to BODY_TOP, PILL_RIGHT to PILL_BOTTOM)) {
            val intro = Offset(box.left + x * box.width / MarkGeometry.VIEWPORT, box.top + y * box.height / MarkGeometry.VIEWPORT)
            val vector = Offset(
                splash.left + (MarkGeometry.LOCKUP_DX + x * MarkGeometry.LOCKUP_SCALE) * u,
                splash.top + (MarkGeometry.LOCKUP_DY + y * MarkGeometry.LOCKUP_SCALE) * u,
            )
            assertEquals(vector.x, intro.x, 0.01f)
            assertEquals(vector.y, intro.y, 0.01f)
        }
        // The lockup's middle on the splash's centre.
        assertEquals(splash.center.y, box.top + MarkGeometry.LOCKUP_CENTRE_Y * box.height / MarkGeometry.VIEWPORT, 0.01f)
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
