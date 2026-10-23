package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.atv.AppCatalog
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.bravia.PlayingContent
import io.kud.tutti.protocol.bravia.PowerStatus

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
    if (state.devices.tv == null) null else foregroundAppName(state.remote.currentApp, state.links.tv, state.tv.power)

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
        nowShowing(state.tv.playing, state.tv.inputs, state.links.rest, state.remote.currentApp, state.links.tv, state.tv.power)
    }

private fun String.clean(): String? = trim().ifEmpty { null }
