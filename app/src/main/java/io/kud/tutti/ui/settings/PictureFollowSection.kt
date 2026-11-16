package io.kud.tutti.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.apps.AppHistory
import io.kud.tutti.protocol.apps.AppLibrary
import io.kud.tutti.protocol.picture.PictureFollow
import io.kud.tutti.protocol.picture.PictureFollowConfig
import io.kud.tutti.ui.common.OptionsRow
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow
import io.kud.tutti.ui.common.SwitchRow
import io.kud.tutti.ui.scenes.appName
import io.kud.tutti.ui.scenes.inputName

/** "No rule of his own" in a row's choices: the suggestion for an app, nothing for an input. */
private const val UNSET = ""

/**
 * "The picture follows what's on" (`PictureFollow`), self-contained so it can sit wherever the
 * TV's picture settings live: the switch, the suggestions, then one row per app and input that has
 * a rule or could take one (the TV's suggested apps, the app in front, the TV's connected inputs).
 * Only shown while IP control answers, like the rest of the picture settings.
 */
@Composable
internal fun PictureFollowGroup(state: TuttiUiState, controller: TuttiController) {
    val config = state.prefs.pictureFollow
    val follow by controller.pictureFollow.collectAsState()
    val pictureSetting = state.tv.settings.picture.firstOrNull { it.target == "pictureMode" }
    val modes = state.tv.pictureModes.ifEmpty { pictureSetting?.candidates?.filter { it.isAvailable }?.map { it.value }.orEmpty() }
    val word: @Composable (String) -> String = { tvValueName(it, pictureSetting) }
    SettingsGroup(stringResource(R.string.picture_follow_group)) {
        SwitchRow(
            label = stringResource(R.string.picture_follow_title),
            checked = config.enabled,
            onCheckedChange = { on -> controller.updatePictureFollow { it.copy(enabled = on) } },
            supporting = stringResource(R.string.picture_follow_line),
            notice = if (config.enabled && follow.paused) stringResource(R.string.picture_follow_paused) else null,
        )
        if (!config.enabled) return@SettingsGroup
        SwitchRow(
            label = stringResource(R.string.picture_follow_suggestions),
            checked = config.suggestions,
            onCheckedChange = { on -> controller.updatePictureFollow { it.copy(suggestions = on) } },
            supporting = stringResource(R.string.picture_follow_suggestions_line),
        )
        if (modes.isEmpty()) {
            SettingsRow(title = stringResource(R.string.picture_follow_waiting))
            return@SettingsGroup
        }
        val leave = stringResource(R.string.picture_follow_leave)
        val installed = state.tv.apps.mapNotNull { AppLibrary.packageOf(it.uri) }.toSet()
        val front = state.remote.currentApp?.takeIf { it !in AppHistory.PASS_THROUGH }
        val apps = (config.apps.keys + PictureFollow.SUGGESTED.keys.filter { it in installed } + listOfNotNull(front)).distinct()
        apps.forEach { pkg ->
            val suggested = PictureFollow.suggestion(config, pkg, modes)
            val unsetLabel = suggested?.let { stringResource(R.string.picture_follow_suggested, word(it)) } ?: leave
            val chosen = config.apps[pkg] ?: UNSET
            val options = listOf(UNSET to unsetLabel) +
                (if (suggested != null) listOf(PictureFollowConfig.LEAVE to leave) else emptyList()) +
                modes.map { it to word(it) }
            OptionsRow(
                label = appName(pkg),
                selected = chosen,
                valueLabel = options.firstOrNull { it.first == chosen }?.second ?: word(chosen),
                options = options,
                onSelect = { value ->
                    controller.updatePictureFollow { it.copy(apps = if (value == UNSET) it.apps - pkg else it.apps + (pkg to value)) }
                },
            )
        }
        state.tv.inputs.filter { it.connected || it.uri in config.inputs }.forEach { input ->
            val chosen = config.inputs[input.uri] ?: UNSET
            val options = listOf(UNSET to leave) + modes.map { it to word(it) }
            OptionsRow(
                label = inputName(input.uri, state),
                selected = chosen,
                valueLabel = options.firstOrNull { it.first == chosen }?.second ?: word(chosen),
                options = options,
                onSelect = { value ->
                    controller.updatePictureFollow { it.copy(inputs = if (value == UNSET || value == PictureFollowConfig.LEAVE) it.inputs - input.uri else it.inputs + (input.uri to value)) }
                },
            )
        }
    }
}
