package io.kud.tutti.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiRefusalTest {

    private val now = 1_763_200_000_000L

    private fun of(code: Int, remaining: String? = null, reset: String? = null, retryAfter: String? = null) =
        ApiRefusal.of(code, remaining, reset, retryAfter, now)

    @Test
    fun exhaustedLimitWaitsUntilTheReset() {
        val reset = (now / 1000 + 1_800).toString()
        assertEquals(ApiRefusal.Wait(reset.toLong() * 1000), of(403, remaining = "0", reset = reset))
        assertEquals(ApiRefusal.Wait(reset.toLong() * 1000), of(429, remaining = "0", reset = reset))
    }

    @Test
    fun retryAfterWinsOverTheReset() {
        val reset = (now / 1000 + 1_800).toString()
        assertEquals(ApiRefusal.Wait(now + 60_000), of(403, remaining = "0", reset = reset, retryAfter = "60"))
        // The secondary limit: a 403 with only retry-after.
        assertEquals(ApiRefusal.Wait(now + 60_000), of(403, retryAfter = " 60 "))
    }

    @Test
    fun aWaitWithNoUsableTimeHasNoEnd() {
        assertEquals(ApiRefusal.Wait(null), of(429))
        assertEquals(ApiRefusal.Wait(null), of(403, remaining = "0"))
        assertEquals(ApiRefusal.Wait(null), of(403, remaining = "0", reset = "soon"))
        // A reset already past (clock skew) says nothing about when to come back.
        assertEquals(ApiRefusal.Wait(null), of(403, remaining = "0", reset = (now / 1000 - 5).toString()))
        assertEquals(ApiRefusal.Wait(null), of(429, retryAfter = "-3"))
    }

    @Test
    fun aResetWithoutAnExhaustedLimitIsNotUsed() {
        // A 429 with a quota left (secondary limit) and a reset header: the reset is for the primary limit.
        assertEquals(ApiRefusal.Wait(null), of(429, remaining = "12", reset = (now / 1000 + 600).toString()))
    }

    @Test
    fun otherRefusalsAreErrorsNotConnectionProblems() {
        assertEquals(ApiRefusal.Error(403), of(403))
        assertEquals(ApiRefusal.Error(403), of(403, remaining = "41", reset = (now / 1000 + 600).toString()))
        assertEquals(ApiRefusal.Error(404), of(404))
        assertEquals(ApiRefusal.Error(500), of(500))
        assertEquals(ApiRefusal.Error(503), of(503, retryAfter = "30"))
    }
}
