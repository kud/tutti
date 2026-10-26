package io.kud.tutti.platform.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Any installed app can send ACTION_SCENE to the exported launcher activity: only the token may run a scene. */
class ShortcutGuardTest {
    private val token = "tok-0123456789abcdefghijklmnopqrstuvwxyzABCD"

    @Test
    fun theRightTokenRunsTheScene() {
        assertEquals("movie", ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", token, token))
    }

    @Test
    fun noTokenRunsNothing() {
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", null, token))
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", "", token))
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", "   ", token))
    }

    @Test
    fun aWrongTokenRunsNothing() {
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", token.dropLast(1), token))
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", token + "x", token))
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", token.uppercase(), token))
    }

    @Test
    fun anInstallWithoutAReadableTokenRunsNothing() {
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", token, null))
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "movie", "", ""))
        assertFalse(ShortcutGuard.accepts(null, null))
    }

    @Test
    fun aMissingSceneIdOrAnotherActionRunsNothing() {
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, null, token, token))
        assertNull(ShortcutGuard.sceneToRun(ACTION_SCENE, "", token, token))
        assertNull(ShortcutGuard.sceneToRun(ACTION_KEYBOARD, "movie", token, token))
        assertNull(ShortcutGuard.sceneToRun(null, "movie", token, token))
    }

    @Test
    fun tokensAreLongAndFreshPerInstall() {
        val a = ShortcutGuard.newToken()
        val b = ShortcutGuard.newToken()
        assertEquals(43, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(a, b)
        assertTrue(ShortcutGuard.accepts(a, a))
        assertFalse(ShortcutGuard.accepts(a, b))
    }
}
