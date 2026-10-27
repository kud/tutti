package io.kud.tutti.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPlanTest {

    private fun plan(target: PowerTarget, on: Boolean, tvReach: Boolean, barReach: Boolean, tv: Boolean = true, bar: Boolean = true) =
        powerPlan(target, on, tvConfigured = tv, tvReachable = tvReach, barConfigured = bar, barReachable = barReach)

    @Test
    fun bothIsTodaysShortPress() {
        assertEquals(PowerPlan(sendTv = true, sendBar = true, wakeTv = false, wakeBar = false, overridePowered = false), plan(PowerTarget.BOTH, false, true, true))
        // Turning on with the TV asleep: the bar now, the TV woken.
        assertEquals(PowerPlan(sendTv = false, sendBar = true, wakeTv = true, wakeBar = false, overridePowered = true), plan(PowerTarget.BOTH, true, false, true))
        // Turning off what is out of reach does nothing: it is asleep already.
        assertTrue(plan(PowerTarget.BOTH, false, false, false).nothing)
    }

    @Test
    fun theTvAloneNeverTouchesTheBar() {
        val wake = plan(PowerTarget.TV, true, tvReach = false, barReach = true)
        assertEquals(PowerPlan(sendTv = false, sendBar = false, wakeTv = true, wakeBar = false, overridePowered = null), wake)
        val off = plan(PowerTarget.TV, false, tvReach = true, barReach = true)
        assertEquals(PowerPlan(sendTv = true, sendBar = false, wakeTv = false, wakeBar = false, overridePowered = null), off)
        // On while in reach (Remote start keeps the link up in standby): the command, and the room shows on.
        assertEquals(PowerPlan(sendTv = true, sendBar = false, wakeTv = false, wakeBar = false, overridePowered = true), plan(PowerTarget.TV, true, true, false))
    }

    @Test
    fun theBarAloneNeverTouchesTheTv() {
        val off = plan(PowerTarget.BAR, false, tvReach = false, barReach = true)
        assertEquals(PowerPlan(sendTv = false, sendBar = true, wakeTv = false, wakeBar = false, overridePowered = null), off)
        val wake = plan(PowerTarget.BAR, true, tvReach = true, barReach = false)
        assertEquals(PowerPlan(sendTv = false, sendBar = false, wakeTv = false, wakeBar = true, overridePowered = null), wake)
    }

    @Test
    fun aDeviceNotSetUpIsNeverAskedToWake() {
        val p = plan(PowerTarget.BOTH, true, tvReach = false, barReach = false, bar = false)
        assertTrue(p.wakeTv)
        assertFalse(p.wakeBar)
        assertTrue(plan(PowerTarget.BAR, true, tvReach = false, barReach = false, bar = false).nothing)
    }

    @Test
    fun theShortPressSwitchesTheTvAlone() {
        // The bar's own power never decides it: on, off or unknown, the press follows the TV.
        assertEquals(PowerTarget.TV to false, shortPress(hasTv = true, tvPowered = true, barPowered = false))
        assertEquals(PowerTarget.TV to true, shortPress(hasTv = true, tvPowered = false, barPowered = true))
        assertEquals(PowerTarget.TV to true, shortPress(hasTv = true, tvPowered = null, barPowered = true))
        // With no TV set up it switches the bar.
        assertEquals(PowerTarget.BAR to false, shortPress(hasTv = false, tvPowered = null, barPowered = true))
        assertEquals(PowerTarget.BAR to true, shortPress(hasTv = false, tvPowered = null, barPowered = null))
    }
}
