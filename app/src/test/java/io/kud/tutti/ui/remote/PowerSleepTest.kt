package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerSleepTest {
    private fun row(code: String?, rest: Boolean, powered: Boolean?) = sleepTimerRow(code, rest, powered, "down", "standby")

    @Test
    fun noSleepKeyNoRow() {
        assertNull(row(null, rest = true, powered = true))
    }

    @Test
    fun theRowGoesDownWithIpControlAndOnStandby() {
        assertEquals(SleepTimerRow(null), row("AAAA", rest = true, powered = true))
        assertEquals(SleepTimerRow(null), row("AAAA", rest = true, powered = null))
        assertEquals(SleepTimerRow("down"), row("AAAA", rest = false, powered = true))
        assertEquals(SleepTimerRow("standby"), row("AAAA", rest = true, powered = false))
    }

    @Test
    fun theTvAloneGetsTheSheetToo() {
        val choices = powerChoices(hasTv = true, tvPowered = true, hasBar = false, barPowered = null, powered = true)
        assertEquals(listOf(PowerChoice(io.kud.tutti.controller.PowerTarget.TV, on = false)), choices)
    }
}
