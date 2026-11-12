package io.kud.tutti.ui.settings

/**
 * Settings' top level, one row each, in this order (2026-11-12, Erwann: « Je préfère plus de
 * settings, menus… quitte à avoir dedans moins de choses que un peu un fourre-tout »): one topic per
 * page, grouped under [SettingsSection]s. The names are back-stack keys (`settings:<NAME>`), so a
 * name never changes; a title can. DISPLAY is titled Appearance and ADVANCED Developer.
 */
enum class SettingsCategory {
    DISPLAY, FEEDBACK, VOLUME, AUTOMATIC,
    LAYOUT, LONG_PRESS, APPS, LOCK_SCREEN,
    PLAYBACK, THIRD_PARTY,
    DEVICES, TV, SOUNDBAR, UNLOCK,
    SYNC, BACKUP,
    ADVANCED, ABOUT,
}

/**
 * The headers Settings' rows sit under, each holding two to four rows. PLAYBACK ("What's playing")
 * came with Automatic and Say again? (2026-11-12): Everyday and The remote were full, so Lock screen
 * moved under The remote (it is the remote outside the app) and Third-party services joined Playback
 * under the new header, both being about the title on screen.
 */
enum class SettingsSection { EVERYDAY, REMOTE, PLAYBACK, DEVICES, SYNC, TUTTI }

/**
 * What Settings' top level shows, kept apart from Compose so it can be tested. Each row says what
 * its section holds, a line that never changes (Erwann, 2026-11-06); where things stand lives in
 * the status tiles above the list ([dashboard]).
 */
internal object SettingsMenu {

    /**
     * Each header and its rows, in order. A page added later joins its header here and gets its
     * own file (Third-party services is [ThirdPartyPage], which took Title links' place).
     */
    val sections: List<Pair<SettingsSection, List<SettingsCategory>>> = listOf(
        SettingsSection.EVERYDAY to listOf(SettingsCategory.DISPLAY, SettingsCategory.FEEDBACK, SettingsCategory.VOLUME, SettingsCategory.AUTOMATIC),
        SettingsSection.REMOTE to listOf(SettingsCategory.LAYOUT, SettingsCategory.LONG_PRESS, SettingsCategory.APPS, SettingsCategory.LOCK_SCREEN),
        SettingsSection.PLAYBACK to listOf(SettingsCategory.PLAYBACK, SettingsCategory.THIRD_PARTY),
        SettingsSection.DEVICES to listOf(SettingsCategory.DEVICES, SettingsCategory.TV, SettingsCategory.SOUNDBAR, SettingsCategory.UNLOCK),
        SettingsSection.SYNC to listOf(SettingsCategory.SYNC, SettingsCategory.BACKUP),
        SettingsSection.TUTTI to listOf(SettingsCategory.ADVANCED, SettingsCategory.ABOUT),
    )

    /**
     * The categories whose screen is a page of Settings itself. The others open a screen that was
     * already there (What tutti can do, the TV hub, the soundbar's settings, About) or, for LAYOUT,
     * the remote in edit mode.
     */
    val pages: Set<SettingsCategory> = SettingsCategory.entries.toSet() -
        setOf(SettingsCategory.LAYOUT, SettingsCategory.TV, SettingsCategory.SOUNDBAR, SettingsCategory.UNLOCK, SettingsCategory.ABOUT)

    /**
     * Pages a 0.3.x back stack could hold that no longer exist. Remote & dock split four ways
     * (2026-11-12) with no single successor, so it lands on the Settings list rather than being
     * dropped without a word.
     */
    val retired: Set<String> = setOf("REMOTE")

    /**
     * Pages whose key changed, to the page that took their place: Title links became Third-party
     * services (2026-11-12) with its three switches, so a back stack holding it lands there.
     */
    val renamed: Map<String, SettingsCategory> = mapOf("TITLE_LINKS" to SettingsCategory.THIRD_PARTY)
}
