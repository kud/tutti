package io.kud.tutti.controller

import io.kud.tutti.platform.data.TonightCap
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Where a volume change comes from, which decides what the high-volume guard may do with it.
 */
enum class VolumeSource {
    /**
     * In the app: the rail, its + and −, the pad's two fingers, the volume sheet, and the phone's
     * keys while the remote is in front. It can stop at the threshold and ask.
     */
    APP,

    /**
     * The widget, the notification and the phone's keys through it, the Quick Settings overlay: no
     * room to ask, so it stops at the threshold and says so; going above needs the app.
     */
    QUICK,
}

/** The guard as it stands: [unlocked] once going above was confirmed, until the level drops below [threshold]. */
data class VolumeGuardState(val enabled: Boolean, val threshold: Int, val unlocked: Boolean)

/** What to do with a requested level. */
sealed interface VolumeDecision {
    /** Send [level] (already inside the ceiling). */
    data class Send(val level: Int) : VolumeDecision

    /**
     * Stopped at [threshold]: send [send] (the threshold itself, or null when already there), and
     * [ask] the question (in the app) or say going above needs the app (elsewhere). [wanted] is
     * what was asked for, inside the ceiling: confirming goes there.
     */
    data class Held(val send: Int?, val threshold: Int, val wanted: Int, val ask: Boolean) : VolumeDecision
}

/** The question the remote shows: go from [threshold] to [wanted], or stay. */
data class VolumeAsk(val threshold: Int, val wanted: Int)

/** How long a cap for tonight lasts, as the volume sheet offers it. */
enum class CapUntil { ONE_HOUR, THREE_HOURS, MORNING }

/**
 * The volume's limits, pure (tested in VolumeRulesTest): the ceiling (a scene's cap and the cap for
 * tonight, the lower wins), the high-volume guard under it, and the volume sheet's saved levels.
 *
 * The guard (Erwann, 2026-10-25) stops a change that would take the bar from at or below the
 * threshold to above it. It asks once: confirmed, the bar may go above until its level is below the
 * threshold again (or the bar's link drops, or the app restarts), so stepping up past it never nags
 * at every step. A level already above the threshold (the bar's own remote, an earlier evening) is
 * not a crossing and is left alone. Scenes and their undo don't go through it: running a scene is
 * an explicit choice of its volume; the ceiling still applies to them.
 */
object VolumeRules {
    const val PRESET_SLOTS = 3

    /** "Morning" for a cap for tonight: 7:00, the next one. */
    val MORNING: LocalTime = LocalTime.of(7, 0)

    /** The cap for tonight, while it lasts; null once [TonightCap.until] has passed. */
    fun tonightActive(cap: TonightCap?, now: Long): TonightCap? = cap?.takeIf { now < it.until }

    /** The ceiling every change and every scene obeys: the lower of the scene's cap and tonight's, else [max]. */
    fun ceiling(sceneCap: Int?, tonight: TonightCap?, now: Long, max: Int): Int =
        listOfNotNull(sceneCap, tonightActive(tonight, now)?.level, max).min().coerceIn(0, max)

    /** The effective cap, or null when nothing caps the volume below [max]. */
    fun cap(sceneCap: Int?, tonight: TonightCap?, now: Long, max: Int): Int? =
        ceiling(sceneCap, tonight, now, max).takeIf { sceneCap != null || tonightActive(tonight, now) != null }

    fun decide(current: Int?, wanted: Int, ceiling: Int, guard: VolumeGuardState, source: VolumeSource): VolumeDecision {
        val target = wanted.coerceIn(0, ceiling)
        val line = guard.threshold
        val guarded = guard.enabled && !guard.unlocked && line < ceiling
        // Already above the line: no crossing, so no question.
        if (!guarded || target <= line || (current != null && current > line)) return VolumeDecision.Send(target)
        return VolumeDecision.Held(send = line.takeIf { it != current }, threshold = line, wanted = target, ask = source == VolumeSource.APP)
    }

    /** The guard re-arms once the level is below the threshold again; unknown changes nothing. */
    fun stillUnlocked(unlocked: Boolean, level: Int?, threshold: Int): Boolean = unlocked && (level == null || level >= threshold)

    /**
     * Where the guard stands on the rail, or null when it can't stop anything right now: off,
     * unlocked, above the ceiling, or the level already above it.
     */
    fun guardLine(guard: VolumeGuardState, level: Int?, ceiling: Int): Int? =
        guard.threshold.takeIf { guard.enabled && !guard.unlocked && it < ceiling && (level == null || level <= it) }

    /** When a cap for tonight set at [now] ends. */
    fun capUntil(now: Long, zone: ZoneId, until: CapUntil): Long = when (until) {
        CapUntil.ONE_HOUR -> now + 3_600_000L
        CapUntil.THREE_HOURS -> now + 3 * 3_600_000L
        CapUntil.MORNING -> {
            val here = Instant.ofEpochMilli(now).atZone(zone)
            val today = here.toLocalDate().atTime(MORNING).atZone(zone)
            (if (today.isAfter(here)) today else here.toLocalDate().plusDays(1).atTime(MORNING).atZone(zone)).toInstant().toEpochMilli()
        }
    }

    /** Always [PRESET_SLOTS] slots, whatever was stored. */
    fun presets(stored: List<Int?>): List<Int?> = List(PRESET_SLOTS) { stored.getOrNull(it) }

    fun withPreset(stored: List<Int?>, slot: Int, level: Int?): List<Int?> =
        presets(stored).mapIndexed { index, value -> if (index == slot) level?.coerceIn(0, TuttiController.MAX_VOLUME) else value }

    /** A threshold the settings can hold: never 0 (everything would ask), never the top. */
    fun threshold(level: Int): Int = level.coerceIn(MIN_GUARD, TuttiController.MAX_VOLUME - 1)

    const val MIN_GUARD = 5
}
