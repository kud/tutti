package io.kud.tutti.protocol.sleep

import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * "Switch off at the end of the episode" (Erwann, 2026-11-12: « Éteindre à la fin de l'épisode »):
 * the gentle timer aimed at the end of the Cast item playing, as plain rules over Cast readings.
 * The end is `now + (duration - position) / rate`; the fade ([SleepFade]) runs over its last two
 * minutes, which on most episodes is the credits, and then the TV and the bar go off.
 *
 * The plan follows the reading:
 * - a seek or a rate change moves the end (beyond [JITTER_MS], so the 15 s re-reads don't nudge it);
 * - a pause holds it: the end stays [PAUSE_RECHECK_MS] past the fade's reach, so the alarm wakes
 *   the timer again in a few minutes to read once more, rather than counting down a paused episode;
 * - another item in front (the next episode autoplaying, or one picked by hand) means the one that
 *   was playing is over: the end comes no later than one fade from then, and the plan stops
 *   following Cast (it never re-aims at the new episode's end);
 * - Cast going silent (the phone asleep, the session dropping) keeps the last plan.
 *
 * The item is compared in memory only (title and duration, both viewing data): nothing here logs it.
 */
object EpisodeEnd {
    /** Offered only with room for the whole fade before the end. */
    const val MIN_LEFT_MS: Long = SleepFade.FADE_MS

    /** A re-read that moves the end by less than this leaves it: readings are whole seconds, 15 s apart. */
    const val JITTER_MS: Long = 5_000L

    /** While paused, the end sits this far past the fade's start, so the alarm re-reads in about this long. */
    const val PAUSE_RECHECK_MS: Long = 5 * 60_000L

    /** Time left in the item at [now], at its rate, or null when the reading can't say. */
    fun leftMs(cast: CastNowPlaying, now: Long): Long? {
        val duration = cast.durationSec?.takeIf { it > 0 } ?: return null
        val at = cast.positionAt(now) ?: return null
        val rate = rateOf(cast)
        return (((duration - at) / rate) * 1000).roundToLong().coerceAtLeast(0)
    }

    /** Whether the Power sheet offers the option: a Cast item with a known duration, playing or paused, with room for the fade. */
    fun offered(cast: CastNowPlaying?, now: Long): Boolean {
        if (cast == null) return false
        if (cast.state != CastPlayerState.PLAYING && cast.state != CastPlayerState.PAUSED) return false
        val left = leftMs(cast, now) ?: return false
        return left >= MIN_LEFT_MS && left <= SwitchOffAt.MAX_LEAD_MS
    }

    /** The first plan, or null when the option isn't [offered]. */
    fun start(cast: CastNowPlaying?, now: Long): Plan? {
        if (cast == null || !offered(cast, now)) return null
        val left = leftMs(cast, now) ?: return null
        val paused = cast.state == CastPlayerState.PAUSED
        return Plan(
            endsAt = if (paused) pausedEnd(now, left) else now + left,
            item = Item(cast.title, cast.durationSec),
            rate = rateOf(cast),
            paused = paused,
        )
    }

    /** Folds one reading (or none) into [plan]. [Step.why] is set only when something worth a log line happened. */
    fun replan(plan: Plan, cast: CastNowPlaying?, now: Long): Step {
        if (plan.over) return Step(plan, null)
        if (cast == null) return if (plan.silent) Step(plan, null) else Step(plan.copy(silent = true), Why.SILENT)
        val heard = plan.copy(silent = false)
        if (Item(cast.title, cast.durationSec) != plan.item) {
            return Step(heard.copy(endsAt = minOf(plan.endsAt, now + SleepFade.FADE_MS), over = true, paused = false), Why.ITEM_CHANGED)
        }
        val left = leftMs(cast, now)
        return when {
            // Buffering, idle between two statuses, or a stale reading: nothing new about the end.
            left == null || cast.state == CastPlayerState.BUFFERING || cast.state == CastPlayerState.IDLE -> Step(heard, null)
            cast.state == CastPlayerState.PAUSED -> Step(
                heard.copy(endsAt = pausedEnd(now, left), paused = true),
                if (plan.paused) null else Why.PAUSED,
            )
            else -> {
                val end = now + left
                val rate = rateOf(cast)
                when {
                    plan.paused -> Step(heard.copy(endsAt = end, paused = false, rate = rate), Why.RESUMED)
                    abs(end - plan.endsAt) <= JITTER_MS -> Step(heard.copy(rate = rate), null)
                    abs(rate - plan.rate) > 0.01 -> Step(heard.copy(endsAt = end, rate = rate), Why.RATE)
                    else -> Step(heard.copy(endsAt = end), Why.SEEK)
                }
            }
        }
    }

    /** For the event log: what moved the end and by how much, never a title or a position. */
    fun logLine(why: Why, before: Long, after: Long, now: Long): String {
        val shift = (after - before) / 1000
        val left = SleepFade.minutesLeft(now, after)
        val what = when (why) {
            Why.SEEK -> "seek"
            Why.RATE -> "rate changed"
            Why.PAUSED -> "paused, holding"
            Why.RESUMED -> "resumed"
            Why.ITEM_CHANGED -> "another item in front, ending the one that played"
            Why.SILENT -> "cast silent, keeping the plan"
        }
        val sign = if (shift >= 0) "+" else ""
        return "episode end: $what (end ${sign}${shift} s, $left min left)"
    }

    private fun pausedEnd(now: Long, left: Long): Long = now + maxOf(left, SleepFade.FADE_MS) + PAUSE_RECHECK_MS

    private fun rateOf(cast: CastNowPlaying): Double = cast.playbackRate.takeIf { it > 0.01 } ?: 1.0

    /** What identifies the item followed. Viewing data: [toString] hides it. */
    class Item(private val title: String, private val durationSec: Double?) {
        override fun equals(other: Any?): Boolean = other is Item && other.title == title && other.durationSec == durationSec
        override fun hashCode(): Int = title.hashCode() * 31 + durationSec.hashCode()
        override fun toString(): String = "Item(…)"
    }

    /**
     * A running episode plan. [over] once another item came in front: the end no longer follows
     * Cast. [silent] while Cast says nothing, so the log line comes once per gap.
     */
    data class Plan(
        val endsAt: Long,
        val item: Item,
        val rate: Double,
        val paused: Boolean = false,
        val over: Boolean = false,
        val silent: Boolean = false,
    )

    data class Step(val plan: Plan, val why: Why?)

    enum class Why { SEEK, RATE, PAUSED, RESUMED, ITEM_CHANGED, SILENT }
}
