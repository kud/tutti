package io.kud.tutti.protocol.net

import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectPaceTest {
    @Test
    fun `a drop after a healthy session is retried at once`() {
        assertEquals(0L, ReconnectPace.pause(sessionMs = 60_000L, backoffMs = 1_000L))
        assertEquals(0L, ReconnectPace.pause(sessionMs = ReconnectPace.HEALTHY_MS, backoffMs = 8_000L))
    }

    @Test
    fun `a session that drops at once or a failed attempt waits its backoff`() {
        assertEquals(1_000L, ReconnectPace.pause(sessionMs = ReconnectPace.HEALTHY_MS - 1, backoffMs = 1_000L))
        assertEquals(4_000L, ReconnectPace.pause(sessionMs = null, backoffMs = 4_000L))
    }
}
