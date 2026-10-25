package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.protocol.atv.KeyGroup
import io.kud.tutti.protocol.atv.NamedKeys
import io.kud.tutti.protocol.atv.V2Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowShowingActionsTest {
    private val app = NowShowing.App("Example", "com.example.player")

    @Test
    fun `every playback key is a named remote v2 playback key`() {
        for (code in PLAYBACK_TRANSPORT + PLAYBACK_TRACKS) {
            assertEquals("key $code", KeyGroup.PLAYBACK, NamedKeys.byCode(code)?.group)
        }
    }

    @Test
    fun `play pause is one toggle in the middle of the transport`() {
        assertEquals(V2Key.MEDIA_PLAY_PAUSE, PLAYBACK_TRANSPORT[PLAYBACK_TRANSPORT.size / 2])
        assertFalse(V2Key.MEDIA_PLAY in PLAYBACK_TRANSPORT)
        assertFalse(V2Key.MEDIA_PAUSE in PLAYBACK_TRANSPORT)
    }

    @Test
    fun `the tap opens playback only while the tv shows something`() {
        assertTrue(cardOpensPlayback(CardShowing.Showing(app)))
        assertTrue(cardOpensPlayback(CardShowing.Showing(NowShowing.Input("HDMI 2"))))
        assertFalse(cardOpensPlayback(CardShowing.Home))
        assertFalse(cardOpensPlayback(CardShowing.Offline))
        assertFalse(cardOpensPlayback(CardShowing.Reconnecting))
        assertFalse(cardOpensPlayback(CardShowing.Standby(barOn = true)))
        assertFalse(cardOpensPlayback(null))
    }

    @Test
    fun `the long press acts on apps only`() {
        assertEquals(app, cardActionsApp(CardShowing.Showing(app)))
        assertNull(cardActionsApp(CardShowing.Showing(NowShowing.Input("HDMI 2"))))
        assertNull(cardActionsApp(CardShowing.Home))
    }

    @Test
    fun `pinned index finds the pin that launches the app`() {
        val dock = DockConfig(
            slots = listOf(
                DockSlot(DockAction.Key(V2Key.HOME)),
                DockSlot(),
                DockSlot(DockAction.LaunchApp("com.example.player", "Example")),
            ),
        )
        assertEquals(2, pinnedIndex(dock, "com.example.player"))
        assertNull(pinnedIndex(dock, "com.example.other"))
    }

    @Test
    fun `actions offer pin or unpin, and closing only with ip control`() {
        assertEquals(listOf(AppAction.PIN), appActions(pinnedAt = null, pskSet = false))
        assertEquals(listOf(AppAction.UNPIN, AppAction.CLOSE_APPS), appActions(pinnedAt = 3, pskSet = true))
    }
}
