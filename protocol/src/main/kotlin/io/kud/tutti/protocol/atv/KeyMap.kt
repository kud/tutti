package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode

/**
 * Remote v2 key codes are Android `KeyEvent` codes: the proto enum mirrors
 * `android.view.KeyEvent` from 0 to 304. These constants are pure ints so `:protocol`
 * stays free of Android; `KeyMapTest` pins each one to the generated enum.
 */
object V2Key {
    const val HOME = 3
    const val NUM_0 = 7
    const val NUM_1 = 8
    const val NUM_2 = 9
    const val NUM_3 = 10
    const val NUM_4 = 11
    const val NUM_5 = 12
    const val NUM_6 = 13
    const val NUM_7 = 14
    const val NUM_8 = 15
    const val NUM_9 = 16
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val TAB = 61
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DEL = 112
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val NUMPAD_ENTER = 160
    const val VOLUME_MUTE = 164
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val TV = 170
    const val GUIDE = 172
    const val CAPTIONS = 175
    const val SETTINGS = 176
    const val TV_INPUT = 178
    const val PROG_RED = 183
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186
    const val APP_SWITCH = 187
    const val ASSIST = 219
    const val MEDIA_AUDIO_TRACK = 222
    const val LAST_CHANNEL = 229
    const val TV_INPUT_HDMI_1 = 243
    const val TV_INPUT_HDMI_2 = 244
    const val TV_INPUT_HDMI_3 = 245
    const val TV_INPUT_HDMI_4 = 246
    const val ALL_APPS = 284

    /** `NUM_0`..`NUM_9` by digit. */
    fun digit(n: Int): Int {
        require(n in 0..9) { "not a digit: $n" }
        return NUM_0 + n
    }
}

/** What a hardware-keyboard key does while the remote is in front. */
sealed interface HardwareKey {
    /** Send this v2 key code. */
    data class Send(val keyCode: Int) : HardwareKey

    /** Let the text field handle it: it reaches the TV as an IME batch edit. */
    data object Text : HardwareKey

    data object Ignore : HardwareKey
}

object KeyMap {
    /** Android `KeyEvent` code to v2 key code; null for codes the TV does not know. */
    fun fromAndroid(keyCode: Int): Int? {
        val code = RemoteKeyCode.fromValue(keyCode) ?: return null
        return if (code == RemoteKeyCode.KEYCODE_UNKNOWN) null else code.value
    }

    /**
     * Hardware keyboard: arrows → D-pad, Enter → OK (or the TV field's action while typing),
     * Esc → Back, printable keys → text.
     */
    fun hardwareKeyboard(keyCode: Int, typing: Boolean): HardwareKey = when (keyCode) {
        V2Key.DPAD_UP, V2Key.DPAD_DOWN, V2Key.DPAD_LEFT, V2Key.DPAD_RIGHT, V2Key.DPAD_CENTER -> HardwareKey.Send(keyCode)
        V2Key.ENTER, V2Key.NUMPAD_ENTER -> HardwareKey.Send(if (typing) V2Key.ENTER else V2Key.DPAD_CENTER)
        V2Key.ESCAPE, V2Key.BACK -> HardwareKey.Send(V2Key.BACK)
        V2Key.DEL, V2Key.FORWARD_DEL, V2Key.MOVE_HOME, V2Key.MOVE_END ->
            if (typing) HardwareKey.Text else HardwareKey.Send(keyCode)
        V2Key.TAB -> if (typing) HardwareKey.Text else HardwareKey.Send(V2Key.DPAD_DOWN)
        in MEDIA_AND_SYSTEM -> HardwareKey.Send(keyCode)
        in 7..18, in 29..56, V2Key.SPACE, in 68..77, 81, in 144..163 -> HardwareKey.Text
        else -> HardwareKey.Ignore
    }

    private val MEDIA_AND_SYSTEM = setOf(
        V2Key.HOME, V2Key.MEDIA_PLAY_PAUSE, V2Key.MEDIA_STOP, V2Key.MEDIA_NEXT, V2Key.MEDIA_PREVIOUS,
        V2Key.MEDIA_REWIND, V2Key.MEDIA_FAST_FORWARD, V2Key.MEDIA_PLAY, V2Key.MEDIA_PAUSE,
        V2Key.VOLUME_UP, V2Key.VOLUME_DOWN, V2Key.VOLUME_MUTE, V2Key.PAGE_UP, V2Key.PAGE_DOWN,
        V2Key.SEARCH, V2Key.MENU, V2Key.INFO, V2Key.GUIDE, V2Key.SETTINGS, V2Key.TV_INPUT,
    )
}
