package io.kud.tutti.platform.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prefs saved before layouts existed must draw the remote exactly as it always was. */
class RemoteLayoutCompatTest {
    private val stored = """{"haptics":false,"theme":"DARK","trueBlack":true,"hiddenApps":["com.x"],"padMode":"DPAD","onboarded":true}"""

    private val shipped = listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD, RowButton.MUTE)

    @Test
    fun `prefs stored before layouts read as today's screen`() {
        val prefs = StoreJson.decodeFromString(AppPrefs.serializer(), stored)
        assertEquals(RemoteLayout(), prefs.layout)
        assertEquals(shipped, prefs.layout.shownRow)
        assertTrue(prefs.layout.scenes)
        assertEquals(PadSize.NORMAL, prefs.layout.padSize)
        // The rest of the old prefs is untouched.
        assertEquals(PadMode.DPAD, prefs.padMode)
        assertEquals(setOf("com.x"), prefs.hiddenApps)
    }

    @Test
    fun `a fresh install gets the same screen`() {
        assertEquals(shipped, AppPrefs().layout.shownRow)
    }

    @Test
    fun `a customised layout survives a round trip`() {
        val prefs = AppPrefs(layout = RemoteLayout(listOf(RowButton.INFO, RowButton.BACK, RowButton.CAPTIONS), scenes = false, padSize = PadSize.LARGE))
        val again = StoreJson.decodeFromString(AppPrefs.serializer(), StoreJson.encodeToString(AppPrefs.serializer(), prefs))
        assertEquals(prefs, again)
    }

    @Test
    fun `the row never holds more than six or a button twice`() {
        val full = RemoteLayout()
        assertSame(full, full.withAdded(RowButton.INFO))
        val crowded = RemoteLayout(row = shipped + RowButton.INFO + RowButton.BACK)
        assertEquals(shipped, crowded.shownRow)
        val twice = RemoteLayout(row = listOf(RowButton.BACK, RowButton.BACK, RowButton.HOME))
        assertEquals(listOf(RowButton.BACK, RowButton.HOME), twice.shownRow)
        assertTrue(RowButton.BACK !in twice.addable)
    }

    @Test
    fun `remove, add and move edit the row in place`() {
        val layout = RemoteLayout().withRemoved(RowButton.VOICE).withAdded(RowButton.CAPTIONS).withMoved(5, 0)
        assertEquals(
            listOf(RowButton.CAPTIONS, RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.KEYBOARD, RowButton.MUTE),
            layout.shownRow,
        )
        assertEquals(layout, layout.withMoved(9, 2))
    }

    @Test
    fun `moving a pin shifts the others and keeps pages whole`() {
        val a = DockSlot(DockAction.Key(1))
        val b = DockSlot(DockAction.Key(2))
        val c = DockSlot(DockAction.Key(3))
        val dock = DockConfig(slots = listOf(a, b, c, DockSlot()))
        assertEquals(listOf(c, a, b, DockSlot()), dock.withSlotMoved(2, 0).slots)
        assertEquals(dock.slots, dock.withSlotMoved(1, 1).slots)
        assertTrue(dock.withPageAdded().pageIsEmpty(1))
        assertTrue(!dock.pageIsEmpty(0))
    }
}
