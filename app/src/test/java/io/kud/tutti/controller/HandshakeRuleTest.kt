package io.kud.tutti.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

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
}
