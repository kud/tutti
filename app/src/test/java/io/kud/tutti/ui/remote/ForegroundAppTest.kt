package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.protocol.bravia.PowerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppTest {
    @Test
    fun `a catalogued package shows by name`() {
        assertEquals("Netflix", foregroundAppName("com.netflix.ninja", Link.ONLINE, PowerStatus.UNKNOWN))
    }

    @Test
    fun `an unknown package shows nothing, never the raw id`() {
        assertNull(foregroundAppName("com.example.launcher", Link.ONLINE, PowerStatus.ACTIVE))
        assertNull(foregroundAppName(null, Link.ONLINE, PowerStatus.ACTIVE))
    }

    @Test
    fun `a stale package says nothing once the tv is away or asleep`() {
        assertNull(foregroundAppName("com.netflix.ninja", Link.UNREACHABLE, PowerStatus.ACTIVE))
        assertNull(foregroundAppName("com.netflix.ninja", Link.ONLINE, PowerStatus.STANDBY))
    }
}
