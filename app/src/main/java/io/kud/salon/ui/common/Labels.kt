package io.kud.salon.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kud.salon.R
import io.kud.salon.controller.SalonUiState
import io.kud.salon.platform.data.DockAction
import io.kud.salon.protocol.atv.V2Key
import io.kud.salon.protocol.theatre.TheatreFeature

/** Scene glyphs offered in the editor, by Material Symbols name (stored in Scene.glyph). */
val SceneGlyphs: List<String> = listOf("movie", "tv", "bedtime", "music_note", "sports_soccer", "sports_esports", "newspaper", "auto_awesome")

@DrawableRes
fun glyphRes(name: String): Int = when (name) {
    "movie" -> R.drawable.ic_movie
    "tv" -> R.drawable.ic_tv
    "bedtime" -> R.drawable.ic_bedtime
    "music_note" -> R.drawable.ic_music
    "sports_soccer" -> R.drawable.ic_sport
    "sports_esports" -> R.drawable.ic_game
    "newspaper" -> R.drawable.ic_article
    else -> R.drawable.ic_scene
}

@StringRes
fun featureLabel(feature: TheatreFeature): Int = when (feature) {
    TheatreFeature.POWER -> R.string.bar_power
    TheatreFeature.VOLUME -> R.string.bar_volume
    TheatreFeature.MUTE -> R.string.bar_mute
    TheatreFeature.INPUT -> R.string.bar_input
    TheatreFeature.BASS -> R.string.bar_bass
    TheatreFeature.REAR_LEVEL -> R.string.bar_rear
    TheatreFeature.VOICE -> R.string.bar_voice
    TheatreFeature.SOUND_FIELD -> R.string.bar_sound_field
    TheatreFeature.NIGHT -> R.string.bar_night
    TheatreFeature.DRC -> R.string.bar_drc
    TheatreFeature.AUTO_VOLUME -> R.string.bar_auto_volume
    TheatreFeature.SPATIAL_360 -> R.string.bar_360
    TheatreFeature.IMAX -> R.string.bar_imax
    TheatreFeature.AV_SYNC -> R.string.bar_av_sync
    TheatreFeature.DUAL_MONO -> R.string.bar_dual_mono
    TheatreFeature.VOICE_ZOOM -> R.string.bar_voice_zoom
    TheatreFeature.VOICE_ZOOM_LEVEL -> R.string.bar_voice_zoom_level
    TheatreFeature.HDMI_CEC -> R.string.bar_hdmi_cec
    TheatreFeature.HDMI_PASSTHROUGH -> R.string.bar_hdmi_passthrough
    TheatreFeature.HDMI_STANDBY_LINK -> R.string.bar_hdmi_standby
    TheatreFeature.AUTO_STANDBY -> R.string.bar_auto_standby
    TheatreFeature.AUTO_UPDATE -> R.string.bar_auto_update
    TheatreFeature.MODEL_NAME -> R.string.bar_model
    TheatreFeature.FIRMWARE_VERSION -> R.string.bar_firmware
    TheatreFeature.BLUETOOTH_MODE -> R.string.bar_bt_mode
    TheatreFeature.BLUETOOTH_QUALITY -> R.string.bar_bt_quality
}

/** TV keys offered as shortcuts. */
val ShortcutKeys: List<Int> = listOf(V2Key.SETTINGS, V2Key.TV_INPUT, V2Key.GUIDE, V2Key.CAPTIONS, V2Key.ASSIST, V2Key.INFO)

@Composable
fun keyLabel(keyCode: Int): String = when (keyCode) {
    V2Key.SETTINGS -> stringResource(R.string.key_settings)
    V2Key.TV_INPUT -> stringResource(R.string.key_input)
    V2Key.GUIDE -> stringResource(R.string.key_guide)
    V2Key.CAPTIONS -> stringResource(R.string.key_captions)
    V2Key.ASSIST -> stringResource(R.string.key_assistant)
    V2Key.INFO -> stringResource(R.string.key_info)
    else -> stringResource(R.string.key_generic, keyCode)
}

/** Bar settings a single tap can toggle (dock and long-press). */
val ToggleFeatures: List<TheatreFeature> = listOf(
    TheatreFeature.NIGHT,
    TheatreFeature.VOICE,
    TheatreFeature.SOUND_FIELD,
    TheatreFeature.SPATIAL_360,
    TheatreFeature.AUTO_VOLUME,
    TheatreFeature.MUTE,
)

@Composable
fun actionLabel(action: DockAction, state: SalonUiState): String = when (action) {
    is DockAction.LaunchApp -> action.label
    is DockAction.Input -> action.label
    is DockAction.ToggleBar -> TheatreFeature.fromKey(action.featureKey)?.let { stringResource(featureLabel(it)) } ?: action.featureKey
    is DockAction.RunScene -> state.scenes.firstOrNull { it.scene.id == action.sceneId }?.scene?.name
        ?: stringResource(R.string.scene_missing)
    is DockAction.Key -> keyLabel(action.keyCode)
    is DockAction.Macro -> action.name
}

@DrawableRes
fun actionIcon(action: DockAction, state: SalonUiState): Int = when (action) {
    is DockAction.LaunchApp -> R.drawable.ic_apps
    is DockAction.Input -> R.drawable.ic_input
    is DockAction.ToggleBar -> when (action.featureKey) {
        TheatreFeature.NIGHT.key -> R.drawable.ic_bedtime
        TheatreFeature.MUTE.key -> R.drawable.ic_volume_off
        else -> R.drawable.ic_speaker
    }
    is DockAction.RunScene -> state.scenes.firstOrNull { it.scene.id == action.sceneId }?.scene?.glyph?.let(::glyphRes) ?: R.drawable.ic_scene
    is DockAction.Key -> if (action.keyCode == V2Key.SETTINGS) R.drawable.ic_settings else R.drawable.ic_tv
    is DockAction.Macro -> R.drawable.ic_bolt
}
