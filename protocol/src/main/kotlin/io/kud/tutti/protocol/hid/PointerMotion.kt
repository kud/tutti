package io.kud.tutti.protocol.hid

import kotlin.math.abs
import kotlin.math.sign

/** One relative mouse step, already inside the report's int8 range. */
data class MouseStep(val dx: Int, val dy: Int, val wheel: Int = 0)

/**
 * Turns fractional motion into whole int8 report steps. Fractions carry over to the next call, so
 * a slow drag still moves the cursor; a large motion is split into several reports of at most 127.
 */
class MotionAccumulator {
    private var x = 0.0
    private var y = 0.0
    private var wheel = 0.0

    fun add(dx: Double, dy: Double, dWheel: Double = 0.0): List<MouseStep> {
        x += dx
        y += dy
        wheel += dWheel
        var ix = x.toInt()
        var iy = y.toInt()
        var iw = wheel.toInt()
        x -= ix
        y -= iy
        wheel -= iw
        val steps = mutableListOf<MouseStep>()
        while (ix != 0 || iy != 0 || iw != 0) {
            val sx = ix.coerceIn(-127, 127)
            val sy = iy.coerceIn(-127, 127)
            val sw = iw.coerceIn(-127, 127)
            steps += MouseStep(sx, sy, sw)
            ix -= sx
            iy -= sy
            iw -= sw
        }
        return steps
    }

    fun reset() {
        x = 0.0
        y = 0.0
        wheel = 0.0
    }
}

/**
 * The touchpad in Bluetooth mode: finger pixels to cursor units, with a gentle acceleration so a
 * flick crosses the TV and a slow drag can land on a small target. Two fingers scroll: one wheel
 * detent per [SCROLL_PX] of travel, natural direction (content follows the fingers).
 */
class TouchpadMotion(var sensitivity: Double = 1.0) {
    private val accumulator = MotionAccumulator()

    fun move(dxPx: Double, dyPx: Double, dtMs: Double): List<MouseStep> {
        val speed = if (dtMs > 0) kotlin.math.hypot(dxPx, dyPx) / dtMs else 0.0 // px per ms
        val gain = sensitivity * BASE_GAIN * (1.0 + ACCEL * (speed - ACCEL_FROM).coerceAtLeast(0.0)).coerceAtMost(MAX_ACCEL)
        return accumulator.add(dxPx * gain, dyPx * gain)
    }

    fun scroll(dyPx: Double): List<MouseStep> = accumulator.add(0.0, 0.0, dyPx / SCROLL_PX)

    fun reset() = accumulator.reset()

    companion object {
        const val BASE_GAIN = 1.2
        const val ACCEL = 0.8
        const val ACCEL_FROM = 0.4
        const val MAX_ACCEL = 3.0
        const val SCROLL_PX = 48.0
    }
}

/**
 * The air-mouse pointer: the phone's gyroscope turned into cursor motion, as LG's Magic Remote does.
 *
 * Input is the angular velocity from Android's `TYPE_GYROSCOPE` (rad/s, device axes) and the time
 * since the last sample. The phone is held like a remote, top towards the TV: turning it left or
 * right is a rotation about the screen's normal (z), tilting it up or down a rotation about x.
 * Which sign moves the cursor which way is checked on a device, so both are [invertX]/[invertY].
 *
 * Per sample: a dead zone drops hand tremor and gyro drift, an exponential moving average smooths
 * what is left, the gain turns radians into cursor units, and [MotionAccumulator] keeps fractions.
 * The clutch ([hold]) freezes the cursor while the phone is re-aimed: nothing moves, and the filter
 * starts from rest on release so the cursor doesn't jump.
 */
class AirPointer(
    var sensitivity: Double = 1.0,
    var invertX: Boolean = false,
    var invertY: Boolean = false,
) {
    private val accumulator = MotionAccumulator()
    private var smoothX = 0.0
    private var smoothY = 0.0

    var held: Boolean = false
        private set

    fun hold() {
        held = true
        rest()
    }

    fun release() {
        held = false
        rest()
    }

    /** [wx], [wz]: rad/s about the device's x and z axes; [dtSeconds] since the last sample. */
    fun sample(wx: Double, wz: Double, dtSeconds: Double): List<MouseStep> {
        if (held || dtSeconds <= 0.0 || dtSeconds > MAX_DT || wx.isNaN() || wz.isNaN()) return emptyList()
        val yaw = deadZone(-wz)
        val pitch = deadZone(-wx)
        smoothX += SMOOTHING * (yaw - smoothX)
        smoothY += SMOOTHING * (pitch - smoothY)
        val gain = BASE_GAIN * sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) * dtSeconds
        val dx = smoothX * gain * (if (invertX) -1 else 1)
        val dy = smoothY * gain * (if (invertY) -1 else 1)
        return accumulator.add(dx, dy)
    }

    private fun rest() {
        smoothX = 0.0
        smoothY = 0.0
        accumulator.reset()
    }

    companion object {
        /** rad/s below which a hand at rest is taken as still. */
        const val DEAD_ZONE = 0.03

        /** EMA weight of the new sample; ~3 samples of lag at 100 Hz. */
        const val SMOOTHING = 0.45

        /** Cursor units per radian at sensitivity 1: a ~35° sweep crosses a 1920-unit screen. */
        const val BASE_GAIN = 3200.0

        const val MIN_SENSITIVITY = 0.25
        const val MAX_SENSITIVITY = 3.0

        /** A gap longer than this (a paused sensor, a dropped batch) moves nothing. */
        const val MAX_DT = 0.25

        /** Subtracts the dead zone rather than cutting at it, so motion starts from zero, not a step. */
        fun deadZone(w: Double): Double = if (abs(w) <= DEAD_ZONE) 0.0 else w - DEAD_ZONE * sign(w)
    }
}
