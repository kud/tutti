package io.kud.tutti.controller

import io.kud.tutti.controller.TvLinkRule.GIVE_UP_MS
import io.kud.tutti.protocol.atv.V2Key
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLinkRuleTest {
    @Test
    fun `a drop after being up shows at once, never connected while presses fail`() {
        // Erwann, 2026-11-07: « Soit c'est connecté, soit c'est pas connecté, et puis basta. »
        assertEquals(TvShown(Link.CONNECTING, reconnecting = true), TvLinkRule.shown(Link.UNREACHABLE, 0))
        assertEquals(TvShown(Link.CONNECTING, reconnecting = true), TvLinkRule.shown(Link.CONNECTING, 1))
        for (raw in Link.entries.filter { it != Link.ONLINE }) {
            for (down in listOf(null, 0L, 500L, 2_999L, 3_000L, GIVE_UP_MS, GIVE_UP_MS * 10)) {
                assertTrue("$raw after $down ms must not read online", TvLinkRule.shown(raw, down).link != Link.ONLINE)
            }
        }
    }

    @Test
    fun `it reads reconnecting until give-up, then out of reach`() {
        assertEquals(TvShown(Link.CONNECTING, reconnecting = true), TvLinkRule.shown(Link.CONNECTING, GIVE_UP_MS - 1))
        assertEquals(TvShown(Link.UNREACHABLE), TvLinkRule.shown(Link.UNREACHABLE, GIVE_UP_MS))
        // A retry after give-up does not flicker back to "Connecting…".
        assertEquals(TvShown(Link.UNREACHABLE), TvLinkRule.shown(Link.CONNECTING, GIVE_UP_MS + 5_000))
    }

    @Test
    fun `a link that is up reads up, whatever the clock`() {
        assertEquals(TvShown(Link.ONLINE), TvLinkRule.shown(Link.ONLINE, null))
        assertEquals(TvShown(Link.ONLINE), TvLinkRule.shown(Link.ONLINE, 0))
        assertEquals(TvShown(Link.ONLINE), TvLinkRule.shown(Link.ONLINE, GIVE_UP_MS * 2))
    }

    @Test
    fun `a retry after an out-of-reach attempt keeps reading out of reach`() {
        // dev.16, 2026-10-26: a TV off the network read "connecting…" on both lines forever,
        // because every retry (woken at once by the remote and the power button) flipped back.
        assertEquals(Link.CONNECTING, TvLinkRule.whileTrying(failedInARow = 0))
        assertEquals(Link.UNREACHABLE, TvLinkRule.whileTrying(failedInARow = 1))
        assertEquals(Link.UNREACHABLE, TvLinkRule.whileTrying(failedInARow = 7))
    }

    @Test
    fun `a link never up this session shows as it is`() {
        assertEquals(TvShown(Link.CONNECTING), TvLinkRule.shown(Link.CONNECTING, null))
        assertEquals(TvShown(Link.UNREACHABLE), TvLinkRule.shown(Link.UNREACHABLE, null))
    }

    @Test
    fun `pairing and absent keep their own state whatever the clock`() {
        assertEquals(TvShown(Link.NEEDS_PAIRING), TvLinkRule.shown(Link.NEEDS_PAIRING, 0))
        assertEquals(TvShown(Link.ABSENT), TvLinkRule.shown(Link.ABSENT, 500))
        assertEquals(TvShown(Link.ONLINE), TvLinkRule.shown(Link.ONLINE, null))
    }

    @Test
    fun `the clock wakes at give-up, then stops`() {
        assertNull(TvLinkRule.nextChangeInMs(null))
        assertEquals(GIVE_UP_MS, TvLinkRule.nextChangeInMs(0))
        assertEquals(GIVE_UP_MS - 3_000, TvLinkRule.nextChangeInMs(3_000))
        assertNull(TvLinkRule.nextChangeInMs(GIVE_UP_MS))
    }

    @Test
    fun `no key or launch is ever held, whatever the link reads`() {
        // Erwann, 2026-11-07: « soit c'est connecté et ça marche, soit c'est pas connecté et ça fait rien ».
        assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.whileDown(Link.UNREACHABLE, TvShown(Link.ONLINE)))
        assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.whileDown(Link.CONNECTING, TvShown(Link.ONLINE)))
        assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.whileDown(Link.CONNECTING, TvShown(Link.CONNECTING, reconnecting = true)))
        assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.whileDown(Link.CONNECTING, TvShown(Link.CONNECTING)))
    }

    @Test
    fun `a key pressed while down is never sent when the link comes back`() {
        // The controller has no test harness; this reads its source, as DownDressTest does for the UI.
        // sendTvKey either sends now or reports the drop: nothing in it waits on the link.
        val file = sequenceOf(File("src/main/java/io/kud/tutti/controller/TuttiController.kt"), File("app/src/main/java/io/kud/tutti/controller/TuttiController.kt"))
            .first { it.isFile }
        val source = file.readText()
        val start = source.indexOf("private suspend fun sendTvKey(")
        assertTrue("sendTvKey not found", start >= 0)
        val body = source.substring(start, source.indexOf("\n    }\n", start))
        assertTrue("sendTvKey reports the drop", "KeyNotSent" in body)
        for (wait in listOf("withTimeout", ".first", "delay(", "launch")) {
            assertFalse("sendTvKey must not wait for the link ($wait)", wait in body)
        }
    }

    @Test
    fun `a tv that is gone or forgot us says so`() {
        assertEquals(KeyOutcome.DROPPED_DOWN, TvLinkRule.whileDown(Link.CONNECTING, TvShown(Link.UNREACHABLE)))
        assertEquals(KeyOutcome.DROPPED_DOWN, TvLinkRule.whileDown(Link.UNREACHABLE, TvShown(Link.UNREACHABLE)))
        assertEquals(KeyOutcome.DROPPED_DOWN, TvLinkRule.whileDown(Link.ABSENT, TvShown(Link.ABSENT)))
        assertEquals(KeyOutcome.DROPPED_PAIRING, TvLinkRule.whileDown(Link.NEEDS_PAIRING, TvShown(Link.NEEDS_PAIRING)))
    }

    @Test
    fun `home and play pause wake a tv known asleep, and nothing else does`() {
        assertEquals(true, TvLinkRule.wakesTv(V2Key.HOME, long = false, tvPowered = false, barAlone = false))
        assertEquals(true, TvLinkRule.wakesTv(V2Key.MEDIA_PLAY_PAUSE, long = false, tvPowered = false, barAlone = false))
        for (key in listOf(V2Key.BACK, V2Key.DPAD_CENTER, V2Key.DPAD_UP, V2Key.MEDIA_PLAY, V2Key.GUIDE, V2Key.SETTINGS)) {
            assertEquals(false, TvLinkRule.wakesTv(key, long = false, tvPowered = false, barAlone = false))
        }
        assertEquals(false, TvLinkRule.wakesTv(V2Key.HOME, long = true, tvPowered = false, barAlone = false))
    }

    @Test
    fun `a tv that is on or only out of reach is never woken by a key`() {
        assertEquals(false, TvLinkRule.wakesTv(V2Key.HOME, long = false, tvPowered = true, barAlone = false))
        assertEquals(false, TvLinkRule.wakesTv(V2Key.HOME, long = false, tvPowered = null, barAlone = false))
        assertEquals(false, TvLinkRule.wakesTv(V2Key.MEDIA_PLAY_PAUSE, long = false, tvPowered = null, barAlone = true))
    }

    @Test
    fun `with the bar playing its own music only home wakes the tv`() {
        assertEquals(true, TvLinkRule.wakesTv(V2Key.HOME, long = false, tvPowered = false, barAlone = true))
        assertEquals(false, TvLinkRule.wakesTv(V2Key.MEDIA_PLAY_PAUSE, long = false, tvPowered = false, barAlone = true))
    }

    @Test
    fun `an app tapped while the tv is known in standby wakes it, whatever its links say`() {
        assertEquals(true, TvLinkRule.launchWakes(hasTv = true, tvPowered = false, raw = Link.ONLINE, shown = TvShown(Link.ONLINE)))
        assertEquals(true, TvLinkRule.launchWakes(hasTv = true, tvPowered = false, raw = Link.UNREACHABLE, shown = TvShown(Link.UNREACHABLE)))
    }

    @Test
    fun `an app tapped while the tv is out of reach wakes it, deep standby looks like that`() {
        assertEquals(true, TvLinkRule.launchWakes(hasTv = true, tvPowered = null, raw = Link.UNREACHABLE, shown = TvShown(Link.UNREACHABLE)))
    }

    @Test
    fun `an app tapped on a tv that is on, or coming back from a blip, does not wake it`() {
        assertEquals(false, TvLinkRule.launchWakes(hasTv = true, tvPowered = true, raw = Link.ONLINE, shown = TvShown(Link.ONLINE)))
        assertEquals(false, TvLinkRule.launchWakes(hasTv = true, tvPowered = null, raw = Link.UNREACHABLE, shown = TvShown(Link.ONLINE)))
        assertEquals(false, TvLinkRule.launchWakes(hasTv = true, tvPowered = null, raw = Link.CONNECTING, shown = TvShown(Link.CONNECTING, reconnecting = true)))
    }

    @Test
    fun `no tv or a lost pairing never wakes on an app tap`() {
        assertEquals(false, TvLinkRule.launchWakes(hasTv = false, tvPowered = false, raw = Link.ABSENT, shown = TvShown(Link.ABSENT)))
        assertEquals(false, TvLinkRule.launchWakes(hasTv = true, tvPowered = false, raw = Link.NEEDS_PAIRING, shown = TvShown(Link.NEEDS_PAIRING)))
        assertEquals(false, TvLinkRule.launchWakes(hasTv = true, tvPowered = false, raw = Link.UNAUTHORIZED, shown = TvShown(Link.UNAUTHORIZED)))
    }
}
