package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.atv.V2Key
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetKeysTest {
    @Test
    fun `subtitles and audio track close the playback sheet`() {
        for (code in PLAYBACK_TRACKS) assertTrue("key $code", keyClosesSheet(code))
    }

    @Test
    fun `the transport keys keep the playback sheet open`() {
        for (code in PLAYBACK_TRANSPORT) assertFalse("key $code", keyClosesSheet(code))
    }

    @Test
    fun `all keys closes on the keys that open a tv menu`() {
        for (code in listOf(V2Key.GUIDE, V2Key.INFO, V2Key.TV_INPUT, V2Key.MENU, V2Key.SETTINGS)) {
            assertTrue("key $code", keyClosesSheet(code))
        }
    }

    @Test
    fun `repeatable all keys stay open`() {
        val repeatable = (0..9).map(V2Key::digit) + listOf(
            V2Key.CHANNEL_UP, V2Key.CHANNEL_DOWN, V2Key.LAST_CHANNEL, V2Key.TV,
            V2Key.PROG_RED, V2Key.PROG_GREEN, V2Key.PROG_YELLOW, V2Key.PROG_BLUE,
        )
        for (code in repeatable) assertFalse("key $code", keyClosesSheet(code))
    }
}
