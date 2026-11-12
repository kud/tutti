package io.kud.tutti.protocol.auto

import io.kud.tutti.protocol.auto.AutoPause.Cause
import io.kud.tutti.protocol.auto.AutoPause.Decision
import io.kud.tutti.protocol.auto.AutoPause.Snapshot
import io.kud.tutti.protocol.auto.AutoPause.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoPauseTest {
    private val watching = Snapshot(tvOn = true, app = "com.example.video")
    private val home = Snapshot(tvOn = true, app = "com.google.android.apps.tv.launcherx")

    @Test
    fun `a call pauses an app and its end plays it again`() {
        val rang = AutoPause.begin(State(), Cause.CALL, watching, at = 0)
        assertIs<Decision.Pause>(rang.decision)
        val ended = AutoPause.end(rang.state, Cause.CALL, watching, at = 60_000)
        assertIs<Decision.Resume>(ended.decision)
        assertEquals(State(), ended.state)
    }

    @Test
    fun `ringing then answering pauses once`() {
        val rang = AutoPause.begin(State(), Cause.CALL, watching, at = 0)
        val answered = AutoPause.begin(rang.state, Cause.CALL, watching, at = 5_000)
        assertIs<Decision.Nothing>(answered.decision)
        assertEquals(rang.state, answered.state)
    }

    @Test
    fun `nothing is paused on the launcher, with the tv off, or when cast says paused`() {
        for (now in listOf(home, watching.copy(tvOn = false), watching.copy(app = null), watching.copy(castPlaying = false))) {
            val step = AutoPause.begin(State(), Cause.CALL, now, at = 0)
            assertIs<Decision.Nothing>(step.decision)
            assertNull(step.state.claim)
            // And its end plays nothing: tutti paused nothing.
            assertIs<Decision.Nothing>(AutoPause.end(step.state, Cause.CALL, watching, at = 1).decision)
        }
    }

    @Test
    fun `never resumes another app, another item, a tv off, or something already playing`() {
        val paused = AutoPause.begin(State(), Cause.CALL, watching.copy(castItem = "a"), at = 0).state
        val refusals = listOf(
            watching.copy(app = "com.example.other"),
            watching.copy(castItem = "b"),
            watching.copy(tvOn = false),
            watching.copy(castPlaying = true),
            home,
        )
        for (now in refusals) {
            val step = AutoPause.end(paused, Cause.CALL, now, at = 1_000)
            assertIs<Decision.Nothing>(step.decision, "$now")
            assertEquals(State(), step.state)
        }
        // Cast silent at the end (the remote left the front): the app alone decides.
        assertIs<Decision.Resume>(AutoPause.end(paused, Cause.CALL, watching, at = 1_000).decision)
    }

    @Test
    fun `a stale claim is dropped`() {
        val paused = AutoPause.begin(State(), Cause.CALL, watching, at = 0).state
        val step = AutoPause.end(paused, Cause.CALL, watching, at = AutoPause.MAX_CLAIM_MS + 1)
        assertIs<Decision.Nothing>(step.decision)
    }

    @Test
    fun `turning an option off drops its claim without playing`() {
        val paused = AutoPause.begin(State(), Cause.CALL, watching, at = 0).state
        assertEquals(State(), AutoPause.drop(paused, Cause.CALL))
    }

    @Test
    fun `the fade climbs back to the level it left`() {
        val levels = ResumeFade.levels(30)
        assertEquals(12, levels.first())
        assertEquals(30, levels.last())
        assertEquals(levels.sorted(), levels)
        assertTrue(ResumeFade.levels(3).isEmpty())
    }
}
