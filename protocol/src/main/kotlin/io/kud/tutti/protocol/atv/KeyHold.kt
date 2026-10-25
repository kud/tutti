package io.kud.tutti.protocol.atv

/**
 * How long a held remote v2 key stays down. The TV only sees a long press when START_LONG and
 * END_LONG arrive apart: on START_LONG the remote service puts the key down, the TV's own input
 * dispatcher repeats it and flags the long press after its timeout (400 to 500 ms on Android TV),
 * and END_LONG lifts it. A physical remote does exactly that: down on press, up on release.
 *
 * Sent back to back (as tutti did until 2026-10-25), the two messages are a key down and up a few
 * milliseconds apart, which every app reads as a plain OK: YouTube never opened its options.
 * So a hold lasts as long as the finger stays, and never less than [MIN_HOLD_MS]; a fixed hold
 * (a TalkBack action, a pin's "Hold" step) lasts exactly that. Pure; the clock comes in.
 */
object KeyHold {
    /** Past the TV's long-press timeout with room for Wi-Fi jitter between the two messages. */
    const val MIN_HOLD_MS = 700L

    /**
     * A hold never outlives this: a lost release (the app killed mid-hold) must not leave OK
     * repeating on the TV. Far past any menu's long press.
     */
    const val MAX_HOLD_MS = 10_000L

    /** How long to wait before END_LONG, for a key that went down at [startedAt], released at [now]. */
    fun releaseDelay(startedAt: Long, now: Long): Long = (MIN_HOLD_MS - (now - startedAt)).coerceIn(0L, MIN_HOLD_MS)
}
