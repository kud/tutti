package io.kud.tutti.ui

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
    }
}
