package io.kud.tutti.protocol.auto

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "Sound that follows the hour" (Erwann, 2026-11-12): between two times of day (22:00 to 07:00 by
 * default) the bar's night mode and voice enhancement turn on and the high-volume guard sits lower;
 * at the end each goes back to what it was, unless the user changed it meanwhile.
 *
 * No alarm: the window is judged whenever tutti looks (the bar connecting, a minute's tick while
 * the process runs), so it applies when tutti next talks to the bar, and ends the same way. The
 * session is stored, so an end the process slept through is still restored at the next connect.
 *
 * Clock changes: the window is a pair of wall-clock times, so "inside" is read on the phone's
 * clock in its zone ([inside]); its end is fixed as an instant when the session opens ([endOf]),
 * resolved like the switch-off timer resolves a time of day (a skipped end lands just after the
 * gap, a doubled one takes its first reading still ahead).
 */
object NightHours {
    const val DEFAULT_START = 22 * 60
    const val DEFAULT_END = 7 * 60

    /** Never lower than the guard itself allows (`VolumeRules.MIN_GUARD`). */
    const val MIN_LINE = 5

    /** The features it turns on, by their wire name (`audio.nightmode`, `audio.voiceenhancer`). */
    enum class Feature { NIGHT, VOICE }

    /** The guard's line at night: two thirds of the day's, so 30 becomes 20, the Nuit scene's cap. */
    fun guardLine(dayLine: Int): Int = maxOf(MIN_LINE, dayLine * 2 / 3)

    /** Whether [minuteOfDay] (0..1439) falls in the window; across midnight when [start] > [end]; never when equal. */
    fun inside(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
    }

    fun inside(now: Instant, zone: ZoneId, start: Int, end: Int): Boolean {
        val local = now.atZone(zone).toLocalTime()
        return inside(local.hour * 60 + local.minute, start, end)
    }

    /** The next instant after [now] when the clock in [zone] reads [end]: the open window's end. */
    fun endOf(now: Instant, zone: ZoneId, end: Int): Instant {
        val wanted = LocalTime.of(end / 60, end % 60)
        val today = now.atZone(zone).toLocalDate()
        for (date in listOf(today, today.plusDays(1))) {
            val first = ZonedDateTime.ofLocal(date.atTime(wanted), zone, null)
            if (first.toInstant().isAfter(now)) return first.toInstant()
            val second = first.withLaterOffsetAtOverlap().toInstant()
            if (second.isAfter(now)) return second
        }
        return ZonedDateTime.ofLocal(today.plusDays(2).atTime(wanted), zone, null).toInstant()
    }

    /**
     * An open window, stored on this phone only. [before] is each feature's value as the bar
     * reported it when the window opened; [asked] what tutti set; [seen] the features the bar has
     * since reported at the asked value; [handsOff] those the user changed afterwards, which tutti
     * leaves alone, now and at the end.
     */
    @Serializable
    data class Session(
        val endsAt: Long,
        val before: Map<Feature, Boolean> = emptyMap(),
        val asked: Map<Feature, Boolean> = emptyMap(),
        val seen: Set<Feature> = emptySet(),
        val handsOff: Set<Feature> = emptySet(),
    )

    /** What the bar reports now: null when its link is down or a value hasn't come yet. */
    data class Bar(val connected: Boolean, val values: Map<Feature, Boolean?>)

    data class Step(val session: Session?, val set: Map<Feature, Boolean>, val log: List<String>)

    /**
     * One look at the window. [enabled] is the option; [start] and [end] are minutes of the day.
     * Sends only on a change of phase (open, close, roll over), never again while it holds, so a bar
     * that refuses a value is not asked forever and a manual change is never fought.
     */
    fun step(session: Session?, enabled: Boolean, now: Instant, zone: ZoneId, start: Int, end: Int, bar: Bar): Step {
        val watched = session?.let { observe(it, bar) }
        val log = mutableListOf<String>()
        if (watched != null && watched.handsOff != session.handsOff) {
            log += "night hours: left to the user: ${(watched.handsOff - session.handsOff).joinToString { it.name.lowercase() }}"
        }
        val within = enabled && inside(now, zone, start, end)
        if (watched == null) {
            // Nothing open and nothing to open.
            if (!within) return Step(null, emptyMap(), log)
            // Opening: wait until the bar has said where both stand, so "before" is real.
            if (!bar.connected || Feature.entries.any { bar.values[it] == null }) return Step(null, emptyMap(), log)
            val before = Feature.entries.associateWith { bar.values[it] == true }
            val asked = Feature.entries.filter { before[it] == false }.associateWith { true }
            log += "night hours: open, on: ${asked.keys.joinToString { it.name.lowercase() }.ifEmpty { "nothing, already on" }}"
            return Step(Session(endOf(now, zone, end).toEpochMilli(), before, asked), asked, log)
        }
        // Still open: keep watching.
        if (within && now.toEpochMilli() < watched.endsAt) return Step(watched, emptyMap(), log)
        // Closing, or the option turned off: back to before, for what tutti set and the user left.
        if (!bar.connected) return Step(watched, emptyMap(), log)
        if (within) {
            // The process slept through the end into the next window: it rolls over, keeping what
            // to put back, and asks again for what the user didn't leave off.
            val asked = watched.before.filterValues { !it }.keys.associateWith { true }
            val set = asked.filter { (feature, value) -> bar.values[feature] != value }
            log += "night hours: rolled into the next window"
            return Step(Session(endOf(now, zone, end).toEpochMilli(), watched.before, asked), set, log)
        }
        val restore = watched.asked.keys
            .filter { it !in watched.handsOff }
            .associateWith { watched.before[it] == true }
            .filter { (feature, value) -> bar.values[feature] != value }
        log += "night hours: close, back: ${restore.keys.joinToString { it.name.lowercase() }.ifEmpty { "nothing to restore" }}"
        return Step(null, restore, log)
    }

    /** Folds the bar's report into [session]: a value that moved off what tutti set, once it had landed, is the user's. */
    fun observe(session: Session, bar: Bar): Session {
        if (!bar.connected) return session
        var seen = session.seen
        var handsOff = session.handsOff
        for ((feature, wanted) in session.asked) {
            val value = bar.values[feature] ?: continue
            when {
                value == wanted -> seen = seen + feature
                feature in seen -> handsOff = handsOff + feature
            }
        }
        return if (seen == session.seen && handsOff == session.handsOff) session else session.copy(seen = seen, handsOff = handsOff)
    }
}
