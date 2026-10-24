package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundSheetLayoutTest {
    @Test
    fun `only on and off settings are tiles`() {
        val layout = soundSheetLayout(voiceZoom = true)
        assertTrue(layout.tiles.all { it.kind is FeatureKind.Toggle })
        assertTrue(layout.choices.all { it.kind is FeatureKind.Choice })
        assertTrue(layout.levels.all { it.kind is FeatureKind.Level })
    }

    @Test
    fun `every sheet feature lands somewhere, none twice`() {
        val layout = soundSheetLayout(voiceZoom = true)
        val placed = layout.tiles + layout.choices + layout.levels
        assertEquals(SOUND_SHEET_FEATURES.toSet(), placed.toSet())
        assertEquals(placed.size, placed.toSet().size)
    }

    @Test
    fun `DRC and IMAX show all their options, not a tile`() {
        val layout = soundSheetLayout(voiceZoom = false)
        assertEquals(listOf(TheatreFeature.DRC, TheatreFeature.IMAX), layout.choices)
        assertTrue(TheatreFeature.DRC !in layout.tiles && TheatreFeature.IMAX !in layout.tiles)
    }

    @Test
    fun `voice zoom shows only when the bar offers it`() {
        val without = soundSheetLayout(voiceZoom = false)
        assertTrue(TheatreFeature.VOICE_ZOOM !in without.tiles && TheatreFeature.VOICE_ZOOM_LEVEL !in without.levels)
        val with = soundSheetLayout(voiceZoom = true)
        assertTrue(TheatreFeature.VOICE_ZOOM in with.tiles && TheatreFeature.VOICE_ZOOM_LEVEL in with.levels)
    }
}
