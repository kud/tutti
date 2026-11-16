package io.kud.tutti.platform.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Input joined the default row on 2026-10-26 (Erwann). A row he never touched gets it; a row he
 * arranged keeps exactly what he chose. Prefs are stored with every default written out, so these
 * read real JSON as earlier versions wrote it.
 */
class DefaultRowUpgradeTest {
    private val withInput = listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD, RowButton.INPUT)

    private fun prefs(json: String) = StoreJson.decodeFromString(AppPrefs.serializer(), json)

    private fun roundTrip(layout: RemoteLayout) =
        StoreJson.decodeFromString(RemoteLayout.serializer(), StoreJson.encodeToString(RemoteLayout.serializer(), layout))

    @Test
    fun `the default row ends with input and fills the row`() {
        assertEquals(withInput, RemoteLayout.DEFAULT_ROW)
        assertEquals(RemoteLayout.MAX_ROW, RemoteLayout().shownRow.size)
        assertEquals(withInput, AppPrefs().layout.shownRow)
    }

    @Test
    fun `an untouched row stored by the last version gets input, and the rest of the layout stays`() {
        val stored = """{"onboarded":true,"layout":{"row":["BACK","HOME","PLAY","VOICE","KEYBOARD"],"scenes":false,"padSize":"LARGE","hand":"LEFT"}}"""
        val layout = prefs(stored).layout
        assertEquals(withInput, layout.shownRow)
        assertFalse(layout.scenes)
        assertEquals(PadSize.LARGE, layout.padSize)
        assertEquals(Hand.LEFT, layout.hand)
        assertFalse(layout.rowChosen)
    }

    @Test
    fun `an untouched row from the first version, with mute, gets today's row`() {
        val stored = """{"layout":{"row":["BACK","HOME","PLAY","VOICE","KEYBOARD","MUTE"],"scenes":true,"padSize":"NORMAL"}}"""
        assertEquals(withInput, prefs(stored).layout.shownRow)
    }

    @Test
    fun `a row he arranged before input joined is kept exactly`() {
        val shorter = """{"layout":{"row":["INFO","BACK"],"scenes":true,"padSize":"NORMAL","hand":"RIGHT"}}"""
        assertEquals(listOf(RowButton.INFO, RowButton.BACK), prefs(shorter).layout.shownRow)
        val reordered = """{"layout":{"row":["HOME","BACK","PLAY","VOICE","KEYBOARD"],"scenes":true,"padSize":"NORMAL","hand":"RIGHT"}}"""
        assertEquals(
            listOf(RowButton.HOME, RowButton.BACK, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD),
            prefs(reordered).layout.shownRow,
        )
        val full = """{"layout":{"row":["BACK","HOME","PLAY","VOICE","KEYBOARD","GUIDE"],"scenes":true,"padSize":"NORMAL","hand":"RIGHT"}}"""
        assertEquals(RowButton.GUIDE, prefs(full).layout.shownRow.last())
        assertTrue(RowButton.INPUT !in prefs(full).layout.shownRow)
    }

    @Test
    fun `input taken out of the new row stays out after a save and a restart`() {
        val without = RemoteLayout().withRemoved(RowButton.INPUT)
        assertTrue(without.rowChosen)
        val again = roundTrip(without)
        assertEquals(withInput - RowButton.INPUT, again.shownRow)
        // Written out with the mark, so it never reads as an old untouched row.
        assertTrue(""""rowChosen":true""" in StoreJson.encodeToString(RemoteLayout.serializer(), without))
    }

    @Test
    fun `a row he arranged into a shipped default stays his when the default moves on`() {
        val chosen = RemoteLayout(row = listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD), rowChosen = true)
        assertEquals(chosen.row, roundTrip(chosen).shownRow)
    }

    @Test
    fun `an untouched row survives a round trip and keeps following the default`() {
        val untouched = RemoteLayout().copy(hand = Hand.LEFT, padSize = PadSize.COMPACT)
        val again = roundTrip(untouched)
        assertEquals(untouched, again)
        assertFalse(again.rowChosen)
        assertEquals(withInput, again.shownRow)
    }

    @Test
    fun `only a real edit marks the row as his`() {
        val fresh = RemoteLayout()
        assertSame(fresh, fresh.withMoved(2, 2))
        assertSame(fresh, fresh.withMoved(9, 9))
        assertSame(fresh, fresh.withAdded(RowButton.HOME))
        assertTrue(fresh.withMoved(0, 1).rowChosen)
        assertTrue(fresh.withRemoved(RowButton.VOICE).rowChosen)
        assertTrue(fresh.withRemoved(RowButton.VOICE).withAdded(RowButton.VOICE).rowChosen)
    }

    @Test
    fun `reset puts the row back to following the default`() {
        assertFalse(RemoteLayout().rowChosen)
        assertEquals(withInput, RemoteLayout().shownRow)
    }

    @Test
    fun `long presses stored before input had one read unchanged, and input's own round trips`() {
        val stored = """{"slots":[{}],"longPress":{"HOME":{"type":"key","keyCode":176},"BACK":{"type":"scene","sceneId":"s"}}}"""
        val dock = StoreJson.decodeFromString(DockConfig.serializer(), stored)
        assertEquals(DockAction.Key(176), dock.longPress[SecondaryButton.HOME])
        assertEquals(DockAction.RunScene("s"), dock.longPress[SecondaryButton.BACK])
        assertTrue(SecondaryButton.INPUT !in dock.longPress)
        val withInput = dock.copy(longPress = dock.longPress + (SecondaryButton.INPUT to DockAction.Key(178)))
        val again = StoreJson.decodeFromString(DockConfig.serializer(), StoreJson.encodeToString(DockConfig.serializer(), withInput))
        assertEquals(withInput.longPress, again.longPress)
    }
}
