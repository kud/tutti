package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.theatre.TheatreFeature

/**
 * The bar playing on its own (Erwann, 2026-10-24: no switch, a state that comes and goes by
 * itself): the TV asleep or out of reach while the bar is on and plays one of its own sources.
 * [input] is the bar's `main.input`; [tvAsleep] is true when the TV is known to be in standby,
 * false when it is only out of reach (its power unknown).
 */
data class BarAlone(val input: String, val tvAsleep: Boolean)

/** The bar's own sources: what it can play with the TV asleep. "tv" and "hdmi1" need the TV. */
val BarOwnSources: Set<String> = setOf("bluetooth", "airplay2", "spotify")

/**
 * Active when the TV is in standby ([tvPowered] false) or out of reach ([tvLink] unreachable),
 * the bar is connected and on, and its input is one of [BarOwnSources]. It ends by itself when the
 * TV wakes or the input goes back to tv or hdmi1. The bar on "tv" with the TV asleep stays plain
 * standby. Null without both devices, since there is nothing to tell apart then.
 */
fun barAlone(
    hasTv: Boolean,
    hasBar: Boolean,
    tvPowered: Boolean?,
    tvLink: Link,
    barLink: Link,
    barOn: Boolean?,
    barInput: String?,
): BarAlone? {
    if (!hasTv || !hasBar) return null
    if (barLink != Link.ONLINE || barOn != true) return null
    if (barInput == null || barInput !in BarOwnSources) return null
    val asleep = tvPowered == false
    val away = tvLink == Link.UNREACHABLE
    if (!asleep && !away) return null
    return BarAlone(barInput, tvAsleep = asleep)
}

/** The TV's power as the remote knows it: the remote link's word first, then REST's. */
fun tvPowered(state: TuttiUiState): Boolean? = state.remote.powered ?: when (state.tv.power) {
    PowerStatus.ACTIVE -> true
    PowerStatus.STANDBY -> false
    PowerStatus.UNKNOWN -> null
}

/** The bar's power, only while it answers: an unreachable bar has no power to report. */
fun barPowered(state: TuttiUiState): Boolean? = state.bar.isOn(TheatreFeature.POWER).takeIf { state.links.bar == Link.ONLINE }

fun barAlone(state: TuttiUiState): BarAlone? = barAlone(
    hasTv = state.devices.tv != null,
    hasBar = state.devices.bar != null,
    tvPowered = tvPowered(state),
    tvLink = state.links.tvShown.link,
    barLink = state.links.bar,
    barOn = state.bar.isOn(TheatreFeature.POWER),
    barInput = state.bar.value(TheatreFeature.INPUT),
)
