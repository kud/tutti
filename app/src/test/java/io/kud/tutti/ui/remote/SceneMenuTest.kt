package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneMenuTest {
    @Test
    fun undoComesFirstOnlyOnTheSceneThatCanBeUndone() {
        assertEquals(listOf(SceneMenuRow.UNDO, SceneMenuRow.APPLY, SceneMenuRow.EDIT, SceneMenuRow.PIN), sceneMenu(undoable = true, canPin = true))
        assertEquals(listOf(SceneMenuRow.APPLY, SceneMenuRow.EDIT, SceneMenuRow.PIN), sceneMenu(undoable = false, canPin = true))
        assertEquals(listOf(SceneMenuRow.APPLY, SceneMenuRow.EDIT), sceneMenu(undoable = false, canPin = false))
    }
}
