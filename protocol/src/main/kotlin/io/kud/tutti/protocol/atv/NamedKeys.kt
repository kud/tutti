package io.kud.tutti.protocol.atv

/** How the action picker groups the keys it offers. */
enum class KeyGroup { NAVIGATION, PLAYBACK, TV, SOUND, DIGITS, COLOURS }

/**
 * A remote v2 key worth offering by name. [name] is the `KEYCODE_` suffix in the protocol
 * enum (`NamedKeysTest` pins every code to it).
 */
data class NamedKey(val name: String, val code: Int, val group: KeyGroup)

/**
 * The keys a shortcut can send: the ones a TV remote has a meaning for, about seventy, never
 * the three hundred raw `KeyEvent` codes (letters, gamepad buttons, calculator keys).
 * The remote v2 protocol does not say which keys a TV honours, so this list is curated, not probed.
 */
object NamedKeys {
    val all: List<NamedKey> = buildList {
        fun group(group: KeyGroup, vararg keys: Pair<String, Int>) = keys.forEach { (name, code) -> add(NamedKey(name, code, group)) }
        group(
            KeyGroup.NAVIGATION,
            "DPAD_UP" to V2Key.DPAD_UP, "DPAD_DOWN" to V2Key.DPAD_DOWN, "DPAD_LEFT" to V2Key.DPAD_LEFT,
            "DPAD_RIGHT" to V2Key.DPAD_RIGHT, "DPAD_CENTER" to V2Key.DPAD_CENTER, "BACK" to V2Key.BACK,
            "HOME" to V2Key.HOME, "MENU" to V2Key.MENU, "SETTINGS" to V2Key.SETTINGS,
            "APP_SWITCH" to V2Key.APP_SWITCH, "ALL_APPS" to V2Key.ALL_APPS, "SEARCH" to V2Key.SEARCH,
            "ASSIST" to V2Key.ASSIST, "INFO" to V2Key.INFO, "NOTIFICATION" to 83,
            "TV_CONTENTS_MENU" to 256, "TV_MEDIA_CONTEXT_MENU" to 257,
            "PAGE_UP" to V2Key.PAGE_UP, "PAGE_DOWN" to V2Key.PAGE_DOWN,
        )
        group(
            KeyGroup.PLAYBACK,
            "MEDIA_PLAY_PAUSE" to V2Key.MEDIA_PLAY_PAUSE, "MEDIA_PLAY" to V2Key.MEDIA_PLAY,
            "MEDIA_PAUSE" to V2Key.MEDIA_PAUSE, "MEDIA_STOP" to V2Key.MEDIA_STOP,
            "MEDIA_NEXT" to V2Key.MEDIA_NEXT, "MEDIA_PREVIOUS" to V2Key.MEDIA_PREVIOUS,
            "MEDIA_REWIND" to V2Key.MEDIA_REWIND, "MEDIA_FAST_FORWARD" to V2Key.MEDIA_FAST_FORWARD,
            "MEDIA_SKIP_FORWARD" to 272, "MEDIA_SKIP_BACKWARD" to 273, "MEDIA_RECORD" to 130,
            "MEDIA_TOP_MENU" to 226, "CAPTIONS" to V2Key.CAPTIONS, "MEDIA_AUDIO_TRACK" to V2Key.MEDIA_AUDIO_TRACK,
            "TV_AUDIO_DESCRIPTION" to 252,
        )
        group(
            KeyGroup.TV,
            "POWER" to V2Key.POWER, "TV_POWER" to 177, "SLEEP" to 223, "WAKEUP" to 224, "TV" to V2Key.TV,
            "TV_INPUT" to V2Key.TV_INPUT, "TV_INPUT_HDMI_1" to V2Key.TV_INPUT_HDMI_1,
            "TV_INPUT_HDMI_2" to V2Key.TV_INPUT_HDMI_2, "TV_INPUT_HDMI_3" to V2Key.TV_INPUT_HDMI_3,
            "TV_INPUT_HDMI_4" to V2Key.TV_INPUT_HDMI_4, "GUIDE" to V2Key.GUIDE, "CHANNEL_UP" to V2Key.CHANNEL_UP,
            "CHANNEL_DOWN" to V2Key.CHANNEL_DOWN, "LAST_CHANNEL" to V2Key.LAST_CHANNEL, "DVR" to 173,
            "TV_TELETEXT" to 233, "TV_ZOOM_MODE" to 255, "WINDOW" to 171,
            "BRIGHTNESS_UP" to 221, "BRIGHTNESS_DOWN" to 220,
        )
        group(KeyGroup.SOUND, "VOLUME_UP" to V2Key.VOLUME_UP, "VOLUME_DOWN" to V2Key.VOLUME_DOWN, "VOLUME_MUTE" to V2Key.VOLUME_MUTE)
        group(KeyGroup.DIGITS, *Array(10) { "$it" to V2Key.digit(it) })
        group(
            KeyGroup.COLOURS,
            "PROG_RED" to V2Key.PROG_RED, "PROG_GREEN" to V2Key.PROG_GREEN,
            "PROG_YELLOW" to V2Key.PROG_YELLOW, "PROG_BLUE" to V2Key.PROG_BLUE,
        )
    }

    private val byCode = all.associateBy { it.code }

    fun byCode(code: Int): NamedKey? = byCode[code]

    /** Shown before "All keys": the ones people actually pin. */
    val common: List<Int> = listOf(
        V2Key.SETTINGS, V2Key.TV_INPUT, V2Key.GUIDE, V2Key.CAPTIONS, V2Key.MEDIA_AUDIO_TRACK,
        V2Key.ASSIST, V2Key.INFO, V2Key.MEDIA_REWIND, V2Key.MEDIA_FAST_FORWARD,
    )
}
