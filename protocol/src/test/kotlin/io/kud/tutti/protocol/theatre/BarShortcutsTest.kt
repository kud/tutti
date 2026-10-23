package io.kud.tutti.protocol.theatre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarShortcutsTest {
    @Test
    fun `offers only what the bar answered for, and counts the rest`() {
        val state = TheatreState(confirmed = mapOf("audio.nightmode" to "off", "main.bassstep" to "2"))
        val offer = BarShortcuts.offer(state)
        assertEquals(listOf(TheatreFeature.NIGHT, TheatreFeature.BASS), offer.offered)
        assertEquals(BarShortcuts.FEATURES.size - 2, offer.hidden)
    }

    @Test
    fun `an unconnected bar offers nothing`() {
        val offer = BarShortcuts.offer(TheatreState())
        assertTrue(offer.offered.isEmpty())
        assertEquals(BarShortcuts.FEATURES.size, offer.hidden)
    }

    @Test
    fun `never power, read-only or anything tutti never asks for`() {
        assertFalse(TheatreFeature.POWER in BarShortcuts.FEATURES)
        for (feature in BarShortcuts.FEATURES) {
            assertFalse(feature.kind is FeatureKind.ReadOnly, feature.name)
            assertFalse(feature.key in TheatreFeature.NEVER_ASKED, feature.name)
            assertTrue(BarShortcuts.values(feature).isNotEmpty(), feature.name)
        }
    }

    @Test
    fun `values speak the wire`() {
        assertEquals(listOf("upon", "upoff"), BarShortcuts.values(TheatreFeature.VOICE))
        assertEquals(21, BarShortcuts.values(TheatreFeature.BASS).size)
        assertEquals("-10", BarShortcuts.values(TheatreFeature.BASS).first())
        assertEquals(21, BarShortcuts.values(TheatreFeature.VOLUME).size)
        assertEquals("100", BarShortcuts.values(TheatreFeature.VOLUME).last())
    }

    @Test
    fun `starts from the current value, else 0 dB, else the first`() {
        val state = TheatreState(confirmed = mapOf("main.bassstep" to "-2", "main.volumestep" to "33"))
        assertEquals("-2", BarShortcuts.initial(TheatreFeature.BASS, state))
        assertEquals("0", BarShortcuts.initial(TheatreFeature.REAR_LEVEL, state))
        assertEquals("0", BarShortcuts.initial(TheatreFeature.VOLUME, state))
        assertEquals("on", BarShortcuts.initial(TheatreFeature.NIGHT, TheatreState()))
    }
}
