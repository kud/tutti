package io.kud.tutti.quick

import io.kud.tutti.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickHeadlineTest {
    private val cinema = QuickScene("cinema", "Cinema", 0xFF336699)
    private val withTv = QuickState(configured = true, tvConfigured = true, connected = true, power = true, scenes = listOf(cinema))

    @Test
    fun `an app reads by name, then the active scene`() {
        val line = quickHeadline(withTv.copy(showing = QuickShowing.App("YouTube", "com.example.video"), activeSceneId = "cinema"))
        assertEquals(listOf(QuickWord.Plain("YouTube"), QuickWord.Plain("Cinema")), line.words)
        assertEquals(R.drawable.ic_apps, line.glyph)
    }

    @Test
    fun `a scene changed by hand says so`() {
        val line = quickHeadline(
            withTv.copy(showing = QuickShowing.Input("Console"), activeSceneId = "cinema", activeSceneModified = true),
        )
        assertEquals(listOf(QuickWord.Plain("Console"), QuickWord.Res(R.string.quick_scene_modified, "Cinema")), line.words)
        assertEquals(R.drawable.ic_input, line.glyph)
    }

    @Test
    fun `a channel with no words is a plain TV channel`() {
        val line = quickHeadline(withTv.copy(showing = QuickShowing.Channel(null)))
        assertEquals(listOf(QuickWord.Res(R.string.system_tv_channel)), line.words)
        assertEquals(R.drawable.ic_tv, line.glyph)
    }

    @Test
    fun `standby and out of reach never carry the scene`() {
        val asleep = quickHeadline(withTv.copy(showing = QuickShowing.Standby(barOn = false), activeSceneId = "cinema"))
        assertEquals(listOf(QuickWord.Res(R.string.card_standby)), asleep.words)
        assertEquals(R.drawable.ic_power, asleep.glyph)
        val gone = quickHeadline(withTv.copy(showing = QuickShowing.Offline, activeSceneId = "cinema"))
        assertEquals(listOf(QuickWord.Res(R.string.card_offline)), gone.words)
        assertEquals(R.drawable.ic_warning, gone.glyph)
    }

    @Test
    fun `the bar on its own names its source and what the TV is doing`() {
        val line = quickHeadline(withTv.copy(showing = QuickShowing.OnTheBar("spotify", tvAsleep = true)))
        assertEquals(listOf(QuickWord.Plain("Spotify"), QuickWord.Res(R.string.card_standby)), line.words)
        val unknown = quickHeadline(withTv.copy(showing = QuickShowing.OnTheBar("usb", tvAsleep = false)))
        assertEquals(listOf(QuickWord.Res(R.string.card_on_the_bar), QuickWord.Res(R.string.card_offline)), unknown.words)
    }

    @Test
    fun `never the protocol word connected`() {
        val states = listOf(
            QuickState(),
            QuickState(configured = true, barName = "Bar"),
            QuickState(configured = true, connected = true, barName = "Bar"),
            withTv.copy(showing = QuickShowing.Home),
            withTv.copy(showing = QuickShowing.Reconnecting),
        )
        states.forEach { state ->
            val ids = quickHeadline(state).words.filterIsInstance<QuickWord.Res>().map { it.id }
            assertFalse(R.string.quick_status_connected in ids)
            assertFalse(R.string.quick_status_disconnected in ids)
        }
    }

    @Test
    fun `without a TV, the soundbar by name or why it can't be reached`() {
        assertEquals(listOf(QuickWord.Res(R.string.quick_status_set_up)), quickHeadline(QuickState()).words)
        assertEquals(
            listOf(QuickWord.Res(R.string.system_unreachable, "Bar")),
            quickHeadline(QuickState(configured = true, barName = "Bar")).words,
        )
        assertEquals(listOf(QuickWord.Plain("Bar")), quickHeadline(QuickState(configured = true, connected = true, barName = "Bar")).words)
    }

    @Test
    fun `play pause is offered with a TV, except while it sleeps`() {
        assertFalse(playPauseOffered(QuickState(configured = true, barName = "Bar")))
        assertTrue(playPauseOffered(withTv.copy(showing = QuickShowing.Home)))
        assertTrue(playPauseOffered(withTv.copy(showing = QuickShowing.Offline)))
        assertFalse(playPauseOffered(withTv.copy(showing = QuickShowing.Standby(barOn = true))))
    }
}
