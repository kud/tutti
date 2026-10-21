package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMapTest {
    @Test
    fun `v2 constants match the protocol enum`() {
        val expected = mapOf(
            V2Key.HOME to RemoteKeyCode.KEYCODE_HOME,
            V2Key.BACK to RemoteKeyCode.KEYCODE_BACK,
            V2Key.DPAD_UP to RemoteKeyCode.KEYCODE_DPAD_UP,
            V2Key.DPAD_CENTER to RemoteKeyCode.KEYCODE_DPAD_CENTER,
            V2Key.POWER to RemoteKeyCode.KEYCODE_POWER,
            V2Key.ENTER to RemoteKeyCode.KEYCODE_ENTER,
            V2Key.DEL to RemoteKeyCode.KEYCODE_DEL,
            V2Key.MEDIA_PLAY_PAUSE to RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE,
            V2Key.ESCAPE to RemoteKeyCode.KEYCODE_ESCAPE,
            V2Key.NUMPAD_ENTER to RemoteKeyCode.KEYCODE_NUMPAD_ENTER,
            V2Key.VOLUME_MUTE to RemoteKeyCode.KEYCODE_VOLUME_MUTE,
            V2Key.SETTINGS to RemoteKeyCode.KEYCODE_SETTINGS,
            V2Key.TV_INPUT to RemoteKeyCode.KEYCODE_TV_INPUT,
            V2Key.ASSIST to RemoteKeyCode.KEYCODE_ASSIST,
            V2Key.TV_INPUT_HDMI_4 to RemoteKeyCode.KEYCODE_TV_INPUT_HDMI_4,
        )
        expected.forEach { (int, code) -> assertEquals(code.value, int, code.name) }
    }

    @Test
    fun `android key codes map to identical v2 codes`() {
        // android.view.KeyEvent: DPAD_UP 19, ENTER 66, MEDIA_PLAY_PAUSE 85, TV_INPUT 178, DEMO_APP_4 304.
        listOf(19, 66, 85, 178, 304).forEach { assertEquals(it, KeyMap.fromAndroid(it)) }
        assertNull(KeyMap.fromAndroid(0))
        assertNull(KeyMap.fromAndroid(305))
        assertNull(KeyMap.fromAndroid(-1))
    }

    @Test
    fun `hardware keyboard mapping`() {
        assertEquals(HardwareKey.Send(V2Key.DPAD_LEFT), KeyMap.hardwareKeyboard(21, typing = false))
        assertEquals(HardwareKey.Send(V2Key.DPAD_CENTER), KeyMap.hardwareKeyboard(66, typing = false))
        assertEquals(HardwareKey.Send(V2Key.ENTER), KeyMap.hardwareKeyboard(66, typing = true))
        assertEquals(HardwareKey.Send(V2Key.BACK), KeyMap.hardwareKeyboard(111, typing = false))
        assertEquals(HardwareKey.Text, KeyMap.hardwareKeyboard(29, typing = true)) // A
        assertEquals(HardwareKey.Text, KeyMap.hardwareKeyboard(67, typing = true)) // DEL while typing
        assertEquals(HardwareKey.Send(V2Key.DEL), KeyMap.hardwareKeyboard(67, typing = false))
        assertEquals(HardwareKey.Ignore, KeyMap.hardwareKeyboard(57, typing = false)) // ALT_LEFT
    }
}
