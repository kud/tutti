package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.atv.AppCatalog
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
