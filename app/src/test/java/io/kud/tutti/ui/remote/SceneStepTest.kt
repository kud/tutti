package io.kud.tutti.ui.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneStepTest {
    @Test
    fun `each step forward ticks`() {
        assertTrue(isSceneStep(0f, 0.25f))
        assertTrue(isSceneStep(0.25f, 0.5f))
    }

    @Test
    fun `the start and the last step leave it to the outcome`() {
        assertFalse(isSceneStep(null, 0f))
        assertFalse(isSceneStep(0.75f, 1f))
        assertFalse(isSceneStep(1f, null))
    }

    @Test
    fun `a restart from zero does not tick`() {
        assertFalse(isSceneStep(0.5f, 0f))
        assertFalse(isSceneStep(0.5f, 0.5f))
    }
}
