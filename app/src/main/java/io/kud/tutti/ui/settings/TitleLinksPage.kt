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
 * Settings › Title links: optional buttons in Playback that search the title playing (Erwann,
 * 2026-11-11), each off by default: « tout le monde n'a pas forcément Trakt ». Share needs no
 * switch: it is always there. Its own file and its own row under The remote, so a "Third-party
 * services" page can replace it there whole.
 */
@Composable
internal fun TitleLinksPage(state: TuttiUiState, controller: TuttiController) {
    SettingsGroup(null, Modifier.padding(top = 8.dp)) {
        SwitchRow(
            label = stringResource(R.string.settings_trakt_link),
            supporting = stringResource(R.string.settings_trakt_link_detail),
            checked = state.prefs.traktLink,
            onCheckedChange = controller::setTraktLink,
            icon = R.drawable.ic_open_in_new,
        )
        SwitchRow(
            label = stringResource(R.string.settings_justwatch_link),
            supporting = stringResource(R.string.settings_justwatch_link_detail),
            checked = state.prefs.justWatchLink,
            onCheckedChange = controller::setJustWatchLink,
            icon = R.drawable.ic_open_in_new,
        )
    }
}
