package io.kud.tutti.protocol.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchQueueTest {
    private val player = "org.example.player"
    private val music = "org.example.music"

    @Test
    fun `a tap with the link up sends at once and shows the app before the tv answers`() {
        val step = LaunchQueue.tap(LaunchBook(), player, LaunchLink.READY, now = 0)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Send(1, player)), step.effects)
        assertEquals(LaunchPhase.SENDING, step.book.pending?.phase)
        assertEquals(1, step.book.lastSentId)
        assertEquals(player, step.book.shown(reported = music))
    }

    @Test
    fun `the tv confirming hands the screen back to what it reports`() {
        val sent = LaunchQueue.tap(LaunchBook(), player, LaunchLink.READY, now = 0).book
        val done = LaunchQueue.settled(sent, 1, LaunchVerdict.CONFIRMED, now = 800)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Done(1, ok = true)), done.effects)
        assertNull(done.book.pending)
        assertNull(done.book.optimistic)
        assertEquals(player, done.book.shown(reported = player))
    }

    @Test
    fun `a failed launch rolls back and says so`() {
        val sent = LaunchQueue.tap(LaunchBook(), player, LaunchLink.READY, now = 0).book
        val done = LaunchQueue.settled(sent, 1, LaunchVerdict.FAILED, now = 9_000, detail = "launch org.example.player: failed")
        assertEquals(music, done.book.shown(reported = music))
        val failed = done.effects.filterIsInstance<LaunchEffect.Failed>().single().failure
        assertEquals(LaunchFailure(player, expired = false, detail = "launch org.example.player: failed"), failed)
        assertEquals(failed, done.book.failure)
        assertTrue(LaunchEffect.Done(1, ok = false) in done.effects)
    }

    @Test
    fun `an unconfirmed launch keeps the app on screen until the tv speaks or the grace ends`() {
        val sent = LaunchQueue.tap(LaunchBook(), player, LaunchLink.READY, now = 0).book
        val unobserved = LaunchQueue.settled(sent, 1, LaunchVerdict.SENT, now = 100).book
        assertEquals(player, unobserved.shown(reported = music))
        assertEquals(player, LaunchQueue.tick(unobserved, now = 100 + LaunchQueue.SENT_GRACE_MS - 1).book.optimistic)
        assertNull(LaunchQueue.tick(unobserved, now = 100 + LaunchQueue.SENT_GRACE_MS).book.optimistic)
        assertNull(LaunchQueue.foreground(unobserved, reported = music).book.optimistic)
    }

    @Test
    fun `a tap repeated while its launch is in flight never launches twice`() {
        val sent = LaunchQueue.tap(LaunchBook(), player, LaunchLink.READY, now = 0).book
        val again = LaunchQueue.tap(sent, player, LaunchLink.READY, now = 200)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Repeat(player)), again.effects)
        assertEquals(sent, again.book)
        val held = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ON_ITS_WAY, now = 0).book
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Repeat(player)), LaunchQueue.tap(held, player, LaunchLink.ON_ITS_WAY, now = 300).effects)
    }

    @Test
    fun `a tap in a blip is held, then sent when the link is back`() {
        val held = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ON_ITS_WAY, now = 1_000)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Held(1, player, 1_000 + LaunchQueue.HOLD_MS)), held.effects)
        assertEquals(player, held.book.shown(reported = music))
        val back = LaunchQueue.linkReady(held.book, 1, now = 2_500)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Send(1, player)), back.effects)
        assertEquals(LaunchPhase.SENDING, back.book.pending?.phase)
        assertEquals(0, held.book.lastSentId)
        assertEquals(1, back.book.lastSentId)
        assertEquals(1, back.book.inFlightFor(player))
        assertNull(back.book.inFlightFor(music))
    }

    @Test
    fun `a held launch past its window is dropped, rolled back and said`() {
        val held = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ON_ITS_WAY, now = 0).book
        assertEquals(held, LaunchQueue.tick(held, now = LaunchQueue.HOLD_MS - 1).book)
        val gone = LaunchQueue.tick(held, now = LaunchQueue.HOLD_MS)
        assertNull(gone.book.pending)
        assertEquals(music, gone.book.shown(reported = music))
        assertEquals(LaunchFailure(player, expired = true), gone.effects.filterIsInstance<LaunchEffect.Failed>().single().failure)
        // The link coming back too late sends nothing either.
        val late = LaunchQueue.linkReady(held, 1, now = LaunchQueue.HOLD_MS + 1)
        assertTrue(late.effects.none { it is LaunchEffect.Send })
        assertNull(late.book.pending)
    }

    @Test
    fun `a tap with the tv down is refused and changes nothing`() {
        val book = LaunchBook()
        val step = LaunchQueue.tap(book, player, LaunchLink.DOWN, now = 0)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Refused(player)), step.effects)
        assertEquals(book, step.book)
    }

    @Test
    fun `the newest tap wins, and the launch it replaced settles as nothing`() {
        val first = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ON_ITS_WAY, now = 0).book
        val second = LaunchQueue.tap(first, music, LaunchLink.ON_ITS_WAY, now = 500)
        assertEquals(LaunchEffect.Superseded(1), second.effects.first())
        assertIs<LaunchEffect.Held>(second.effects.last())
        assertEquals(music, second.book.pending?.packageName)
        assertEquals(music, second.book.optimistic)
        // The old launch's link-ready or result no longer touches the book.
        assertEquals(second.book, LaunchQueue.linkReady(second.book, 1, now = 600).book)
        assertEquals(second.book, LaunchQueue.settled(second.book, 1, LaunchVerdict.FAILED, now = 700).book)
    }

    @Test
    fun `a report while a launch is in flight leaves the optimistic app alone`() {
        val sent = LaunchQueue.tap(LaunchBook(), player, LaunchLink.READY, now = 0).book
        assertEquals(player, LaunchQueue.foreground(sent, reported = music).book.optimistic)
    }

    @Test
    fun `a new tap clears the last failure`() {
        val failed = LaunchQueue.tick(LaunchQueue.tap(LaunchBook(), player, LaunchLink.ON_ITS_WAY, now = 0).book, now = LaunchQueue.HOLD_MS).book
        assertEquals(player, failed.failure?.packageName)
        assertNull(LaunchQueue.tap(failed, music, LaunchLink.READY, now = 5_000).book.failure)
    }
}
