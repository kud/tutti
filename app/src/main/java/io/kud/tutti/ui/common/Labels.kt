package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.protocol.atv.KeyGroup
import io.kud.tutti.protocol.atv.NamedKeys
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.bravia.Ircc
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.settings.optionLabel

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
    TheatreFeature.HDMI_ARC -> R.string.bar_hdmi_arc
    TheatreFeature.AUTO_STANDBY -> R.string.bar_auto_standby
    TheatreFeature.NET_BT_STANDBY -> R.string.bar_net_bt_standby
    TheatreFeature.AUTO_UPDATE -> R.string.bar_auto_update
    TheatreFeature.MODEL -> R.string.bar_model
    TheatreFeature.FIRMWARE_VERSION -> R.string.bar_firmware
    TheatreFeature.BLUETOOTH_MODE -> R.string.bar_bt_mode
    TheatreFeature.BLUETOOTH_QUALITY -> R.string.bar_bt_quality
    TheatreFeature.TEMPERATURE -> R.string.technical_bar_temperature
    TheatreFeature.TIMEZONE -> R.string.technical_bar_timezone
    TheatreFeature.LANGUAGE -> R.string.technical_bar_language
    TheatreFeature.REGION -> R.string.technical_bar_region
    TheatreFeature.MANUFACTURER -> R.string.technical_bar_manufacturer
    TheatreFeature.DEVICE_NAME -> R.string.technical_bar_name
    TheatreFeature.TV_AV_SYNC -> R.string.technical_bar_tv_av_sync
    TheatreFeature.NETWORK_MODE -> R.string.technical_bar_network_mode
    TheatreFeature.NETWORK_DHCP -> R.string.technical_bar_dhcp
}

/** TV keys offered as shortcuts. */
val ShortcutKeys: List<Int> = listOf(V2Key.SETTINGS, V2Key.TV_INPUT, V2Key.GUIDE, V2Key.CAPTIONS, V2Key.ASSIST, V2Key.INFO)

@Composable
fun keyLabel(keyCode: Int): String {
    when (keyCode) {
        V2Key.SETTINGS -> return stringResource(R.string.key_settings)
        V2Key.TV_INPUT -> return stringResource(R.string.key_input)
        V2Key.GUIDE -> return stringResource(R.string.key_guide)
        V2Key.CAPTIONS -> return stringResource(R.string.key_captions)
        V2Key.ASSIST -> return stringResource(R.string.key_assistant)
        V2Key.INFO -> return stringResource(R.string.key_info)
    }
    val named = NamedKeys.byCode(keyCode) ?: return stringResource(R.string.key_generic, keyCode)
    if (named.group == KeyGroup.DIGITS) return stringResource(R.string.key_digit, keyCode - V2Key.NUM_0)
    return namedKeyRes(named.name)?.let { stringResource(it) } ?: stringResource(R.string.key_generic, keyCode)
}

