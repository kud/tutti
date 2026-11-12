package io.kud.tutti.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' top level: one topic per page, under five headers (2026-11-12). */
class SettingsMenuTest {
    @Test
    fun `sixteen rows under five headers, each row once, in the enum's order`() {
        assertEquals(SettingsSection.entries, SettingsMenu.sections.map { it.first })
        assertEquals(SettingsCategory.entries, SettingsMenu.sections.flatMap { it.second })
        assertEquals(16, SettingsCategory.entries.size)
        SettingsMenu.sections.forEach { (_, rows) -> assertTrue(rows.size in 2..4) }
    }

    @Test
    fun `screens that existed before settings had pages, and remote layout, are not pages`() {
        assertEquals(
            SettingsCategory.entries.toSet() - setOf(
                SettingsCategory.LAYOUT, SettingsCategory.TV, SettingsCategory.SOUNDBAR, SettingsCategory.UNLOCK, SettingsCategory.ABOUT,
            ),
            SettingsMenu.pages,
        )
    }

    @Test
    fun `the page names older back stacks saved are still categories`() {
        listOf("DEVICES", "DISPLAY", "SYNC", "ADVANCED").forEach { SettingsCategory.valueOf(it) }
        assertTrue("REMOTE" in SettingsMenu.retired)
    }
}
