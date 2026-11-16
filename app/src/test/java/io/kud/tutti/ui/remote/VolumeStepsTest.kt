package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeStepsTest {
    @Test
    fun `a drag moves relatively and stops at the ceiling`() {
        assertEquals(30, VolumeSteps.level(start = 24, travelled = 0.1f, stepsPerRail = 60f, ceiling = 100))
        assertEquals(40, VolumeSteps.level(start = 24, travelled = 1f, stepsPerRail = 60f, ceiling = 40))
        assertEquals(0, VolumeSteps.level(start = 5, travelled = -1f, stepsPerRail = 60f, ceiling = 100))
    }

    @Test
    fun `a step is clamped like the controller clamps it`() {
        assertEquals(25, VolumeSteps.step(24, 1, 100))
        assertEquals(40, VolumeSteps.step(40, 1, 40))
        assertEquals(0, VolumeSteps.step(0, -1, 100))
    }

    @Test
    fun `steps inside the range tick and the walls do not, since the controller bumps them`() {
        assertTrue(VolumeSteps.ticks(1, 100))
        assertTrue(VolumeSteps.ticks(99, 100))
        assertFalse(VolumeSteps.ticks(0, 100))
        assertFalse(VolumeSteps.ticks(100, 100))
        assertFalse(VolumeSteps.ticks(40, 40))
    }

    @Test
    fun `tick and wall never agree, so a step plays exactly one of them`() {
        for (ceiling in listOf(1, 40, 100)) {
            for (level in 0..ceiling) {
                assertNotEquals("$level/$ceiling", VolumeSteps.ticks(level, ceiling), VolumeSteps.atWall(level, ceiling))
            }
        }
    }

    @Test
    fun `arriving on or jumping over a landmark is a detent, leaving one is not`() {
        val marks = listOf(24, 40)
        assertTrue(VolumeSteps.crosses(23, 24, marks))
        assertTrue(VolumeSteps.crosses(25, 24, marks))
        assertTrue(VolumeSteps.crosses(20, 30, marks))
        assertTrue(VolumeSteps.crosses(45, 38, marks))
        assertFalse(VolumeSteps.crosses(24, 25, marks))
        assertFalse(VolumeSteps.crosses(24, 23, marks))
        assertFalse(VolumeSteps.crosses(24, 24, marks))
        assertFalse(VolumeSteps.crosses(10, 20, marks))
    }

    @Test
    fun `the wall stays the bump even on a landmark, and an unknown start only ticks`() {
        val marks = listOf(30, 60)
        assertEquals(StepFeel.DETENT, VolumeSteps.feel(29, 30, 100, marks))
        assertEquals(StepFeel.TICK, VolumeSteps.feel(30, 31, 100, marks))
        assertEquals(StepFeel.WALL, VolumeSteps.feel(59, 60, 60, marks))
        assertEquals(StepFeel.WALL, VolumeSteps.feel(1, 0, 100, listOf(0)))
        assertEquals(StepFeel.TICK, VolumeSteps.feel(null, 30, 100, marks))
    }
}
