package io.kud.tutti.ui.common

import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.platform.data.DockLabels
import io.kud.tutti.platform.data.RemoteLayout
import io.kud.tutti.platform.data.StoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dock's dress (Erwann, 2026-10-29): icon, name or both, the dock as it shipped until he picks. */
class PinDressTest {
    @Test
    fun `prefs stored before the dress existed show both, the rest untouched`() {
        val old = """{"layout":{"scenes":false,"padSize":"LARGE","hand":"LEFT"},"onboarded":true}"""
        val prefs = StoreJson.decodeFromString(AppPrefs.serializer(), old)
        assertEquals(DockLabels.BOTH, prefs.layout.dockLabels)
        assertFalse(prefs.layout.scenes)
        assertEquals(DockLabels.BOTH, AppPrefs().layout.dockLabels)
    }

    @Test
    fun `each dress survives a round trip`() {
        DockLabels.entries.forEach { labels ->
            val prefs = AppPrefs(layout = RemoteLayout(dockLabels = labels))
            assertEquals(prefs, StoreJson.decodeFromString(AppPrefs.serializer(), StoreJson.encodeToString(AppPrefs.serializer(), prefs)))
        }
    }

    @Test
    fun `both is the dock as it shipped`() {
        val dress = pinDress(DockLabels.BOTH, "Play / pause")
        assertTrue(dress.glyph && dress.name)
        assertEquals(PIN_ICON, dress.glyphSize)
        assertEquals(PIN_FACE, dress.faceSize)
        assertEquals(1, dress.nameLines)
    }

    @Test
    fun `icons alone grow the glyph and the app's face`() {
        val dress = pinDress(DockLabels.ICON, "YouTube")
        assertTrue(dress.glyph)
        assertFalse(dress.name)
        assertTrue(dress.glyphSize > PIN_ICON)
        assertTrue(dress.faceSize > PIN_FACE)
    }

    @Test
    fun `names alone break only between words`() {
        assertEquals(2, pinDress(DockLabels.NAME, "Play / pause").nameLines)
        assertEquals(1, pinDress(DockLabels.NAME, "Keyboard").nameLines)
        assertEquals(1, pinDress(DockLabels.NAME, " Netflix ").nameLines)
        val dress = pinDress(DockLabels.NAME, "Netflix")
        assertFalse(dress.glyph)
        assertTrue(dress.name)
    }

    @Test
    fun `every dress draws something`() {
        DockLabels.entries.forEach { assertTrue(pinDress(it, "Home").let { d -> d.glyph || d.name }) }
    }
}