@StringRes
private fun namedKeyRes(name: String): Int? = when (name) {
    "DPAD_UP" -> R.string.key_dpad_up
    "DPAD_DOWN" -> R.string.key_dpad_down
    "DPAD_LEFT" -> R.string.key_dpad_left
    "DPAD_RIGHT" -> R.string.key_dpad_right
    "DPAD_CENTER" -> R.string.key_dpad_center
    "BACK" -> R.string.key_back
    "HOME" -> R.string.key_home
    "MENU" -> R.string.key_menu
    "APP_SWITCH" -> R.string.key_app_switch
    "ALL_APPS" -> R.string.key_all_apps
    "SEARCH" -> R.string.key_search
    "NOTIFICATION" -> R.string.key_notification
    "TV_CONTENTS_MENU" -> R.string.key_tv_contents_menu
    "TV_MEDIA_CONTEXT_MENU" -> R.string.key_tv_media_context_menu
    "PAGE_UP" -> R.string.key_page_up
    "PAGE_DOWN" -> R.string.key_page_down
    "MEDIA_PLAY_PAUSE" -> R.string.key_media_play_pause
    "MEDIA_PLAY" -> R.string.key_media_play
    "MEDIA_PAUSE" -> R.string.key_media_pause
    "MEDIA_STOP" -> R.string.key_media_stop
    "MEDIA_NEXT" -> R.string.key_media_next
    "MEDIA_PREVIOUS" -> R.string.key_media_previous
    "MEDIA_REWIND" -> R.string.key_media_rewind
    "MEDIA_FAST_FORWARD" -> R.string.key_media_fast_forward
    "MEDIA_SKIP_FORWARD" -> R.string.key_media_skip_forward
    "MEDIA_SKIP_BACKWARD" -> R.string.key_media_skip_backward
    "MEDIA_RECORD" -> R.string.key_media_record
    "MEDIA_TOP_MENU" -> R.string.key_media_top_menu
    "MEDIA_AUDIO_TRACK" -> R.string.key_media_audio_track
    "TV_AUDIO_DESCRIPTION" -> R.string.key_tv_audio_description
    "POWER" -> R.string.key_power
    "TV_POWER" -> R.string.key_tv_power
    "SLEEP" -> R.string.key_sleep
    "WAKEUP" -> R.string.key_wakeup
    "TV" -> R.string.key_tv
    "TV_INPUT_HDMI_1" -> R.string.key_tv_input_hdmi_1
    "TV_INPUT_HDMI_2" -> R.string.key_tv_input_hdmi_2
    "TV_INPUT_HDMI_3" -> R.string.key_tv_input_hdmi_3
    "TV_INPUT_HDMI_4" -> R.string.key_tv_input_hdmi_4
    "CHANNEL_UP" -> R.string.key_channel_up
    "CHANNEL_DOWN" -> R.string.key_channel_down
    "LAST_CHANNEL" -> R.string.key_last_channel
    "DVR" -> R.string.key_dvr
    "TV_TELETEXT" -> R.string.key_tv_teletext
    "TV_ZOOM_MODE" -> R.string.key_tv_zoom_mode
    "WINDOW" -> R.string.key_window
    "BRIGHTNESS_UP" -> R.string.key_brightness_up
    "BRIGHTNESS_DOWN" -> R.string.key_brightness_down
    "VOLUME_UP" -> R.string.key_volume_up
    "VOLUME_DOWN" -> R.string.key_volume_down
    "VOLUME_MUTE" -> R.string.key_volume_mute
    "PROG_RED" -> R.string.key_prog_red
    "PROG_GREEN" -> R.string.key_prog_green
    "PROG_YELLOW" -> R.string.key_prog_yellow
    "PROG_BLUE" -> R.string.key_prog_blue
    else -> null
}

@DrawableRes
fun keyIcon(keyCode: Int): Int = when (keyCode) {
    V2Key.SETTINGS -> R.drawable.ic_settings
    V2Key.HOME -> R.drawable.ic_home
    V2Key.BACK -> R.drawable.ic_back
    V2Key.POWER, 177 -> R.drawable.ic_power
    V2Key.SEARCH, V2Key.ASSIST -> R.drawable.ic_mic
    V2Key.TV_INPUT, V2Key.TV_INPUT_HDMI_1, V2Key.TV_INPUT_HDMI_2, V2Key.TV_INPUT_HDMI_3, V2Key.TV_INPUT_HDMI_4 -> R.drawable.ic_input
    V2Key.VOLUME_UP, V2Key.VOLUME_DOWN -> R.drawable.ic_volume_up
    V2Key.VOLUME_MUTE -> R.drawable.ic_volume_off
    V2Key.ALL_APPS, V2Key.APP_SWITCH -> R.drawable.ic_apps
    223 -> R.drawable.ic_bedtime
    V2Key.INFO -> R.drawable.ic_info
    V2Key.GUIDE -> R.drawable.ic_guide
    V2Key.CAPTIONS -> R.drawable.ic_subtitles
    V2Key.MEDIA_AUDIO_TRACK, 252 -> R.drawable.ic_audiotrack
    V2Key.MEDIA_PLAY -> R.drawable.ic_play
    V2Key.MEDIA_PAUSE -> R.drawable.ic_pause
    V2Key.MEDIA_STOP -> R.drawable.ic_stop
    V2Key.MEDIA_NEXT -> R.drawable.ic_skip_next
    V2Key.MEDIA_PREVIOUS -> R.drawable.ic_skip_previous
    // Skips jump by a few seconds: drawn as the rewind and fast-forward they resemble.
    V2Key.MEDIA_REWIND, 273 -> R.drawable.ic_fast_rewind
    V2Key.MEDIA_FAST_FORWARD, 272 -> R.drawable.ic_fast_forward
    130 -> R.drawable.ic_record
    226 -> R.drawable.ic_article
    V2Key.CHANNEL_UP -> R.drawable.ic_arrow_up
    V2Key.CHANNEL_DOWN -> R.drawable.ic_arrow_down
    // Only play/pause wears play/pause: every playback key above has its own glyph (KeyIconTest).
    else -> when (NamedKeys.byCode(keyCode)?.group) {
        KeyGroup.PLAYBACK -> R.drawable.ic_play_pause
        KeyGroup.NAVIGATION -> R.drawable.ic_dpad
        KeyGroup.DIGITS, KeyGroup.COLOURS -> R.drawable.ic_dialpad
        else -> R.drawable.ic_tv
    }
}

