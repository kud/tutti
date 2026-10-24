package io.kud.tutti.controller

/**
 * When the TV poll asks the TV for its app list again (getApplicationList). It used to be asked
 * only while the list was empty, so an app installed later needed tutti restarted. It rides the
 * poll, never a loop of its own, and is only asked while the TV is on (in standby it answers
 * nothing useful).
 */
object AppListRefresh {
    /** While the TV stays on, the list is asked again this often. */
    const val EVERY_MS = 10 * 60_000L

    /**
     * [active] the TV is on now, [wasActive] it was on at the previous poll since REST connected
     * (false right after a reconnect), [hasApps] a list is already known, [askedAt] when it was
     * last asked (null: never), all times on one monotonic clock.
     */
    fun due(active: Boolean, wasActive: Boolean, hasApps: Boolean, askedAt: Long?, now: Long): Boolean = when {
        !active -> false
        !hasApps -> true
        // The TV just woke, or REST just (re)connected: apps may have been installed meanwhile.
        !wasActive -> true
        askedAt == null -> true
        else -> now - askedAt >= EVERY_MS
    }
}
