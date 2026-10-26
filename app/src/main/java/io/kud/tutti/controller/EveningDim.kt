package io.kud.tutti.controller

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Salle obscure" (Iris, 2026-10-25, benchmark): in the evening, with the TV on, the phone's own
 * screen dims while tutti is in front, so the remote doesn't light the room. Off by default; a
 * switch in Settings. The phone's brightness setting is never written: tutti only lowers its own
 * window ([dimTarget]), and the moment he moves the system brightness, tutti lets go for the rest
 * of that evening.
 */
object EveningDim {
    /**
     * The window's brightness while dimmed, on Android's 0–1 window scale: low, still legible
     * (Iris, 2026-10-26). That scale is linear in light while the system slider is perceptual, so
     * 0.03 sits about a third of the way up the slider (some 15 nits on a Pixel), where a phone
     * reads comfortably in a room lit by the TV; the first value, 0.12, sat near the slider's
     * middle, brighter than most evenings' own setting.
     */
    const val LEVEL = 0.03f

    /** The evening starts at 20:00 and ends at 05:00, wrapping past midnight. */
    const val FROM_HOUR = 20
    const val UNTIL_HOUR = 5

    /** The fade into and out of the dim. */
    const val FADE_MS = 2_000L

    fun isEvening(hour: Int): Boolean = hour >= FROM_HOUR || hour < UNTIL_HOUR

    /** Which evening [at] belongs to: the day it started, so 01:00 on the 26th is the evening of the 25th. */
    fun eveningOf(at: LocalDateTime): LocalDate = if (at.hour < UNTIL_HOUR) at.toLocalDate().minusDays(1) else at.toLocalDate()
}

/**
 * The window brightness tutti asks for, or null to leave the phone's own. Dims only when the
 * switch is on, the TV is known on, it is evening ([EveningDim.isEvening]) and he hasn't changed
 * the brightness himself this evening. A dim never brightens: with his own level known
 * ([ownLevel], manual brightness, same 0–1 scale) and already at or under [EveningDim.LEVEL],
 * the phone is left as he set it. Under adaptive brightness his level can't be read ([ownLevel]
 * null), and the dim applies as is.
 */
fun dimTarget(enabled: Boolean, tvOn: Boolean, hour: Int, userChangedBrightness: Boolean, ownLevel: Float? = null): Float? =
    when {
        !enabled || !tvOn || userChangedBrightness || !EveningDim.isEvening(hour) -> null
        ownLevel != null && ownLevel <= EveningDim.LEVEL -> null
        else -> EveningDim.LEVEL
    }
