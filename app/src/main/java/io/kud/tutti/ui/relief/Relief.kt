package io.kud.tutti.ui.relief

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry of "3D relief" (prototype, opt-in): every key is a slab standing [depth] above the
 * remote's surface, seen from an eye that moves as the phone tilts (head-coupled perspective).
 * Pure maths, no Android and no drawing, so it is unit tested and the mock-up renderer uses it too.
 *
 * Coordinates are the screen's: x right, y down, z out of the glass towards the viewer. The eye is
 * a direction, given as its tangents ([eyeX], [eyeY]) = lateral offset over viewing distance; the
 * eye straight above the glass is (0, 0).
 */
object Relief {
    /**
     * How far the top face of a slab [depth] high lands from its base, projected onto the glass from
     * an eye at tangents ([eyeX], [eyeY]) and [distance] away (same unit as [depth]). A raised point
     * moves away from the eye, so the walls that face the eye come into view. [gain] exaggerates the
     * true parallax (a 3 dp slab tilted 20° moves only 1 dp for real); the result is clamped to
     * [maxOffset] in length, keeping its direction.
     */
    fun topOffset(eyeX: Float, eyeY: Float, depth: Float, distance: Float, maxOffset: Float, gain: Float = GAIN): Offset {
        if (depth <= 0f) return Offset.Zero
        val perspective = depth / (1f - (depth / distance).coerceAtMost(0.5f))
        val dx = -eyeX * perspective * gain
        val dy = -eyeY * perspective * gain
        // No tilt is exactly no offset (not -0, which packs to a different Offset).
        if (dx == 0f && dy == 0f) return Offset.Zero
        val length = sqrt(dx * dx + dy * dy)
        if (length <= maxOffset || length == 0f) return Offset(dx, dy)
        val k = maxOffset / length
        return Offset(dx * k, dy * k)
    }

    /** A wall with outward normal ([nx], [ny]) shows when the top has moved away from that side. */
    fun wallVisible(nx: Float, ny: Float, top: Offset): Boolean = nx * top.x + ny * top.y < -VISIBLE_EPSILON

    /**
     * The brightness of a wall with outward normal ([nx], [ny], 0) under the one fixed light,
     * Lambert plus ambient, 0..1. The top face (normal (0, 0, 1)) is [TOP_SHADE].
     */
    fun wallShade(nx: Float, ny: Float): Float = AMBIENT + (1f - AMBIENT) * max(0f, nx * LIGHT_X + ny * LIGHT_Y)

    /** Where the contact shadow falls: opposite the light, [depth] × [SHADOW_REACH] away. */
    fun shadowOffset(depth: Float): Offset {
        val length = sqrt(LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y)
        return Offset(-LIGHT_X / length * depth * SHADOW_REACH, -LIGHT_Y / length * depth * SHADOW_REACH)
    }

    /** The light, a unit vector towards it: from the top left and in front of the glass. */
    const val LIGHT_X = -0.42f
    const val LIGHT_Y = -0.58f
    const val LIGHT_Z = 0.70f
    const val AMBIENT = 0.30f
    val TOP_SHADE: Float = AMBIENT + (1f - AMBIENT) * LIGHT_Z

    /** True parallax is about a third of a dp at comfortable tilts; this makes it read. */
    const val GAIN = 2.5f
    const val SHADOW_REACH = 0.8f
    private const val VISIBLE_EPSILON = 1e-4f
}

/**
 * A convex outline as a closed polygon, clockwise on screen, with one outward normal per edge
 * (edge i runs from point i to point i + 1). Preallocated: [setRoundRect] rewrites it in place, so
 * it can be refreshed every frame (a key's corner morphs as it is pressed) without allocating.
 */
class ReliefPolygon(private val segmentsPerCorner: Int = 8) {
    val capacity = segmentsPerCorner * 4 + 4
    val xs = FloatArray(capacity)
    val ys = FloatArray(capacity)
    val nx = FloatArray(capacity)
    val ny = FloatArray(capacity)
    var count = 0
        private set

    /** A rounded rectangle; [radius] is clamped to half the shorter side (a circle at most). */
    fun setRoundRect(left: Float, top: Float, width: Float, height: Float, radius: Float) {
        val r = radius.coerceIn(0f, min(width, height) / 2f)
        count = 0
        // Corners clockwise from the top right, each swept 90°; angle 0 points right, y down.
        corner(left + width - r, top + r, r, -PI / 2)
        corner(left + width - r, top + height - r, r, 0.0)
        corner(left + r, top + height - r, r, PI / 2)
        corner(left + r, top + r, r, PI)
        // A full circle ends where it began: close on the first point instead of repeating it.
        if (count > 1 && abs(xs[count - 1] - xs[0]) < SAME && abs(ys[count - 1] - ys[0]) < SAME) count--
        for (i in 0 until count) {
            val j = if (i + 1 == count) 0 else i + 1
            val ex = xs[j] - xs[i]
            val ey = ys[j] - ys[i]
            val length = sqrt(ex * ex + ey * ey)
            // Clockwise on a y-down screen: the outward normal is the edge turned left in maths terms.
            if (length > 0f) {
                nx[i] = ey / length
                ny[i] = -ex / length
            } else {
                nx[i] = 0f
                ny[i] = 0f
            }
        }
    }

