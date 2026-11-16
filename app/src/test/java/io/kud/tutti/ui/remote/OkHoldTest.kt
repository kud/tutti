package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OK held on the pad: the key goes down once at the timeout and comes up exactly once. */
class OkHoldTest {
    @Test
    fun `a tap never holds and never releases`() {
        val ok = OkHold()
        assertEquals(OkHoldEffect.NONE, ok.end())
        assertFalse(ok.holding)
    }

    @Test
    fun `a still finger past the timeout holds, and the lift releases`() {
        val ok = OkHold()
        assertEquals(OkHoldEffect.HOLD, ok.timeout())
        assertTrue(ok.holding)
        assertEquals(OkHoldEffect.RELEASE, ok.end())
        assertFalse(ok.holding)
    }

    @Test
    fun `the key goes down once however often the timeout is seen`() {
        val ok = OkHold()
        ok.timeout()
        assertEquals(OkHoldEffect.NONE, ok.timeout())
    }

    @Test
    fun `a second finger lifts the held key, and the lift after it sends nothing more`() {
        val ok = OkHold()
        ok.timeout()
        assertEquals(OkHoldEffect.RELEASE, ok.interrupt())
        assertEquals(OkHoldEffect.NONE, ok.end())
    }

    @Test
    fun `a touch taken by two fingers before the timeout never holds`() {
        val ok = OkHold()
        assertEquals(OkHoldEffect.NONE, ok.interrupt())
        assertEquals(OkHoldEffect.NONE, ok.timeout())
        assertEquals(OkHoldEffect.NONE, ok.end())
    }

    @Test
    fun `a cancelled touch releases, and a late timeout after it never puts the key back down`() {
        val ok = OkHold()
        ok.timeout()
        assertEquals(OkHoldEffect.RELEASE, ok.end())
        assertEquals(OkHoldEffect.NONE, ok.timeout())
        assertEquals(OkHoldEffect.NONE, ok.end())
    }
}
