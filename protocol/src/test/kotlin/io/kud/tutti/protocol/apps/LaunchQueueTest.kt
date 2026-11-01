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

    @Test
    fun `a tap while the tv sleeps wakes it once and shows the app meanwhile`() {
        val step = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ASLEEP, now = 0)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Wake(1, player, until = LaunchQueue.WAKE_MS, start = true)), step.effects)
        assertEquals(LaunchPhase.WAKING, step.book.pending?.phase)
        assertEquals(player, step.book.shown(reported = null))
        // Nothing has gone out yet: a caller waiting for the send keeps waiting.
        assertEquals(0, step.book.lastSentId)
        // A report from the waking TV (its home screen) doesn't take over from the tap.
        assertEquals(player, LaunchQueue.foreground(step.book, reported = music).book.optimistic)
    }

    @Test
    fun `the tv waking sends the launch`() {
        val waking = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ASLEEP, now = 0).book
        val awake = LaunchQueue.awake(waking)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Send(1, player)), awake.effects)
        assertEquals(LaunchPhase.SENDING, awake.book.pending?.phase)
        assertNull(awake.book.pending?.heldUntil)
        assertEquals(1, awake.book.lastSentId)
        // Only once: a second word that the TV is on sends nothing more.
        assertEquals(awake.book, LaunchQueue.awake(awake.book).book)
        assertTrue(LaunchQueue.awake(awake.book).effects.isEmpty())
    }

    @Test
    fun `a failed wake drops the launch and says the tv did not come on`() {
        val waking = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ASLEEP, now = 0).book
        val failed = LaunchQueue.notWoken(waking)
        val failure = LaunchFailure(player, expired = true, asleep = true)
        assertEquals(listOf(LaunchEffect.Done(1, ok = false), LaunchEffect.Failed(failure)), failed.effects)
        assertNull(failed.book.pending)
        assertNull(failed.book.optimistic)
        assertEquals(failure, failed.book.failure)
        // Never sent into the void: the TV answering late changes nothing.
        assertTrue(LaunchQueue.awake(failed.book).effects.isEmpty())
    }

    @Test
    fun `a wake past its window gives up on its own`() {
        val waking = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ASLEEP, now = 0).book
        assertEquals(waking, LaunchQueue.tick(waking, now = LaunchQueue.WAKE_MS - 1).book)
        val expired = LaunchQueue.tick(waking, now = LaunchQueue.WAKE_MS)
        assertEquals(LaunchFailure(player, expired = true, asleep = true), expired.book.failure)
    }

    @Test
    fun `another app tapped while waking takes its place and never wakes twice`() {
        val first = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ASLEEP, now = 0).book
        // The TV is still coming up: whatever the link reads, short of ready, the tap joins the wake.
        for (link in listOf(LaunchLink.ASLEEP, LaunchLink.DOWN, LaunchLink.ON_ITS_WAY)) {
            val second = LaunchQueue.tap(first, music, link, now = 3_000)
            assertEquals(
                listOf(LaunchEffect.Superseded(1), LaunchEffect.Done(1, ok = false), LaunchEffect.Wake(2, music, until = LaunchQueue.WAKE_MS, start = false)),
                second.effects,
                "link $link",
            )
            assertEquals(music, second.book.optimistic)
            assertEquals(LaunchQueue.WAKE_MS, second.book.pending?.heldUntil)
            // The wake answers for whichever app waits on it now.
            assertEquals(listOf<LaunchEffect>(LaunchEffect.Send(2, music)), LaunchQueue.awake(second.book).effects)
        }
    }

    @Test
    fun `the same app tapped again while waking joins the wake`() {
        val waking = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ASLEEP, now = 0).book
        val again = LaunchQueue.tap(waking, player, LaunchLink.ASLEEP, now = 1_000)
        assertEquals(listOf<LaunchEffect>(LaunchEffect.Repeat(player)), again.effects)
        assertEquals(waking, again.book)
        assertEquals(1L, again.book.inFlightFor(player))
    }

    @Test
    fun `a tap once the tv is up replaces the waking launch and sends at once`() {
        val waking = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ASLEEP, now = 0).book
        val sent = LaunchQueue.tap(waking, music, LaunchLink.READY, now = 9_000)
        assertEquals(listOf(LaunchEffect.Superseded(1), LaunchEffect.Done(1, ok = false), LaunchEffect.Send(2, music)), sent.effects)
        // The wake answering afterwards has nothing left to send.
        assertTrue(LaunchQueue.awake(sent.book).effects.isEmpty())
        assertTrue(LaunchQueue.notWoken(sent.book).effects.isEmpty())
    }

    @Test
    fun `a blip's held launch is not a wake`() {
        val held = LaunchQueue.tap(LaunchBook(), player, LaunchLink.ON_ITS_WAY, now = 0).book
        assertTrue(LaunchQueue.awake(held).effects.isEmpty())
        assertTrue(LaunchQueue.notWoken(held).effects.isEmpty())
        assertEquals(LaunchFailure(player, expired = true, asleep = false), LaunchQueue.tick(held, now = LaunchQueue.HOLD_MS).book.failure)
    }
}
