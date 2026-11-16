package io.kud.tutti.protocol.scenes

import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerSceneTest {
    @Test
    fun `the computer's scene switches to its input, one per computer`() {
        val scene = StarterScenes.computerOnTv("mac-1", "Mac on the TV", "extInput:hdmi?port=3")
        assertEquals("computer-tv-mac-1", scene.id)
        assertEquals(mapOf<SceneKey, SceneValue>(SceneKey.TV_INPUT to SceneValue.Text("extInput:hdmi?port=3")), scene.settings)
        assertEquals(StarterScenes.computerOnTv("mac-1", "x", "y").id, scene.id)
    }

    @Test
    fun `applied to a sleeping tv it wakes it, then switches`() {
        val scene = StarterScenes.computerOnTv("mac-1", "Mac", "extInput:hdmi?port=3")
        val steps = SceneEngine.plan(scene, DeviceSnapshot(emptyMap(), powered = false))
        assertEquals(listOf(SceneStep.WakeUp, SceneStep.TvInput("extInput:hdmi?port=3")), steps)
    }
}
