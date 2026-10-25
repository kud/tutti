package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.atv.V2Key
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The touchpad's fling (Iris's gesture audit, 2026-10-25, item 1): a fast swipe keeps stepping
 * after the finger lifts and slows down, for long lists and on-screen keyboards. App-side only:
 * each step is a plain short D-pad key, as a swipe's are, so nothing new goes to the TV and a lost
 * packet costs one step, never a runaway scroll.
 *
 * Only the speed above [MIN_VELOCITY_DP] carries, so a slow drag lifts exactly as before (no
 * fling at all) and a release just past the threshold adds a step or two rather than jumping to
 * eight. The carry decays exponentially (time constant [TAU_MS]): distance after t is
 * `surplus * tau * (1 - e^(-t/tau))`, and a step goes out each time it crosses another [stepDp].
 * Steps are never closer than [MIN_GAP_MS] (the TV's own list animation can't follow faster) and
 * never more than [MAX_STEPS]. A new touch-down stops it.
 */
internal object PadFling {
    /** dp/s along the swipe's axis; slower releases don't fling. A deliberate drag sits well under it. */
    const val MIN_VELOCITY_DP = 1_200f
    const val TAU_MS = 300f
    const val MIN_GAP_MS = 45L
    const val MAX_STEPS = 30

    /** When each carried step goes out, in ms after the release, for a release at [velocityDp] dp/s. */
    fun schedule(velocityDp: Float, stepDp: Float): List<Long> {
        val surplus = abs(velocityDp) - MIN_VELOCITY_DP
        if (surplus <= 0f || stepDp <= 0f) return emptyList()
        val reach = surplus * TAU_MS / 1_000f
        val steps = min((reach / stepDp).toInt(), MAX_STEPS)
        var last = 0L
        return (1..steps).map { k ->
            val fraction = (k * stepDp / reach).coerceAtMost(0.999f)
            val at = (-TAU_MS * ln(1f - fraction)).toLong()
            max(at, last + MIN_GAP_MS).also { last = it }
        }
    }

    /** The key a release velocity (px/s, screen axes) carries: its dominant axis, as a swipe's steps are. */
    fun direction(vx: Float, vy: Float): Int =
        if (abs(vx) >= abs(vy)) {
            if (vx > 0) V2Key.DPAD_RIGHT else V2Key.DPAD_LEFT
        } else {
            if (vy > 0) V2Key.DPAD_DOWN else V2Key.DPAD_UP
        }
}

/** What a two-finger drag on the pad sets: the bar's volume (vertical) or the playhead (horizontal). */
internal enum class TwoFingerAxis { VOLUME, SKIP }

/** One step of a two-finger drag: [delta] +1 is louder or forward, -1 quieter or back. */
internal data class TwoFingerStep(val axis: TwoFingerAxis, val delta: Int)

/**
 * A two-finger drag, in both pad modes (audit item 5). It locks to one axis once the fingers'
 * average travel passes [slop], so a slightly diagonal volume drag never skips and the reverse:
 * vertical is the bar's volume, one step per [volumeStep] px (up is louder, as before); horizontal
 * is skip back or forward, one per [skipStep] px. Travel before the lock counts, so the first step
 * comes where it did when two fingers only meant volume. Pure; px in, steps out.
 */
internal class TwoFingerTrack(private val slop: Float, private val volumeStep: Float, private val skipStep: Float) {
    var axis: TwoFingerAxis? = null
        private set
    private var travelX = 0f
    private var travelY = 0f
    private var accumulated = 0f

    /** True once the drag picked an axis: the fingers lifting then is not a two-finger tap. */
    val moved: Boolean get() = axis != null

    fun move(dx: Float, dy: Float): List<TwoFingerStep> {
        travelX += dx
        travelY += dy
        val locked = axis ?: run {
            if (abs(travelX) <= slop && abs(travelY) <= slop) return emptyList()
            val picked = if (abs(travelX) > abs(travelY)) TwoFingerAxis.SKIP else TwoFingerAxis.VOLUME
            axis = picked
            // What was already travelled along the chosen axis counts; the move below adds this event's share.
            accumulated = if (picked == TwoFingerAxis.SKIP) travelX - dx else -(travelY - dy)
            picked
        }
        // Along the axis, positive is forward (right) or louder (up).
        accumulated += if (locked == TwoFingerAxis.SKIP) dx else -dy
        val step = if (locked == TwoFingerAxis.SKIP) skipStep else volumeStep
        val out = mutableListOf<TwoFingerStep>()
        while (accumulated >= step) {
            out += TwoFingerStep(locked, 1)
            accumulated -= step
        }
        while (accumulated <= -step) {
            out += TwoFingerStep(locked, -1)
            accumulated += step
        }
        return out
    }
}

/**
 * The keys a skip sends. MEDIA_SKIP_FORWARD / BACKWARD (272 / 273) are the 10 s jumps most
 * Google TV players honour; FAST_FORWARD / REWIND are Iris's fallback for a player that ignores
 * them. The TV never says what a key did, so the fallback can't be chosen at run time: if the
 * check on the BRAVIA shows the skip keys do nothing in the apps Erwann uses, flip [SKIP_KEYS_FALLBACK].
 */
internal fun skipKey(delta: Int, fallback: Boolean = SKIP_KEYS_FALLBACK): Int = when {
    delta > 0 && !fallback -> V2Key.MEDIA_SKIP_FORWARD
    delta > 0 -> V2Key.MEDIA_FAST_FORWARD
    !fallback -> V2Key.MEDIA_SKIP_BACKWARD
    else -> V2Key.MEDIA_REWIND
}

internal const val SKIP_KEYS_FALLBACK = false

