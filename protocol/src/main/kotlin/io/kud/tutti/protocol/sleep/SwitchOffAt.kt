package io.kud.tutti.protocol.sleep

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "Switch off at 21:50": the gentle timer aimed at a time of day rather than a duration. Pure
 * rules over java.time, the zone passed in (the phone's), so midnight and the clock changes are
 * tested here rather than discovered on a Sunday in March.
 *
 * The end is the next time the clock reads [LocalTime] after now: today if it is still ahead,
 * otherwise tomorrow. Two clock-change cases, both resolved the way java.time resolves them:
 * - A time skipped when the clocks go forward (02:30 on the spring night in Paris) lands just
 *   after the gap, at the same distance past it (03:30), rather than being refused.
 * - A time read twice when the clocks go back takes its first reading if that is still ahead,
 *   else its second, so 02:30 picked at 02:45 summer time means 02:30 winter time, 45 min later.
 */
object SwitchOffAt {
    /** Never closer than the fade: a shorter lead would cut the two-minute fade short. */
    const val MIN_LEAD_MS: Long = SleepFade.FADE_MS

    /**
     * The furthest an end may be: a day on the clock. Real time is 25 h on the night the clocks go
     * back, when a time a minute behind now is tomorrow's, 24 h 59 min away.
     */
    const val MAX_LEAD_MS: Long = 25 * 60 * 60_000L

    /**
     * The service's own slack under [MIN_LEAD_MS]: the sheet checked the lead a moment before the
     * service starts, and those few hundred milliseconds must not turn an accepted time into a refusal.
     */
    const val START_GRACE_MS: Long = 5_000L

    /** The next instant, after [now], when the clock in [zone] reads [time] (seconds dropped). */
    fun nextOccurrence(now: Instant, time: LocalTime, zone: ZoneId): Instant {
        val wanted = time.withSecond(0).withNano(0)
        val today = now.atZone(zone).toLocalDate()
        for (date in listOf(today, today.plusDays(1))) {
            val first = ZonedDateTime.ofLocal(date.atTime(wanted), zone, null)
            if (first.toInstant().isAfter(now)) return first.toInstant()
            val second = first.withLaterOffsetAtOverlap().toInstant()
            if (second.isAfter(now)) return second
        }
        // Unreachable: tomorrow's reading is always after now. Kept total for the compiler.
        return ZonedDateTime.ofLocal(today.plusDays(2).atTime(wanted), zone, null).toInstant()
    }

    /** Whether [endsAt] falls on a later calendar day than [now], in [zone]: the running line says "tomorrow". */
    fun isTomorrow(now: Instant, endsAt: Instant, zone: ZoneId): Boolean =
        endsAt.atZone(zone).toLocalDate().isAfter(now.atZone(zone).toLocalDate())

    /** Why a chosen end can't be used, or null when it can. */
    fun refusal(now: Long, endsAt: Long): Refusal? {
        val lead = endsAt - now
        return when {
            lead <= 0 -> Refusal.PAST
            lead < MIN_LEAD_MS -> Refusal.TOO_SOON
            lead > MAX_LEAD_MS -> Refusal.TOO_FAR
            else -> null
        }
    }

    /** The sheet's check, from a picked time: the end to start, or why not. */
    fun plan(now: Instant, time: LocalTime, zone: ZoneId): Plan {
        val end = nextOccurrence(now, time, zone)
        val refused = refusal(now.toEpochMilli(), end.toEpochMilli())
        return if (refused == null) Plan.Start(end.toEpochMilli(), isTomorrow(now, end, zone)) else Plan.Refused(refused)
    }

    /**
     * The service's check on an absolute end it was handed: in the future, within a day, and no
     * closer than the fade less [START_GRACE_MS]. Anything else is dropped, as an unknown duration is.
     */
    fun accepts(now: Long, endsAt: Long): Boolean = when (refusal(now, endsAt)) {
        null -> true
        Refusal.TOO_SOON -> endsAt - now >= MIN_LEAD_MS - START_GRACE_MS
        else -> false
    }

    /** Hours and minutes left, minutes rounded up as [SleepFade.minutesLeft] does. */
    fun hoursAndMinutesLeft(now: Long, endsAt: Long): Pair<Int, Int> {
        val minutes = SleepFade.minutesLeft(now, endsAt)
        return minutes / 60 to minutes % 60
    }

    enum class Refusal { PAST, TOO_SOON, TOO_FAR }

    sealed interface Plan {
        data class Start(val endsAt: Long, val tomorrow: Boolean) : Plan
        data class Refused(val why: Refusal) : Plan
    }
}
