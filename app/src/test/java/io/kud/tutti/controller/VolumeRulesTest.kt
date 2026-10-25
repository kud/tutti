package io.kud.tutti.controller

import io.kud.tutti.controller.VolumeDecision.Held
import io.kud.tutti.controller.VolumeDecision.Send
import io.kud.tutti.platform.data.TonightCap
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeRulesTest {
    private val armed = VolumeGuardState(enabled = true, threshold = 30, unlocked = false)
    private val app = VolumeSource.APP
    private val quick = VolumeSource.QUICK

    @Test
    fun belowTheLineGoesStraightThrough() {
        assertEquals(Send(29), VolumeRules.decide(20, 29, 100, armed, app))
        assertEquals(Send(30), VolumeRules.decide(29, 30, 100, armed, app))
        assertEquals(Send(10), VolumeRules.decide(40, 10, 100, armed, app))
    }

    @Test
    fun crossingStopsExactlyAtTheLineAndAsksInTheApp() {
        assertEquals(Held(send = 30, threshold = 30, wanted = 45, ask = true), VolumeRules.decide(25, 45, 100, armed, app))
        // Already on the line: nothing more to send, the question still stands.
        assertEquals(Held(send = null, threshold = 30, wanted = 31, ask = true), VolumeRules.decide(30, 31, 100, armed, app))
        // An unknown level never sneaks past it either.
        assertEquals(Held(send = 30, threshold = 30, wanted = 31, ask = true), VolumeRules.decide(null, 31, 100, armed, app))
    }

    @Test
    fun repeatedStepsWhileTheQuestionIsPendingNeverOvershoot() {
        // A held + repeats from the shown level; each press is held at the line, never past it.
        var level = 27
        repeat(10) {
            when (val d = VolumeRules.decide(level, level + 1, 100, armed, app)) {
                is Send -> level = d.level
                is Held -> level = d.send ?: level
            }
        }
        assertEquals(30, level)
    }

    @Test
    fun quickSurfacesStopAndSaySoInsteadOfAsking() {
        assertEquals(Held(send = 30, threshold = 30, wanted = 31, ask = false), VolumeRules.decide(29, 31, 100, armed, quick))
        assertEquals(Held(send = null, threshold = 30, wanted = 31, ask = false), VolumeRules.decide(30, 31, 100, armed, quick))
    }

    @Test
    fun onceConfirmedItDoesNotNagUntilTheLevelIsBelowTheLineAgain() {
        val open = armed.copy(unlocked = true)
        assertEquals(Send(31), VolumeRules.decide(30, 31, 100, open, app))
        assertEquals(Send(32), VolumeRules.decide(31, 32, 100, open, quick))
        assertTrue(VolumeRules.stillUnlocked(true, 35, 30))
        assertTrue(VolumeRules.stillUnlocked(true, 30, 30))
        assertTrue(VolumeRules.stillUnlocked(true, null, 30))
        assertFalse(VolumeRules.stillUnlocked(true, 29, 30))
    }

    @Test
    fun aLevelAlreadyAboveTheLineIsNotACrossing() {
        assertEquals(Send(41), VolumeRules.decide(40, 41, 100, armed, quick))
        assertNull(VolumeRules.guardLine(armed, 40, 100))
    }

    @Test
    fun theCapWinsAndTheGuardSitsBelowIt() {
        // A cap at or under the line: the guard has nothing to guard.
        assertEquals(Send(20), VolumeRules.decide(15, 45, 20, armed, app))
        assertNull(VolumeRules.guardLine(armed, 15, 30))
        // Confirmed past the line, the cap still clamps.
        assertEquals(Held(send = 30, threshold = 30, wanted = 40, ask = true), VolumeRules.decide(25, 70, 40, armed, app))
        assertEquals(Send(40), VolumeRules.decide(30, 70, 40, armed.copy(unlocked = true), app))
    }

    @Test
    fun offMeansOff() {
        assertEquals(Send(80), VolumeRules.decide(10, 80, 100, armed.copy(enabled = false), app))
        assertNull(VolumeRules.guardLine(armed.copy(enabled = false), 10, 100))
        assertEquals(30, VolumeRules.guardLine(armed, 10, 100))
    }

    @Test
    fun theCeilingIsTheLowerCapWhileTonightLasts() {
        val tonight = TonightCap(level = 18, until = 1_000)
        assertEquals(18, VolumeRules.ceiling(sceneCap = 20, tonight = tonight, now = 999, max = 100))
        assertEquals(20, VolumeRules.ceiling(sceneCap = 20, tonight = tonight, now = 1_000, max = 100))
        assertEquals(12, VolumeRules.ceiling(sceneCap = 12, tonight = tonight, now = 0, max = 100))
        assertEquals(100, VolumeRules.ceiling(null, null, 0, 100))
        assertNull(VolumeRules.cap(null, tonight, 1_000, 100))
        assertEquals(18, VolumeRules.cap(null, tonight, 0, 100))
    }

    @Test
    fun aCapForTonightEndsInTheMorning() {
        val zone = ZoneId.of("Europe/Paris")
        fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        assertEquals(at("2026-10-26T07:00"), VolumeRules.capUntil(at("2026-10-25T22:30"), zone, CapUntil.MORNING))
        // After midnight, the same morning.
        assertEquals(at("2026-10-26T07:00"), VolumeRules.capUntil(at("2026-10-26T01:10"), zone, CapUntil.MORNING))
        assertEquals(at("2026-10-25T23:30"), VolumeRules.capUntil(at("2026-10-25T22:30"), zone, CapUntil.ONE_HOUR))
    }

    @Test
    fun presetsAreThreeSlots() {
        assertEquals(listOf(null, null, null), VolumeRules.presets(emptyList()))
        assertEquals(listOf(null, 18, null), VolumeRules.withPreset(emptyList(), 1, 18))
        assertEquals(listOf(30, null, 25), VolumeRules.withPreset(listOf(30, 18, 25, 99), 1, null))
        assertEquals(listOf(100, null, null), VolumeRules.withPreset(emptyList(), 0, 140))
    }

    @Test
    fun theThresholdStaysUsable() {
        assertEquals(VolumeRules.MIN_GUARD, VolumeRules.threshold(0))
        assertEquals(99, VolumeRules.threshold(100))
        assertEquals(30, VolumeRules.threshold(30))
    }
}
