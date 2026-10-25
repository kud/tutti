package io.kud.tutti.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class PskRulesTest {
    @Test
    fun `eight to thirty-two printable ASCII characters`() {
        assertTrue(PskRules.valid("12345678"))
        assertTrue(PskRules.valid("A!b#c\$d%e^f&g*h(i)j_k+l=m~n?o<p>"))
        assertFalse(PskRules.valid("1234567"))
        assertFalse(PskRules.valid("x".repeat(33)))
        assertFalse("no spaces", PskRules.valid("1234 5678"))
        assertFalse("no accents", PskRules.valid("motdepassé"))
        assertFalse("no control characters", PskRules.valid("12345678\n"))
    }
}

class LoggingHandlerTest {
    @Test
    fun `a throwing coroutine is logged and the scope lives on`() = runBlocking {
        val logged = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + loggingHandler { logged += it })
        scope.launch { error("boom") }.join()
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("boom"))
        var ran = false
        scope.launch { ran = true }.join()
        assertTrue(ran)
    }
}
