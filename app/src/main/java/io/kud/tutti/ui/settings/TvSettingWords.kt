package io.kud.tutti.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.protocol.bravia.TvSetting
import io.kud.tutti.protocol.bravia.TvSettingCatalog
import io.kud.tutti.protocol.bravia.TvSettingCatalog.Hue
import io.kud.tutti.protocol.bravia.TvSettingCatalog.Part
import io.kud.tutti.protocol.bravia.TvSettingCatalog.PictureSection

/**
 * The TV's settings in words (Iris, 2026-10-26): Sony's menu names, in both languages, for every
 * target and value tutti knows. A target it doesn't know keeps the TV's title or its raw name, so
 * it can still be looked up; a value it doesn't know is made readable ([TvSettingCatalog.readable]).
 */
internal object TvSettingWords {

    @StringRes
    fun target(target: String): Int? = when (target) {
        "pictureMode" -> R.string.tv_target_picture_mode
        "autoPictureMode" -> R.string.tv_target_auto_picture_mode
        "lightSensor" -> R.string.tv_target_light_sensor
        "brightness" -> R.string.tv_target_brightness
        "contrast" -> R.string.tv_target_contrast
        "gamma" -> R.string.tv_target_gamma
        "blackLevel" -> R.string.tv_target_black_level
        "blackAdjust" -> R.string.tv_target_black_adjust
        "advContrastEnhancer" -> R.string.tv_target_adv_contrast_enhancer
        "peakLuminance" -> R.string.tv_target_peak_luminance
        "autoLocalDimming" -> R.string.tv_target_auto_local_dimming
        "xtendedDynamicRange" -> R.string.tv_target_xdr
        "autoLuminanceLevel" -> R.string.tv_target_auto_luminance_level
        "autoToneCurve" -> R.string.tv_target_auto_tone_curve
        "color" -> R.string.tv_target_color
        "hue" -> R.string.tv_target_hue
        "colorTemperature" -> R.string.tv_target_color_temperature
        "liveColor" -> R.string.tv_target_live_colour
        "autoWhiteBalance" -> R.string.tv_target_auto_white_balance
        "sharpness" -> R.string.tv_target_sharpness
        "realityCreation" -> R.string.tv_target_reality_creation
        "randomNoiseReduction" -> R.string.tv_target_random_noise_reduction
        "digitalNoiseReduction" -> R.string.tv_target_digital_noise_reduction
        "smoothGradation" -> R.string.tv_target_smooth_gradation
        "motionflowMode" -> R.string.tv_target_motionflow
        "motionflowSmoothnessFilm" -> R.string.tv_target_smoothness_film
        "motionflowSmoothnessCamera" -> R.string.tv_target_smoothness_camera
        "motionflowClearness" -> R.string.tv_target_clearness
        "cineMotion" -> R.string.tv_target_cinemotion
        "filmMode" -> R.string.tv_target_film_mode
        "hdrMode" -> R.string.tv_target_hdr_mode
        "colorSpace" -> R.string.tv_target_color_space
        "hdmiVideoRange" -> R.string.tv_target_hdmi_video_range
        "pixelShift" -> R.string.tv_target_pixel_shift
        "outputTerminal" -> R.string.tv_target_output_terminal
        "soundMode" -> R.string.tv_target_sound_mode
        "tvPosition" -> R.string.tv_target_tv_position
        "subwooferLevel" -> R.string.tv_target_subwoofer_level
        "subwooferFreq" -> R.string.tv_target_subwoofer_freq
        "subwooferPhase" -> R.string.tv_target_subwoofer_phase
        "subwooferPower" -> R.string.tv_target_subwoofer_power
        else -> null
    }

    @StringRes
    fun hue(hue: Hue): Int = when (hue) {
        Hue.RED -> R.string.tv_hue_red
        Hue.GREEN -> R.string.tv_hue_green
        Hue.BLUE -> R.string.tv_hue_blue
        Hue.CYAN -> R.string.tv_hue_cyan
        Hue.MAGENTA -> R.string.tv_hue_magenta
        Hue.YELLOW -> R.string.tv_hue_yellow
    }

