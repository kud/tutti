package io.kud.tutti.ui.common

import io.kud.tutti.platform.data.VibrationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackRulesTest {
    @Test
    fun `every event has its one haptic`() {
        val table = mapOf(
            FeedbackEvent.KEY to HapticPattern.CLICK,
            FeedbackEvent.STEP to HapticPattern.TICK,
            FeedbackEvent.DETENT to HapticPattern.DETENT,
            FeedbackEvent.SELECT to HapticPattern.SELECT,
            FeedbackEvent.LONG_PRESS to HapticPattern.LONG_PRESS,
            FeedbackEvent.BUMP to HapticPattern.BUMP,
            FeedbackEvent.CONFIRM to HapticPattern.CONFIRM,
            FeedbackEvent.REJECT to HapticPattern.REJECT,
            FeedbackEvent.SCENE_DONE to HapticPattern.DOUBLE_PULSE,
        )
        assertEquals(FeedbackEvent.entries.toSet(), table.keys)
        for ((event, haptic) in table) {
            assertEquals(event.name, haptic, FeedbackRules.plan(event, VibrationLevel.ALL, keySounds = false).haptic)
        }
    }

    @Test
    fun `essentials drops only the key click and the step tick`() {
        for (event in FeedbackEvent.entries) {
            val plan = FeedbackRules.plan(event, VibrationLevel.ESSENTIALS, keySounds = false)
            if (event == FeedbackEvent.KEY || event == FeedbackEvent.STEP) {
                assertNull(event.name, plan.haptic)
            } else {
                assertEquals(event.name, FeedbackRules.haptic(event), plan.haptic)
            }
        }
    }

    @Test
    fun `a detent is not the step tick or the wall, and essentials keeps it`() {
        val detent = FeedbackRules.haptic(FeedbackEvent.DETENT)
        assertNotEquals(FeedbackRules.haptic(FeedbackEvent.STEP), detent)
        assertNotEquals(FeedbackRules.haptic(FeedbackEvent.BUMP), detent)
        assertEquals(detent, FeedbackRules.plan(FeedbackEvent.DETENT, VibrationLevel.ESSENTIALS, keySounds = false).haptic)
    }

    @Test
    fun `off plays no haptic at all`() {
        for (event in FeedbackEvent.entries) {
            assertNull(event.name, FeedbackRules.plan(event, VibrationLevel.OFF, keySounds = false).haptic)
        }
    }

    @Test
    fun `the click sound plays on key presses only, and only when on`() {
        for (level in VibrationLevel.entries) {
            for (event in FeedbackEvent.entries) {
                assertEquals("$level $event", event == FeedbackEvent.KEY, FeedbackRules.plan(event, level, keySounds = true).sound)
                assertFalse("$level $event", FeedbackRules.plan(event, level, keySounds = false).sound)
            }
        }
    }

    @Test
    fun `sound is independent of vibration`() {
        assertTrue(FeedbackRules.plan(FeedbackEvent.KEY, VibrationLevel.OFF, keySounds = true).sound)
        assertTrue(FeedbackRules.plan(FeedbackEvent.KEY, VibrationLevel.ESSENTIALS, keySounds = true).sound)
    }
}
