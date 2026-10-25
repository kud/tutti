package io.kud.tutti.platform.shortcuts

import io.kud.tutti.protocol.scenes.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherShortcutsTest {
    private fun scene(id: String) = Scene(id = id, name = "Scene $id", color = 0, glyph = "movie")

    @Test
    fun firstThreeScenesThenKeyboard() {
        val list = launcherShortcuts(listOf(scene("a"), scene("b"), scene("c"), scene("d")), "Clavier")
        assertEquals(listOf("scene-a", "scene-b", "scene-c", "keyboard"), list.map { it.id })
        assertEquals(null, list.last().sceneId)
    }

    @Test
    fun noScenesStillOffersTheKeyboard() {
        assertEquals(listOf("keyboard"), launcherShortcuts(emptyList(), "Keyboard").map { it.id })
    }

    @Test
    fun respectsTheLauncherLimit() {
        assertEquals(listOf("scene-a", "keyboard"), launcherShortcuts(listOf(scene("a"), scene("b")), "K", max = 2).map { it.id })
        assertTrue(launcherShortcuts(listOf(scene("a")), "K", max = 0).isEmpty())
    }
}
