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

    private val distance = 1900f
    private val max = 4f

    @Test
    fun `an eye straight above moves nothing`() {
        assertEquals(Offset.Zero, Relief.topOffset(0f, 0f, 3f, distance, max))
    }

    @Test
    fun `depth zero collapses to flat whatever the tilt`() {
        assertEquals(Offset.Zero, Relief.topOffset(0.6f, -0.4f, 0f, distance, max))
        // And so no wall can show.
        val polygon = ReliefPolygon().apply { setRoundRect(0f, 0f, 52f, 52f, 26f) }
        for (i in 0 until polygon.count) assertFalse(Relief.wallVisible(polygon.nx[i], polygon.ny[i], Offset.Zero))
    }

    @Test
    fun `the top moves away from the eye and the walls facing the eye show`() {
        val top = Relief.topOffset(0.2f, 0f, 3f, distance, max)
        assertTrue(top.x < 0f)
        assertEquals(0f, top.y, 0f)
        assertTrue("right wall faces an eye on the right", Relief.wallVisible(1f, 0f, top))
        assertFalse("left wall is hidden", Relief.wallVisible(-1f, 0f, top))

        val fromBelow = Relief.topOffset(0f, 0.2f, 3f, distance, max)
        assertTrue(fromBelow.y < 0f)
        assertTrue(Relief.wallVisible(0f, 1f, fromBelow))
        assertFalse(Relief.wallVisible(0f, -1f, fromBelow))
    }

    @Test
    fun `deeper slabs move further, in proportion`() {
        val shallow = Relief.topOffset(0.1f, 0f, 1f, distance, max)
        val deep = Relief.topOffset(0.1f, 0f, 2f, distance, max)
        assertTrue(abs(deep.x) > abs(shallow.x))
        assertEquals(2f, deep.x / shallow.x, 0.01f)
    }

    @Test
    fun `offsets are clamped to a few dp, keeping their direction`() {
        val top = Relief.topOffset(1f, 1f, 4f, distance, max)
        assertEquals(max, sqrt(top.x * top.x + top.y * top.y), 1e-4f)
        assertEquals(top.x, top.y, 1e-6f)
        assertTrue(top.x < 0f)
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
    fun `the contact shadow falls away from the light`() {
        val shadow = Relief.shadowOffset(3f)
        assertTrue(shadow.x > 0f && shadow.y > 0f)
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
