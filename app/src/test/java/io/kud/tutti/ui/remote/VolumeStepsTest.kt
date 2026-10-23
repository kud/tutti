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
}
