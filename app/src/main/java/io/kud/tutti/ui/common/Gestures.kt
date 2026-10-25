package io.kud.tutti.ui.common

import androidx.annotation.StringRes
import io.kud.tutti.R

/**
 * Where a hidden gesture lives, in the order the "Gestes" sheet lists them: top of the remote
 * down to the sheets, then the surfaces outside the app.
 */
enum class GestureSurface(@StringRes val title: Int) {
    REMOTE(R.string.gestures_surface_remote),
    PAD(R.string.gestures_surface_pad),
    VOLUME(R.string.gestures_surface_volume),
    KEYS(R.string.gestures_surface_keys),
    DOCK(R.string.gestures_surface_dock),
    SCENES(R.string.gestures_surface_scenes),
    SOUND(R.string.gestures_surface_sound),
    SHEETS(R.string.gestures_surface_sheets),
    OUTSIDE(R.string.gestures_surface_outside),
}

/** The gesture itself, said in words on every row (never an icon alone). */
enum class GestureKind(@StringRes val label: Int) {
    LONG_PRESS(R.string.gestures_kind_long_press),
    HOLD(R.string.gestures_kind_hold),
    DRAG(R.string.gestures_kind_drag),
    SWIPE(R.string.gestures_kind_swipe),
    TWO_FINGER_TAP(R.string.gestures_kind_two_finger_tap),
    TWO_FINGER_DRAG(R.string.gestures_kind_two_finger_drag),
    TAP(R.string.gestures_kind_tap),
}

/**
 * One hidden gesture: [id] is stable (hints remember it), [title] names what it does, as its
 * TalkBack action does, [description] says where and when, in one sentence.
 */
data class GestureEntry(
    val id: String,
    val surface: GestureSurface,
    val kind: GestureKind,
    @StringRes val title: Int,
    @StringRes val description: Int,
)

/**
 * The single source of truth for hidden gestures (Iris, 2026-10-25, discoverability rule 2): a
 * gesture that is not in [all] does not exist. The "Gestes" sheet lists it, hints name it by [id],
 * and docs/design.md points here. Adding a long press, a two-finger gesture or a hold anywhere in
 * the app means adding its entry here, in values/ and values-fr/strings_gestures.xml.
 *
 * The grammar: tap does, long press opens the thing's menu (first row the most useful, last
 * "Pin to dock" or "Edit"), drag sets a continuous value. No double tap anywhere.
 */
