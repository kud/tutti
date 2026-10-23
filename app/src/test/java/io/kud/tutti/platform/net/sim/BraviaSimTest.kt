package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.OkHttpBraviaTransport
import io.kud.tutti.protocol.bravia.BraviaClient
import io.kud.tutti.protocol.bravia.BraviaError
import io.kud.tutti.protocol.bravia.BraviaResult
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.sim.FakeBraviaTv
import io.kud.tutti.protocol.sim.SimKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The real OkHttp transport and BRAVIA client against the simulated TV's REST ports on loopback. */
class BraviaSimTest {
    private val tv = FakeBraviaTv(psk = "1234").start()
    private val saved = CopyOnWriteArrayList<CertificatePin>()

    @After
    fun stop() = tv.close()

    private fun transport(pin: CertificatePin? = null, readTimeoutMs: Long = 2_000) =
        OkHttpBraviaTransport(HOST, pin, { saved += it }, tv.https.port, tv.http.port, ::loopbackOnly, readTimeoutMs)

    private fun client(transport: OkHttpBraviaTransport = transport(), psk: String = "1234") = BraviaClient(transport) { psk }

    private fun <T> BraviaResult<T>.ok(): T = (this as? BraviaResult.Ok)?.value ?: throw AssertionError("expected Ok, got $this")

    private fun BraviaResult<*>.error(): BraviaError = (this as? BraviaResult.Failure)?.error ?: throw AssertionError("expected a failure, got $this")

    @Test
    fun `discovers, polls and pins over HTTPS`() = runBlocking {
        val transport = transport()
        val client = client(transport)
        assertEquals(BraviaClient.SOURCE_GUIDE, client.discover().ok().source)
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
        assertEquals(12, client.volumeInformation().ok().single().volume)
        assertEquals("https", transport.scheme)
        assertEquals(listOf(CertificatePin.of(SimKeys.tv.certificate)), saved)
        assertTrue(tv.http.accepted.get() == 0)
    }

    @Test
    fun `a wrong PSK is unauthorised, by 403 or 401`() = runBlocking {
        assertEquals(BraviaError.Unauthorized, client(psk = "9999").powerStatus().error())
        tv.authFailureStatus = 401
        assertEquals(BraviaError.Unauthorized, client(psk = "9999").powerStatus().error())
        // Discovery needs no PSK, as on the TV.
        assertEquals(BraviaClient.SOURCE_GUIDE, client(psk = "9999").discover().ok().source)
    }

    @Test
    fun `a TV still booting refuses both ports, then answers over HTTPS`() = runBlocking {
        tv.sleep()
        val transport = transport()
        val client = client(transport)
        assertTrue(client.powerStatus().error() is BraviaError.Transport)
        assertNull("a refused boot must not settle on HTTP", transport.scheme)
        tv.wake()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
        assertEquals("https", transport.scheme)
    }

    @Test
    fun `polling survives the TV restarting between two polls`() = runBlocking {
        val client = client()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
        tv.sleep()
        tv.wake()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
    }

    @Test
    fun `polling survives the TV closing an idle connection`() = runBlocking {
        val client = client()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
        tv.https.dropConnections()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
    }

    @Test
    fun `an HTTP-only TV is reached over HTTP when no pin exists`() = runBlocking {
        tv.https.sleep()
        val transport = transport()
        assertEquals(PowerStatus.ACTIVE, client(transport).powerStatus().ok())
        assertEquals("http", transport.scheme)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a pinned TV is never reached over HTTP`() = runBlocking {
        tv.https.sleep()
        val transport = transport(pin = CertificatePin.of(SimKeys.tv.certificate))
        assertTrue(client(transport).powerStatus().error() is BraviaError.Transport)
        assertEquals(0, tv.http.accepted.get())
    }

    @Test
    fun `a slow reply past the read timeout fails, and the next one succeeds`() = runBlocking {
        val client = client(transport(readTimeoutMs = 400))
        tv.https.faults.replyDelayMillis = 900
        assertTrue(client.powerStatus().error() is BraviaError.Transport)
        tv.https.faults.reset()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
    }

    @Test
    fun `a TV that closes mid-request fails the call, not the next`() = runBlocking {
        val client = client()
        tv.https.faults.closeAfter = 1
        assertTrue(client.powerStatus().error() is BraviaError.Transport)
        tv.https.faults.reset()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
    }

    @Test
    fun `a changed certificate fails against the pin, never falls back, never re-pins`() = runBlocking {
        val client = client(transport())
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
        tv.https.changeCertificate(SimKeys.tvAfterReset)
        tv.https.dropConnections()
        assertTrue(client.powerStatus().error() is BraviaError.Transport)
        assertEquals(listOf(CertificatePin.of(SimKeys.tv.certificate)), saved)
        assertEquals(0, tv.http.accepted.get())
    }

    @Test
    fun `a method the model lacks is unsupported after discovery`() = runBlocking {
        tv.missing += "getVolumeInformation"
        val client = client()
        client.discover().ok()
        assertEquals(BraviaError.Unsupported("getVolumeInformation"), client.volumeInformation().error())
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
    }
}
