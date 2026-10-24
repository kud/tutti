package io.kud.tutti.protocol.net

import io.kud.tutti.protocol.net.WakeAttempt.Cause
import io.kud.tutti.protocol.net.WakeAttempt.Reach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeAttemptTest {
    private fun attempt(
        tv: Reach = Reach.SILENT,
        bar: Reach = Reach.NOT_CONFIGURED,
        macKnown: Boolean = false,
        packets: Int = 0,
        last: String? = "timeout",
    ) = WakeAttempt(tv, bar, macKnown, packets, 12_000, last)

    @Test
    fun `a missing bar is reported as not configured, never as answered`() {
        val outcome = attempt().outcome()
        assertTrue("bar not configured" in outcome, outcome)
        assertFalse("bar answered" in outcome, outcome)
    }

    @Test
    fun `the report from the phone names the missing mac and the silence`() {
        assertEquals(
            "tv did not answer, bar not configured within 12000 ms; wake-on-lan: mac not learnt (needs ip control with psk), " +
                "packets sent 0; remote last connect: timeout (nothing answered at the address)",
            attempt().outcome(),
        )
    }

    @Test
    fun `no mac means no packet, whatever the connect said`() {
        listOf("timeout", "connection_refused", null).forEach { assertEquals(Cause.TV_NO_MAC, attempt(last = it).cause, it) }
    }

    @Test
    fun `packets sent and nothing at the address is not found, a refusal is asleep`() {
        assertEquals(Cause.TV_NOT_FOUND, attempt(macKnown = true, packets = 3, last = "timeout").cause)
        assertEquals(Cause.TV_NOT_FOUND, attempt(macKnown = true, packets = 3, last = "no_route").cause)
        assertEquals(Cause.TV_ASLEEP, attempt(macKnown = true, packets = 3, last = "connection_refused").cause)
        assertEquals(Cause.TV_ASLEEP, attempt(macKnown = true, packets = 3, last = null).cause)
        assertTrue("host up, port closed" in attempt(macKnown = true, last = "connection_refused").outcome())
    }

    @Test
    fun `the bar is blamed only when the tv came back or is not involved`() {
        assertEquals(Cause.BAR_SILENT, attempt(tv = Reach.ANSWERED, bar = Reach.SILENT).cause)
        val barOnly = attempt(tv = Reach.NOT_CONFIGURED, bar = Reach.SILENT)
        assertEquals(Cause.BAR_SILENT, barOnly.cause)
        assertFalse("wake-on-lan" in barOnly.outcome(), barOnly.outcome())
    }

    @Test
    fun `success has no cause`() {
        val ok = attempt(tv = Reach.ANSWERED, bar = Reach.ANSWERED, macKnown = true, packets = 1)
        assertFalse(ok.failed)
        assertNull(ok.cause)
        assertFalse(attempt(tv = Reach.ANSWERED, bar = Reach.NOT_CONFIGURED).failed)
    }
}
