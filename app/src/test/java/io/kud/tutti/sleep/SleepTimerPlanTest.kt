package io.kud.tutti.sleep

import io.kud.tutti.protocol.sleep.SleepFade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerPlanTest {
    private val endsAt = 10_000_000L
    private val fadeStart = SleepFade.fadeStartsAt(endsAt)

    @Test
    fun waitsBeforeTheFade() {
        assertEquals(SleepStep.Wait, sleepStep(fadeStart - 1, endsAt, null, 40, barOnline = true))
    }

    @Test
    fun firstFadeStepTakesTheBarsLevelAsTheStart() {
        val step = sleepStep(fadeStart, endsAt, null, 40, barOnline = true)
        assertEquals(SleepStep.Lower(SleepFade.volumeAt(fadeStart, endsAt, 40)!!, 40), step)
    }

    @Test
    fun aHandOnTheRailBelowTheFadeWins() {
        assertEquals(SleepStep.Hold(40), sleepStep(fadeStart + 30_000L, endsAt, 40, 5, barOnline = true))
    }

    @Test
    fun aRaisedLevelIsLoweredAgainFromTheOriginalStart() {
        val now = fadeStart + 60_000L
        val step = sleepStep(now, endsAt, 40, 50, barOnline = true)
        assertEquals(SleepStep.Lower(SleepFade.volumeAt(now, endsAt, 40)!!, 40), step)
    }

    @Test
    fun barDownHoldsWithoutGuessing() {
        assertEquals(SleepStep.Hold(null), sleepStep(fadeStart, endsAt, null, 40, barOnline = false))
        assertEquals(SleepStep.Hold(30), sleepStep(fadeStart, endsAt, 30, null, barOnline = true))
    }

    @Test
    fun switchesOffAtTheEndEvenWithTheBarDown() {
        assertEquals(SleepStep.SwitchOff, sleepStep(endsAt, endsAt, 40, 3, barOnline = false))
    }

    @Test
    fun cancelRestoresOnlyMidFadeWithTheBarUp() {
        val waiting = SleepTimerState(endsAt, exact = true, startVolume = null)
        val fading = SleepTimerState(endsAt, exact = true, startVolume = 40)
        assertNull(sleepRestore(null, fadeStart, 20, barOnline = true))
        assertNull(sleepRestore(waiting, fadeStart - 1, 20, barOnline = true))
        assertNull(sleepRestore(fading, fadeStart + 30_000L, 20, barOnline = false))
        assertEquals(40, sleepRestore(fading, fadeStart + 30_000L, 20, barOnline = true))
        // Already back above where it began: left alone.
        assertNull(sleepRestore(fading, fadeStart + 30_000L, 45, barOnline = true))
    }

    @Test
    fun rowIsOfferedWithADeviceAndDownWhenAllIsOff() {
        assertNull(gentleOffRow(hasDevice = false, powered = null, running = null, offReason = "off"))
        assertEquals(GentleOffRow(null, null), gentleOffRow(hasDevice = true, powered = null, running = null, offReason = "off"))
        assertEquals(GentleOffRow(null, "off"), gentleOffRow(hasDevice = true, powered = false, running = null, offReason = "off"))
        val running = SleepTimerState(endsAt, exact = false)
        assertEquals(GentleOffRow(running, null), gentleOffRow(hasDevice = true, powered = false, running = running, offReason = "off"))
    }
}
