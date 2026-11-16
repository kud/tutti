package io.kud.tutti.ui

import io.kud.tutti.ui.remote.ConnAction
import io.kud.tutti.ui.settings.DashAction
import io.kud.tutti.ui.settings.DashTile
import io.kud.tutti.ui.settings.SettingsCategory
import io.kud.tutti.ui.settings.Unlock

/**
 * Where Settings' rows and the ways in from elsewhere land, as the screens to push: a way in that
 * names a category opens it over Settings, so the top bar's arrow and system back go up one level.
 */
internal object SettingsRoutes {

    /**
     * The screen a top-level row opens; null for Remote layout, whose row puts the remote itself in
     * edit mode rather than opening a page about it.
     */
    fun category(category: SettingsCategory): Screen? = when (category) {
        SettingsCategory.LAYOUT -> null
        SettingsCategory.UNLOCK -> Screen.Unlock()
        SettingsCategory.TV -> Screen.TvSettings
        SettingsCategory.SOUNDBAR -> Screen.BarSettings
        SettingsCategory.ABOUT -> Screen.About
        else -> Screen.SettingsPage(category)
    }

    /** Settings, then [category]'s own screen on top of it. */
    fun into(category: SettingsCategory): List<Screen> = listOfNotNull(Screen.Settings, category(category))

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
        // Never routed here: the LAN ask and its system page are the remote's own handling.
        ConnAction.LAN_REQUEST, ConnAction.LAN_OPEN_SETTINGS,
        -> into(SettingsCategory.DEVICES)
    }

    /**
     * Where a status tile's tap lands, over Settings: the section it speaks for, or, for a device not
     * set up yet, its setup (a bar alone once there is a TV; with no TV, Devices' "Add a device").
     */
    fun tile(tile: DashTile, hasTv: Boolean, hasBar: Boolean): Screen = when (tile) {
        DashTile.TV -> if (hasTv) Screen.TvSettings else Screen.Onboarding(cancellable = true)
        DashTile.SOUNDBAR -> when {
            hasBar -> Screen.BarSettings
            hasTv -> Screen.Onboarding(barOnly = true, cancellable = true)
            else -> Screen.SettingsPage(SettingsCategory.DEVICES)
        }
        DashTile.SYNC -> Screen.SettingsPage(SettingsCategory.SYNC)
        DashTile.UNLOCKS -> Screen.Unlock()
    }

    /** A tile's button, over Settings: the same steps Connexions takes for the same words. */
    fun dashAction(action: DashAction): Screen = when (action) {
        DashAction.PAIR_TV -> Screen.Onboarding(cancellable = true)
        DashAction.SET_UP_IP -> Screen.Unlock(Unlock.IP_CONTROL)
        DashAction.ADD_BAR -> Screen.Onboarding(barOnly = true, cancellable = true)
        DashAction.OPEN_DEVICES -> Screen.SettingsPage(SettingsCategory.DEVICES)
        DashAction.CHECK_SYNC -> Screen.SettingsPage(SettingsCategory.SYNC)
    }
}
