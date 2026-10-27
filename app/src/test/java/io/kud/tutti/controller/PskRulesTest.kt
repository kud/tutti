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

    private fun refused(was: Boolean, auth: PskRules.Auth, psk: Boolean = true, cookie: Boolean = false) =
        PskRules.refused(was, hasPsk = psk, hasCookie = cookie, auth = auth)

    @Test
    fun `a refusal on an authenticated call marks the key refused`() {
        assertTrue(refused(false, PskRules.Auth.REFUSED))
    }

    @Test
    fun `refused stays refused until an authenticated call succeeds`() {
        assertTrue("TV off, nothing asked", refused(true, PskRules.Auth.NOT_ASKED))
        assertTrue("a timeout says nothing about the key", refused(true, PskRules.Auth.OTHER))
        assertFalse(refused(true, PskRules.Auth.ACCEPTED))
        assertFalse("never refused without a refusal", refused(false, PskRules.Auth.OTHER))
    }

    @Test
    fun `only applies with a PSK and no code cookie`() {
        assertFalse("no PSK", refused(true, PskRules.Auth.REFUSED, psk = false))
        assertFalse("the cookie is what was sent", refused(true, PskRules.Auth.REFUSED, cookie = true))
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
