package io.kud.tutti.ui.scenes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.atv.AppCatalog
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneKey
import io.kud.tutti.protocol.scenes.SceneValue
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.barValueLabel
import io.kud.tutti.ui.remote.signed
import io.kud.tutti.ui.settings.outputNames
import io.kud.tutti.ui.settings.tvValueName

fun appName(packageName: String): String = AppCatalog.byPackage(packageName)?.name ?: packageName

fun inputName(uri: String, state: TuttiUiState): String =
    state.tv.inputs.firstOrNull { it.uri == uri }?.let { it.label.ifBlank { it.title } } ?: uri.substringAfter("extInput:").substringBefore("?").uppercase()

@Composable
fun keyName(key: SceneKey): String = stringResource(
    when (key) {
        SceneKey.TV_INPUT -> R.string.scene_key_input
        SceneKey.TV_APP -> R.string.scene_key_app
        SceneKey.PICTURE_MODE -> R.string.scene_key_picture
        SceneKey.TV_AUDIO_OUTPUT -> R.string.scene_key_tv_output
        SceneKey.PICTURE_OFF -> R.string.screen_off_scene_key
        SceneKey.BAR_INPUT -> R.string.bar_input
        SceneKey.SOUND_FIELD -> R.string.bar_sound_field
        SceneKey.VOICE -> R.string.bar_voice
        SceneKey.NIGHT -> R.string.bar_night
        SceneKey.SPATIAL_360 -> R.string.bar_360
        SceneKey.VOICE_ZOOM -> R.string.bar_voice_zoom
        SceneKey.BASS -> R.string.bar_bass
        SceneKey.VOLUME -> R.string.bar_volume
        SceneKey.VOLUME_CAP -> R.string.scene_key_cap
        SceneKey.TARGET -> R.string.scene_key_target
    },
)

/**
 * One value in plain words: "Voix +", "Basses −2", "Max 20". [targets]: the devices the remote can
 * point at, id to name ([io.kud.tutti.controller.TuttiController.targetNames]).
 */
@Composable
fun settingChip(key: SceneKey, value: SceneValue, state: TuttiUiState, targets: Map<String, String> = emptyMap()): String {
    val flag = (value as? SceneValue.Flag)?.on
    val level = (value as? SceneValue.Level)?.value
    val text = (value as? SceneValue.Text)?.value.orEmpty()
    return when (key) {
        SceneKey.TV_INPUT -> inputName(text, state)
        SceneKey.TV_APP -> appName(text)
        SceneKey.PICTURE_MODE -> stringResource(R.string.summary_picture, tvValueName(text, state.tv.settings.picture.firstOrNull { it.target == "pictureMode" }))
        SceneKey.TV_AUDIO_OUTPUT -> stringResource(R.string.summary_tv_output, outputNames()[text] ?: text)
        SceneKey.PICTURE_OFF -> stringResource(if (flag == true) R.string.screen_off_summary_on else R.string.screen_off_summary_off)
        SceneKey.BAR_INPUT -> stringResource(R.string.summary_bar_input, barValueLabel(TheatreFeature.INPUT, text))
        SceneKey.SOUND_FIELD -> stringResource(if (flag == true) R.string.summary_sound_field_on else R.string.summary_sound_field_off)
        SceneKey.VOICE -> stringResource(if (flag == true) R.string.summary_voice_on else R.string.summary_voice_off)
        SceneKey.NIGHT -> stringResource(if (flag == true) R.string.summary_night_on else R.string.summary_night_off)
        SceneKey.SPATIAL_360 -> stringResource(if (flag == true) R.string.summary_360_on else R.string.summary_360_off)
        SceneKey.VOICE_ZOOM -> stringResource(if (flag == true) R.string.summary_voice_zoom_on else R.string.summary_voice_zoom_off)
        SceneKey.BASS -> stringResource(R.string.summary_bass, signed(level ?: 0))
        SceneKey.VOLUME -> stringResource(R.string.summary_volume, level ?: 0)
        SceneKey.VOLUME_CAP -> stringResource(R.string.summary_cap, level ?: 0)
        SceneKey.TARGET -> stringResource(R.string.summary_target, targets[text] ?: stringResource(R.string.summary_target_unknown))
    }
}

/** The gallery card's plain-words summary: "Spatial · Voix + · Basses −2 · puis Netflix". */
@Composable
fun sceneSummary(scene: Scene, state: TuttiUiState, targets: Map<String, String> = emptyMap()): String {
    val parts = scene.settings.entries.sortedBy { it.key.ordinal }.map { settingChip(it.key, it.value, state, targets) }.toMutableList()
    scene.launchApp?.let { parts += stringResource(R.string.summary_then_app, appName(it)) }
    return if (parts.isEmpty()) stringResource(R.string.summary_empty) else parts.joinToString(" · ")
}
