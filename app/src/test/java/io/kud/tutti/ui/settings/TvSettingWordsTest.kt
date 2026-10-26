package io.kud.tutti.ui.settings

import io.kud.tutti.protocol.bravia.TvSettingCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every picture target the catalogue places has its own words, so none falls back to its raw name. */
class TvSettingWordsTest {

    @Test
    fun everyNamedTargetHasATitle() {
        assertTrue(TvSettingCatalog.namedTargets.size > 30)
        val missing = TvSettingCatalog.namedTargets.filter { TvSettingWords.target(it) == null }
        assertEquals(emptyList<String>(), missing)
    }

    /** The screenshot that started this (2026-10-26): every row it showed raw now has words. */
    @Test
    fun theRawRowsErwannSawAreNamed() {
        val seen = listOf(
            "advContrastEnhancer", "autoLuminanceLevel", "autoToneCurve", "autoWhiteBalance", "blackAdjust", "blackLevel",
            "motionflowClearness", "motionflowMode", "motionflowSmoothnessCamera", "motionflowSmoothnessFilm", "peakLuminance", "pixelShift",
        )
        assertEquals(emptyList<String>(), seen.filter { TvSettingWords.target(it) == null })
        for (target in listOf("blueBias", "blueGain", "magentaLightness", "magentaSaturation")) {
            assertNull(TvSettingWords.target(target))
            assertTrue(target, TvSettingCatalog.colourTarget(target) != null)
        }
        assertTrue(listOf("medium", "off", "custom", "cinema", "low").all { TvSettingWords.value(it) != null })
    }

    @Test
    fun everyColourPartAndSectionHasWords() {
        TvSettingCatalog.Hue.entries.forEach { TvSettingWords.hue(it) }
        TvSettingCatalog.Part.entries.forEach { TvSettingWords.part(it) }
        val sections = TvSettingCatalog.PictureSection.entries.map { TvSettingWords.section(it) }
        assertEquals(sections.size, sections.toSet().size)
    }
}
