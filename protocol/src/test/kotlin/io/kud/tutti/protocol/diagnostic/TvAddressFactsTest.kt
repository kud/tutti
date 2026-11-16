package io.kud.tutti.protocol.diagnostic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TvAddressFactsTest {
    @Test
    fun `an ipv4 tv beside the bar reads as such`() {
        assertEquals(
            "ipv4, private yes, same /24 as the bar yes, moved by mDNS this session no, mac for wake yes",
            TvAddressFacts.of("192.168.1.20", "192.168.1.30", movedThisSession = false, macKnown = true),
        )
    }

    @Test
    fun `a tv on another subnet than the bar says so`() {
        assertEquals(
            "ipv4, private yes, same /24 as the bar no, moved by mDNS this session yes, mac for wake no",
            TvAddressFacts.of("192.168.2.20", "192.168.1.30", movedThisSession = true, macKnown = false),
        )
    }

    @Test
    fun `an ipv6 address is named, never compared`() {
        assertEquals(
            "ipv6, private yes, same /24 as the bar n/a, moved by mDNS this session no, mac for wake no",
            TvAddressFacts.of("fd00::20", "192.168.1.30", movedThisSession = false, macKnown = false),
        )
    }

    @Test
    fun `no address is ever printed`() {
        val facts = TvAddressFacts.of("10.0.0.7", "10.0.0.9", movedThisSession = false, macKnown = false)
        assertFalse("10.0.0" in facts)
        assertEquals("none stored", TvAddressFacts.of(null, "10.0.0.9", movedThisSession = false, macKnown = false))
    }
}
