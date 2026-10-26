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
    fun aBarFoundElsewhereIsAnOfferWithItsOneStepNeverAMove() {
        val moved = room.copy(bar = Link.UNREACHABLE, barOffer = true)
        assertEquals(ConnLine(ConnLink.NETWORK, ConnWords.MOVED, ConnAction.USE_NEW_ADDRESS), line(moved, "bar", ConnLink.NETWORK))
        assertEquals(ConnFocus("bar", ConnLink.NETWORK), problemLine(connections(moved)))
        // Back at its own address, the offer no longer shows.
        assertEquals(ConnLine(ConnLink.NETWORK, ConnWords.CONNECTED), line(room.copy(barOffer = true), "bar", ConnLink.NETWORK))
        assertEquals(ConnAction.SET_UP_BAR, line(room.copy(bar = Link.UNREACHABLE), "bar", ConnLink.NETWORK).action)
    }

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
        assertEquals(ConnAction.WAKE_TV, line(room.copy(tvWakeable = true, tvShown = TvShown(Link.UNREACHABLE)), "tv", ConnLink.GOOGLE_TV).action)
        assertEquals(ConnAction.PAIR_TV, line(room.copy(tvShown = TvShown(Link.NEEDS_PAIRING)), "tv", ConnLink.GOOGLE_TV).action)
    }

    /** dev.16: a set-up TV in deep standby, every port timing out. Out of reach, never "Set up". */
    @Test
    fun aSetUpTvOffTheNetworkOffersTurnOnOnceAndNeverSetUp() {
        val off = room.copy(tvShown = TvShown(Link.UNREACHABLE), rest = Link.UNREACHABLE, tvWakeable = true)
        val tv = connections(off).first { it.id == "tv" }
        assertEquals(ConnLine(ConnLink.GOOGLE_TV, ConnWords.UNREACHABLE, ConnAction.WAKE_TV), tv.lines[0])
        assertEquals(ConnLine(ConnLink.SONY_IP, ConnWords.UNREACHABLE), tv.lines[1])
        assertEquals(ConnNote.TV_OFF_NETWORK, tv.note)
        assertEquals(ConnFocus("tv", ConnLink.GOOGLE_TV), problemLine(connections(off)))
        // No MAC: "Turn on" could only retry, which runs anyway, so neither line offers it.
        val noMac = connections(off.copy(tvWakeable = false)).first { it.id == "tv" }
        assertTrue(noMac.lines.all { it.action == null })
        // Remote start is known on: standby isn't the likely cause.
        assertEquals(ConnNote.TV_OFF_NETWORK_REMOTE_START_ON, connections(off.copy(remoteStart = true)).first().note)
        // The remote up, IP control alone out of reach: Turn on moves to its line, still no setup.
        val ipOnly = room.copy(rest = Link.UNREACHABLE, tvWakeable = true)
        assertEquals(ConnLine(ConnLink.SONY_IP, ConnWords.UNREACHABLE, ConnAction.WAKE_TV), line(ipOnly, "tv", ConnLink.SONY_IP))
        assertNull(connections(ipOnly).first().note)
        // Every state of a set-up IP control: setup only for a refused key.
        for (rest in Link.entries) {
            for (shown in Link.entries) {
                val l = line(room.copy(rest = rest, tvShown = TvShown(shown), tvWakeable = true), "tv", ConnLink.SONY_IP)
                assertTrue("$rest/$shown", l.action != ConnAction.SET_UP_IP || l.words == ConnWords.KEY_REFUSED)
            }
        }
    }

    @Test
    fun restCertificateMismatchOffersPairAgain() {
        assertEquals(
            ConnLine(ConnLink.SONY_IP, ConnWords.NEEDS_PAIRING, ConnAction.PAIR_TV),
            line(room.copy(rest = Link.NEEDS_PAIRING), "tv", ConnLink.SONY_IP),
        )
    }

    @Test
    fun standbyIsNotAFault() {
        val asleep = room.copy(tvAsleep = true, tvShown = TvShown(Link.UNREACHABLE), rest = Link.UNREACHABLE)
        assertEquals(ConnLine(ConnLink.GOOGLE_TV, ConnWords.STANDBY, ConnAction.WAKE_TV), line(asleep, "tv", ConnLink.GOOGLE_TV))
        assertEquals(ConnLine(ConnLink.SONY_IP, ConnWords.STANDBY), line(asleep, "tv", ConnLink.SONY_IP))
        assertNull(connections(asleep).first().note)
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

    @Test
    fun thePillsTroubleLandsOnItsLine() {
        assertNull(problemLine(connections(room)))
        assertEquals(ConnFocus("tv", ConnLink.SONY_IP), problemLine(connections(room.copy(ipSet = false, rest = Link.ABSENT))))
        assertEquals(ConnFocus("tv", ConnLink.SONY_IP), problemLine(connections(room.copy(ipSet = false, ipRenew = true))))
        // A fault with a step wins over a step not done yet, the bar's included.
        val both = room.copy(ipSet = false, bar = Link.UNREACHABLE)
        assertEquals(ConnFocus("bar", ConnLink.NETWORK), problemLine(connections(both)))
        assertEquals(ConnFocus("tv", ConnLink.GOOGLE_TV), problemLine(connections(room.copy(tvShown = TvShown(Link.CONNECTING)))))
        // Standby is no trouble, and a computer isn't the pill's to speak for.
        assertNull(problemLine(connections(room.copy(tvAsleep = true, rest = Link.UNREACHABLE))))
        assertNull(problemLine(connections(room.copy(hidPhase = HidPhase.BLUETOOTH_OFF))))
    }
}
