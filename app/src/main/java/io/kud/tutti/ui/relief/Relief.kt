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
 * The geometry of "3D relief" (opt-in): every key is a slab standing a few dp above the remote's
 * surface, seen from an eye that moves as the phone tilts. Since dev.79 it aims for a convincing
 * pop-out rather than a faithful one (Erwann: "ça ressort de l'écran grâce à des tricks"): deep
 * walls, an eased parallax that reaches 10-14 dp at a normal tilt, a top face a touch larger as if
 * nearer, a blurred cast shadow that slides opposite the top, a contact shadow, a lit lip and a
 * specular edge. Pure maths, no Android and no drawing, so it is unit tested and the mock-up
 * renderer uses it too.
 *
 * Coordinates are the screen's: x right, y down, z out of the glass towards the viewer. The eye is
 * a direction, given as its tangents ([eyeX], [eyeY]) = lateral offset over viewing distance; the
 * eye straight above the glass is (0, 0).
 */
object Relief {
    /**
     * Parallax easing, 0..1 for a tilt tangent [t] >= 0: linear-ish near zero, saturating, so a
     * normal tilt already shows most of the walls and a hard one never runs away.
     */
    fun ease(t: Float): Float = if (t <= 0f) 0f else 1f - exp(-t / SATURATION)

    /**
     * How far the top face of a slab [depth] high lands from its base, from an eye at tangents
     * ([eyeX], [eyeY]): away from the eye, so the walls facing it show. A slab [refDepth] deep (or
     * deeper) reaches [maxOffset] at the limit; shallower ones in proportion. Never longer than
     * [maxOffset], whatever the inputs.
     */
    fun topOffset(eyeX: Float, eyeY: Float, depth: Float, refDepth: Float, maxOffset: Float): Offset {
        if (depth <= 0f || refDepth <= 0f) return Offset.Zero
        val t = sqrt(eyeX * eyeX + eyeY * eyeY)
        // No tilt is exactly no offset (not -0, which packs to a different Offset).
        if (t == 0f) return Offset.Zero
        val length = (maxOffset * min(1f, depth / refDepth) * ease(t)).coerceAtMost(maxOffset)
        val k = length / t
        return Offset(-eyeX * k, -eyeY * k)
    }

    /** The top face's scale about its centre: nearer the eye, a touch larger. 1 when flat. */
    fun closeness(depth: Float, refDepth: Float): Float =
        if (refDepth <= 0f) 1f else 1f + CLOSENESS * (depth / refDepth).coerceIn(0f, 1f)

    /**
     * A wall with outward normal ([nx], [ny]) shows when its top edge has moved inwards from its
     * base edge by [displacement] (the top's offset plus the growth from [closeness] there).
     */
    fun wallVisible(nx: Float, ny: Float, displacement: Offset): Boolean =
        nx * displacement.x + ny * displacement.y < -VISIBLE_EPSILON

    /** How much a wall with outward normal ([nx], [ny]) faces the light, 0..1. */
    fun lambert(nx: Float, ny: Float): Float = max(0f, (nx * LIGHT_X + ny * LIGHT_Y) / LIGHT_XY)

    /**
     * The brightness of a wall with outward normal ([nx], [ny], 0) under the one fixed light,
     * Lambert plus ambient, 0..1. The top face (normal (0, 0, 1)) is [TOP_SHADE].
     */
    fun wallShade(nx: Float, ny: Float): Float = AMBIENT + (1f - AMBIENT) * max(0f, nx * LIGHT_X + ny * LIGHT_Y)

    /** The share of the key's own colour a wall keeps under [brightness] (from [wallShade]). */
    fun wallFactor(brightness: Float): Float = WALL_FLOOR + (1f - WALL_FLOOR) * (brightness / TOP_SHADE).coerceIn(0f, 1f)

    /**
     * How far a wall's colour is mixed towards white, 0..1: on a dark key (dark theme) the walls
     * would vanish into near-black, so every wall is lifted a little and the lit ones a lot more.
     * Light, never hue: the mix is towards white.
     */
    fun wallLift(nx: Float, ny: Float, dark: Boolean): Float =
        if (dark) DARK_LIFT_BASE + DARK_LIFT_LIT * lambert(nx, ny) else LIGHT_LIFT_LIT * lambert(nx, ny)

    /** A key colour of this relative luminance or less is treated as dark. */
    const val DARK_LUMINANCE = 0.2f

