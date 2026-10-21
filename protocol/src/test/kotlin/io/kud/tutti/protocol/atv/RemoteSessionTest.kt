package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteAppInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteConfigure
import io.kud.tutti.protocol.atv.proto.remote.RemoteDirection
import io.kud.tutti.protocol.atv.proto.remote.RemoteEditInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeBatchEdit
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeKeyInject
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeObject
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeShowRequest
import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.atv.proto.remote.RemotePingRequest
import io.kud.tutti.protocol.atv.proto.remote.RemoteSetActive
import io.kud.tutti.protocol.atv.proto.remote.RemoteSetVolumeLevel
import io.kud.tutti.protocol.atv.proto.remote.RemoteStart
import io.kud.tutti.protocol.atv.proto.remote.RemoteTextFieldStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteSessionTest {
    private val session = RemoteSession(ClientDeviceInfo(model = "Pixel", vendor = "Google"))

    @Test
    fun `answers the configure handshake and becomes ready`() {
        val step = session.reduce(RemoteState(), RemoteMessage(remote_configure = RemoteConfigure(code1 = 1)))
        assertTrue(step.state.ready)
        assertEquals(622, step.replies.single().remote_configure?.code1)
        assertEquals("Pixel", step.replies.single().remote_configure?.device_info?.model)

        val active = session.reduce(step.state, RemoteMessage(remote_set_active = RemoteSetActive()))
        assertEquals(622, active.replies.single().remote_set_active?.active)
    }

    @Test
    fun `echoes pings`() {
        val step = session.reduce(RemoteState(ready = true), RemoteMessage(remote_ping_request = RemotePingRequest(val1 = 42)))
        assertEquals(42, step.replies.single().remote_ping_response?.val1)
    }

    @Test
    fun `tracks power, volume and the foreground app`() {
        var state = session.reduce(RemoteState(), RemoteMessage(remote_start = RemoteStart(started = true))).state
        state = session.reduce(state, RemoteMessage(remote_set_volume_level = RemoteSetVolumeLevel(volume_max = 100, volume_level = 12, volume_muted = false))).state
        state = session.reduce(state, RemoteMessage(remote_ime_key_inject = RemoteImeKeyInject(app_info = RemoteAppInfo(app_package = "com.netflix.ninja")))).state
        assertEquals(true, state.powered)
        assertEquals(TvVolume(12, 100, false), state.volume)
        assertEquals("com.netflix.ninja", state.currentApp)
    }

    @Test
    fun `ime show opens the field and batch edits update counters and text`() {
        val show = RemoteMessage(
            remote_ime_show_request = RemoteImeShowRequest(
                remote_text_field_status = RemoteTextFieldStatus(counter_field = 5, value = "", label = "Rechercher sur YouTube"),
            ),
        )
        var state = session.reduce(RemoteState(ready = true), show).state
        assertEquals("Rechercher sur YouTube", state.ime?.label)
        assertEquals(5, state.fieldCounter)

        val edit = RemoteMessage(
            remote_ime_batch_edit = RemoteImeBatchEdit(
                ime_counter = 9,
                field_counter = 6,
                edit_info = listOf(RemoteEditInfo(insert = 1, text_field_status = RemoteImeObject(start = 3, end = 3, value = "arte"))),
            ),
        )
        state = session.reduce(state, edit).state
        assertEquals(9, state.imeCounter)
        assertEquals(6, state.fieldCounter)
        assertEquals("arte", state.ime?.text)

        val out = RemoteMessages.text("arte concert", state.imeCounter, state.fieldCounter).remote_ime_batch_edit
        assertNotNull(out)
        assertEquals(9, out.ime_counter)
        assertEquals(12, out.edit_info.single().text_field_status?.start)
    }

    @Test
    fun `switching app closes the mirrored field`() {
        val state = RemoteState(currentApp = "a", ime = ImeField("x", "", 1, 1))
        val next = session.reduce(state, RemoteMessage(remote_ime_key_inject = RemoteImeKeyInject(app_info = RemoteAppInfo(app_package = "b")))).state
        assertNull(next.ime)
    }

    @Test
    fun `key builder maps codes and directions`() {
        val inject = RemoteMessages.key(V2Key.DPAD_CENTER, KeyDirection.START_LONG)?.remote_key_inject
        assertEquals(RemoteKeyCode.KEYCODE_DPAD_CENTER, inject?.key_code)
        assertEquals(RemoteDirection.START_LONG, inject?.direction)
        assertNull(RemoteMessages.key(0))
        assertNull(RemoteMessages.key(9_999))
    }
}
