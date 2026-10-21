package io.kud.tutti.ui.scenes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.atv.AppCatalog
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneKey
import io.kud.tutti.protocol.scenes.SceneValue
import io.kud.tutti.ui.remote.signed

fun appName(packageName: String): String = AppCatalog.byPackage(packageName)?.name ?: packageName

fun inputName(uri: String, state: TuttiUiState): String =
    state.tv.inputs.firstOrNull { it.uri == uri }?.let { it.label.ifBlank { it.title } } ?: uri.substringAfter("extInput:").substringBefore("?").uppercase()

@Composable
fun keyName(key: SceneKey): String = stringResource(
    when (key) {
        SceneKey.TV_INPUT -> R.string.scene_key_input
        SceneKey.TV_APP -> R.string.scene_key_app
        SceneKey.PICTURE_MODE -> R.string.scene_key_picture
        SceneKey.SOUND_FIELD -> R.string.bar_sound_field
        SceneKey.VOICE -> R.string.bar_voice
        SceneKey.NIGHT -> R.string.bar_night
        SceneKey.BASS -> R.string.bar_bass
        SceneKey.VOLUME -> R.string.bar_volume
        SceneKey.VOLUME_CAP -> R.string.scene_key_cap
    },
)

/** One value in plain words: "Voix +", "Basses −2", "Max 20". */
@Composable
fun settingChip(key: SceneKey, value: SceneValue, state: TuttiUiState): String {
    val flag = (value as? SceneValue.Flag)?.on
    val level = (value as? SceneValue.Level)?.value
    val text = (value as? SceneValue.Text)?.value.orEmpty()
    return when (key) {
        SceneKey.TV_INPUT -> inputName(text, state)
        SceneKey.TV_APP -> appName(text)
        SceneKey.PICTURE_MODE -> stringResource(R.string.summary_picture, text)
        SceneKey.SOUND_FIELD -> stringResource(if (flag == true) R.string.summary_sound_field_on else R.string.summary_sound_field_off)
        SceneKey.VOICE -> stringResource(if (flag == true) R.string.summary_voice_on else R.string.summary_voice_off)
        SceneKey.NIGHT -> stringResource(if (flag == true) R.string.summary_night_on else R.string.summary_night_off)
        SceneKey.BASS -> stringResource(R.string.summary_bass, signed(level ?: 0))
        SceneKey.VOLUME -> stringResource(R.string.summary_volume, level ?: 0)
        SceneKey.VOLUME_CAP -> stringResource(R.string.summary_cap, level ?: 0)
    }
}

/** The gallery card's plain-words summary: "Spatial · Voix + · Basses −2 · puis Netflix". */
@Composable
fun sceneSummary(scene: Scene, state: TuttiUiState): String {
    val parts = scene.settings.entries.sortedBy { it.key.ordinal }.map { settingChip(it.key, it.value, state) }.toMutableList()
    scene.launchApp?.let { parts += stringResource(R.string.summary_then_app, appName(it)) }
    return if (parts.isEmpty()) stringResource(R.string.summary_empty) else parts.joinToString(" · ")
}