    private fun corner(cx: Float, cy: Float, r: Float, start: Double) {
        if (r == 0f) {
            add(cx, cy)
            return
        }
        for (s in 0..segmentsPerCorner) {
            val a = start + PI / 2 * s / segmentsPerCorner
            add(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }
    }

    private companion object {
        /** Points closer than this are one point (float noise from the trigonometry). */
        const val SAME = 1e-3f
    }

    private fun add(x: Float, y: Float) {
        // Consecutive corners of a full circle meet at one point: drop the duplicate.
        if (count > 0 && abs(xs[count - 1] - x) < SAME && abs(ys[count - 1] - y) < SAME) return
        if (count == capacity) return
        xs[count] = x
        ys[count] = y
        count++
    }
}

/**
 * Turns the game rotation vector (gyroscope and accelerometer, no magnetometer) into the eye's
 * tangents. The pose when tracking starts is the resting one, where the eye sits at [restX],
 * [restY]; the rest pose then drifts towards the current one with a [recentreSeconds] time
 * constant, so a phone laid down or held differently settles back to the resting look.
 * No allocation per sample.
 */
class HeadTracker(
    private val restX: Float = REST_EYE_X,
    private val restY: Float = REST_EYE_Y,
    private val recentreSeconds: Float = RECENTRE_SECONDS,
) {
    private val rest = FloatArray(4)
    private val now = FloatArray(4)
    private var started = false
    private var lastNanos = 0L

    var eyeX = restX
        private set
    var eyeY = restY
        private set

    /** [values] as the sensor gives them: x, y, z and, when present, w of the unit quaternion. */
    fun update(values: FloatArray, timestampNanos: Long) {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val w = if (values.size >= 4) values[3] else sqrt(max(0f, 1f - x * x - y * y - z * z))
        set(now, x, y, z, w)
        if (!started) {
            now.copyInto(rest)
            started = true
            lastNanos = timestampNanos
        } else {
            val dt = ((timestampNanos - lastNanos).coerceAtLeast(0L) / 1e9f).coerceAtMost(1f)
            lastNanos = timestampNanos
            nlerp(rest, now, 1f - exp(-dt / recentreSeconds))
        }
        eye()
    }

    private fun eye() {
        // The eye's direction in device coordinates at rest (device y is up, screen y is down).
        var ex = restX
        var ey = -restY
        var ez = 1f
        // Into the world with the rest pose, then back into the device with the current one.
        rotate(rest, ex, ey, ez, conjugate = false)
        ex = rx; ey = ry; ez = rz
        rotate(now, ex, ey, ez, conjugate = true)
        val depth = max(rz, MIN_EYE_Z)
        eyeX = (rx / depth).coerceIn(-MAX_TAN, MAX_TAN)
        eyeY = (-ry / depth).coerceIn(-MAX_TAN, MAX_TAN)
    }

    private var rx = 0f
    private var ry = 0f
    private var rz = 0f

    /** v' = q v q* (or q* v q), written out for a unit quaternion. */
    private fun rotate(q: FloatArray, vx: Float, vy: Float, vz: Float, conjugate: Boolean) {
        val s = if (conjugate) -1f else 1f
        val qx = q[0] * s
        val qy = q[1] * s
        val qz = q[2] * s
        val qw = q[3]
        // t = 2 (q.xyz × v); v' = v + w t + q.xyz × t
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        rx = vx + qw * tx + (qy * tz - qz * ty)
        ry = vy + qw * ty + (qz * tx - qx * tz)
        rz = vz + qw * tz + (qx * ty - qy * tx)
    }

    companion object {
        /**
         * At rest the eye sits a little towards the bottom of the screen, as a phone is held below
         * the face: the keys show a sliver of their lower walls before anything moves.
         */
        const val REST_EYE_X = 0f
        const val REST_EYE_Y = 0.12f
        const val RECENTRE_SECONDS = 4f
        private const val MIN_EYE_Z = 0.3f
        private const val MAX_TAN = 1f

        private fun set(q: FloatArray, x: Float, y: Float, z: Float, w: Float) {
            q[0] = x; q[1] = y; q[2] = z; q[3] = w
        }

        /** Moves [a] towards [b] by [t] along the shorter arc, normalised. */
        internal fun nlerp(a: FloatArray, b: FloatArray, t: Float) {
            val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
            val sign = if (dot < 0f) -1f else 1f
            var length = 0f
            for (i in 0..3) {
                a[i] = a[i] + (b[i] * sign - a[i]) * t
                length += a[i] * a[i]
            }
            val inv = 1f / sqrt(length)
            for (i in 0..3) a[i] *= inv
        }
    }
}
