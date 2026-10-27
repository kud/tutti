package io.kud.tutti.protocol.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconProbeTest {
    @Test
    fun `an answer keeps the server, the bare type, the length and only the auth scheme`() {
        assertEquals(
            "http 403 server=Sony UPnP/1.0 type=text/html length=0 auth=Digest",
            IconProbe.response("http", 403, "Sony UPnP/1.0", "text/html; charset=utf-8", "0", "Digest realm=\"tv\", nonce=\"abc123\""),
        )
    }

    @Test
    fun `missing or odd headers read as a dash, and nothing past a token survives`() {
        assertEquals("https 401 server=- type=- length=- auth=-", IconProbe.response("https", 401, null, "", "12abc", "  "))
        val long = IconProbe.response("http", 403, "x".repeat(200) + "\"<>@", null, null, "Basic")
        assertEquals("http 403 server=${"x".repeat(40)} type=- length=- auth=Basic", long)
    }

    @Test
    fun `an unanswered request names its exception and phase`() {
        assertEquals("https SocketTimeoutException at read headers", IconProbe.failure("https", "SocketTimeoutException", "read headers"))
        assertEquals("http ConnectException at -", IconProbe.failure("http", "ConnectException", null))
    }

    @Test
    fun `the book keeps the first failing fetch only, and reports it last`() {
        val book = IconBook()
            .record("a.app", "http://tv/DIAL/icon/a.png", "http /DIAL/icon/… host=tv", IconAttempt(IconOutcome.NO_PIN), 0)
            .record("b.app", "http://tv/DIAL/icon/b.png", "http /DIAL/icon/… host=tv", IconAttempt(IconOutcome.HTTP_STATUS, "403", probe = "http 403 server=- type=- length=- auth=-"), 0)
            .record("c.app", "http://tv/DIAL/icon/c.png", "http /DIAL/icon/… host=tv", IconAttempt(IconOutcome.NETWORK, "x", probe = "https X at tls"), 0)
        assertEquals("b.app: http 403 server=- type=- length=- auth=-", book.probe)
        assertEquals("first failing fetch: b.app: http 403 server=- type=- length=- auth=-", book.report().last())
        assertNull(IconBook().record("a.app", null, "none", IconAttempt(IconOutcome.BLANK), 0).probe)
    }
}
