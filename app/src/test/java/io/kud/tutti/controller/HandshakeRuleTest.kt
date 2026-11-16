package io.kud.tutti.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

class HandshakeRuleTest {
    private val refused = SSLHandshakeException("Received fatal alert: certificate_unknown")
    private val reset = SSLException("Connection reset", SocketException("Connection reset"))

    @Test
    fun `only refused certificates count, and twice in a row means pair again`() {
        var failures = HandshakeRule.next(0, refused)
        assertFalse(HandshakeRule.needsPairing(failures))
        failures = HandshakeRule.next(failures, refused)
        assertTrue(HandshakeRule.needsPairing(failures))
    }

    @Test
    fun `a handshake cut by the network clears the count`() {
        assertFalse(HandshakeRule.isCertificateAlert(reset))
        assertEquals(0, HandshakeRule.next(1, reset))
        assertEquals(0, HandshakeRule.next(1, SocketException("timeout")))
    }

    /** What dev.27 read on 2026-10-27: TLS 1.3 refused the certificate on the first read, not in the handshake. */
    private val refusedOnRead = SSLProtocolException(
        "Read error: ssl=0x1: Failure in SSL library, usually a protocol error\n" +
            "error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN",
    )

    @Test
    fun `a certificate refused before ready counts like one refused in the handshake`() {
        var failures = HandshakeRule.afterNotReady(0, refusedOnRead)
        assertEquals(1, failures)
        failures = HandshakeRule.afterNotReady(failures, refusedOnRead)
        assertTrue(HandshakeRule.needsPairing(failures))
        // A handshake refusal then a read refusal is still two in a row.
        assertTrue(HandshakeRule.needsPairing(HandshakeRule.afterNotReady(HandshakeRule.next(0, refused), refusedOnRead)))
    }

    @Test
    fun `a quiet session leaves the count, a reset before ready clears it`() {
        assertEquals(1, HandshakeRule.afterNotReady(1, null))
        assertEquals(0, HandshakeRule.afterNotReady(1, reset))
    }
}
