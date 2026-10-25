package io.kud.tutti.ui.settings

import io.kud.tutti.controller.IpCodeStatus
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.Links
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.TvSettingsState
import io.kud.tutti.controller.TvState
import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.platform.data.BarDevice
import io.kud.tutti.platform.data.Devices
import io.kud.tutti.platform.data.TvDevice
import io.kud.tutti.protocol.grpc.CloudPlaneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlocksTest {
    private val tv = TvDevice(host = "192.168.0.10")
    private val bar = BarDevice(host = "192.168.0.11")

    private fun TuttiUiState.status(step: Unlock) = statusOf(step, this)

    @Test
    fun `a fresh install has only the base to do, and everything after it waits`() {
        val state = TuttiUiState()
        assertEquals(UnlockStatus.TODO, state.status(Unlock.PAIR_TV))
        assertEquals(UnlockStatus.BLOCKED, state.status(Unlock.IP_CONTROL))
        assertEquals(UnlockStatus.BLOCKED, state.status(Unlock.REMOTE_START))
        assertEquals(UnlockStatus.TODO, state.status(Unlock.SOUNDBAR))
        assertEquals(Unlock.PAIR_TV, unlockSummary(unlocks(state)).next)
    }

    @Test
    fun `a paired TV without a key asks for IP control next`() {
        val state = TuttiUiState(devices = Devices(tv = tv), links = Links(tv = Link.ONLINE))
        assertEquals(UnlockStatus.DONE, state.status(Unlock.PAIR_TV))
        assertEquals(UnlockStatus.TODO, state.status(Unlock.IP_CONTROL))
        assertEquals(Unlock.IP_CONTROL, unlockSummary(unlocks(state)).next)
    }

    @Test
    fun `a TV that forgot us needs pairing again`() {
        val state = TuttiUiState(devices = Devices(tv = tv), links = Links(tv = Link.NEEDS_PAIRING))
        assertEquals(UnlockStatus.ATTENTION, state.status(Unlock.PAIR_TV))
    }

    @Test
    fun `a stored key reads by what the TV answers`() {
        fun with(rest: Link) = TuttiUiState(devices = Devices(tv = tv), links = Links(rest = rest), pskSet = true).status(Unlock.IP_CONTROL)
        assertEquals(UnlockStatus.DONE, with(Link.ONLINE))
        assertEquals(UnlockStatus.ATTENTION, with(Link.UNAUTHORIZED))
        assertEquals(UnlockStatus.ATTENTION, with(Link.UNREACHABLE))
        assertEquals(UnlockStatus.CHECKING, with(Link.CONNECTING))
    }

    @Test
    fun `the code shown on the TV counts as IP control, and a refused one needs a look`() {
        fun with(code: IpCodeStatus, rest: Link = Link.ONLINE) =
            TuttiUiState(devices = Devices(tv = tv), links = Links(rest = rest), ipCode = code).status(Unlock.IP_CONTROL)
        assertEquals(UnlockStatus.DONE, with(IpCodeStatus.SET))
        assertEquals(UnlockStatus.CHECKING, with(IpCodeStatus.SET, Link.CONNECTING))
        assertEquals(UnlockStatus.ATTENTION, with(IpCodeStatus.RENEW, Link.UNAUTHORIZED))
        assertEquals(UnlockStatus.TODO, with(IpCodeStatus.NONE, Link.ABSENT))
        assertTrue(TuttiUiState(ipCode = IpCodeStatus.SET).ipControlSet)
        assertFalse(TuttiUiState(ipCode = IpCodeStatus.RENEW).ipControlSet)
        assertTrue(TuttiUiState(pskSet = true).ipControlSet)
    }

    @Test
    fun `remote start is read from the TV's wake-on-LAN mode, unknown until it says`() {
        fun with(wol: Boolean?) = TuttiUiState(devices = Devices(tv = tv), tv = TvState(settings = TvSettingsState(wol = wol))).status(Unlock.REMOTE_START)
        assertEquals(UnlockStatus.DONE, with(true))
        assertEquals(UnlockStatus.TODO, with(false))
        assertEquals(UnlockStatus.UNKNOWN, with(null))
    }

    @Test
    fun `the bar is done only while it answers`() {
        fun with(link: Link) = TuttiUiState(devices = Devices(bar = bar), links = Links(bar = link)).status(Unlock.SOUNDBAR)
        assertEquals(UnlockStatus.DONE, with(Link.ONLINE))
        assertEquals(UnlockStatus.ATTENTION, with(Link.UNREACHABLE))
        assertEquals(UnlockStatus.CHECKING, with(Link.CONNECTING))
    }

    @Test
    fun `developer mode and Sony sign-in never count towards the everyday summary`() {
        val all = TuttiUiState(
            devices = Devices(tv = tv.copy(mac = "00:00:00:00:00:00"), bar = bar),
            links = Links(tv = Link.ONLINE, rest = Link.ONLINE, bar = Link.ONLINE),
            tv = TvState(settings = TvSettingsState(wol = true)),
            pskSet = true,
        )
        val summary = unlockSummary(unlocks(all))
        assertEquals(4, summary.total)
        assertEquals(4, summary.done)
        assertNull(summary.next)
        assertTrue(canWakeFromOff(all))
        assertEquals(UnlockStatus.TODO, all.status(Unlock.DEVELOPER))
        assertEquals(UnlockStatus.COMING, all.status(Unlock.SONY))
        assertEquals(UnlockStatus.DONE, all.copy(prefs = AppPrefs(developerMode = true)).status(Unlock.DEVELOPER))
        assertEquals(UnlockStatus.DONE, all.copy(cloud = CloudPlaneState.Connected(sessionExpiresAtMillis = 0)).status(Unlock.SONY))
    }

    @Test
    fun `something broken comes before something not yet done`() {
        val state = TuttiUiState(devices = Devices(tv = tv, bar = bar), links = Links(tv = Link.ONLINE, bar = Link.UNREACHABLE))
        assertEquals(Unlock.SOUNDBAR, unlockSummary(unlocks(state)).next)
    }

    @Test
    fun `the checklist opens on the step asked for, else the next one`() {
        val state = TuttiUiState(devices = Devices(tv = tv))
        assertEquals(Unlock.REMOTE_START, openStep(unlocks(state), Unlock.REMOTE_START))
        assertEquals(Unlock.IP_CONTROL, openStep(unlocks(state), null))
        assertFalse(canWakeFromOff(state))
    }
}