@StringRes
fun keyGroupLabel(group: KeyGroup): Int = when (group) {
    KeyGroup.NAVIGATION -> R.string.picker_group_navigation
    KeyGroup.PLAYBACK -> R.string.picker_group_playback
    KeyGroup.TV -> R.string.picker_group_tv
    KeyGroup.SOUND -> R.string.picker_group_sound
    KeyGroup.DIGITS -> R.string.picker_group_digits
    KeyGroup.COLOURS -> R.string.picker_group_colours
}

/** A bar value as the picker and the pin's name say it: On / Off, a signed level, a named choice. */
@Composable
fun barValueLabel(feature: TheatreFeature, value: String): String = when (val kind = feature.kind) {
    is FeatureKind.Toggle -> stringResource(if (value == kind.on) R.string.state_on else R.string.state_off)
    is FeatureKind.Level -> value.toIntOrNull()?.let { if (it > 0 && kind.min < 0) "+$it" else if (it < 0) "\u2212${-it}" else "$it" } ?: value
    is FeatureKind.Choice -> when (value) {
        "tv" -> "TV"
        "hdmi1" -> "HDMI 1"
        "spotify" -> "Spotify"
        "bluetooth" -> "Bluetooth"
        "airplay2" -> "AirPlay 2"
        else -> optionLabel(value)
    }
    FeatureKind.ReadOnly -> when {
        // The bar's raw "rx" / "tx" read as jargon (seen on the Bar 9, 2026-10-24).
        feature == TheatreFeature.BLUETOOTH_MODE && value == "rx" -> stringResource(R.string.bar_bt_mode_rx)
        feature == TheatreFeature.BLUETOOTH_MODE && value == "tx" -> stringResource(R.string.bar_bt_mode_tx)
        else -> value
    }
}

/** Icons a pin can wear instead of its action's, by name (stored in DockSlot.icon). */
val PinGlyphs: List<String> = listOf(
    "bolt", "movie", "tv", "bedtime", "music_note", "speaker", "volume_off", "input",
    "play_pause", "tune", "sports_esports", "sports_soccer", "newspaper", "mic", "home", "settings",
)

@DrawableRes
fun pinGlyphRes(name: String): Int = when (name) {
    "bolt" -> R.drawable.ic_bolt
    "speaker" -> R.drawable.ic_speaker
    "volume_off" -> R.drawable.ic_volume_off
    "input" -> R.drawable.ic_input
    "play_pause" -> R.drawable.ic_play_pause
    "tune" -> R.drawable.ic_tune
    "mic" -> R.drawable.ic_mic
    "home" -> R.drawable.ic_home
    "settings" -> R.drawable.ic_settings
    else -> glyphRes(name)
}

@StringRes
fun pinGlyphLabel(name: String): Int = when (name) {
    "bolt" -> R.string.pin_glyph_bolt
    "movie" -> R.string.pin_glyph_movie
    "tv" -> R.string.pin_glyph_tv
    "bedtime" -> R.string.pin_glyph_bedtime
    "music_note" -> R.string.pin_glyph_music_note
    "speaker" -> R.string.pin_glyph_speaker
    "volume_off" -> R.string.pin_glyph_volume_off
    "input" -> R.string.pin_glyph_input
    "play_pause" -> R.string.pin_glyph_play_pause
    "tune" -> R.string.pin_glyph_tune
    "sports_esports" -> R.string.pin_glyph_sports_esports
    "sports_soccer" -> R.string.pin_glyph_sports_soccer
    "newspaper" -> R.string.pin_glyph_newspaper
    "mic" -> R.string.pin_glyph_mic
    "home" -> R.string.pin_glyph_home
    "settings" -> R.string.pin_glyph_settings
    else -> R.string.pin_icon
}

