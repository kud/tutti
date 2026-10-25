package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TvShown
import io.kud.tutti.platform.bluetooth.HidPhase
import io.kud.tutti.protocol.devices.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsTest {
    private val room = ConnInput(
        tvId = "tv",
        tvName = "BRAVIA 8",
        tvShown = TvShown(Link.ONLINE),
        ipSet = true,
        rest = Link.ONLINE,
        barId = "bar",
        barName = "Bar 9",
        bar = Link.ONLINE,
        computers = listOf(ConnComputer("work", "Work", "AA")),
        hidPhase = HidPhase.READY,
        bonded = setOf("AA"),
        targetId = "tv",
    )

    private fun line(input: ConnInput, device: String, link: ConnLink): ConnLine =
        connections(input).first { it.id == device }.lines.first { it.link == link }

    @Test
    fun allWellSaysConnectedAndOffersNothing() {
        val tv = connections(room).first()
        assertEquals(listOf(ConnLink.GOOGLE_TV, ConnLink.SONY_IP), tv.lines.map { it.link })
        assertTrue(tv.lines.all { it.words == ConnWords.CONNECTED && it.action == null })
        assertTrue(tv.targeted)
        assertEquals(ConnLine(ConnLink.NETWORK, ConnWords.CONNECTED), line(room, "bar", ConnLink.NETWORK))
    }

    @Test
    fun ipControlWithoutKeyOffersSetUp() {
        assertEquals(
            ConnLine(ConnLink.SONY_IP, ConnWords.NOT_SET_UP, ConnAction.SET_UP_IP),
            line(room.copy(ipSet = false, rest = Link.ABSENT), "tv", ConnLink.SONY_IP),
        )
        assertEquals(
            ConnLine(ConnLink.SONY_IP, ConnWords.CODE_TO_RENEW, ConnAction.SET_UP_IP),
            line(room.copy(ipSet = false, ipRenew = true, rest = Link.UNAUTHORIZED), "tv", ConnLink.SONY_IP),
        )
        assertEquals(ConnWords.KEY_REFUSED, line(room.copy(rest = Link.UNAUTHORIZED), "tv", ConnLink.SONY_IP).words)
    }

    @Test
    fun tvLinkStatesEachHaveTheirStep() {
        assertEquals(ConnWords.RECONNECTING, line(room.copy(tvShown = TvShown(Link.CONNECTING, reconnecting = true)), "tv", ConnLink.GOOGLE_TV).words)
        assertEquals(ConnAction.WAKE_TV, line(room.copy(tvShown = TvShown(Link.UNREACHABLE)), "tv", ConnLink.GOOGLE_TV).action)
        assertEquals(ConnAction.PAIR_TV, line(room.copy(tvShown = TvShown(Link.NEEDS_PAIRING)), "tv", ConnLink.GOOGLE_TV).action)
    }

    @Test
    fun standbyIsNotAFault() {
        val asleep = room.copy(tvAsleep = true, tvShown = TvShown(Link.UNREACHABLE), rest = Link.UNREACHABLE)
        assertEquals(ConnLine(ConnLink.GOOGLE_TV, ConnWords.STANDBY, ConnAction.WAKE_TV), line(asleep, "tv", ConnLink.GOOGLE_TV))
        assertEquals(ConnLine(ConnLink.SONY_IP, ConnWords.STANDBY), line(asleep, "tv", ConnLink.SONY_IP))
    }

    @Test
    fun computerBluetoothReadyConnectedOrUnpaired() {
        assertEquals(ConnLine(ConnLink.BLUETOOTH, ConnWords.READY, ConnAction.CONTROL), line(room, "work", ConnLink.BLUETOOTH))
        val controlled = room.copy(targetId = "work", hidPhase = HidPhase.CONNECTED, hidConnectedTo = "work")
        assertEquals(ConnLine(ConnLink.BLUETOOTH, ConnWords.CONNECTED), line(controlled, "work", ConnLink.BLUETOOTH))
        assertEquals(ConnAction.PAIR_COMPUTER, line(room.copy(bonded = emptySet()), "work", ConnLink.BLUETOOTH).action)
        // Stopped, the bonded list is unknown: no claim that it isn't paired.
        assertEquals(ConnWords.READY, line(room.copy(hidPhase = HidPhase.STOPPED, bonded = emptySet()), "work", ConnLink.BLUETOOTH).words)
        assertEquals(ConnAction.OPEN_DEVICES, line(room.copy(hidPhase = HidPhase.BLUETOOTH_OFF), "work", ConnLink.BLUETOOTH).action)
    }

    @Test
    fun missingDevicesOfferTheirSetUp() {
        val noBar = connections(room.copy(barName = null, barId = null))
        assertEquals(ConnAction.ADD_BAR, noBar.first { it.kind == DeviceKind.SOUNDBAR }.lines.single().action)
        val empty = connections(ConnInput())
        assertEquals(1, empty.size)
        assertNull(empty.single().name)
        assertEquals(ConnAction.PAIR_TV, empty.single().lines.single().action)
    }

    @Test
    fun anotherAppHoldingBluetoothIsAProblemWithNoStep() {
        val busy = line(room.copy(hidPhase = HidPhase.BUSY), "work", ConnLink.BLUETOOTH)
        assertEquals(ConnMark.PROBLEM, busy.words.mark)
        assertNull(busy.action)
    }
}
