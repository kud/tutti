package io.kud.tutti.protocol.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactTest {
    @Test
    fun `scrubs addresses, ids and known values`() {
        val input = "tv 192.168.1.20 / fe80::1c2d:3e4f:5a6b:7c8d%wlan0 / [fd00::12] mac AA:BB:CC:DD:EE:FF " +
            "id 123e4567-e89b-12d3-a456-426614174000 pin 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08 " +
            "token dGhpcyBpcyBhIHZlcnkgbG9uZyB0b2tlbiB2YWx1ZQ== psk Hunter22 serial 4012345"
        val out = Redact.text(input, listOf("hunter22", "4012345"))
        for (leak in listOf("192.168", "fe80", "fd00", "AA:BB", "123e4567", "9f86d0", "dGhpcy", "Hunter22", "4012345")) {
            assertFalse(leak in out, "$leak leaked: $out")
        }
    }

    @Test
    fun `leaves the useful words alone`() {
        val input = "OK      rest.sound  212 ms  system.getSoundSettings@1.1 at 20:14 com.netflix.ninja K-65XR80 v2.3"
        assertEquals(input, Redact.text(input, listOf("ab")))
    }

    @Test
    fun `the detector still detects`() {
        // A scrubber whose passing state is "found nothing" needs a probe that it still matches.
        assertTrue("<ip>" in Redact.text("10.0.0.1"))
        assertTrue("<mac>" in Redact.text("00-11-22-33-44-55"))
    }
}
