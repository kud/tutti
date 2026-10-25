package io.kud.tutti.controller

import io.kud.tutti.controller.TvLinkRule.BLIP_MS
import io.kud.tutti.controller.TvLinkRule.GIVE_UP_MS
import io.kud.tutti.protocol.atv.V2Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvLinkRuleTest {
    @Test
    fun `a blip after being up changes nothing on screen`() {
        assertEquals(TvShown(Link.ONLINE), TvLinkRule.shown(Link.UNREACHABLE, 0))
        assertEquals(TvShown(Link.ONLINE), TvLinkRule.shown(Link.CONNECTING, BLIP_MS - 1))
    }

    @Test
    fun `past the blip it reads reconnecting, past give-up out of reach`() {
        assertEquals(TvShown(Link.CONNECTING, reconnecting = true), TvLinkRule.shown(Link.UNREACHABLE, BLIP_MS))
        assertEquals(TvShown(Link.CONNECTING, reconnecting = true), TvLinkRule.shown(Link.CONNECTING, GIVE_UP_MS - 1))
        assertEquals(TvShown(Link.UNREACHABLE), TvLinkRule.shown(Link.UNREACHABLE, GIVE_UP_MS))
        // A retry after give-up does not flicker back to "Connecting…".
        assertEquals(TvShown(Link.UNREACHABLE), TvLinkRule.shown(Link.CONNECTING, GIVE_UP_MS + 5_000))
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
    fun `the clock wakes at each threshold, then stops`() {
        assertNull(TvLinkRule.nextChangeInMs(null))
        assertEquals(BLIP_MS, TvLinkRule.nextChangeInMs(0))
        assertEquals(GIVE_UP_MS - BLIP_MS, TvLinkRule.nextChangeInMs(BLIP_MS))
        assertNull(TvLinkRule.nextChangeInMs(GIVE_UP_MS))
    }

    @Test
    fun `one-shot keys wait, the d-pad and ok never do`() {
        val blip = TvShown(Link.ONLINE)
        listOf(V2Key.HOME, V2Key.BACK, V2Key.MEDIA_PLAY_PAUSE).forEach {
            assertEquals(KeyOutcome.HELD, TvLinkRule.whileDown(it, long = false, raw = Link.UNREACHABLE, shown = blip))
        }
        listOf(V2Key.DPAD_UP, V2Key.DPAD_LEFT, V2Key.DPAD_CENTER, V2Key.ENTER, V2Key.CHANNEL_UP, V2Key.NUM_1, V2Key.POWER).forEach {
            assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.whileDown(it, long = false, raw = Link.CONNECTING, shown = blip))
        }
        assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.whileDown(V2Key.HOME, long = true, raw = Link.CONNECTING, shown = blip))
    }

    @Test
    fun `nothing waits for a tv that is gone or forgot us`() {
        val gone = TvShown(Link.UNREACHABLE)
        assertEquals(KeyOutcome.DROPPED_DOWN, TvLinkRule.whileDown(V2Key.HOME, long = false, raw = Link.CONNECTING, shown = gone))
        assertEquals(KeyOutcome.DROPPED_PAIRING, TvLinkRule.whileDown(V2Key.HOME, long = false, raw = Link.NEEDS_PAIRING, shown = TvShown(Link.NEEDS_PAIRING)))
        assertEquals(KeyOutcome.DROPPED_DOWN, TvLinkRule.whileDown(V2Key.BACK, long = false, raw = Link.ABSENT, shown = TvShown(Link.ABSENT)))
    }

    @Test
    fun `a reconnect still holds a one-shot key`() {
        val back = TvShown(Link.CONNECTING, reconnecting = true)
        assertEquals(KeyOutcome.HELD, TvLinkRule.whileDown(V2Key.BACK, long = false, raw = Link.CONNECTING, shown = back))
        assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.whileDown(V2Key.DPAD_DOWN, long = false, raw = Link.CONNECTING, shown = back))
    }

    @Test
    fun `a launch waits out a blip, and is refused once the remote shows the tv down`() {
        assertEquals(KeyOutcome.HELD, TvLinkRule.launchWhileDown(Link.UNREACHABLE, TvShown(Link.ONLINE)))
        assertEquals(KeyOutcome.HELD, TvLinkRule.launchWhileDown(Link.CONNECTING, TvShown(Link.ONLINE)))
        assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.launchWhileDown(Link.CONNECTING, TvShown(Link.CONNECTING, reconnecting = true)))
        assertEquals(KeyOutcome.DROPPED_WAITING, TvLinkRule.launchWhileDown(Link.CONNECTING, TvShown(Link.CONNECTING)))
        assertEquals(KeyOutcome.DROPPED_DOWN, TvLinkRule.launchWhileDown(Link.UNREACHABLE, TvShown(Link.UNREACHABLE)))
        assertEquals(KeyOutcome.DROPPED_DOWN, TvLinkRule.launchWhileDown(Link.ABSENT, TvShown(Link.ABSENT)))
        assertEquals(KeyOutcome.DROPPED_PAIRING, TvLinkRule.launchWhileDown(Link.NEEDS_PAIRING, TvShown(Link.NEEDS_PAIRING)))
    }
}
