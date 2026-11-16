package io.kud.tutti.controller

/**
 * When the REST poll runs early, and when it asks for the input list again. Remote v2 pushes the
 * foreground app the moment it changes; the REST poll only every [TvPollRules.EVERY_MS]. A change
 * of app (switching to the Mac's HDMI input included) wakes the poll so [TargetController]'s auto
 * target follows within a couple of seconds instead of up to ten.
 */
object TvPollRules {
    const val EVERY_MS = 10_000L

    /** A nudged poll waits this long after the change, so the TV has settled on the new input. */
    const val SETTLE_MS = 700L

    /** Never two polls closer than this, however fast he zaps. */
    const val MIN_GAP_MS = 2_000L

    /** While the TV stays on, the input list (CEC names, what is plugged in) is read this often. */
    const val INPUTS_EVERY_MS = 60_000L

    /** How long to wait, after a change at [now], before polling again; the last poll began at [startedAt]. */
    fun delayAfterChange(startedAt: Long, now: Long): Long = maxOf(SETTLE_MS, startedAt + MIN_GAP_MS - now)

    /**
     * [known] an input list is already held, [askedAt] when it was last read (null: never),
     * [inputChanged] the poll sees another input than the previous one.
     */
    fun inputsDue(active: Boolean, known: Boolean, askedAt: Long?, now: Long, inputChanged: Boolean): Boolean = when {
        !active -> false
        !known || askedAt == null -> true
        inputChanged -> true
        else -> now - askedAt >= INPUTS_EVERY_MS
    }
}
