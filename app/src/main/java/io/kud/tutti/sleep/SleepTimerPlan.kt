package io.kud.tutti.sleep

import io.kud.tutti.protocol.sleep.SleepFade

/**
 * A running gentle switch-off timer (Iris's top 10, item 5; docs/architecture.md, "Gentle
 * switch-off timer"). [exact] is false when the phone refused exact alarms, so the UI says it may
 * switch off a few minutes late. [startVolume] is the bar's level when the fade began, null before.
 */
data class SleepTimerState(
    val endsAt: Long,
    val exact: Boolean,
    val startVolume: Int? = null,
) {
    fun fading(now: Long): Boolean = SleepFade.phase(now, endsAt) != SleepFade.Phase.WAITING
}

/** What the timer does at one wake-up. */
sealed interface SleepStep {
    /** Before the fade: nothing to send, sleep until [SleepFade.fadeStartsAt]. */
    data object Wait : SleepStep

    /** Lower the bar to [level]; [startVolume] is the level the fade runs from. */
    data class Lower(val level: Int, val startVolume: Int) : SleepStep

    /**
     * Fading, but nothing to send: a hand on the rail kept it at or below the fade, or the bar is
     * down (its controls go down with its link, so the fade waits for the next step rather than
     * guessing). [startVolume] stays null until the bar has told its level once.
     */
    data class Hold(val startVolume: Int?) : SleepStep

    /** The end: turn off the TV and the bar. */
    data object SwitchOff : SleepStep
}

/**
 * The timer's down rules over [SleepFade]: the fade only ever lowers, only from a level the bar
 * reported, and only while the bar's link is up. The first level it hears becomes the start.
 */
fun sleepStep(now: Long, endsAt: Long, startVolume: Int?, current: Int?, barOnline: Boolean): SleepStep =
    when (SleepFade.phase(now, endsAt)) {
        SleepFade.Phase.WAITING -> SleepStep.Wait
        SleepFade.Phase.DUE -> SleepStep.SwitchOff
        SleepFade.Phase.FADING -> {
            if (!barOnline || current == null) {
                SleepStep.Hold(startVolume)
            } else {
                val start = startVolume ?: current
                SleepFade.nextLevel(now, endsAt, start, current)?.let { SleepStep.Lower(it, start) } ?: SleepStep.Hold(start)
            }
        }
    }

/**
 * The level to put back on a cancel, or null to leave the bar alone: only mid-fade, only with the
 * bar's link up, and never above where the fade began ([SleepFade.restoreOnCancel]). The caller
 * still sends it through the volume ceiling and the high-volume guard.
 */
fun sleepRestore(timer: SleepTimerState?, now: Long, current: Int?, barOnline: Boolean): Int? {
    if (timer == null || !timer.fading(now) || !barOnline) return null
    return SleepFade.restoreOnCancel(timer.startVolume, current)
}

/**
 * The Power sheet's switch-off timer row: offered once a device is set up; down, with the reason
 * in words, when neither is on (nothing to switch off). A timer already running is always shown,
 * so it can be cancelled whatever the links say.
 */
data class GentleOffRow(val running: SleepTimerState?, val reason: String?)

fun gentleOffRow(hasDevice: Boolean, powered: Boolean?, running: SleepTimerState?, offReason: String): GentleOffRow? = when {
    running != null -> GentleOffRow(running, null)
    !hasDevice -> null
    powered == false -> GentleOffRow(null, offReason)
    else -> GentleOffRow(null, null)
}
