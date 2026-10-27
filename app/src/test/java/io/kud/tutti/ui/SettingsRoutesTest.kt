package io.kud.tutti.ui

import io.kud.tutti.ui.remote.ConnAction
import io.kud.tutti.ui.settings.SettingsCategory
import io.kud.tutti.ui.settings.SettingsMenu
import io.kud.tutti.ui.settings.Unlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Settings' rows and the ways in from the remote land (Iris, 2026-10-26, step 2). */
class SettingsRoutesTest {
    @Test
    fun `each row opens its own screen`() {
        assertEquals(Screen.Unlock(), SettingsRoutes.category(SettingsCategory.UNLOCK))
        assertEquals(Screen.TvSettings, SettingsRoutes.category(SettingsCategory.TV))
        assertEquals(Screen.BarSettings, SettingsRoutes.category(SettingsCategory.SOUNDBAR))
        assertEquals(Screen.About, SettingsRoutes.category(SettingsCategory.ABOUT))
        SettingsMenu.pages.forEach { assertEquals(Screen.SettingsPage(it), SettingsRoutes.category(it)) }
        // Every row's screen is different: no two categories land in the same place.
        assertEquals(SettingsCategory.entries.size, SettingsCategory.entries.map(SettingsRoutes::category).toSet().size)
    }

    @Test
    fun `a computer to pair or bluetooth to allow lands on devices, one level under settings`() {
        val devices = listOf(Screen.Settings, Screen.SettingsPage(SettingsCategory.DEVICES))
        assertEquals(devices, SettingsRoutes.connectionStep(ConnAction.PAIR_COMPUTER))
        assertEquals(devices, SettingsRoutes.connectionStep(ConnAction.OPEN_DEVICES))
    }

    @Test
    fun `set up keeps landing on what tutti can do, pairing and adding on the onboarding`() {
        assertEquals(listOf(Screen.Unlock(Unlock.IP_CONTROL)), SettingsRoutes.connectionStep(ConnAction.SET_UP_IP))
        assertEquals(listOf(Screen.Onboarding(cancellable = true)), SettingsRoutes.connectionStep(ConnAction.PAIR_TV))
        assertEquals(listOf(Screen.Onboarding(barOnly = true, cancellable = true)), SettingsRoutes.connectionStep(ConnAction.ADD_BAR))
    }

    @Test
    fun `every way in is a stack that restores as it was`() {
        ConnAction.entries.forEach { action ->
            val screens = SettingsRoutes.connectionStep(action)
            assertTrue(screens.isNotEmpty())
            assertEquals(listOf(Screen.Remote) + screens, ScreenKey.stack(listOf("remote") + screens.map(ScreenKey::of)))
        }
    }
}