    /**
     * Where the cast shadow sits: away from the light by [depth] x [SHADOW_REACH], and moved
     * against the top face's offset [top], so the key reads as hovering over it.
     */
    fun castShadow(depth: Float, top: Offset): Offset = Offset(
        -LIGHT_X / LIGHT_XY * depth * SHADOW_REACH - top.x * SHADOW_OPPOSE,
        -LIGHT_Y / LIGHT_XY * depth * SHADOW_REACH - top.y * SHADOW_OPPOSE,
    )

    /** How far the cast shadow's soft edge spreads beyond the key, for a slab [depth] high. */
    fun shadowSpread(depth: Float): Float = depth * SHADOW_BLUR

    /**
     * A slab [rest] deep pressed in by [sink]: 1 is fully down, leaving a thin [LIP]; a release's
     * overshoot (negative [sink]) lifts it a little above rest, at most [OVERSHOOT_LIMIT].
     */
    fun pressedDepth(rest: Float, sink: Float): Float = rest * (1f - sink.coerceIn(-OVERSHOOT_LIMIT, 1f) * (1f - LIP))

    /**
     * The direction, in the glass, of the top face's specular edge: towards the light, slid
     * towards the eye (the eye is opposite the top's offset [top]), so the gleam moves with tilt.
     */
    fun highlightDirection(top: Offset, maxOffset: Float): Offset {
        val slide = if (maxOffset > 0f) HIGHLIGHT_SLIDE / maxOffset else 0f
        val hx = LIGHT_X / LIGHT_XY - top.x * slide
        val hy = LIGHT_Y / LIGHT_XY - top.y * slide
        val length = sqrt(hx * hx + hy * hy)
        return if (length == 0f) Offset(LIGHT_X / LIGHT_XY, LIGHT_Y / LIGHT_XY) else Offset(hx / length, hy / length)
    }

    /** How strongly the top-face edge with outward normal ([nx], [ny]) gleams, 0..1. */
    fun highlight(nx: Float, ny: Float, direction: Offset): Float {
        val d = max(0f, nx * direction.x + ny * direction.y)
        val d2 = d * d
        return d2 * d2
    }

    /** The light, a unit vector towards it: from the top left and in front of the glass. */
    const val LIGHT_X = -0.42f
    const val LIGHT_Y = -0.58f
    const val LIGHT_Z = 0.70f
    private val LIGHT_XY = sqrt(LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y)
    const val AMBIENT = 0.30f
    val TOP_SHADE: Float = AMBIENT + (1f - AMBIENT) * LIGHT_Z

    /** Tilt tangent at which the parallax reaches 63 % of its reach: about 11°. */
    const val SATURATION = 0.2f
    /** The top face of the deepest slab grows by this much: 5 %. */
    const val CLOSENESS = 0.05f
    /** The darkest wall keeps this much of the key's own colour. */
    const val WALL_FLOOR = 0.35f
    const val DARK_LIFT_BASE = 0.08f
    const val DARK_LIFT_LIT = 0.26f
    const val LIGHT_LIFT_LIT = 0.10f
    const val SHADOW_REACH = 0.7f
    const val SHADOW_OPPOSE = 0.35f
    const val SHADOW_BLUR = 0.9f
    /** What a fully pressed slab keeps of its depth: a thin lip. */
    const val LIP = 0.12f
    const val OVERSHOOT_LIMIT = 0.25f
    /** How far, in units of the top's maximum offset, the gleam slides towards the eye. */
    const val HIGHLIGHT_SLIDE = 0.9f
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
    var centreX = 0f
        private set
    var centreY = 0f
        private set

    /** Which quarter edge [i]'s middle lies in, seen from the centre: 0 right, 1 down, 2 left, 3 up. */
    fun quarter(i: Int): Int {
        val j = if (i + 1 == count) 0 else i + 1
        val dx = (xs[i] + xs[j]) / 2f - centreX
        val dy = (ys[i] + ys[j]) / 2f - centreY
        return if (abs(dx) >= abs(dy)) (if (dx >= 0f) 0 else 2) else (if (dy >= 0f) 1 else 3)
    }

    /** A rounded rectangle; [radius] is clamped to half the shorter side (a circle at most). */
    fun setRoundRect(left: Float, top: Float, width: Float, height: Float, radius: Float) {
        val r = radius.coerceIn(0f, min(width, height) / 2f)
        centreX = left + width / 2f
        centreY = top + height / 2f
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