object Gestures {
    val all: List<GestureEntry> = listOf(
        // Top of the remote.
        GestureEntry("power_long", GestureSurface.REMOTE, GestureKind.LONG_PRESS, R.string.gesture_power_long, R.string.gesture_power_long_detail),
        GestureEntry("now_showing_long", GestureSurface.REMOTE, GestureKind.LONG_PRESS, R.string.gesture_now_showing_long, R.string.gesture_now_showing_long_detail),
        // The pad.
        GestureEntry("pad_hold", GestureSurface.PAD, GestureKind.HOLD, R.string.gesture_pad_hold, R.string.gesture_pad_hold_detail),
        GestureEntry("pad_long", GestureSurface.PAD, GestureKind.LONG_PRESS, R.string.gesture_pad_long, R.string.gesture_pad_long_detail),
        GestureEntry("pad_two_tap", GestureSurface.PAD, GestureKind.TWO_FINGER_TAP, R.string.gesture_pad_two_tap, R.string.gesture_pad_two_tap_detail),
        GestureEntry("pad_two_vertical", GestureSurface.PAD, GestureKind.TWO_FINGER_DRAG, R.string.gesture_pad_two_vertical, R.string.gesture_pad_two_vertical_detail),
        GestureEntry("pad_two_horizontal", GestureSurface.PAD, GestureKind.TWO_FINGER_DRAG, R.string.gesture_pad_two_horizontal, R.string.gesture_pad_two_horizontal_detail),
        GestureEntry("pad_fling", GestureSurface.PAD, GestureKind.SWIPE, R.string.gesture_pad_fling, R.string.gesture_pad_fling_detail),
        // Volume.
        GestureEntry("volume_drag", GestureSurface.VOLUME, GestureKind.DRAG, R.string.gesture_volume_drag, R.string.gesture_volume_drag_detail),
        GestureEntry("volume_hold", GestureSurface.VOLUME, GestureKind.HOLD, R.string.gesture_volume_hold, R.string.gesture_volume_hold_detail),
        GestureEntry("volume_number", GestureSurface.VOLUME, GestureKind.TAP, R.string.gesture_volume_number, R.string.gesture_volume_number_detail),
        // Round keys.
        GestureEntry("back_long", GestureSurface.KEYS, GestureKind.LONG_PRESS, R.string.gesture_back_long, R.string.gesture_back_long_detail),
        GestureEntry("home_long", GestureSurface.KEYS, GestureKind.LONG_PRESS, R.string.gesture_home_long, R.string.gesture_home_long_detail),
        GestureEntry("keyboard_long", GestureSurface.KEYS, GestureKind.LONG_PRESS, R.string.gesture_keyboard_long, R.string.gesture_keyboard_long_detail),
        GestureEntry("key_long", GestureSurface.KEYS, GestureKind.LONG_PRESS, R.string.gesture_key_long, R.string.gesture_key_long_detail),
        GestureEntry("mic_hold", GestureSurface.KEYS, GestureKind.HOLD, R.string.gesture_mic_hold, R.string.gesture_mic_hold_detail),
        // Dock.
        GestureEntry("pin_long", GestureSurface.DOCK, GestureKind.LONG_PRESS, R.string.gesture_pin_long, R.string.gesture_pin_long_detail),
        GestureEntry("dock_swipe", GestureSurface.DOCK, GestureKind.SWIPE, R.string.gesture_dock_swipe, R.string.gesture_dock_swipe_detail),
        // Scenes.
        GestureEntry("scene_long", GestureSurface.SCENES, GestureKind.LONG_PRESS, R.string.gesture_scene_long, R.string.gesture_scene_long_detail),
        // The Son sheet.
        GestureEntry("bar_input_long", GestureSurface.SOUND, GestureKind.LONG_PRESS, R.string.gesture_bar_input_long, R.string.gesture_bar_input_long_detail),
        GestureEntry("bluetooth_long", GestureSurface.SOUND, GestureKind.LONG_PRESS, R.string.gesture_bluetooth_long, R.string.gesture_bluetooth_long_detail),
        GestureEntry("effect_long", GestureSurface.SOUND, GestureKind.LONG_PRESS, R.string.gesture_effect_long, R.string.gesture_effect_long_detail),
        GestureEntry("choice_long", GestureSurface.SOUND, GestureKind.LONG_PRESS, R.string.gesture_choice_long, R.string.gesture_choice_long_detail),
        // Other sheets and screens.
        GestureEntry("app_tile_long", GestureSurface.SHEETS, GestureKind.LONG_PRESS, R.string.gesture_app_tile_long, R.string.gesture_app_tile_long_detail),
        GestureEntry("all_keys_long", GestureSurface.SHEETS, GestureKind.LONG_PRESS, R.string.gesture_all_keys_long, R.string.gesture_all_keys_long_detail),
        GestureEntry("tv_setting_long", GestureSurface.SHEETS, GestureKind.LONG_PRESS, R.string.gesture_tv_setting_long, R.string.gesture_tv_setting_long_detail),
        GestureEntry("keyboard_swipe", GestureSurface.SHEETS, GestureKind.SWIPE, R.string.gesture_keyboard_swipe, R.string.gesture_keyboard_swipe_detail),
        // Outside the app.
        GestureEntry("launcher_long", GestureSurface.OUTSIDE, GestureKind.LONG_PRESS, R.string.gesture_launcher_long, R.string.gesture_launcher_long_detail),
        GestureEntry("tile_long", GestureSurface.OUTSIDE, GestureKind.LONG_PRESS, R.string.gesture_tile_long, R.string.gesture_tile_long_detail),
    )

    fun byId(id: String): GestureEntry? = all.firstOrNull { it.id == id }

    /** The sheet's sections: surfaces in their order, each with its gestures in registry order. */
    fun grouped(entries: List<GestureEntry> = all): List<Pair<GestureSurface, List<GestureEntry>>> =
        GestureSurface.entries.mapNotNull { surface -> entries.filter { it.surface == surface }.takeIf { it.isNotEmpty() }?.let { surface to it } }
}
