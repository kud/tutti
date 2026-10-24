package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputModeTest {
    private val closed = InputMode()

    @Test
    fun `a field from the tv opens the view, live`() {
        val mode = InputRules.tv(closed, hasField = true, shows = 1, now = 0)
        assertTrue(mode.open)
        assertEquals(InputOrigin.TV, mode.origin)
        assertEquals(FieldPhase.LIVE, InputRules.phase(mode, reachable = true))
    }

    @Test
    fun `the keyboard key with no tv field waits, then goes live when the tv opens one`() {
        val waiting = InputRules.keyboardKey(closed)
        assertTrue(waiting.open)
        assertEquals(InputOrigin.KEY, waiting.origin)
        assertEquals(FieldPhase.WAITING, InputRules.phase(waiting, reachable = true))
        val live = InputRules.tv(waiting, hasField = true, shows = 3, now = 10)
        assertTrue(live.open)
        assertEquals(FieldPhase.LIVE, InputRules.phase(live, reachable = true))
        assertEquals("going live is not a hand-over", 0, live.handOvers)
    }

    @Test
    fun `leaving keeps the tv field behind the keyboard key and the same field doesn't reopen`() {
        val open = InputRules.tv(closed, true, 1, 0)
        val left = InputRules.leave(open)
        assertFalse(left.open)
        assertTrue(left.fieldWaiting)
        assertEquals(left, InputRules.tv(left, true, 1, 50))
        val back = InputRules.keyboardKey(left)
        assertTrue(back.open)
        assertEquals(FieldPhase.LIVE, InputRules.phase(back, true))
    }

    @Test
    fun `a new tv field reopens the view after leaving`() {
        val left = InputRules.leave(InputRules.tv(closed, true, 1, 0))
        val next = InputRules.tv(left, true, 2, 100)
        assertTrue(next.open)
        assertNull(next.left)
    }

    @Test
    fun `the tv closing a field it waited behind clears the keyboard key`() {
        val left = InputRules.leave(InputRules.tv(closed, true, 1, 0))
        val gone = InputRules.tv(left, false, 1, 100)
        assertFalse(gone.fieldWaiting)
        assertNull(gone.left)
    }

    @Test
    fun `the tv closing the field holds the closed frame for 700 ms, then the remote`() {
        val open = InputRules.tv(closed, true, 1, 0)
        val shut = InputRules.tv(open, false, 1, 1_000)
        assertTrue(shut.open)
        assertEquals(FieldPhase.CLOSED, InputRules.phase(shut, true))
        assertFalse(shut.sent)
        assertTrue(InputRules.tick(shut, 1_699).open)
        val home = InputRules.tick(shut, 1_700)
        assertFalse(home.open)
        assertFalse(home.fieldWaiting)
    }

    @Test
    fun `a close soon after enter counts as sent, a late one doesn't`() {
        val open = InputRules.tv(closed, true, 1, 0)
        val entered = InputRules.enter(open, 5_000)
        assertTrue(InputRules.tv(entered, false, 1, 6_500).sent)
        assertFalse(InputRules.tv(entered, false, 1, 7_001).sent)
        assertFalse(InputRules.tv(open, false, 1, 6_500).sent)
    }

    @Test
    fun `a new field inside the grace takes over in place`() {
        val open = InputRules.enter(InputRules.tv(closed, true, 1, 0), 900)
        val shut = InputRules.tv(open, false, 1, 1_000)
        val next = InputRules.tv(shut, true, 2, 1_400)
        assertTrue(next.open)
        assertEquals(FieldPhase.LIVE, InputRules.phase(next, true))
        assertNull(next.closedAt)
        assertEquals(1, next.handOvers)
        assertTrue("the grace no longer runs", InputRules.tick(next, 5_000).open)
    }

    @Test
    fun `a new field while one is open hands over, the same one typed in doesn't`() {
        val open = InputRules.tv(closed, true, 1, 0)
        assertEquals(open, InputRules.tv(open, true, 1, 10))
        assertEquals(1, InputRules.tv(open, true, 2, 10).handOvers)
    }

    @Test
    fun `back hides the phone keyboard first, then leaves`() {
        assertEquals(BackAction.HIDE_KEYBOARD, InputRules.back(imeVisible = true))
        assertEquals(BackAction.LEAVE, InputRules.back(imeVisible = false))
    }

    @Test
    fun `an unreachable tv outranks every other phase`() {
        val open = InputRules.tv(closed, true, 1, 0)
        assertEquals(FieldPhase.UNREACHABLE, InputRules.phase(open, reachable = false))
        assertEquals(FieldPhase.UNREACHABLE, InputRules.phase(InputRules.keyboardKey(closed), reachable = false))
    }
}
