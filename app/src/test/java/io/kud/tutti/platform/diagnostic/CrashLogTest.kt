package io.kud.tutti.platform.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLogTest {
    @Test
    fun `a trace that cannot be scrubbed is never shown raw`() {
        assertEquals(CrashLog.UNSCRUBBED, CrashLog.scrubbed("secret 192.168.1.20") { throw IllegalStateException("redact broke") })
        assertEquals("ok", CrashLog.scrubbed("raw") { "ok" })
    }
}
