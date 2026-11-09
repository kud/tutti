package io.kud.tutti.protocol.atv

/**
 * A held arrow on the pad, the way a physical remote holds it (Erwann, 2026-11-09: holding left or
 * right to scrub "starts to crack up" and stays stuck). The remote sends one key down, the TV's own
 * key repeat runs (and the player accelerates its scrub off the repeat count, as YouTube does), then
 * one key up. tutti used to send a separate SHORT key every 90 ms, easing to 50 ms: twenty taps a
 * second, each launched on its own, so a TV slow to read them queued them up and kept scrubbing
 * after the finger lifted, and a player never saw a held key to accelerate on.
 *
 * Now a press is a tap until [HOLD_AFTER_MS]; then the key goes down (START_LONG) and stays down
 * until the finger lifts or the touch is cancelled (END_LONG), two messages for a hold of any
 * length. Pure: the caller passes the clock and carries out each [DirectionEffect].
 */
object DirectionHold {
    /** A press held this long is a hold: the key goes down on the TV. Shorter is a tap. */
    const val HOLD_AFTER_MS = 400L

    /** While the finger stays, the UI renews the hold's lease this often. */
    const val RENEW_EVERY_MS = 500L

    /**
     * A held arrow comes up this long after the last renewal: a release lost on the phone (the
     * app frozen mid-hold) stops the TV scrubbing within two seconds, and a finger that stays
     * never feels it, because each renewal re-arms it.
     */
    const val LEASE_MS = 2_000L
}

/** What the caller does for one step of a [DirectionPress]. */
enum class DirectionEffect {
    /** Nothing to send. */
    NONE,

    /** A quick press: one plain key (SHORT). */
    TAP,

    /** The press became a hold: the key goes down on the TV (START_LONG). */
    HOLD,

    /** The finger is still there: re-arm the hold's lease. Nothing goes to the TV. */
    RENEW,

    /** The hold ends: the key comes up (END_LONG). */
    RELEASE,
}

/**
 * One press of one arrow, from finger down at [startedAt] to its end. A single owner: once it has
 * ended, every call is [DirectionEffect.NONE], so nothing is ever sent after the release.
 */
class DirectionPress(val startedAt: Long) {
    private enum class Phase { PENDING, HELD, REPEATING, ENDED }

    private var phase = Phase.PENDING

    val held: Boolean @Synchronized get() = phase == Phase.HELD
    val ended: Boolean @Synchronized get() = phase == Phase.ENDED

    /** The clock ran to [now] with the finger down. */
    @Synchronized
    fun tick(now: Long): DirectionEffect = when (phase) {
        Phase.PENDING -> if (now - startedAt >= DirectionHold.HOLD_AFTER_MS) {
            phase = Phase.HELD
            DirectionEffect.HOLD
        } else {
            DirectionEffect.NONE
        }
        Phase.HELD -> DirectionEffect.RENEW
        Phase.REPEATING, Phase.ENDED -> DirectionEffect.NONE
    }

    /**
     * The route can't hold a key (a computer gets plain presses): the caller repeats plain keys
     * itself from here. The release then sends nothing more.
     */
    @Synchronized
    fun holdRefused() {
        if (phase == Phase.HELD) phase = Phase.REPEATING
    }

    /** The finger lifted. */
    @Synchronized
    fun release(): DirectionEffect = end(lifted = true)

    /** The touch was cancelled (the pad went down, the screen left, a second finger). */
    @Synchronized
    fun cancel(): DirectionEffect = end(lifted = false)

    private fun end(lifted: Boolean): DirectionEffect {
        val was = phase
        phase = Phase.ENDED
        return when (was) {
            Phase.PENDING -> if (lifted) DirectionEffect.TAP else DirectionEffect.NONE
            Phase.HELD -> DirectionEffect.RELEASE
            Phase.REPEATING, Phase.ENDED -> DirectionEffect.NONE
        }
    }
}

/**
 * How long a held key may stay down without word from the finger. Each [renew] re-arms it, so a
 * hold lasts as long as the finger does; only a hold nobody renews runs out. Replaces the fixed
 * 10 s cut, which lifted a key the finger was still holding.
 */
class HoldLease(private val leaseMs: Long, now: Long) {
    private var until = now + leaseMs

    @Synchronized
    fun renew(now: Long) {
        until = maxOf(until, now + leaseMs)
    }

    /** How long until the hold runs out, 0 once it has. */
    @Synchronized
    fun remaining(now: Long): Long = (until - now).coerceAtLeast(0L)
}

/**
 * Keys on their way to the TV. Each key went out on its own coroutine, so a socket slow to drain
 * let them pile up without bound, and the pile kept arriving after the finger lifted. At most
 * [MAX_IN_FLIGHT] wait now (one being written, one next); a key past that is dropped, never buffered.
 */
class KeyBacklog(private val maxInFlight: Int = MAX_IN_FLIGHT) {
    private var inFlight = 0

    /** True when the key may go; the caller must then call [done] once it has. */
    @Synchronized
    fun tryAcquire(): Boolean {
        if (inFlight >= maxInFlight) return false
        inFlight++
        return true
    }

    @Synchronized
    fun done() {
        if (inFlight > 0) inFlight--
    }

    companion object {
        const val MAX_IN_FLIGHT = 2
    }
}
