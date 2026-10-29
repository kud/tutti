package io.kud.tutti.platform.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prefs saved before layouts existed must draw the remote exactly as it always was. */
class RemoteLayoutCompatTest {
    private val stored = """{"haptics":false,"theme":"DARK","trueBlack":true,"hiddenApps":["com.x"],"padMode":"DPAD","onboarded":true}"""

    // Today's row: mute moved to the volume rail's foot (2026-10-24), Input joined (2026-10-26).
    private val shipped = listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD, RowButton.INPUT)

    @Test
    fun `prefs stored before layouts read as today's screen`() {
        val prefs = StoreJson.decodeFromString(AppPrefs.serializer(), stored)
        assertEquals(RemoteLayout(), prefs.layout)
        assertEquals(shipped, prefs.layout.shownRow)
        assertTrue(prefs.layout.scenes)
        assertEquals(PadSize.NORMAL, prefs.layout.padSize)
        assertEquals(Hand.RIGHT, prefs.layout.hand)
        // The rest of the old prefs is untouched.
        assertEquals(PadMode.DPAD, prefs.padMode)
        assertEquals(setOf("com.x"), prefs.hiddenApps)
    }

    @Test
    fun `a layout stored before the hand existed loads, right-handed, with the rest untouched`() {
        val old = """{"layout":{"row":["INFO","BACK"],"scenes":false,"padSize":"LARGE"}}"""
        val layout = StoreJson.decodeFromString(AppPrefs.serializer(), old).layout
        assertEquals(RemoteLayout(listOf(RowButton.INFO, RowButton.BACK), scenes = false, padSize = PadSize.LARGE, hand = Hand.RIGHT), layout)
    }

    @Test
    fun `a left hand survives a round trip`() {
        val left = RemoteLayout(hand = Hand.LEFT)
        assertEquals(left, StoreJson.decodeFromString(RemoteLayout.serializer(), StoreJson.encodeToString(RemoteLayout.serializer(), left)))
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
        val full = RemoteLayout().withRemoved(RowButton.INPUT).withAdded(RowButton.INFO)
        assertEquals(RemoteLayout.MAX_ROW, full.shownRow.size)
        assertSame(full, full.withAdded(RowButton.CAPTIONS))
        val fresh = RemoteLayout()
        assertSame(fresh, fresh.withAdded(RowButton.INFO))
        val crowded = RemoteLayout(row = shipped + RowButton.INFO + RowButton.CAPTIONS + RowButton.BACK)
        assertEquals(shipped, crowded.shownRow)
        val twice = RemoteLayout(row = listOf(RowButton.BACK, RowButton.BACK, RowButton.HOME))
        assertEquals(listOf(RowButton.BACK, RowButton.HOME), twice.shownRow)
        assertTrue(RowButton.BACK !in twice.addable)
    }

    @Test
    fun `remove, add and move edit the row in place`() {
        val layout = RemoteLayout().withRemoved(RowButton.VOICE).withAdded(RowButton.CAPTIONS).withMoved(5, 0)
        assertEquals(
            listOf(RowButton.CAPTIONS, RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.KEYBOARD, RowButton.INPUT),
            layout.shownRow,
        )
        assertEquals(layout, layout.withMoved(9, 2))
    }

    @Test
    fun `a stored row with the old mute key draws without it and never offers it back`() {
        // An arranged row: mute is dropped, the rest kept as he left it.
        val old = RemoteLayout(row = listOf(RowButton.INFO, RowButton.HOME, RowButton.MUTE), rowChosen = true)
        val again = StoreJson.decodeFromString(RemoteLayout.serializer(), StoreJson.encodeToString(RemoteLayout.serializer(), old))
        assertEquals(listOf(RowButton.INFO, RowButton.HOME), again.shownRow)
        assertTrue(RowButton.MUTE !in again.addable)
        assertEquals(again.shownRow, again.withAdded(RowButton.MUTE).shownRow)
    }

    @Test
    fun `moving a pin shifts the others and keeps pages whole`() {
        val a = DockSlot(DockAction.Key(1))
        val b = DockSlot(DockAction.Key(2))
        val c = DockSlot(DockAction.Key(3))
        val dock = DockConfig(slots = listOf(a, b, c, DockSlot()))
        val rest = List(DockConfig.PAGE_SIZE - 3) { DockSlot() }
        assertEquals(listOf(c, a, b) + rest, dock.withSlotMoved(2, 0).slots)
        assertEquals(listOf(a, b, c) + rest, dock.withSlotMoved(1, 1).slots)
    }

    @Test
    fun `the right hand draws the row mirrored, back under the thumb`() {
        val right = RemoteLayout(hand = Hand.RIGHT)
        assertEquals(RemoteLayout.DEFAULT_ROW.reversed(), right.rowOnScreen)
        assertEquals(RowButton.BACK, right.rowOnScreen.last())
        assertEquals(RemoteLayout.DEFAULT_ROW, RemoteLayout(hand = Hand.LEFT).rowOnScreen)
    }

    @Test
    fun `a move on screen lands where it was dropped, for either hand`() {
        val right = RemoteLayout(hand = Hand.RIGHT).withMovedOnScreen(from = 5, to = 0)
        assertEquals(RowButton.BACK, right.rowOnScreen.first())
        assertEquals(RowButton.BACK, right.shownRow.last())
        val left = RemoteLayout(hand = Hand.LEFT).withMovedOnScreen(from = 0, to = 5)
        assertEquals(RowButton.BACK, left.rowOnScreen.last())
    }
}
