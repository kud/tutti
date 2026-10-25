package io.kud.tutti.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPollRulesTest {
    @Test
    fun `a change long after the last poll waits only to settle`() =
        assertEquals(TvPollRules.SETTLE_MS, TvPollRules.delayAfterChange(startedAt = 0, now = 8_000))

    @Test
    fun `a change right after a poll keeps the minimum gap`() =
        assertEquals(1_800L, TvPollRules.delayAfterChange(startedAt = 1_000, now = 1_200))

    @Test
    fun `inputs are read when unknown, on a new input and every minute, never with the tv off`() {
        assertTrue(TvPollRules.inputsDue(active = true, known = false, askedAt = 5, now = 6, inputChanged = false))
        assertTrue(TvPollRules.inputsDue(active = true, known = true, askedAt = null, now = 6, inputChanged = false))
        assertTrue(TvPollRules.inputsDue(active = true, known = true, askedAt = 0, now = 10_000, inputChanged = true))
        assertFalse(TvPollRules.inputsDue(active = true, known = true, askedAt = 0, now = TvPollRules.INPUTS_EVERY_MS - 1, inputChanged = false))
        assertTrue(TvPollRules.inputsDue(active = true, known = true, askedAt = 0, now = TvPollRules.INPUTS_EVERY_MS, inputChanged = false))
        assertFalse(TvPollRules.inputsDue(active = false, known = false, askedAt = null, now = 0, inputChanged = true))
    }
}
