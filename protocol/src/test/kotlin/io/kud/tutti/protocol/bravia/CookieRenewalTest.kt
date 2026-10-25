package io.kud.tutti.protocol.bravia

import io.kud.tutti.protocol.bravia.CookieRenewal.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookieRenewalTest {
    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_800_000_000_000L
    private val fourteenDays = AuthCookie("tutti:x", "v", expiresAtMs = t0 + 14 * day, obtainedAtMs = t0)
    private val noExpiry = AuthCookie("tutti:x", "v", expiresAtMs = null, obtainedAtMs = t0)

    private fun decide(cookie: AuthCookie = fourteenDays, now: Long, tried: Long = 0, refused: Boolean = false, awaiting: Boolean = false) =
        CookieRenewal.decide(cookie, awaitingCode = awaiting, refused = refused, nowMs = now, lastTriedAtMs = tried)

    @Test
    fun `a fresh cookie is kept`() = assertEquals(Step.Keep, decide(now = t0 + day))

    @Test
    fun `renews three days before the expiry the tv gave`() {
        assertEquals(Step.Keep, decide(now = t0 + 11 * day - 1))
        assertEquals(Step.Renew, decide(now = t0 + 11 * day))
    }

    @Test
    fun `without an expiry, renews after the assumed week`() {
        assertEquals(Step.Keep, decide(noExpiry, now = t0 + 7 * day - 1))
        assertEquals(Step.Renew, decide(noExpiry, now = t0 + 7 * day))
    }

    @Test
    fun `a due renewal is retried at most every six hours`() {
        val now = t0 + 12 * day
        assertEquals(Step.Keep, decide(now = now, tried = now - CookieRenewal.RETRY_MS + 1))
        assertEquals(Step.Renew, decide(now = now, tried = now - CookieRenewal.RETRY_MS))
    }

    @Test
    fun `past its expiry the retry window no longer holds it back`() {
        val now = t0 + 14 * day
        assertEquals(Step.Renew, decide(now = now, tried = now - 1000))
    }

    @Test
    fun `a refusal renews at once, even mid retry window`() =
        assertEquals(Step.Renew, decide(now = t0 + day, tried = t0 + day - 2 * CookieRenewal.REFUSED_AFTER_RENEW_MS, refused = true))

    @Test
    fun `refused again right after a renewal asks for a code`() =
        assertEquals(Step.NeedCode, decide(now = t0 + day, tried = t0 + day - 1000, refused = true))

    @Test
    fun `while a code is awaited nothing is tried`() {
        assertEquals(Step.NeedCode, decide(now = t0 + day, awaiting = true))
        assertEquals(Step.NeedCode, decide(now = t0 + 13 * day, awaiting = true))
    }

    @Test
    fun `an unreachable tv keeps a valid cookie, never a refused or expired one`() {
        assertTrue(CookieRenewal.keepAfterUnreachable(fourteenDays, refused = false, nowMs = t0 + 12 * day))
        assertFalse(CookieRenewal.keepAfterUnreachable(fourteenDays, refused = true, nowMs = t0 + 12 * day))
        assertFalse(CookieRenewal.keepAfterUnreachable(fourteenDays, refused = false, nowMs = t0 + 14 * day))
        assertTrue(CookieRenewal.keepAfterUnreachable(noExpiry, refused = false, nowMs = t0 + 100 * day))
    }
}
