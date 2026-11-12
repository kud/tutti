package io.kud.tutti.ui

import io.kud.tutti.ui.settings.SettingsCategory
import io.kud.tutti.ui.settings.SettingsMenu
import io.kud.tutti.ui.settings.TvPage
import io.kud.tutti.ui.settings.Unlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenKeyTest {
    @Test
    fun `every screen survives the round trip`() {
        val screens = listOf(
            Screen.Remote, Screen.Scenes, Screen.SceneEditor("s-1"), Screen.SceneEditor(null), Screen.Settings,
            *SettingsMenu.pages.map { Screen.SettingsPage(it) }.toTypedArray(),
            Screen.TvSettings, *TvPage.entries.map { Screen.TvSettingsPage(it) }.toTypedArray(),
            Screen.BarSettings, Screen.Diagnostic, Screen.Onboarding(barOnly = true, cancellable = false), Screen.About,
            Screen.Unlock(Unlock.entries.first()), Screen.Unlock(null), Screen.Technical,
        )
        screens.forEach { assertEquals(it, ScreenKey.screen(ScreenKey.of(it))) }
    }

    @Test
    fun `a restored stack starts on the remote and drops keys it does not know`() {
        assertEquals(listOf(Screen.Remote, Screen.Settings), ScreenKey.stack(listOf("remote", "settings")))
        assertEquals(listOf(Screen.Remote, Screen.Settings), ScreenKey.stack(listOf("settings", "gone")))
        assertEquals(listOf(Screen.Remote), ScreenKey.stack(emptyList()))
        assertNull(ScreenKey.screen("gone"))
        assertNull(ScreenKey.screen("tv-settings:GONE"))
        assertNull(ScreenKey.screen("settings:GONE"))
        // A category that opens a screen of its own elsewhere is never a page of Settings.
        assertNull(ScreenKey.screen("settings:TV"))
        assertEquals(
            listOf(Screen.Remote, Screen.Settings, Screen.SettingsPage(SettingsCategory.DEVICES)),
            ScreenKey.stack(listOf("remote", "settings", "settings:DEVICES")),
        )
    }

    /** Every `settings:` key a 0.3.x stack could hold (2026-11-12, the split into one page per topic). */
    @Test
    fun `every settings key an older tutti saved still resolves`() {
        for (name in listOf("DEVICES", "DISPLAY", "SYNC", "ADVANCED")) {
            assertEquals(Screen.SettingsPage(SettingsCategory.valueOf(name)), ScreenKey.screen("settings:$name"))
        }
        // Remote & dock split four ways: it lands on the list, once.
        assertEquals(Screen.Settings, ScreenKey.screen("settings:REMOTE"))
        assertEquals(listOf(Screen.Remote, Screen.Settings), ScreenKey.stack(listOf("remote", "settings", "settings:REMOTE")))
        // Title links became Third-party services (2026-11-12): its key opens the page that took its row.
        assertEquals(Screen.SettingsPage(SettingsCategory.THIRD_PARTY), ScreenKey.screen("settings:TITLE_LINKS"))
        assertEquals(
            listOf(Screen.Remote, Screen.Settings, Screen.SettingsPage(SettingsCategory.THIRD_PARTY)),
            ScreenKey.stack(listOf("remote", "settings", "settings:TITLE_LINKS")),
        )
        assertEquals("settings:THIRD_PARTY", ScreenKey.of(Screen.SettingsPage(SettingsCategory.THIRD_PARTY)))
        // The developer step left What tutti can do: its key opens the list, unfocused.
        assertEquals(Screen.Unlock(null), ScreenKey.screen("unlock:DEVELOPER"))
    }
}
