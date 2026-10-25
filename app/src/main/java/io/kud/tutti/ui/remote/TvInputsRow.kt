package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.ui.settings.spoken

/** One of the TV's inputs in the Power sheet: its [name] in words, whether it is on screen, whether something is plugged in. */
data class TvInputChip(val uri: String, val name: String, val current: Boolean, val connected: Boolean)

/** The Power sheet's inputs row: the TV's inputs in its own order, and [reason] when a tap can't reach the TV now. */
data class TvInputsRow(val inputs: List<TvInputChip>, val reason: String?)

/**
 * The TV's inputs under the power choices (benchmark idea, Iris, 2026-10-25), or null to hide
 * them. They come from `getCurrentExternalInputsStatus`, so only IP control lists them: without
 * it, or before the TV answered, there is no row rather than a guessed "HDMI 1…4". Each is named
 * as the TV names it ([spoken]): the label the TV holds for it, which is the plugged-in device's
 * CEC name when it sent one, else the port's title. A switch rides IP control like the screen-off
 * row, so it goes down with REST and while the TV is on standby.
 */
fun tvInputsRow(
    ipControl: Boolean,
    inputs: List<ExternalInput>,
    currentUri: String?,
    restOnline: Boolean,
    tvPowered: Boolean?,
    downReason: String,
    standbyReason: String,
): TvInputsRow? {
    if (!ipControl || inputs.isEmpty()) return null
    val chips = inputs.map { TvInputChip(it.uri, it.spoken(), current = it.uri == currentUri, connected = it.connected) }
    val reason = when {
        !restOnline -> downReason
        tvPowered == false -> standbyReason
        else -> null
    }
    return TvInputsRow(chips, reason)
}
