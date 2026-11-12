package io.kud.tutti.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' top level: one topic per page, under six headers (2026-11-12). */
class SettingsMenuTest {
    @Test
    fun `eighteen rows under six headers, each row once, in the enum's order`() {
        assertEquals(SettingsSection.entries, SettingsMenu.sections.map { it.first })
        assertEquals(SettingsCategory.entries, SettingsMenu.sections.flatMap { it.second })
        assertEquals(18, SettingsCategory.entries.size)
        SettingsMenu.sections.forEach { (_, rows) -> assertTrue(rows.size in 2..4) }
    }

    @Test
    fun `automatic is everyday, and say again's page sits with the other playback page`() {
        val under = SettingsMenu.sections.flatMap { (section, rows) -> rows.map { it to section } }.toMap()
        assertEquals(SettingsSection.EVERYDAY, under[SettingsCategory.AUTOMATIC])
        assertEquals(SettingsSection.PLAYBACK, under[SettingsCategory.PLAYBACK])
        assertEquals(SettingsSection.PLAYBACK, under[SettingsCategory.THIRD_PARTY])
        assertEquals(SettingsSection.REMOTE, under[SettingsCategory.LOCK_SCREEN])
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
