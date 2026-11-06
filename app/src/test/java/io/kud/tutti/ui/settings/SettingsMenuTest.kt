package io.kud.tutti.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings' top level: the categories in their order (2026-10-26, step 2). */
class SettingsMenuTest {
    @Test
    fun `nine rows, what tutti can do first and about last`() {
        assertEquals(
            listOf(
                SettingsCategory.UNLOCK, SettingsCategory.DEVICES, SettingsCategory.TV, SettingsCategory.SOUNDBAR,
                SettingsCategory.REMOTE, SettingsCategory.DISPLAY, SettingsCategory.SYNC, SettingsCategory.ADVANCED, SettingsCategory.ABOUT,
            ),
            SettingsCategory.entries,
        )
    }

    @Test
    fun `only devices, remote, display, sync and advanced are pages of settings itself`() {
        assertEquals(
            setOf(SettingsCategory.DEVICES, SettingsCategory.REMOTE, SettingsCategory.DISPLAY, SettingsCategory.SYNC, SettingsCategory.ADVANCED),
            SettingsMenu.pages,
        )
    }
}