    /** A format string taking the colour's name: "%1$s gain", « Gain du %1$s ». */
    @StringRes
    fun part(part: Part): Int = when (part) {
        Part.GAIN -> R.string.tv_part_gain
        Part.BIAS -> R.string.tv_part_bias
        Part.HUE -> R.string.tv_part_hue
        Part.SATURATION -> R.string.tv_part_saturation
        Part.LIGHTNESS -> R.string.tv_part_lightness
    }

    @StringRes
    fun section(section: PictureSection): Int = when (section) {
        PictureSection.PICTURE -> R.string.tv_picture
        PictureSection.BRIGHTNESS -> R.string.tv_section_brightness
        PictureSection.COLOUR -> R.string.tv_section_colour
        PictureSection.WHITE_BALANCE -> R.string.tv_section_white_balance
        PictureSection.COLOUR_ADJUSTMENT -> R.string.tv_section_colour_adjustment
        PictureSection.CLARITY -> R.string.tv_section_clarity
        PictureSection.MOTION -> R.string.tv_section_motion
        PictureSection.VIDEO -> R.string.tv_section_video
        PictureSection.OTHER -> R.string.tv_section_other
    }

    /** Values Sony's menus share across targets (levels, picture modes, colour temperatures, Motionflow). */
    @StringRes
    fun value(value: String): Int? = when (value) {
        "off" -> R.string.tv_value_off
        "on" -> R.string.tv_value_on
        "low" -> R.string.tv_value_low
        "middle", "medium", "mid" -> R.string.tv_value_medium
        "high" -> R.string.tv_value_high
        "auto" -> R.string.tv_value_auto
        "custom" -> R.string.tv_value_custom
        "standard" -> R.string.tv_value_standard
        "cinema" -> R.string.tv_value_cinema
        "vivid" -> R.string.tv_value_vivid
        "game" -> R.string.tv_value_game
        "graphics" -> R.string.tv_value_graphics
        "photo" -> R.string.tv_value_photo
        "sports" -> R.string.tv_value_sports
        "animation" -> R.string.tv_value_animation
        "cool" -> R.string.tv_value_cool
        "neutral" -> R.string.tv_value_neutral
        "warm" -> R.string.tv_value_warm
        "smooth" -> R.string.tv_value_smooth
        "clear" -> R.string.tv_value_clear
        "trueCinema" -> R.string.tv_value_true_cinema
        "tableTop" -> R.string.tv_value_table_top
        "wallMount" -> R.string.tv_value_wall_mount
        "normal" -> R.string.tv_value_normal
        "reverse" -> R.string.tv_value_reverse
        "dialog", "dialogue" -> R.string.tv_value_dialogue
        "music" -> R.string.tv_value_music
        else -> null
    }
}

/** A target in plain words; a per-colour target from its colour and part; else the TV's title or the raw target. */
@Composable
internal fun settingTitle(setting: TvSetting): String {
    TvSettingWords.target(setting.target)?.let { return stringResource(it) }
    TvSettingCatalog.colourTarget(setting.target)?.let { colour ->
        return stringResource(TvSettingWords.part(colour.part), stringResource(TvSettingWords.hue(colour.hue)))
    }
    return setting.title ?: setting.target
}

/** A value in words: tutti's own, then the TV's title for it, then the value made readable. */
@Composable
internal fun settingValue(setting: TvSetting, value: String): String {
    TvSettingWords.value(value)?.let { return stringResource(it) }
    return setting.candidates.firstOrNull { it.value == value }?.title ?: TvSettingCatalog.readable(value)
}

/** A value of a TV setting known only by its value (a scene's picture mode): tutti's words, the TV's title when [setting] carries one, else readable. */
@Composable
internal fun tvValueName(value: String, setting: TvSetting?): String =
    if (setting != null) settingValue(setting, value) else TvSettingWords.value(value)?.let { stringResource(it) } ?: TvSettingCatalog.readable(value)
