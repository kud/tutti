package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.PowerTarget
import io.kud.tutti.protocol.bravia.PowerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarAloneTest {

    private fun alone(
        tvPowered: Boolean? = false,
        tvLink: Link = Link.ONLINE,
        barLink: Link = Link.ONLINE,
        barOn: Boolean? = true,
        input: String? = "bluetooth",
        hasTv: Boolean = true,
        hasBar: Boolean = true,
    ) = barAlone(hasTv, hasBar, tvPowered, tvLink, barLink, barOn, input)

    @Test
    fun theBarsOwnSourcesWithTheTvAsleepAreTheBarOnItsOwn() {
        for (input in listOf("bluetooth", "airplay2", "spotify")) {
            assertEquals(BarAlone(input, tvAsleep = true), alone(input = input))
        }
    }

    @Test
    fun aTvOutOfReachWithItsPowerUnknownCountsButIsNotAsleep() {
        assertEquals(BarAlone("spotify", tvAsleep = false), alone(tvPowered = null, tvLink = Link.UNREACHABLE, input = "spotify"))
    }

    @Test
    fun theBarOnTheTvInputWithTheTvAsleepStaysPlainStandby() {
        assertNull(alone(input = "tv"))
        assertNull(alone(input = "hdmi1"))
        assertNull(alone(input = null))
    }

    @Test
    fun itEndsWhenTheTvWakes() {
        assertNull(alone(tvPowered = true))
        // Awake but unknown and the link up: nothing says it sleeps.
        assertNull(alone(tvPowered = null, tvLink = Link.ONLINE))
        // A reconnect is not out of reach yet.
        assertNull(alone(tvPowered = null, tvLink = Link.CONNECTING))
    }

    @Test
    fun itNeedsTheBarOnAndAnswering() {
        assertNull(alone(barOn = false))
        assertNull(alone(barOn = null))
        assertNull(alone(barLink = Link.UNREACHABLE))
        assertNull(alone(barLink = Link.CONNECTING))
    }

    @Test
    fun itNeedsBothDevicesSetUp() {
        assertNull(alone(hasTv = false))
        assertNull(alone(hasBar = false))
    }

    @Test
    fun theCardSaysTheBarAboveStandbyAndOutOfReach() {
        val bar = BarAlone("airplay2", tvAsleep = true)
        assertEquals(CardShowing.OnTheBar(bar), cardShowing(true, null, Link.UNREACHABLE, PowerStatus.STANDBY, barOn = true, barAlone = bar))
        assertEquals(CardShowing.OnTheBar(bar), cardShowing(true, null, Link.UNREACHABLE, PowerStatus.UNKNOWN, barOn = true, barAlone = bar))
        // Without it, standby is as it was.
        assertEquals(CardShowing.Standby(true), cardShowing(true, null, Link.ONLINE, PowerStatus.STANDBY, barOn = true))
        assertNull(cardShowing(false, null, Link.ABSENT, PowerStatus.UNKNOWN, barOn = true, barAlone = bar))
    }

    @Test
    fun theLongPressStatesEachDevicesOwnAction() {
        // The bar on its own: the TV is offered on, the bar off, and Both does what the short press does.
        assertEquals(
            listOf(PowerChoice(PowerTarget.TV, on = true), PowerChoice(PowerTarget.BAR, on = false), PowerChoice(PowerTarget.BOTH, on = false)),
            powerChoices(hasTv = true, tvPowered = false, hasBar = true, barPowered = true, powered = true),
        )
        // Everything off, or silent: every row turns on.
        assertEquals(
            listOf(PowerChoice(PowerTarget.TV, on = true), PowerChoice(PowerTarget.BAR, on = true), PowerChoice(PowerTarget.BOTH, on = true)),
            powerChoices(hasTv = true, tvPowered = null, hasBar = true, barPowered = null, powered = null),
        )
        // One device: no Both.
        assertEquals(listOf(PowerChoice(PowerTarget.TV, on = false)), powerChoices(hasTv = true, tvPowered = true, hasBar = false, barPowered = null, powered = true))
    }
}
