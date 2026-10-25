package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.devices.DeviceLink
import io.kud.tutti.protocol.devices.HoldPlan
import io.kud.tutti.protocol.devices.ShownOn
import io.kud.tutti.protocol.devices.Targeting
import io.kud.tutti.protocol.devices.ComputerOs
import io.kud.tutti.protocol.atv.proto.remote.RemoteDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OK held reaches the TV as a held key: down, held long enough, then up (Erwann, 2026-10-25). */
class KeyHoldTest {
    @Test
    fun `a quick lift still keeps the key down past the TV's long-press timeout`() {
        assertEquals(KeyHold.MIN_HOLD_MS, KeyHold.releaseDelay(startedAt = 1_000, now = 1_000))
        assertEquals(KeyHold.MIN_HOLD_MS - 200, KeyHold.releaseDelay(startedAt = 1_000, now = 1_200))
        assertTrue(KeyHold.MIN_HOLD_MS > 500, "past Android TV's 400 to 500 ms long-press timeout")
    }

    @Test
    fun `a finger held longer lifts the key at once`() {
        assertEquals(0, KeyHold.releaseDelay(startedAt = 1_000, now = 1_000 + KeyHold.MIN_HOLD_MS))
        assertEquals(0, KeyHold.releaseDelay(startedAt = 1_000, now = 5_000))
    }

    @Test
    fun `a clock that went backwards never waits longer than the minimum`() {
        assertEquals(KeyHold.MIN_HOLD_MS, KeyHold.releaseDelay(startedAt = 5_000, now = 1_000))
    }

    @Test
    fun `a hold is START_LONG then END_LONG on OK, never a short press`() {
        assertEquals(RemoteDirection.START_LONG, RemoteMessages.key(V2Key.DPAD_CENTER, KeyDirection.START_LONG)?.remote_key_inject?.direction)
        assertEquals(RemoteDirection.END_LONG, RemoteMessages.key(V2Key.DPAD_CENTER, KeyDirection.END_LONG)?.remote_key_inject?.direction)
    }

    // Invented hosts and addresses only.
    private val tv = Device(Device.TV_ID, "Salon", DeviceKind.TV, listOf(DeviceLink.Google("192.168.1.20")))
    private val mac = Device("mac-a", "Work", DeviceKind.COMPUTER, listOf(DeviceLink.Bluetooth("00:11:22:33:44:55")), ShownOn("extInput:hdmi?port=2"), ComputerOs.MACOS)

    private fun plan(target: Device?, lan: Boolean, hid: Boolean) =
        Targeting.hold(Targeting.route(V2Key.DPAD_CENTER, target, tvLanUp = lan, hidConnected = hid))

    @Test
    fun `the TV on its LAN holds the remote key`() {
        assertEquals(HoldPlan.REMOTE, plan(tv, lan = true, hid = false))
        assertEquals(HoldPlan.REMOTE, plan(null, lan = true, hid = true))
    }

    @Test
    fun `the TV over Bluetooth holds the keyboard report`() {
        assertEquals(HoldPlan.HID, plan(tv, lan = false, hid = true))
    }

    @Test
    fun `a computer gets one plain Enter, never a held one that would repeat`() {
        assertEquals(HoldPlan.PLAIN, plan(mac, lan = true, hid = true))
    }

    @Test
    fun `nowhere to go holds nothing`() {
        assertEquals(HoldPlan.NONE, plan(tv, lan = false, hid = false))
        assertEquals(HoldPlan.NONE, plan(mac, lan = true, hid = false))
    }
}
