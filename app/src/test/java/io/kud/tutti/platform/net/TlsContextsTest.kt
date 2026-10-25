package io.kud.tutti.platform.net

import io.kud.tutti.protocol.net.CertificatePin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TlsContextsTest {
    private val tvPin = CertificatePin("aa".repeat(32))
    private val me = CertificatePin("bb".repeat(32))
    private val key = TlsContexts.Key("192.0.2.10", 6466, tvPin, me)
    private var made = 0
    private fun make() = Any().also { made++ }

    @Test
    fun `a pinned device reuses its context`() {
        val contexts = TlsContexts<Any>()
        assertSame(contexts.get(key, ::make), contexts.get(key.copy(), ::make))
        assertEquals(1, made)
    }

    @Test
    fun `first use, with nothing pinned, is never cached`() {
        val contexts = TlsContexts<Any>()
        assertNotSame(contexts.get(null, ::make), contexts.get(null, ::make))
    }

    @Test
    fun `a new pin, identity or port never resumes the old session`() {
        val contexts = TlsContexts<Any>()
        val first = contexts.get(key, ::make)
        assertNotSame(first, contexts.get(key.copy(pin = CertificatePin("cc".repeat(32))), ::make))
        assertNotSame(first, contexts.get(key.copy(identity = CertificatePin("dd".repeat(32))), ::make))
        assertNotSame(first, contexts.get(key.copy(port = 6467), ::make))
    }

    @Test
    fun `it keeps only a few`() {
        val contexts = TlsContexts<Any>()
        val first = contexts.get(key, ::make)
        repeat(10) { contexts.get(key.copy(port = 7000 + it), ::make) }
        assertNotSame(first, contexts.get(key, ::make))
    }
}
