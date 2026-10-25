package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.OkHttpBraviaTransport
import io.kud.tutti.protocol.apps.IconAttempt
import io.kud.tutti.protocol.apps.IconOutcome
import io.kud.tutti.protocol.bravia.AuthCookie
import io.kud.tutti.protocol.bravia.BraviaClient
import io.kud.tutti.protocol.bravia.BraviaError
import io.kud.tutti.protocol.bravia.BraviaResult
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.bravia.Registration
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
    fun `an HTTP-only TV is reached over HTTP only once discovery answered there without the key`() = runBlocking {
        tv.https.sleep()
        val transport = transport()
        val client = client(transport)
        // An authenticated call never falls back on its own: the key would go out on a guess.
        assertTrue(client.powerStatus().error() is BraviaError.Transport)
        assertEquals(0, tv.http.pskSeen.get())
        client.discover().ok()
        assertEquals(0, tv.http.pskSeen.get())
        assertEquals("http", transport.scheme)
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `HTTP does not stick, the next discovery tries HTTPS again`() = runBlocking {
        tv.https.sleep()
        val transport = transport()
        val client = client(transport)
        client.discover().ok()
        assertEquals("http", transport.scheme)
        tv.https.wake()
        client.discover().ok()
        assertEquals("https", transport.scheme)
        val before = tv.http.pskSeen.get()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
        assertEquals(before, tv.http.pskSeen.get())
    }

    @Test
    fun `discovery never carries the key`() = runBlocking {
        client().discover().ok()
        assertEquals(0, tv.https.pskSeen.get())
        assertEquals(0, tv.http.pskSeen.get())
    }

    @Test
    fun `the pin is kept only after an authenticated call succeeds`() = runBlocking {
        val transport = transport()
        client(transport, psk = "9999").discover().ok()
        assertTrue("an unauthenticated answer pins nothing", saved.isEmpty())
        assertEquals(BraviaError.Unauthorized, client(transport, psk = "9999").powerStatus().error())
        assertTrue("a refused key pins nothing", saved.isEmpty())
        client(transport).powerStatus().ok()
        assertEquals(listOf(CertificatePin.of(SimKeys.tv.certificate)), saved)
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
        assertEquals(BraviaError.PinMismatch, client.powerStatus().error())
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

    // Sony's "Normal" authentication: the code on the TV, then a cookie. Invented code and cookies.
    private val clientId = "tutti:00000000-0000-4000-8000-000000000001"

    private fun cookieClient(transport: OkHttpBraviaTransport, cookie: () -> String?) = BraviaClient(transport, cookie = cookie) { null }

    private fun registered(result: BraviaResult<Registration>): AuthCookie =
        ((result as? BraviaResult.Ok)?.value as? Registration.Registered)?.cookie ?: throw AssertionError("expected a registration, got $result")

    @Test
    fun `the code on the TV gets a cookie over HTTPS, which then carries every call`() = runBlocking {
        tv.code = "4821"
        val transport = transport()
        val api = cookieClient(transport) { null }
        assertEquals(BraviaResult.Ok(Registration.CodeShown), api.requestCode(clientId))
        assertEquals(1, tv.codesShown.get())
        assertTrue("asking for the code pins nothing", saved.isEmpty())
        val cookie = registered(api.submitCode(clientId, "4821"))
        assertEquals(listOf(CertificatePin.of(SimKeys.tv.certificate)), saved)
        val withCookie = cookieClient(transport) { cookie.value }
        assertEquals(PowerStatus.ACTIVE, withCookie.powerStatus().ok())
        assertEquals("https", transport.scheme)
        assertEquals(0, tv.http.accepted.get())
    }

    @Test
    fun `a wrong code is refused and pins nothing`() = runBlocking {
        tv.code = "4821"
        val api = cookieClient(transport()) { null }
        api.requestCode(clientId)
        assertEquals(BraviaResult.Ok(Registration.Refused), api.submitCode(clientId, "1111"))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a cookie renews itself, and a forgotten one asks for a new code`() = runBlocking {
        tv.code = "4821"
        val transport = transport()
        val api = cookieClient(transport) { null }
        val first = registered(api.submitCode(clientId, "4821"))
        val second = registered(api.renew(first))
        assertTrue(second.value != first.value)
        tv.cookies.clear()
        assertEquals(BraviaResult.Ok(Registration.Refused), api.renew(second))
        assertEquals(BraviaError.Unauthorized, cookieClient(transport) { second.value }.powerStatus().error())
    }

    @Test
    fun `on an HTTP-only TV the code goes over HTTP only after the probe, and only to the LAN`() = runBlocking {
        tv.code = "4821"
        tv.https.sleep()
        val transport = transport()
        val api = cookieClient(transport) { null }
        // Straight to the code: a credential never falls back to HTTP on its own.
        assertTrue((api.submitCode(clientId, "4821") as BraviaResult.Failure).error is BraviaError.Transport)
        assertEquals(0, tv.http.credentialSeen.get())
        assertEquals(BraviaResult.Ok(Registration.CodeShown), api.requestCode(clientId))
        assertEquals("http", transport.scheme)
        val cookie = registered(api.submitCode(clientId, "4821"))
        assertEquals(PowerStatus.ACTIVE, cookieClient(transport) { cookie.value }.powerStatus().ok())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a pinned TV never sees the cookie over HTTP`() = runBlocking {
        tv.code = "4821"
        val pinned = transport()
        val cookie = registered(cookieClient(pinned) { null }.submitCode(clientId, "4821"))
        tv.https.sleep()
        val later = transport(pin = saved.single())
        assertTrue(cookieClient(later) { cookie.value }.powerStatus().error() is BraviaError.Transport)
        assertEquals(0, tv.http.accepted.get())
    }

    @Test
    fun `icons ride the cookie to the pinned TV only`() = runBlocking {
        tv.code = "4821"
        tv.filesNeedPsk = true
        tv.files["/DIAL/icon/a.png"] = png
        val transport = transport()
        val cookie = registered(cookieClient(transport) { null }.submitCode(clientId, "4821"))
        assertTrue(transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/a.png", cookie = cookie.value) is OkHttpBraviaTransport.IconFetch.Image)
        assertEquals(0, tv.http.credentialSeen.get())
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)

    private suspend fun pinnedTransport(): OkHttpBraviaTransport = transport().also { client(it).powerStatus().ok() }

    private fun OkHttpBraviaTransport.IconFetch.failure(): IconAttempt =
        (this as? OkHttpBraviaTransport.IconFetch.Failed)?.attempt ?: throw AssertionError("expected a failure, got $this")

    @Test
    fun `an icon comes from the pinned TV over HTTPS, and an error page is no icon`() = runBlocking {
        tv.files["/DIAL/icon/a.png"] = png
        val transport = pinnedTransport()
        val got = transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/a.png")
        assertTrue(got is OkHttpBraviaTransport.IconFetch.Image && got.bytes.contentEquals(png))
        assertEquals(IconAttempt(IconOutcome.HTTP_STATUS, "404"), transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/b.png").failure())
        tv.files["/DIAL/icon/c.png"] = "<html>oops</html>".encodeToByteArray()
        assertEquals(IconOutcome.NOT_IMAGE, transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/c.png").failure().outcome)
        assertEquals(IconOutcome.TOO_BIG, transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/a.png", maxBytes = 4).failure().outcome)
    }

    @Test
    fun `an HTTP icon on a pinned TV with port 80 shut is asked over HTTPS`() = runBlocking {
        tv.files["/DIAL/icon/a.png"] = png
        val transport = pinnedTransport()
        tv.http.sleep()
        val got = transport.fetchIcon("http://$HOST:${tv.http.port}/DIAL/icon/a.png")
        assertTrue("$got", got is OkHttpBraviaTransport.IconFetch.Image)
        assertEquals(0, tv.http.accepted.get())
    }

    @Test
    fun `HTTPS icons wait for a pin, and other hosts are refused`() = runBlocking {
        tv.files["/DIAL/icon/a.png"] = png
        val transport = transport()
        assertEquals(IconOutcome.NO_PIN, transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/a.png").failure().outcome)
        assertEquals(IconOutcome.INTERNET, transport.fetchIcon("http://192.0.2.1/DIAL/icon/a.png").failure().outcome)
        assertTrue(tv.gets.isEmpty())
    }

    @Test
    fun `an icon refused without the key is asked once more with it`() = runBlocking {
        tv.files["/DIAL/icon/a.png"] = png
        tv.filesNeedPsk = true
        val transport = pinnedTransport()
        assertEquals(IconAttempt(IconOutcome.HTTP_STATUS, "403"), transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/a.png").failure())
        assertTrue(transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/a.png", psk = "1234") is OkHttpBraviaTransport.IconFetch.Image)
        assertEquals(3, tv.gets.size)
    }

    @Test
    fun `an icon after the TV closed an idle connection is retried on a fresh one`() = runBlocking {
        tv.files["/DIAL/icon/a.png"] = png
        val transport = pinnedTransport()
        tv.https.dropConnections()
        assertTrue(transport.fetchIcon("https://$HOST:${tv.https.port}/DIAL/icon/a.png") is OkHttpBraviaTransport.IconFetch.Image)
    }

    @Test
    fun `an icon refused over HTTP gets the key over HTTPS, and only from a pinned TV`() = runBlocking {
        tv.files["/DIAL/icon/a.png"] = png
        tv.filesNeedPsk = true
        val unpinned = transport()
        assertEquals(IconOutcome.HTTP_STATUS, unpinned.fetchIcon("http://$HOST:${tv.http.port}/DIAL/icon/a.png", psk = "1234").failure().outcome)
        val transport = pinnedTransport()
        assertTrue(transport.fetchIcon("http://$HOST:${tv.http.port}/DIAL/icon/a.png", psk = "1234") is OkHttpBraviaTransport.IconFetch.Image)
        assertEquals("the key never goes over HTTP", 0, tv.http.pskSeen.get())
    }
}
