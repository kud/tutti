package io.kud.tutti.ui

import io.kud.tutti.ui.remote.ConnAction
import io.kud.tutti.ui.settings.SettingsCategory
import io.kud.tutti.ui.settings.Unlock

/**
 * Where Settings' rows and the ways in from elsewhere land, as the screens to push: a way in that
 * names a category opens it over Settings, so the top bar's arrow and system back go up one level.
 */
internal object SettingsRoutes {

    /** The screen a top-level row opens. */
    fun category(category: SettingsCategory): Screen = when (category) {
        SettingsCategory.UNLOCK -> Screen.Unlock()
        SettingsCategory.TV -> Screen.TvSettings
        SettingsCategory.SOUNDBAR -> Screen.BarSettings
        SettingsCategory.ABOUT -> Screen.About
        SettingsCategory.DEVICES, SettingsCategory.REMOTE, SettingsCategory.DISPLAY, SettingsCategory.ADVANCED -> Screen.SettingsPage(category)
    }

    /** Settings, then [category]'s own screen on top of it. */
    fun into(category: SettingsCategory): List<Screen> = listOf(Screen.Settings, category(category))

    /**
     * A next step of the Connections sheet the remote doesn't run itself: pairing the TV or adding
     * the bar (the onboarding), IP control (its step of What tutti can do), and a computer to pair
     * or the phone's Bluetooth to allow (Settings › Devices, where its row lives).
     */
    fun connectionStep(action: ConnAction): List<Screen> = when (action) {
        ConnAction.PAIR_TV -> listOf(Screen.Onboarding(cancellable = true))
        ConnAction.SET_UP_IP -> listOf(Screen.Unlock(Unlock.IP_CONTROL))
        ConnAction.ADD_BAR -> listOf(Screen.Onboarding(barOnly = true, cancellable = true))
        ConnAction.PAIR_COMPUTER, ConnAction.OPEN_DEVICES,
        ConnAction.WAKE_TV, ConnAction.CONTROL, ConnAction.USE_NEW_ADDRESS,
        -> into(SettingsCategory.DEVICES)
    }
}
