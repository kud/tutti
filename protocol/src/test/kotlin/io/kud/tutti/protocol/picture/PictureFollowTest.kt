package io.kud.tutti.protocol.picture

import io.kud.tutti.protocol.picture.PictureFollow.Reason
import io.kud.tutti.protocol.picture.PictureFollow.Write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PictureFollowTest {
    private val modes = listOf("vivid", "standard", "cinema", "game", "custom")
    private val on = PictureFollowConfig(enabled = true)
    private val netflix = PictureContext.App("com.netflix.ninja")
    private val youtube = PictureContext.App("com.google.android.youtube.tv")
    private val other = PictureContext.App("org.example.weather")
    private val hdmi2 = PictureContext.Input("extInput:hdmi?port=2")

    private fun enter(state: PictureFollowState, context: PictureContext, current: String?, config: PictureFollowConfig = on, now: Long = 0) =
        PictureFollow.onContext(state, config, context, current, modes, now)

    @Test
    fun `off by default and does nothing while off`() {
        assertFalse(PictureFollowConfig().enabled)
        val step = enter(PictureFollowState(), netflix, "standard", PictureFollowConfig())
        assertNull(step.write)
        assertFalse(step.state.holding)
    }

    @Test
    fun `a suggested app sets its mode and remembers the one it replaced`() {
        val step = enter(PictureFollowState(), netflix, "standard")
        assertEquals(Write("cinema", Reason.RULE), step.write)
        assertEquals("standard", step.state.baseline)
        assertEquals("cinema", step.state.expected)
        assertTrue(step.log!!.startsWith("picture follows: app rule"))
    }

    @Test
    fun `log lines never name the app or the input`() {
        val lines = listOfNotNull(
            enter(PictureFollowState(), netflix, "standard").log,
            enter(PictureFollowState(), hdmi2, "standard", on.copy(inputs = mapOf(hdmi2.uri to "game"))).log,
        )
        assertEquals(2, lines.size)
        lines.forEach { assertFalse("netflix" in it || "hdmi" in it, it) }
    }

    @Test
    fun `nothing is written when the TV already shows the mode`() {
        assertNull(enter(PictureFollowState(), youtube, "standard").write)
    }

    @Test
    fun `leaving every rule gives the old mode back`() {
        val inNetflix = enter(PictureFollowState(), netflix, "standard").state
        val step = enter(inNetflix, other, "cinema")
        assertEquals(Write("standard", Reason.RESTORE), step.write)
        assertFalse(step.state.holding)
    }

    @Test
    fun `going from one rule to another keeps the first baseline`() {
        val custom = on.copy(apps = mapOf("com.google.android.youtube.tv" to "vivid"))
        val inNetflix = enter(PictureFollowState(), netflix, "standard", custom).state
        val inYoutube = enter(inNetflix, youtube, "cinema", custom)
        assertEquals(Write("vivid", Reason.RULE), inYoutube.write)
        assertEquals("standard", inYoutube.state.baseline)
        assertEquals(Write("standard", Reason.RESTORE), enter(inYoutube.state, other, "vivid", custom).write)
    }

    @Test
    fun `Home is passed through, neither setting nor restoring`() {
        val inNetflix = enter(PictureFollowState(), netflix, "standard").state
        val home = enter(inNetflix, PictureContext.App("com.google.android.apps.tv.launcherx"), "cinema")
        assertNull(home.write)
        assertEquals(inNetflix, home.state)
        assertEquals(PictureContext.PassThrough, PictureFollow.contextOf(null, "com.google.android.apps.tv.launcherx"))
    }

    @Test
    fun `an override beats the suggestion and leave beats both`() {
        val cinemaless = on.copy(apps = mapOf("com.netflix.ninja" to "custom"))
        assertEquals("custom", PictureFollow.modeFor(cinemaless, netflix, modes))
        val leave = on.copy(apps = mapOf("com.netflix.ninja" to PictureFollowConfig.LEAVE))
        assertNull(PictureFollow.modeFor(leave, netflix, modes))
        assertNull(PictureFollow.modeFor(on.copy(suggestions = false), netflix, modes))
    }

    @Test
    fun `a mode the TV does not list is never written`() {
        assertNull(PictureFollow.modeFor(on, netflix, listOf("standard", "vivid")))
        assertNull(PictureFollow.modeFor(on.copy(apps = mapOf("com.netflix.ninja" to "dolbyVision")), netflix, modes))
        // Suggestions wait for the TV's list; his own pick came from it already.
        assertNull(PictureFollow.modeFor(on, netflix, emptyList()))
        assertEquals("custom", PictureFollow.modeFor(on.copy(apps = mapOf("com.netflix.ninja" to "custom")), netflix, emptyList()))
    }

    @Test
    fun `inputs take a rule only when he sets one`() {
        assertNull(PictureFollow.modeFor(on, hdmi2, modes))
        assertEquals("game", PictureFollow.modeFor(on.copy(inputs = mapOf(hdmi2.uri to "game")), hdmi2, modes))
    }

    @Test
    fun `an external input from REST wins over the package Remote v2 reports`() {
        assertEquals(hdmi2, PictureFollow.contextOf("extInput:hdmi?port=2", "com.netflix.ninja"))
        assertEquals(netflix, PictureFollow.contextOf(null, "com.netflix.ninja"))
        assertEquals(netflix, PictureFollow.contextOf("tv:dvbt?trip=1", "com.netflix.ninja"))
        assertEquals(PictureContext.Unknown, PictureFollow.contextOf(null, null))
    }

    @Test
    fun `an app whose profile scene sets the picture is left to the scene`() {
        assertNull(PictureFollow.modeFor(on, netflix, modes, sceneApps = setOf("com.netflix.ninja")))
    }

    @Test
    fun `a stale poll inside the settle window is not a change by hand`() {
        val inNetflix = enter(PictureFollowState(), netflix, "standard", now = 1_000).state
        val stale = PictureFollow.onObserved(inNetflix, "standard", 5_000)
        assertFalse(stale.state.paused)
        val seen = PictureFollow.onObserved(stale.state, "cinema", 12_000)
        assertNull(seen.state.wroteAt)
        assertFalse(seen.state.paused)
    }

    @Test
    fun `a change by hand pauses until standby and is never fought`() {
        val inNetflix = enter(PictureFollowState(), netflix, "standard", now = 0).state
        val settled = PictureFollow.onObserved(inNetflix, "cinema", 11_000).state
        val byHand = PictureFollow.onObserved(settled, "vivid", 21_000)
        assertTrue(byHand.state.paused)
        assertFalse(byHand.state.holding)
        // Paused: no rule writes, and leaving restores nothing.
        assertNull(enter(byHand.state, youtube, "vivid").write)
        assertNull(enter(byHand.state, other, "vivid").write)
        // Standby is a new session.
        val woke = PictureFollow.onStandby(byHand.state)
        assertEquals(Write("cinema", Reason.RULE), enter(woke, netflix, "vivid").write)
    }

    @Test
    fun `a change by hand past the window pauses even before the TV confirmed the write`() {
        val inNetflix = enter(PictureFollowState(), netflix, "standard", now = 0).state
        assertTrue(PictureFollow.onObserved(inNetflix, "vivid", PictureFollow.SETTLE_MS + 1).state.paused)
    }

    @Test
    fun `leaving after he moved the mode gives nothing back`() {
        val inNetflix = enter(PictureFollowState(), netflix, "standard").state
        val step = enter(inNetflix, other, "vivid")
        assertNull(step.write)
        assertFalse(step.state.holding)
    }

    @Test
    fun `a failed write leaves the state as it was`() {
        val before = PictureFollowState()
        val step = enter(before, netflix, "standard")
        val failed = PictureFollow.onWriteFailed(before, step.write!!)
        assertEquals(before, failed.state)
        assertTrue(failed.log!!.contains("failed"))
    }

    @Test
    fun `turning it off gives back what tutti changed`() {
        val inNetflix = enter(PictureFollowState(), netflix, "standard").state
        val off = enter(inNetflix, netflix, "cinema", on.copy(enabled = false))
        assertEquals(Write("standard", Reason.RESTORE), off.write)
        assertEquals(PictureFollowState(), off.state)
    }

    @Test
    fun `no reading of the current mode means no write`() {
        assertNull(enter(PictureFollowState(), netflix, null).write)
    }

    @Test
    fun `the config survives a round trip and reads an older file`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val config = on.copy(apps = mapOf("a" to "cinema"), inputs = mapOf("extInput:hdmi?port=1" to PictureFollowConfig.LEAVE))
        assertEquals(config, json.decodeFromString(PictureFollowConfig.serializer(), json.encodeToString(PictureFollowConfig.serializer(), config)))
        assertEquals(PictureFollowConfig(), json.decodeFromString(PictureFollowConfig.serializer(), "{}"))
    }
}
