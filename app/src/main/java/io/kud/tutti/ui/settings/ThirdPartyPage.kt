package io.kud.tutti.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SwitchRow

/**
 * Settings › Third-party services: every `NonFreeFeature` on one page, under The remote, where Title
 * links was (2026-11-12). Every one is opt-in, and one switch turns them all off (Erwann:
 * « avoir un bouton qui permet de réactiver facilement, service libre uniquement »). The master
 * leads, so it is found first and flipped back as easily; each row under it keeps its own value and
 * says why it is off while the master is on. A new feature joins `NonFreeFeature` and gets its row
 * here; the old `TITLE_LINKS` back-stack key lands on this page ([SettingsMenu.renamed]).
 */
@Composable
internal fun ThirdPartyPage(state: TuttiUiState, controller: TuttiController) {
    val prefs = state.prefs
    val freeReason = stringResource(R.string.settings_only_free_reason).takeIf { prefs.onlyFreeServices }

    SettingsGroup(null, Modifier.padding(top = 8.dp)) {
        SwitchRow(
            label = stringResource(R.string.settings_only_free),
            supporting = stringResource(R.string.settings_only_free_detail),
            checked = prefs.onlyFreeServices,
            onCheckedChange = controller::setOnlyFreeServices,
            icon = R.drawable.ic_info,
        )
    }

    // What tutti fetches itself, to show in Playback.
    SettingsGroup(stringResource(R.string.playback_label)) {
        SwitchRow(
            label = stringResource(R.string.settings_cast_artwork),
            supporting = stringResource(R.string.settings_cast_artwork_detail),
            reason = freeReason,
            checked = prefs.castArtwork,
            onCheckedChange = controller::setCastArtwork,
            icon = R.drawable.ic_movie,
        )
        // Sends the IMDb id of what plays to Stremio's Cinemeta, so off until turned on (Erwann, 2026-11-12).
        SwitchRow(
            label = stringResource(R.string.settings_title_details),
            supporting = stringResource(R.string.settings_title_details_detail),
            reason = freeReason,
            checked = prefs.titleDetails,
            onCheckedChange = controller::setTitleDetails,
            icon = R.drawable.ic_info,
        )
    }

    // Buttons in Playback that open a search or a page for the title playing (Erwann, 2026-11-11),
    // each off by default: « tout le monde n'a pas forcément Trakt ». Share needs no switch.
    SettingsGroup(stringResource(R.string.settings_title_links)) {
        SwitchRow(
            label = stringResource(R.string.settings_trakt_link),
            supporting = stringResource(R.string.settings_trakt_link_detail),
            reason = freeReason,
            checked = prefs.traktLink,
            onCheckedChange = controller::setTraktLink,
            icon = R.drawable.ic_open_in_new,
        )
        SwitchRow(
            label = stringResource(R.string.settings_justwatch_link),
            supporting = stringResource(R.string.settings_justwatch_link_detail),
            reason = freeReason,
            checked = prefs.justWatchLink,
            onCheckedChange = controller::setJustWatchLink,
            icon = R.drawable.ic_open_in_new,
        )
        SwitchRow(
            label = stringResource(R.string.settings_imdb_link),
            supporting = stringResource(R.string.settings_imdb_link_detail),
            reason = freeReason,
            checked = prefs.imdbLink,
            onCheckedChange = controller::setImdbLink,
            icon = R.drawable.ic_open_in_new,
        )
    }
}
