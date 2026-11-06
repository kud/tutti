package io.kud.tutti.protocol.atv

/**
 * Keys pressed while the remote link is reconnecting, held so they are not simply lost
 * (Erwann, 2026-11-06: « Ok, on part là-dessus »). Before this, `AtvRemote.send` refused a
 * stale session and the press was gone, or only the newest one-shot waited.
 *
 * Pure and immutable: time comes in as `now`, on whatever clock the caller uses for every call.
 * The caller decides whether a reconnect is under way and only then calls [press]; with no link
 * coming back there is nothing to hold for.
 *
 * - One-shot keys ([ONE_SHOT]) are held and collapsed: ten OK taps are one OK, and the playback
 *   keys share one slot, so play/pause can't flip twice when the link comes back. Distinct keys
 *   replay in the order they were first pressed ([flush]).
 * - Everything else is never held: the D-pad's arrows (a burst landing late moves the focus
 *   somewhere nobody looked), volume and mute (the bar's, and a late step is a surprise), power
 *   and input switching (their own wake paths; a late toggle is the worst kind).
 * - A press older than [HOLD_WINDOW_MS] when the session returns is dropped silently.
 */
data class KeyBuffer(val held: List<HeldPress> = emptyList()) {

    /** A press waiting for the link: [pressedAt] is the latest press that landed in its slot. */
    data class HeldPress(val press: PendingPress, val pressedAt: Long)

    /** What can wait. App links share one slot: the newest app asked for is the one that opens. */
    sealed interface PendingPress {
        data class Key(val keyCode: Int) : PendingPress
        data class AppLink(val link: String) : PendingPress
    }

    /** Whether [press] went into the buffer, and the buffer after it. */
    data class Step(val buffer: KeyBuffer, val held: Boolean)

    /**
     * A press while a reconnect is under way. A key that is never held comes back with
     * `held = false` and the buffer unchanged: the caller drops it as before. A press whose slot
     * already waits replaces it in place: its position stays (first press order), its time is
     * the new press, since pressing again means the person is still asking.
     */
    fun press(press: PendingPress, now: Long): Step {
        if (press is PendingPress.Key && !holds(press.keyCode)) return Step(this, held = false)
        val live = expire(now).held
        val slot = slotOf(press)
        val at = live.indexOfFirst { slotOf(it.press) == slot }
        val next = if (at >= 0) {
            live.toMutableList().also { it[at] = HeldPress(press, now) }
        } else {
            live + HeldPress(press, now)
        }
        return Step(KeyBuffer(next), held = true)
    }

    /** The session is back: what to send, in press order, and the empty buffer. Older presses are dropped silently. */
    fun flush(now: Long): Pair<KeyBuffer, List<PendingPress>> = KeyBuffer() to expire(now).held.map { it.press }

    /** Drops every press older than [HOLD_WINDOW_MS]. */
    fun expire(now: Long): KeyBuffer {
        val live = held.filter { now - it.pressedAt < HOLD_WINDOW_MS }
        return if (live.size == held.size) this else KeyBuffer(live)
    }

    /** Ms until the newest press expires, when the buffer has nothing left; null when it is empty. */
    fun emptyInMs(now: Long): Long? = held.maxOfOrNull { it.pressedAt }?.let { (it + HOLD_WINDOW_MS - now).coerceAtLeast(0) }

    val isEmpty: Boolean get() = held.isEmpty()

    companion object {
        /**
         * How long a press waits for the link, from the press. A first setting, meant to be tuned
         * from use (Erwann, 2026-11-06): a blip's reconnect lands within about 2 s of the drop, and
         * a Home that arrives much later than that is a surprise, not help.
         */
        const val HOLD_WINDOW_MS = 2_000L

        private val PLAYBACK = setOf(V2Key.MEDIA_PLAY_PAUSE, V2Key.MEDIA_PLAY, V2Key.MEDIA_PAUSE)

        /**
         * Keys whose effect does not depend on a focus that moves meanwhile. OK is one now: arrows
         * are never held, so the focus OK lands on is the one it was aimed at.
         */
        val ONE_SHOT: Set<Int> = setOf(
            V2Key.DPAD_CENTER,
            V2Key.HOME,
            V2Key.BACK,
            V2Key.MEDIA_PLAY_PAUSE,
            V2Key.MEDIA_PLAY,
            V2Key.MEDIA_PAUSE,
            V2Key.MEDIA_STOP,
            V2Key.GUIDE,
            V2Key.SETTINGS,
            V2Key.ALL_APPS,
            V2Key.TV,
        )

        fun holds(keyCode: Int): Boolean = keyCode in ONE_SHOT

        private fun slotOf(press: PendingPress): Any = when (press) {
            is PendingPress.AppLink -> "app"
            is PendingPress.Key -> if (press.keyCode in PLAYBACK) "playback" else press.keyCode
        }
    }
}
