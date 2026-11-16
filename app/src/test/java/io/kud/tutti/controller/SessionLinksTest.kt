package io.kud.tutti.controller

import io.kud.tutti.platform.data.BarDevice
import io.kud.tutti.platform.data.Devices
import io.kud.tutti.platform.data.TvDevice
import io.kud.tutti.quick.ControllerQuickControls
import io.kud.tutti.platform.diagnostic.remotePairingRecorded
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLinksTest {
    @Test
    fun `a stopped session leaves no link up and none trying, and keeps verdicts`() {
        assertEquals(Link.UNREACHABLE, Link.ONLINE.stopped())
        assertEquals(Link.UNREACHABLE, Link.CONNECTING.stopped())
        assertEquals(Link.NEEDS_PAIRING, Link.NEEDS_PAIRING.stopped())
        assertEquals(Link.UNAUTHORIZED, Link.UNAUTHORIZED.stopped())
        assertEquals(Link.ABSENT, Link.ABSENT.stopped())
    }

    @Test
    fun `a new epoch asks every live or failed link again`() {
        val old = Links(tv = Link.ONLINE, rest = Link.UNAUTHORIZED, bar = Link.UNREACHABLE, epoch = 3)
        val fresh = old.restarted(4)
        assertEquals(Links(tv = Link.CONNECTING, rest = Link.UNAUTHORIZED, bar = Link.CONNECTING, epoch = 4), fresh)
    }

    private val both = Devices(tv = TvDevice("192.168.1.20"), bar = BarDevice("192.168.1.21"))

    @Test
    fun `Power waits for power to be heard while a link is still trying`() {
        val connecting = TuttiUiState(devices = both, links = Links(tv = Link.CONNECTING, bar = Link.ONLINE))
        assertFalse(ControllerQuickControls.powerSettled(connecting))
        assertTrue(ControllerQuickControls.powerSettled(connecting.copy(powered = false)))
    }

    @Test
    fun `a quick surface waits for the link only on a cold start`() {
        // Erwann, 2026-11-07: « Soit c'est connecté, soit c'est pas connecté, et puis basta. »
        assertTrue("no sessions: the tap starts the connect", ControllerQuickControls.waitsForLink(sessionsWereLive = false))
        assertFalse("a live session is never waited on through a blip", ControllerQuickControls.waitsForLink(sessionsWereLive = true))
    }

    @Test
    fun `the quick surfaces gate their wait on a cold start, and read liveness before retaining`() {
        // The controller has no harness; this reads the source, as TvLinkRuleTest does for sendTvKey.
        val source = source("quick/ControllerQuickControls.kt")
        val start = source.indexOf("private suspend fun held(")
        assertTrue("held not found", start >= 0)
        val body = source.substring(start, source.indexOf("\n    }\n", start))
        val live = body.indexOf("controller.sessionsLive")
        val retain = body.indexOf("controller.retainSession()")
        val gate = body.indexOf("if (waitsForLink(")
        val wait = body.indexOf("withTimeoutOrNull(")
        assertTrue("liveness is read before retaining starts the sessions", live in 0 until retain)
        assertTrue("the wait sits inside the cold-start gate", gate in 0 until wait)
    }

    @Test
    fun `restarted sessions read as a fresh connect, never as a reconnect`() {
        val source = source("controller/TuttiController.kt")
        val start = source.indexOf("private suspend fun runSessions(")
        assertTrue("runSessions not found", start >= 0)
        val body = source.substring(start, source.indexOf("\n    }\n", start))
        val reset = body.indexOf("tvWasUp = false")
        // A resume keeps the clock retainSession started (TvLinkRule.resumes).
        val clock = body.indexOf("if (!tvResuming.value) tvDownSince = null")
        val restarted = body.indexOf("links.update { it.restarted(epoch) }")
        assertTrue("the shown clock resets before the links move", reset in 0 until restarted && clock in 0 until restarted)
        assertEquals(TvShown(Link.CONNECTING), TvLinkRule.shown(Link.CONNECTING, null))
    }

    private fun source(path: String): String =
        sequenceOf(File("src/main/java/io/kud/tutti/$path"), File("app/src/main/java/io/kud/tutti/$path"))
            .first { it.isFile }
            .readText()

    @Test
    fun `Power goes ahead (and wakes) once no configured link is trying`() {
        val tvAsleep = TuttiUiState(devices = both.copy(bar = null), links = Links(tv = Link.UNREACHABLE, bar = Link.ABSENT))
        assertTrue(ControllerQuickControls.powerSettled(tvAsleep))
    }
}

class RemotePairingRuleTest {
    @Test
    fun `remote pairing needs both the tv pin and the client identity`() {
        assertTrue(remotePairingRecorded(pinPresent = true, identityPresent = true))
        assertFalse(remotePairingRecorded(pinPresent = true, identityPresent = false))
        assertFalse(remotePairingRecorded(pinPresent = false, identityPresent = true))
        assertFalse(remotePairingRecorded(pinPresent = false, identityPresent = false))
    }
}
