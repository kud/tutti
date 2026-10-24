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
}
