package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.V2Key
import kotlin.math.abs
import kotlin.math.sign

/**
 * How one app on the TV jumps when the jog wheel turns (Erwann, 2026-11-01: « pas aller en avant,
 * en arrière avec x2, x3 »). Each notch is one fixed jump, never a speed: the wheel sends no fast
 * forward or rewind, which YouTube and most players turn into 2x, 3x scrubbing.
 *
 * - [forward] / [back]: the keys of one jump. D-pad Right / Left during playback is Android TV's
 *   own rule for "+N / −N seconds" (developer.android.com/training/tv/playback/controls). The skip
 *   keys (272 / 273) are not used: the platform `MediaSession` ignores them by default, and
 *   Media3's default turns them into next / previous item, a jump to another video.
 * - [seconds]: the app's jump per key when known, else null and the readout counts jumps.
 * - [documented]: whether the app itself documents [seconds]. Until one does, the readout says
 *   "about".
 * - [revealFirst]: whether the first key after the controls have hidden only shows them. That
 *   key is sent but not counted as a jump.
 */
data class SeekStep(
    val forward: Int = V2Key.DPAD_RIGHT,
    val back: Int = V2Key.DPAD_LEFT,
    val seconds: Int? = null,
    val documented: Boolean = false,
    val revealFirst: Boolean = false,
) {
    fun key(delta: Int): Int = if (delta > 0) forward else back
}

/**
 * The step for the app in front. YouTube, Netflix and Prime Video step about 10 s per D-pad
 * press by every account found (none from the apps' own docs), so their readout reads seconds
 * with "about"; any other app gets the same keys and a count of jumps. None is marked
 * [SeekStep.revealFirst] yet: flip it for an app once the BRAVIA shows its first press only
 * brings the bar up.
 */
object SeekSteps {
    private val tenSeconds = SeekStep(seconds = 10)
    private val known = mapOf(
        "com.google.android.youtube.tv" to tenSeconds,
        "com.netflix.ninja" to tenSeconds,
        "com.amazon.amazonvideo.livingroom" to tenSeconds,
    )

    val DEFAULT = SeekStep()

    fun forPackage(packageName: String?): SeekStep = known[packageName] ?: DEFAULT
}

/** What the wheel shows while it turns: [jumps] net, signed; [seconds] when the step is known. */
data class JogReadout(
    val jumps: Int,
    val seconds: Int?,
    val approximate: Boolean,
    /** The first key of this turn only brought the app's controls up. */
    val revealed: Boolean,
)

/**
 * Notches in, keys out, at the TV's pace. A fast spin queues notches and [next] lets one key out
 * every [gapMs]; at most [maxAhead] wait, so a flick never floods the TV and the readout never
 * counts a jump that won't be sent. Turning back takes queued jumps off before it adds the other
 * way. A turn ends [READOUT_MS] after the last notch with nothing queued; the readout then fades.
 * Pure: the caller passes the time, sends what [next] returns and wakes at [wakeAt].
 */
class JogSeek(
    private val step: SeekStep,
    private val gapMs: Long = MIN_GAP_MS,
    private val maxAhead: Int = MAX_AHEAD,
) {
    private var queued = 0
    private var revealQueued = 0
    private var total = 0
    private var revealed = false
    private var lastNotchAt: Long? = null
    private var lastKeyAt: Long? = null

    /** One notch, +1 forward or −1 back. False when it was dropped (too many already queued). */
    fun turn(delta: Int, now: Long): Boolean {
        val dir = delta.sign
        if (dir == 0) return false
        if (!turning(now)) {
            total = 0
            revealed = false
        }
        lastNotchAt = now
        val keyAt = lastKeyAt
        if (step.revealFirst && !revealed && queued == 0 &&
            (keyAt == null || now - keyAt >= CONTROLS_HIDE_MS)
        ) {
            revealed = true
            revealQueued = dir
            return true
        }
        if (queued != 0 && queued.sign != dir) {
            queued += dir
            total += dir
            return true
        }
        if (abs(queued) >= maxAhead) return false
        queued += dir
        total += dir
        return true
    }

    /** The key to send now, or null when none is due yet. */
    fun next(now: Long): Int? {
        val keyAt = lastKeyAt
        if (keyAt != null && now - keyAt < gapMs) return null
        val dir = when {
            revealQueued != 0 -> revealQueued.also { revealQueued = 0 }
            queued != 0 -> queued.sign.also { queued -= it }
            else -> return null
        }
        lastKeyAt = now
        return step.key(dir)
    }

    /** When to call back: the next key's slot, or the readout's end, or null when all is done. */
    fun wakeAt(now: Long): Long? {
        val keyAt = lastKeyAt
        if (revealQueued != 0 || queued != 0) return if (keyAt == null) now else maxOf(now, keyAt + gapMs)
        return lastNotchAt?.let { it + READOUT_MS }?.takeIf { it > now }
    }

    /** The readout while a turn is on, else null. */
    fun readout(now: Long): JogReadout? {
        if (!turning(now)) return null
        return JogReadout(
            jumps = total,
            seconds = step.seconds?.let { it * total },
            approximate = !step.documented,
            revealed = revealed,
        )
    }

    private fun turning(now: Long): Boolean {
        if (queued != 0 || revealQueued != 0) return true
        val at = lastNotchAt ?: return false
        return now - at < READOUT_MS
    }

    companion object {
        /** The least time between two keys: a spin reaches the TV as presses it can keep up with. */
        const val MIN_GAP_MS = 150L

        /** Jumps that may wait at once; past that a notch is dropped, not counted. */
        const val MAX_AHEAD = 12

        /** The readout stays this long after the last notch. */
        const val READOUT_MS = 1_500L

        /** After this long without a key, an app that hides its controls has hidden them. */
        const val CONTROLS_HIDE_MS = 5_000L
    }
}
