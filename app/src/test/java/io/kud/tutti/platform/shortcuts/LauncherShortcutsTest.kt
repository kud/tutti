package io.kud.tutti.platform.shortcuts

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.protocol.scenes.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherShortcutsTest {
    private fun scene(id: String) = Scene(id = id, name = "Scene $id", color = 0, glyph = "movie")
    private fun app(pkg: String) = PinnedApp("com.example.$pkg", pkg)
    private fun ids(list: List<LauncherShortcut>) = list.map { it.id }

    @Test
    fun appsThenOneSceneThenKeyboard() {
        val list = launcherShortcuts(listOf(app("a"), app("b"), app("c")), listOf(scene("x"), scene("y")), "Clavier")
        assertEquals(listOf("app-com.example.a", "app-com.example.b", "scene-x", "keyboard"), ids(list))
        assertEquals("com.example.a", list.first().packageName)
        assertNull(list.last().sceneId)
        assertNull(list.last().packageName)
    }

    @Test
    fun scenesTakeWhatAppsLeave() {
        val list = launcherShortcuts(listOf(app("a")), listOf(scene("x"), scene("y"), scene("z")), "K")
        assertEquals(listOf("app-com.example.a", "scene-x", "scene-y", "keyboard"), ids(list))
    }

    @Test
    fun appsTakeEverythingWithoutScenes() {
        val list = launcherShortcuts(listOf(app("a"), app("b"), app("c"), app("d")), emptyList(), "K")
        assertEquals(listOf("app-com.example.a", "app-com.example.b", "app-com.example.c", "keyboard"), ids(list))
    }

    @Test
    fun firstThreeScenesThenKeyboardWithoutApps() {
        val list = launcherShortcuts(emptyList(), listOf(scene("a"), scene("b"), scene("c"), scene("d")), "Clavier")
        assertEquals(listOf("scene-a", "scene-b", "scene-c", "keyboard"), ids(list))
    }

    @Test
    fun nothingPinnedStillOffersTheKeyboard() {
        assertEquals(listOf("keyboard"), ids(launcherShortcuts(emptyList(), emptyList(), "Keyboard")))
    }

    @Test
    fun respectsTheLauncherLimit() {
        assertEquals(listOf("scene-a", "keyboard"), ids(launcherShortcuts(listOf(app("a")), listOf(scene("a"), scene("b")), "K", max = 2)))
        assertEquals(listOf("app-com.example.a", "keyboard"), ids(launcherShortcuts(listOf(app("a")), emptyList(), "K", max = 2)))
        assertEquals(listOf("keyboard"), ids(launcherShortcuts(listOf(app("a")), listOf(scene("a")), "K", max = 1)))
        assertTrue(launcherShortcuts(listOf(app("a")), listOf(scene("a")), "K", max = 0).isEmpty())
    }

    @Test
    fun pinnedAppsFollowTheDockOncePerPackage() {
        val dock = DockConfig(
            slots = listOf(
                DockSlot(DockAction.OpenApps),
                DockSlot(DockAction.LaunchApp("com.example.tube", "Tube")),
                DockSlot(DockAction.ToggleBar("audio.nightmode")),
                DockSlot(DockAction.LaunchApp("com.example.flix", "Flix"), label = "Films"),
                DockSlot(DockAction.LaunchApp("com.example.tube", "Tube", link = "https://example.com/feed")),
                DockSlot(secondary = DockAction.LaunchApp("com.example.held", "Held")),
            ),
        )
        assertEquals(listOf(PinnedApp("com.example.tube", "Tube"), PinnedApp("com.example.flix", "Films")), pinnedApps(dock))
    }

    @Test
    fun usageIsReportedUnderTheShortcutsOwnId() {
        assertEquals("app-com.example.a", shortcutIdFor(ACTION_APP, null, "com.example.a"))
        assertEquals("scene-x", shortcutIdFor(ACTION_SCENE, "x", null))
        assertEquals("keyboard", shortcutIdFor(ACTION_KEYBOARD, null, null))
        assertNull(shortcutIdFor(ACTION_APP, null, " "))
        assertNull(shortcutIdFor("android.intent.action.MAIN", "x", "com.example.a"))
    }

    @Test
    fun pinnedShortcutsOfLiveScenesAreRepublished() {
        val kept = pinnedSceneShortcuts(listOf("scene-d", "scene-gone"), listOf(scene("a"), scene("d")))
        assertEquals(listOf(LauncherShortcut("scene-d", "Scene d", "d")), kept)
    }
}
