package io.kud.tutti.ui.common

import io.kud.tutti.R
import io.kud.tutti.protocol.atv.KeyGroup
import io.kud.tutti.protocol.atv.NamedKeys
import io.kud.tutti.protocol.atv.V2Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeyIconTest {
    @Test
    fun `only play pause wears the play pause glyph`() {
        val wearing = NamedKeys.all.filter { keyIcon(it.code) == R.drawable.ic_play_pause }.map { it.name }
        assertEquals(listOf("MEDIA_PLAY_PAUSE"), wearing)
    }

    @Test
    fun `every playback key has a glyph of its own kind`() {
        for (key in NamedKeys.all.filter { it.group == KeyGroup.PLAYBACK && it.code != V2Key.MEDIA_PLAY_PAUSE }) {
            assertNotEquals(key.name, R.drawable.ic_play_pause, keyIcon(key.code))
        }
        assertEquals(R.drawable.ic_fast_rewind, keyIcon(V2Key.MEDIA_REWIND))
        assertEquals(R.drawable.ic_fast_forward, keyIcon(V2Key.MEDIA_FAST_FORWARD))
        assertEquals(R.drawable.ic_subtitles, keyIcon(V2Key.CAPTIONS))
        assertEquals(R.drawable.ic_audiotrack, keyIcon(V2Key.MEDIA_AUDIO_TRACK))
    }

    @Test
    fun `the tv keys in all keys each wear a glyph of their own`() {
        val keys = listOf(V2Key.GUIDE, V2Key.INFO, V2Key.TV_INPUT, V2Key.CAPTIONS, V2Key.MEDIA_AUDIO_TRACK, V2Key.MENU, V2Key.SETTINGS)
        val glyphs = keys.map(::keyIcon)
        assertEquals(keys.size, glyphs.toSet().size)
        assertEquals(R.drawable.ic_more_vert, keyIcon(V2Key.MENU))
    }

    @Test
    fun `the tv back key wears the return arrow wherever it shows`() {
        assertEquals(R.drawable.ic_tv_back, keyIcon(V2Key.BACK))
        assertEquals(R.drawable.ic_tv_back, io.kud.tutti.ui.remote.rowButtonIcon(io.kud.tutti.platform.data.RowButton.BACK))
    }
}
