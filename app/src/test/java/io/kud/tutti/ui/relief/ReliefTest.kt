package io.kud.tutti.ui.relief

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ReliefTest {

    private val ref = 10f
    private val max = 14f

    @Test
    fun `an eye straight above moves nothing`() {
        assertEquals(Offset.Zero, Relief.topOffset(0f, 0f, 9f, ref, max))
    }

    @Test
    fun `depth zero collapses to flat whatever the tilt`() {
        assertEquals(Offset.Zero, Relief.topOffset(0.6f, -0.4f, 0f, ref, max))
        assertEquals(1f, Relief.closeness(0f, ref), 0f)
        // And so no wall can show.
        val polygon = ReliefPolygon().apply { setRoundRect(0f, 0f, 52f, 52f, 26f) }
        for (i in 0 until polygon.count) assertFalse(Relief.wallVisible(polygon.nx[i], polygon.ny[i], Offset.Zero))
    }

    @Test
    fun `the top moves away from the eye and the walls facing the eye show`() {
        val top = Relief.topOffset(0.2f, 0f, 9f, ref, max)
        assertTrue(top.x < 0f)
        assertEquals(0f, top.y, 0f)
        assertTrue("right wall faces an eye on the right", Relief.wallVisible(1f, 0f, top))
        assertFalse("left wall is hidden", Relief.wallVisible(-1f, 0f, top))

        val fromBelow = Relief.topOffset(0f, 0.2f, 9f, ref, max)
        assertTrue(fromBelow.y < 0f)
        assertTrue(Relief.wallVisible(0f, 1f, fromBelow))
        assertFalse(Relief.wallVisible(0f, -1f, fromBelow))
    }

    @Test
    fun `a normal tilt shows 10 to 14 dp of wall on a key`() {
        // About 19 degrees.
        val top = Relief.topOffset(0.35f, 0f, 9f, ref, max)
        assertTrue("${top.x}", -top.x in 10f..14f)
    }

    @Test
    fun `the easing is eased, not linear, and saturates`() {
        assertEquals(0f, Relief.ease(0f), 0f)
        var last = 0f
        var lastStep = Float.MAX_VALUE
        for (i in 1..40) {
            val e = Relief.ease(i * 0.025f)
            val step = e - last
            assertTrue("rises at $i", step > 0f)
            assertTrue("slows down at $i", step < lastStep)
            assertTrue(e < 1f)
            last = e
            lastStep = step
        }
        assertTrue("near its reach at a hard tilt", Relief.ease(1f) > 0.95f)
    }

    @Test
    fun `deeper slabs move further, in proportion, up to the reference`() {
        val shallow = Relief.topOffset(0.1f, 0f, 3f, ref, max)
        val deep = Relief.topOffset(0.1f, 0f, 6f, ref, max)
        assertEquals(2f, deep.x / shallow.x, 0.01f)
        assertEquals(Relief.topOffset(0.3f, 0f, ref, ref, max), Relief.topOffset(0.3f, 0f, ref * 2f, ref, max))
    }

    @Test
    fun `offsets are clamped, keeping their direction`() {
        for (tan in listOf(0.5f, 1f, 5f, 100f)) {
            val top = Relief.topOffset(tan, tan, 40f, ref, max)
            assertTrue(sqrt(top.x * top.x + top.y * top.y) <= max + 1e-4f)
            assertEquals(top.x, top.y, 1e-5f)
            assertTrue(top.x < 0f)
        }
    }

    @Test
    fun `the resting eye already shows walls`() {
        val top = Relief.topOffset(HeadTracker.REST_EYE_X, HeadTracker.REST_EYE_Y, 9f, ref, max)
        assertTrue("${top.y}", -top.y > 4f)
        assertTrue(Relief.wallVisible(0f, 1f, top))
    }

    @Test
    fun `the top face is a touch larger, never more than 6 percent`() {
        assertTrue(Relief.closeness(9f, ref) in 1.03f..1.06f)
        assertTrue(Relief.closeness(40f, ref) <= 1.06f)
        // With no tilt the larger top hides every wall (it overhangs them all).
        val polygon = ReliefPolygon().apply { setRoundRect(0f, 0f, 52f, 52f, 26f) }
        val k = Relief.closeness(9f, ref) - 1f
        for (i in 0 until polygon.count) {
            val j = (i + 1) % polygon.count
            val growth = Offset(((polygon.xs[i] + polygon.xs[j]) / 2f - 26f) * k, ((polygon.ys[i] + polygon.ys[j]) / 2f - 26f) * k)
            assertFalse(Relief.wallVisible(polygon.nx[i], polygon.ny[i], growth))
        }
    }

    @Test
    fun `the cast shadow falls away from the light and slides opposite the top`() {
        val still = Relief.castShadow(9f, Offset.Zero)
        assertTrue(still.x > 0f && still.y > 0f)
        val top = Relief.topOffset(0.3f, 0.1f, 9f, ref, max)
        val moved = Relief.castShadow(9f, top)
        val shift = moved - still
        assertTrue("opposite the top", shift.x * top.x + shift.y * top.y < 0f)
        assertTrue(Relief.shadowSpread(9f) > 0f)
    }

    @Test
    fun `a press goes down to a thin lip and a bounce lifts only a little`() {
        assertEquals(9f, Relief.pressedDepth(9f, 0f), 0f)
        assertEquals(9f * Relief.LIP, Relief.pressedDepth(9f, 1f), 1e-5f)
        assertEquals(9f * Relief.LIP, Relief.pressedDepth(9f, 3f), 1e-5f)
        val bounce = Relief.pressedDepth(9f, -1f)
        assertTrue(bounce > 9f && bounce <= 9f * (1f + Relief.OVERSHOOT_LIMIT))
        // Pressed, a hard tilt barely moves the top.
        val down = Relief.topOffset(1f, 0f, Relief.pressedDepth(9f, 1f), ref, max)
        assertTrue(abs(down.x) < 2f)
    }

    @Test
    fun `the gleam sits on the lit edge and slides towards the eye`() {
        val still = Relief.highlightDirection(Offset.Zero, max)
        assertTrue(Relief.highlight(-0.6f, -0.8f, still) > Relief.highlight(0.6f, 0.8f, still))
        assertEquals(0f, Relief.highlight(0.6f, 0.8f, still), 0f)
        // Eye to the right: the top moves left and the gleam turns towards the right.
        val tilted = Relief.highlightDirection(Offset(-max, 0f), max)
        assertTrue(tilted.x > still.x)
    }

    @Test
    fun `dark keys lift their walls, the lit ones most, by light only`() {
        assertTrue(Relief.wallLift(-0.6f, -0.8f, dark = true) > Relief.wallLift(0.6f, 0.8f, dark = true))
        assertTrue(Relief.wallLift(0.6f, 0.8f, dark = true) > 0f)
        assertTrue(Relief.wallLift(-0.6f, -0.8f, dark = true) > Relief.wallLift(-0.6f, -0.8f, dark = false))
        assertEquals(0f, Relief.wallLift(0.6f, 0.8f, dark = false), 0f)
    }

    @Test
    fun `edges know their quarter`() {
        val polygon = ReliefPolygon(8).apply { setRoundRect(0f, 0f, 100f, 100f, 50f) }
        val seen = IntArray(4)
        for (i in 0 until polygon.count) seen[polygon.quarter(i)]++
        for (q in 0..3) assertEquals(8, seen[q])
    }

    @Test
    fun `a circle's normals point outwards and close the loop`() {
        val polygon = ReliefPolygon(8).apply { setRoundRect(10f, 20f, 52f, 52f, 26f) }
        assertEquals(32, polygon.count)
        for (i in 0 until polygon.count) {
            val j = (i + 1) % polygon.count
            val mx = (polygon.xs[i] + polygon.xs[j]) / 2f - 36f
            val my = (polygon.ys[i] + polygon.ys[j]) / 2f - 46f
            assertTrue("edge $i faces out", polygon.nx[i] * mx + polygon.ny[i] * my > 0f)
            assertEquals(1f, sqrt(polygon.nx[i] * polygon.nx[i] + polygon.ny[i] * polygon.ny[i]), 1e-4f)
        }
    }

    @Test
    fun `a square corner gives four points`() {
        val polygon = ReliefPolygon().apply { setRoundRect(0f, 0f, 80f, 64f, 0f) }
        assertEquals(4, polygon.count)
    }

    @Test
    fun `walls facing the light are brighter, none brighter than the top`() {
        val lit = Relief.wallShade(-1f, 0f)
        val dark = Relief.wallShade(1f, 0f)
        assertTrue(lit > dark)
        assertEquals(Relief.AMBIENT, dark, 1e-6f)
        for (deg in 0 until 360 step 15) {
            val a = Math.toRadians(deg.toDouble())
            assertTrue(Relief.wallShade(cos(a).toFloat(), sin(a).toFloat()) <= Relief.TOP_SHADE + 0.02f)
        }
    }

    @Test
    fun `the tracker starts at the resting eye`() {
        val tracker = HeadTracker()
        tracker.update(about(0f, 1f, 0f, 30f), 0L)
        assertEquals(HeadTracker.REST_EYE_X, tracker.eyeX, 1e-4f)
        assertEquals(HeadTracker.REST_EYE_Y, tracker.eyeY, 1e-4f)
    }

    @Test
    fun `turning the right edge away puts the eye on the left`() {
        val tracker = HeadTracker(restX = 0f, restY = 0f)
        tracker.update(identity(), 0L)
        tracker.update(about(0f, 1f, 0f, 20f), 20_000_000L)
        assertTrue(tracker.eyeX < -0.3f)
        assertEquals(0f, tracker.eyeY, 1e-3f)
    }

    @Test
    fun `tilting the top edge towards you puts the eye above`() {
        val tracker = HeadTracker(restX = 0f, restY = 0f)
        tracker.update(identity(), 0L)
        tracker.update(about(1f, 0f, 0f, 20f), 20_000_000L)
        assertTrue(tracker.eyeY < -0.3f)
        assertEquals(0f, tracker.eyeX, 1e-3f)
    }

    @Test
    fun `a held pose settles back to rest`() {
        val tracker = HeadTracker(restX = 0f, restY = 0f, recentreSeconds = 2f)
        tracker.update(identity(), 0L)
        val tilted = about(0f, 1f, 0f, 20f)
        var t = 0L
        repeat(30 * 20) {
            t += 33_000_000L
            tracker.update(tilted, t)
        }
        assertEquals(0f, tracker.eyeX, 0.01f)
    }

    private fun identity() = floatArrayOf(0f, 0f, 0f, 1f)

    private fun about(x: Float, y: Float, z: Float, degrees: Float): FloatArray {
        val half = Math.toRadians(degrees.toDouble()) / 2
        val s = sin(half).toFloat()
        return floatArrayOf(x * s, y * s, z * s, cos(half).toFloat())
    }
}
