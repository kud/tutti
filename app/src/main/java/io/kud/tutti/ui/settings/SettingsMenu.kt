package io.kud.tutti.ui.settings

/** Settings' top level, one row each, in this order (2026-10-26, docs/proposals/settings-structure.md). */
enum class SettingsCategory { UNLOCK, DEVICES, TV, SOUNDBAR, REMOTE, DISPLAY, SYNC, ADVANCED, ABOUT }

/**
 * What Settings' top level shows, kept apart from Compose so it can be tested. Each row says what
 * its section holds, a line that never changes (Erwann, 2026-11-06); where things stand lives in
 * the status tiles above the list ([dashboard]).
 */
internal object SettingsMenu {

    /**
     * The categories whose screen is a page of Settings itself. The others open a screen that was
     * already there: What tutti can do, the TV hub, the soundbar's settings, About.
     */
    val pages: Set<SettingsCategory> = setOf(SettingsCategory.DEVICES, SettingsCategory.REMOTE, SettingsCategory.DISPLAY, SettingsCategory.SYNC, SettingsCategory.ADVANCED)
}