/** A pin's own name, else its action's. */
@Composable
fun slotLabel(slot: DockSlot, state: TuttiUiState): String? =
    slot.label?.takeIf { it.isNotBlank() } ?: slot.primary?.let { actionLabel(it, state) }

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
fun actionLabel(action: DockAction, state: TuttiUiState): String = when (action) {
    is DockAction.LaunchApp -> action.label
    is DockAction.Input -> action.label
    is DockAction.ToggleBar -> TheatreFeature.fromKey(action.featureKey)?.let { stringResource(featureLabel(it)) } ?: action.featureKey
    is DockAction.SetBar -> TheatreFeature.fromKey(action.featureKey)?.let {
        stringResource(R.string.action_set, stringResource(featureLabel(it)), barValueLabel(it, action.value))
    } ?: action.featureKey
    is DockAction.RunScene -> state.scenes.firstOrNull { it.scene.id == action.sceneId }?.scene?.name
        ?: stringResource(R.string.scene_missing)
    is DockAction.Key -> keyLabel(action.keyCode).let { if (action.long) stringResource(R.string.action_held, it) else it }
    is DockAction.Ircc -> Ircc.displayName(action.name)
    is DockAction.PictureMode -> stringResource(R.string.action_picture, action.label)
    is DockAction.TvSoundOutput -> stringResource(tvSoundOutputLabel(action.terminal))
    is DockAction.Macro -> action.name.ifBlank {
        action.steps.count { it is io.kud.tutti.platform.data.MacroStep.Run }.let { pluralStringResource(R.plurals.macro_steps, it, it) }
    }
}

@DrawableRes
fun actionIcon(action: DockAction, state: TuttiUiState): Int = when (action) {
    is DockAction.LaunchApp -> R.drawable.ic_apps
    is DockAction.Input -> R.drawable.ic_input
    is DockAction.ToggleBar -> barIcon(action.featureKey)
    is DockAction.SetBar -> barIcon(action.featureKey)
    is DockAction.RunScene -> state.scenes.firstOrNull { it.scene.id == action.sceneId }?.scene?.glyph?.let(::glyphRes) ?: R.drawable.ic_scene
    is DockAction.Key -> keyIcon(action.keyCode)
    is DockAction.Ircc -> R.drawable.ic_tv
    is DockAction.PictureMode -> R.drawable.ic_tune
    is DockAction.TvSoundOutput -> tvSoundOutputIcon(action.terminal)
    is DockAction.Macro -> R.drawable.ic_bolt
}

/** A bar feature's glyph, the same on its Sound sheet tile and on a Dock pin, so one is recognised in the other. */
@DrawableRes
fun barIcon(featureKey: String): Int = when (featureKey) {
    TheatreFeature.NIGHT.key -> R.drawable.ic_bedtime
    TheatreFeature.VOICE.key -> R.drawable.ic_voice_enhance
    TheatreFeature.VOICE_ZOOM.key -> R.drawable.ic_frame_person
    TheatreFeature.SOUND_FIELD.key -> R.drawable.ic_surround_sound
    TheatreFeature.SPATIAL_360.key -> R.drawable.ic_360
    TheatreFeature.AUTO_VOLUME.key -> R.drawable.ic_graphic_eq
    TheatreFeature.MUTE.key -> R.drawable.ic_volume_off
    TheatreFeature.VOLUME.key -> R.drawable.ic_volume_up
    TheatreFeature.INPUT.key -> R.drawable.ic_input
    else -> R.drawable.ic_speaker
}

/** A pin's icon: the one he chose, else its action's. */
@DrawableRes
fun slotIcon(slot: DockSlot, state: TuttiUiState): Int? =
    slot.icon?.let(::pinGlyphRes) ?: slot.primary?.let { actionIcon(it, state) }

/** "Sound to the bar" / "Sound to the TV speakers": the shortcut names where the sound goes, not the wire value. */
@StringRes
fun tvSoundOutputLabel(terminal: String): Int = when (terminal) {
    io.kud.tutti.protocol.bravia.TvSetting.TERMINAL_SPEAKER -> R.string.action_tv_sound_speaker
    else -> R.string.action_tv_sound_bar
}

@DrawableRes
fun tvSoundOutputIcon(terminal: String): Int =
    if (terminal == io.kud.tutti.protocol.bravia.TvSetting.TERMINAL_SPEAKER) R.drawable.ic_tv else R.drawable.ic_speaker
