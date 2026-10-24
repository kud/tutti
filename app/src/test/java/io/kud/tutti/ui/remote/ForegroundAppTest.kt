package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.bravia.PlayingContent
import io.kud.tutti.protocol.bravia.PowerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppTest {
    @Test
    fun `a catalogued package shows by name`() {
        assertEquals("Netflix", foregroundAppName("com.netflix.ninja", Link.ONLINE, PowerStatus.UNKNOWN))
    }

    @Test
    fun `an unknown package shows nothing, never the raw id`() {
        assertNull(foregroundAppName("com.example.launcher", Link.ONLINE, PowerStatus.ACTIVE))
        assertNull(foregroundAppName(null, Link.ONLINE, PowerStatus.ACTIVE))
    }

    private val tuner = PlayingContent(
        uri = "tv:dvbt?trip=1.1.1",
        source = "tv:dvbt",
        title = "Channel Nine",
        programTitle = "The Garden Hour",
        dispNum = "009",
    )
    private val hdmi = PlayingContent(uri = "extInput:hdmi?port=2", source = "extInput:hdmi", title = "HDMI 2")

    private fun now(playing: PlayingContent?, rest: Link = Link.ONLINE, app: String? = "com.netflix.ninja", inputs: List<ExternalInput> = emptyList()) =
        nowShowing(playing, inputs, rest, app, Link.ONLINE, PowerStatus.ACTIVE)

    @Test
    fun `the tuner shows number, channel and programme`() {
        val channel = now(tuner) as NowShowing.Channel
        assertEquals("9 · Channel Nine — The Garden Hour", channel.text)
        assertEquals("9 · Channel Nine", (now(tuner.copy(programTitle = " ")) as NowShowing.Channel).text)
        assertNull((now(PlayingContent("tv:dvbt", null, null)) as NowShowing.Channel).text)
    }

    @Test
    fun `an input shows its user label, else the tv's title`() {
        assertEquals(NowShowing.Input("HDMI 2"), now(hdmi))
        val labelled = listOf(ExternalInput(uri = "extInput:hdmi?port=2", title = "HDMI 2", label = "Console", connected = true))
        assertEquals(NowShowing.Input("Console"), now(hdmi, inputs = labelled))
    }

    @Test
    fun `no rest answer falls back to the app, and a stale one is ignored`() {
        assertEquals(NowShowing.App("Netflix", "com.netflix.ninja"), now(null))
        assertEquals(NowShowing.App("Netflix", "com.netflix.ninja"), now(tuner, rest = Link.UNREACHABLE))
        assertNull(now(null, app = null))
    }

    @Test
    fun `a stale package says nothing once the tv is away or asleep`() {
        assertNull(foregroundAppName("com.netflix.ninja", Link.UNREACHABLE, PowerStatus.ACTIVE))
        assertNull(foregroundAppName("com.netflix.ninja", Link.ONLINE, PowerStatus.STANDBY))
    }

    @Test
    fun `the card always has something to say once a tv is set up`() {
        val app = NowShowing.App("Netflix", "com.netflix.ninja")
        assertNull(cardShowing(false, app, Link.ONLINE, PowerStatus.ACTIVE, barOn = true))
        assertEquals(CardShowing.Showing(app), cardShowing(true, app, Link.ONLINE, PowerStatus.ACTIVE, barOn = true))
        assertEquals(CardShowing.Home, cardShowing(true, null, Link.ONLINE, PowerStatus.ACTIVE, barOn = true))
        assertEquals(CardShowing.Offline, cardShowing(true, null, Link.UNREACHABLE, PowerStatus.UNKNOWN, barOn = false))
        assertEquals(CardShowing.Offline, cardShowing(true, null, Link.CONNECTING, PowerStatus.UNKNOWN, barOn = false))
    }

    @Test
    fun `a reconnect says so, and a link down wins over what rest still reports`() {
        val channel = NowShowing.Channel("2", "France 2", null)
        assertEquals(CardShowing.Reconnecting, cardShowing(true, channel, Link.CONNECTING, PowerStatus.ACTIVE, barOn = true, reconnecting = true))
        assertEquals(CardShowing.Offline, cardShowing(true, channel, Link.UNREACHABLE, PowerStatus.ACTIVE, barOn = true))
        assertEquals(CardShowing.Standby(true), cardShowing(true, null, Link.CONNECTING, PowerStatus.STANDBY, barOn = true, reconnecting = true))
    }

    @Test
    fun `standby wins and says whether the bar stays on`() {
        val app = NowShowing.App("Netflix", "com.netflix.ninja")
        assertEquals(CardShowing.Standby(true), cardShowing(true, app, Link.ONLINE, PowerStatus.STANDBY, barOn = true))
        assertEquals(CardShowing.Standby(false), cardShowing(true, null, Link.UNREACHABLE, PowerStatus.STANDBY, barOn = false))
    }
}
