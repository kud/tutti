package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.theatre.TheatreFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SoundExplainTest {
    @Test
    fun `every setting the Son sheet shows has an explanation`() {
        for (feature in SOUND_SHEET_FEATURES + TheatreFeature.INPUT) {
            assertNotNull("$feature has no explanation", explanation(feature))
        }
    }

    @Test
    fun `settings outside the Son sheet have none`() {
        assertNull(explanation(TheatreFeature.HDMI_CEC))
    }

    @Test
    fun `the sections explain every shown setting exactly once`() {
        val layout = soundSheetLayout(voiceZoom = true)
        val explained = soundSections(layout).flatMap { sectionFeatures(it, layout) }
        assertEquals((SOUND_SHEET_FEATURES + TheatreFeature.INPUT).toSet(), explained.toSet())
        assertEquals(explained.size, explained.toSet().size)
    }

    @Test
    fun `DRC and IMAX each get their own section`() {
        val sections = soundSections(soundSheetLayout(voiceZoom = false))
        assertEquals(
            listOf(SoundSection.Input, SoundSection.Effects, SoundSection.Choice(TheatreFeature.DRC), SoundSection.Choice(TheatreFeature.IMAX), SoundSection.Levels),
            sections,
        )
    }

    @Test
    fun `Voice Zoom 3 is explained only when the sheet shows it`() {
        val layout = soundSheetLayout(voiceZoom = false)
        val explained = soundSections(layout).flatMap { sectionFeatures(it, layout) }
        assertFalse(TheatreFeature.VOICE_ZOOM in explained)
        assertFalse(TheatreFeature.VOICE_ZOOM_LEVEL in explained)
    }

    @Test
    fun `the hint follows the name`() {
        assertEquals("Night. Softer.", withHint("Night", "Softer."))
        assertEquals("Night", withHint("Night", null))
        assertEquals("Night", withHint("Night", " "))
    }
}
