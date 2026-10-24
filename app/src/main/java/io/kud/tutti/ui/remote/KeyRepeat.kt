package io.kud.tutti.ui.remote

import kotlin.math.roundToLong
import kotlinx.coroutines.delay

/**
 * Hold-to-repeat for a direction, the way the Google TV app and BRAVIA Connect do it: nothing
 * for [DELAY_MS], then a key every [INTERVAL_MS], easing down to [FASTEST_MS] over [RAMP_MS]
 * once the hold passes [ACCELERATE_FROM_MS]. The D-pad, the touchpad's resting finger and the
 * input view's arrow bar share it. Each repeat is a plain SHORT key through the controller,
 * never a held key on the TV, so a lost release can't leave the TV scrolling. Not animation:
 * reduced motion leaves it alone.
 */
internal object KeyRepeat {
    const val DELAY_MS = 400L
    const val INTERVAL_MS = 90L
    const val FASTEST_MS = 50L
    const val ACCELERATE_FROM_MS = 1_500L
    const val RAMP_MS = 1_000L

    /** The gap after a repeat sent [heldMs] into the hold. */
    fun interval(heldMs: Long): Long {
        if (heldMs <= ACCELERATE_FROM_MS) return INTERVAL_MS
        val t = ((heldMs - ACCELERATE_FROM_MS).toFloat() / RAMP_MS).coerceAtMost(1f)
        return (INTERVAL_MS - (INTERVAL_MS - FASTEST_MS) * t).roundToLong()
    }

    /** When the next repeat goes out, from the start of the hold: the first at [DELAY_MS]. */
    fun nextAt(previousAt: Long?): Long = if (previousAt == null) DELAY_MS else previousAt + interval(previousAt)

    /** Every repeat a hold of [heldMs] sends, by time; release before [DELAY_MS] sends none. */
    fun schedule(heldMs: Long): List<Long> = generateSequence(nextAt(null)) { nextAt(it) }.takeWhile { it <= heldMs }.toList()

    /** Calls [onRepeat] on [schedule] until cancelled (the finger lifts or moves). */
    suspend fun run(onRepeat: () -> Unit) {
        var at: Long? = null
        while (true) {
            val next = nextAt(at)
            delay(next - (at ?: 0L))
            at = next
            onRepeat()
        }
    }
}
