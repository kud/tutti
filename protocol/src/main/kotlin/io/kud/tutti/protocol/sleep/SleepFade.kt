package io.kud.tutti.protocol.sleep

import kotlin.math.roundToInt

/**
 * The gentle switch-off timer tutti owns (Iris's top 10, item 5), as plain timing: no clock, no
 * Android. The app passes `now` in wall-clock milliseconds and acts on what comes back.
 *
 * Shape: nothing happens until [FADE_MS] before the end; then the volume steps down once every
 * [STEP_MS], linearly from where it stood to near silence, and at the end the TV and the bar turn
 * off. A volume raised by hand during the fade is never fought: the fade only ever lowers.
 */
object SleepFade {
    /** The choices offered, in minutes. Kept short on purpose: no "end of programme" guessing. */
    val DURATIONS_MIN: List<Int> = listOf(15, 30, 45, 60, 90)

    const val FADE_MS: Long = 120_000L
    const val STEP_MS: Long = 10_000L
    const val STEPS: Int = (FADE_MS / STEP_MS).toInt()

    fun endsAt(now: Long, minutes: Int): Long = now + minutes * 60_000L

    fun fadeStartsAt(endsAt: Long): Long = endsAt - FADE_MS

    fun phase(now: Long, endsAt: Long): Phase = when {
        now >= endsAt -> Phase.DUE
        now >= fadeStartsAt(endsAt) -> Phase.FADING
        else -> Phase.WAITING
    }

    /** Whole minutes left, rounded up, for "23 min left"; 0 once due. */
    fun minutesLeft(now: Long, endsAt: Long): Int =
        if (now >= endsAt) 0 else ((endsAt - now + 59_999L) / 60_000L).toInt()

    /** How many fade steps have been taken by [now] (0 before the fade, [STEPS] at the end). */
    fun stepsTaken(now: Long, endsAt: Long): Int = when (phase(now, endsAt)) {
        Phase.WAITING -> 0
        Phase.DUE -> STEPS
        Phase.FADING -> 1 + ((now - fadeStartsAt(endsAt)) / STEP_MS).toInt().coerceAtMost(STEPS - 1)
    }

    /**
     * The level the fade asks for at [now], from [startVolume] (the level when the fade began).
     * Each step takes an equal share; the last step before the end leaves a whisper, never 0, so the
     * room isn't silent with the TV still lit. Null before the fade: nothing to do.
     */
    fun volumeAt(now: Long, endsAt: Long, startVolume: Int): Int? {
        if (phase(now, endsAt) == Phase.WAITING) return null
        val taken = stepsTaken(now, endsAt)
        val left = startVolume * (STEPS - taken).toDouble() / STEPS
        return maxOf(if (startVolume > 0) 1 else 0, left.roundToInt())
    }

    /** What to send now: the fade's level only when it is lower than [current] (a hand on the rail wins). */
    fun nextLevel(now: Long, endsAt: Long, startVolume: Int, current: Int?): Int? {
        val wanted = volumeAt(now, endsAt, startVolume) ?: return null
        return if (current == null || wanted < current) wanted else null
    }

    /** The next instant the app must wake: the fade's start, the next step, or the end. Null once due. */
    fun nextWake(now: Long, endsAt: Long): Long? = when (phase(now, endsAt)) {
        Phase.WAITING -> fadeStartsAt(endsAt)
        Phase.FADING -> minOf(endsAt, fadeStartsAt(endsAt) + stepsTaken(now, endsAt) * STEP_MS)
        Phase.DUE -> null
    }

    /** Cancelled mid-fade: put back the level it started from, unless he has already gone above it. */
    fun restoreOnCancel(startVolume: Int?, current: Int?): Int? =
        if (startVolume != null && (current == null || current < startVolume)) startVolume else null

    enum class Phase { WAITING, FADING, DUE }
}
