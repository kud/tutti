package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.atv.AppCatalog
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.bravia.PlayingContent
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.theatre.TheatreFeature

/**
 * The app on the TV's screen, by name, for the system chip; null when there is nothing worth
 * saying. The remote v2 reports a package (`app_info.app_package`); only one the curated
 * catalogue knows becomes a name, so the home screen or an unknown app shows nothing rather than
 * a raw package id. The last package outlives the screen, so a TV out of reach or in standby
 * shows nothing too.
 */
fun foregroundAppName(currentApp: String?, tvLink: Link, tvPower: PowerStatus): String? {
    if (tvLink != Link.ONLINE || tvPower == PowerStatus.STANDBY) return null
    return currentApp?.let(AppCatalog::byPackage)?.name
}

fun foregroundAppName(state: TuttiUiState): String? =
    if (state.devices.tv == null) null else foregroundAppName(state.remote.currentApp, state.links.tvShown.link, state.tv.power)

/** What the system chip's second line says is on the TV. */
sealed interface NowShowing {
    /**
     * The TV's tuner. [text] is "2 · Channel — Programme", from whichever of the three the TV
     * sent; null when it sent none of them (the chip then says a plain "TV channel").
     */
    data class Channel(val number: String?, val name: String?, val programme: String?) : NowShowing {
        val text: String?
            get() {
                val head = listOfNotNull(number, name).joinToString(" · ").ifEmpty { null }
                return listOfNotNull(head, programme).joinToString(" — ").ifEmpty { null }
            }
    }

    /** An HDMI (or other external) input, by the label the user gave it, else the TV's own title. */
    data class Input(val title: String) : NowShowing

    /** An app the catalogue knows, by name; [packageName] finds its icon. */
    data class App(val name: String, val packageName: String) : NowShowing
}

/**
 * Tuner first, then an external input, both from getPlayingContentInfo (REST, PSK only); else the
 * app from the remote v2's package. The REST answer counts only while that link is up and the TV
 * is not in standby, so a stale channel never outlives the connection.
 */
fun nowShowing(
    playing: PlayingContent?,
    inputs: List<ExternalInput>,
    restLink: Link,
    currentApp: String?,
    tvLink: Link,
    tvPower: PowerStatus,
): NowShowing? {
    val live = playing?.takeIf { restLink == Link.ONLINE && tvPower != PowerStatus.STANDBY }
    if (live != null && live.isTuner) {
        return NowShowing.Channel(
            number = live.dispNum?.clean()?.let { n -> n.trimStart('0').ifEmpty { n } },
            name = live.title?.clean(),
            programme = live.programTitle?.clean(),
        )
    }
    if (live != null && live.isExternalInput) {
        val label = inputs.firstOrNull { it.uri == live.uri }?.label?.clean()
        (label ?: live.title?.clean())?.let { return NowShowing.Input(it) }
    }
    if (tvLink != Link.ONLINE || tvPower == PowerStatus.STANDBY) return null
    val app = currentApp?.let(AppCatalog::byPackage) ?: return null
    return NowShowing.App(app.name, app.packageName)
}

fun nowShowing(state: TuttiUiState): NowShowing? =
    if (state.devices.tv == null) {
        null
    } else {
        nowShowing(state.tv.playing, state.tv.inputs, state.links.rest, state.remote.currentApp, state.links.tvShown.link, state.tv.power)
    }

private fun String.clean(): String? = trim().ifEmpty { null }

/** What the now-showing card says: always something, so it never stands empty. */
sealed interface CardShowing {
    /** A channel, an input or a known app, from [nowShowing]. */
    data class Showing(val now: NowShowing) : CardShowing

    /** The TV asleep; [barOn] when the bar still plays ("the bar stays on"). */
    data class Standby(val barOn: Boolean) : CardShowing

    /** The TV awake with nothing the catalogue knows: the home screen, or an app we can't name. */
    data object Home : CardShowing

    /** A link that was up is being won back, past the blip ([io.kud.tutti.controller.TvLinkRule]). */
    data object Reconnecting : CardShowing

    /** The TV's link is down or still connecting. */
    data object Offline : CardShowing
}

/**
 * Standby first (the power answer outlives the app's package), then Reconnecting or Offline while
 * the TV's link isn't up, then what [nowShowing] finds, else Home. [tvLink] is the shown link
 * (`Links.tvShown`), so a blip stays on the last app. The link wins over a channel or input that
 * REST still reports: the keys are what the card vouches for (Iris, 2026-10-24). Null without a
 * TV configured: then there is nothing to show and the card stands aside.
 */
fun cardShowing(
    hasTv: Boolean,
    now: NowShowing?,
    tvLink: Link,
    tvPower: PowerStatus,
    barOn: Boolean,
    reconnecting: Boolean = false,
): CardShowing? = when {
    !hasTv -> null
    tvPower == PowerStatus.STANDBY -> CardShowing.Standby(barOn)
    reconnecting -> CardShowing.Reconnecting
    tvLink != Link.ONLINE -> CardShowing.Offline
    now != null -> CardShowing.Showing(now)
    else -> CardShowing.Home
}

fun cardShowing(state: TuttiUiState): CardShowing? = cardShowing(
    hasTv = state.devices.tv != null,
    now = nowShowing(state),
    tvLink = state.links.tvShown.link,
    tvPower = state.tv.power,
    barOn = state.links.bar == Link.ONLINE && state.bar.isOn(TheatreFeature.POWER) != false,
    reconnecting = state.links.tvShown.reconnecting,
)
