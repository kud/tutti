package io.kud.tutti.protocol.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanAddressTest {
    @Test
    fun `private ranges are allowed`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.255", "169.254.3.4", "fe80::1", "[fd00::2]")
            .forEach { assertTrue(LanAddress.isPrivate(it), it) }
    }

    @Test
    fun `public addresses and hostnames are refused`() {
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "2001:db8::1", "bravia.local", "256.1.1.1", "1.2.3")
            .forEach { assertFalse(LanAddress.isPrivate(it), it) }
    }
}
