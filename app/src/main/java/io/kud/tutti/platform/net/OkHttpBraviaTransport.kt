package io.kud.tutti.platform.net

import io.kud.tutti.protocol.apps.AppIcons
import io.kud.tutti.protocol.apps.IconAttempt
import io.kud.tutti.protocol.apps.IconOutcome
import io.kud.tutti.protocol.bravia.BraviaClient
import io.kud.tutti.protocol.bravia.BraviaTransport
import io.kud.tutti.protocol.bravia.HttpReply
import io.kud.tutti.protocol.bravia.IpRegistration
import io.kud.tutti.protocol.net.CertificatePinMismatch
import io.kud.tutti.protocol.net.LanAddress
import io.kud.tutti.protocol.net.PublicKeyPin
import io.kud.tutti.protocol.net.ServerIdentityPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/**
 * BRAVIA REST over OkHttp, LAN only.
 *
 * Authenticated means carrying any of [BraviaClient.CREDENTIAL_HEADERS]: the PSK, the IP-control
 * cookie, or the code from the TV during `actRegister`. All three follow the same rules below,
 * written for the PSK.
 *
 * HTTPS first, pinned trust-on-first-use: the first server public key seen is persisted through
 * [savePin] only after an authenticated request has had a 2xx answer. Later certificates may be
 * reissued, but their public key must match; a changed key is a [CertificatePinMismatch]. Plain
 * HTTP is used only when HTTPS is refused outright (port closed), no pin has ever been
 * recorded for this TV, an unauthenticated request (discovery) has answered over HTTP, and
 * only towards a private address. The PSK never goes out on that first HTTP request, and
 * every unauthenticated request tries HTTPS again first, so a TV caught while booting does
 * not stay on HTTP. Once a pin exists, HTTPS is the only way in. Headers (they carry the
 * PSK) are never logged: there is no logging interceptor on this client.
 *
 * The internal constructor is the test seam: the device simulator listens on loopback ports.
 */
class OkHttpBraviaTransport internal constructor(
    private val host: String,
    initialPin: ServerIdentityPin?,
    private val savePin: suspend (PublicKeyPin) -> Unit,
    private val httpsPort: Int,
    private val httpPort: Int,
    private val checkHost: (String) -> Unit,
    private val readTimeoutMs: Long,
) : BraviaTransport {
    constructor(host: String, initialPin: ServerIdentityPin?, savePin: suspend (PublicKeyPin) -> Unit) :
        this(host, initialPin, savePin, HTTPS_PORT, HTTP_PORT, ::requireLan, READ_TIMEOUT_MS)

    @Volatile private var observedKeyPin: PublicKeyPin? = null
    @Volatile private var useHttp = false
    private val trust = PinningTrustManager(initialPin) { observedKeyPin = PublicKeyPin.of(it) }

    private val client: OkHttpClient by lazy {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust)
            // The TV's self-signed certificate never names its IP; the pinned identity is checked
            // again here so a session can never outlive a key change.
            .hostnameVerifier { _, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                val pin = trust.expected ?: observedKeyPin
                leaf != null && pin != null && pin.matches(leaf)
            }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            // The TV is on the LAN: a system or app proxy must never see the PSK.
            .proxy(Proxy.NO_PROXY)
            // Never follow a redirect: OkHttp keeps custom headers on one, so the PSK could
            // reach a host that is not the TV. A 3xx is an answer, reported as such.
            .followRedirects(false)
            .followSslRedirects(false)
            .eventListener(FreshConnectionListener)
            .build()
    }

    override val scheme: String? get() = if (useHttp) "http" else if (observedKeyPin != null || trust.expected != null) "https" else null

    /**
     * Public key seen on HTTPS in this transport's lifetime.
     * Read-only: callers may promote it to a stored pin only after their own trust ceremony.
     */
    internal val observedPin: PublicKeyPin? get() = observedKeyPin

    override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply =
        withContext(Dispatchers.IO) {
            checkHost(host)
            val authenticated = BraviaClient.carriesCredential(headers)
            if (useHttp && authenticated) return@withContext execute("http", path, body, headers)
            try {
                execute("https", path, body, headers).also { reply ->
                    useHttp = false
                    if (authenticated && reply.status in 200..299) persistObservedPin()
                }
            } catch (e: ConnectException) {
                // Port 443 closed. HTTP only for a TV never pinned, and only on a request without
                // the key: it is the probe, and HTTP sticks only once it has answered.
                if (trust.expected != null || authenticated) throw e
                execute("http", path, body, headers).also { useHttp = true }
            } catch (e: SSLException) {
                if (generateSequence<Throwable>(e) { it.cause }.take(8).any { it is PinMismatchException }) throw CertificatePinMismatch(e)
                throw e
            }
        }

    /** One icon fetch: the image bytes, or why there are none. */
    sealed interface IconFetch {
        class Image(val bytes: ByteArray) : IconFetch
        data class Failed(val attempt: IconAttempt) : IconFetch
    }

    /**
     * One app icon, from this TV only ([AppIcons.plan] aims every icon URL at it). HTTPS goes
     * through the same pinned client (no pin yet, no icon); HTTP only to a private address. An
     * HTTP icon on a pinned TV that refuses port 80, or answers there with a redirect, is asked
     * again over HTTPS at the same path. No PSK (or [cookie], which wins when both are given) goes with the first request; a
     * 401 or 403 is asked once more with it, since it is this TV's own key going to this TV. A stale pooled connection is retried once, as
     * for [post]. Anything that is not an image, or over [maxBytes], is refused.
     */
    suspend fun fetchIcon(url: String, psk: String? = null, maxBytes: Int = MAX_ICON_BYTES, cookie: String? = null): IconFetch = withContext(Dispatchers.IO) {
        val credential: Pair<String, String>? = when {
            cookie != null && IpRegistration.validCookieValue(cookie) -> BraviaClient.COOKIE to IpRegistration.cookieHeader(cookie)
            !psk.isNullOrEmpty() -> BraviaClient.PSK_HEADER to psk
            else -> null
        }
        fun failed(outcome: IconOutcome, detail: String? = null) = IconFetch.Failed(IconAttempt(outcome, detail))
        val parsed = url.toHttpUrlOrNull() ?: return@withContext failed(IconOutcome.MALFORMED)
        if (parsed.host.removePrefix("[").removeSuffix("]") != host.removePrefix("[").removeSuffix("]")) {
            return@withContext failed(IconOutcome.INTERNET, "not the tv")
        }
        runCatching { checkHost(parsed.host) }.onFailure { return@withContext failed(IconOutcome.INTERNET, "not private") }
        val pinned = trust.expected != null || observedKeyPin != null
        if (parsed.scheme == "https" && !pinned) return@withContext failed(IconOutcome.NO_PIN)
        var target = parsed
        var note: String? = null
        // The key only ever rides HTTPS to the pinned TV (the hostname verifier checks the pin).
        fun get(withPsk: Boolean): IconFetch = execute(
            Request.Builder().url(target).get()
                .apply { if (withPsk && credential != null && target.scheme == "https") header(credential.first, credential.second) },
        ) { response ->
            when {
                !response.isSuccessful -> failed(IconOutcome.HTTP_STATUS, "${response.code}")
                response.body.contentLength() > maxBytes -> failed(IconOutcome.TOO_BIG)
                else -> {
                    val bytes = response.body.bytes()
                    when {
                        bytes.size > maxBytes -> failed(IconOutcome.TOO_BIG)
                        !AppIcons.looksLikeImage(bytes) -> failed(IconOutcome.NOT_IMAGE, response.body.contentType()?.let { "${it.type}/${it.subtype}" })
                        else -> IconFetch.Image(bytes)
                    }
                }
            }
        }
        fun attempt(): IconFetch = try {
            get(withPsk = false).let { first ->
                val code = ((first as? IconFetch.Failed)?.attempt?.detail)
                when {
                    first !is IconFetch.Failed || (code != "401" && code != "403") || credential == null -> first
                    target.scheme == "https" -> get(withPsk = true)
                    // Refused over HTTP: the key goes only over HTTPS, so only to a pinned TV.
                    pinned -> {
                        target = target.newBuilder().scheme("https").port(httpsPort).build()
                        note = "http $code, https with key"
                        get(withPsk = true)
                    }
                    else -> first
                }
            }
        } catch (e: ConnectException) {
            failed(IconOutcome.REFUSED)
        } catch (e: IOException) {
            failed(IconOutcome.NETWORK, e.javaClass.simpleName)
        }
        var result = attempt()
        // Port 80 shut, or open only to send everything to HTTPS: ask the pinned TV itself over
        // HTTPS at the same path. The redirect's Location is never read, so it can't aim us elsewhere.
        val upgrade = (result as? IconFetch.Failed)?.attempt?.let { failure ->
            when {
                failure.outcome == IconOutcome.REFUSED -> "http refused, https"
                failure.outcome == IconOutcome.HTTP_STATUS && failure.detail?.toIntOrNull() in 300..399 -> "http ${failure.detail}, https"
                else -> null
            }
        }
        if (upgrade != null && parsed.scheme == "http" && pinned) {
            target = parsed.newBuilder().scheme("https").port(httpsPort).build()
            note = upgrade
            result = attempt()
        }
        if (result is IconFetch.Failed && note != null) failed(result.attempt.outcome, listOfNotNull(note, result.attempt.detail).joinToString(": ")) else result
    }

    private suspend fun persistObservedPin() {
        val seen = observedKeyPin ?: return
        // A matching legacy full-certificate pin is promoted after one authenticated success.
        // A mismatching legacy pin never reaches here: the TLS handshake has already failed.
        if (trust.expected != seen) {
            trust.expected = seen
            savePin(seen)
        }
    }

    /**
     * One POST, retried once on a fresh connection when it failed on a pooled one. The TV closes
     * idle keep-alive connections, and OkHttp only probes an idle connection before reusing it
     * after 10 s idle, so a call within 10 s of the last one (the poll's own period) was written
     * to a dead socket and failed with "unexpected end of stream". Timeouts are never retried,
     * and neither is a failure on a connection opened for this call: those are real answers.
     */
    private fun execute(scheme: String, path: String, body: String, headers: Map<String, String>): HttpReply {
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val request = Request.Builder()
            .url("$scheme://${urlHost()}:${if (scheme == "https") httpsPort else httpPort}$path")
            .post(body.toRequestBody(contentType?.toMediaTypeOrNull()))
            .apply { headers.forEach { (name, value) -> if (!name.equals("Content-Type", true)) header(name, value) } }
        return execute(request) { response -> HttpReply(response.code, response.body.string(), response.headers("Set-Cookie")) }
    }

    private fun <T> execute(request: Request.Builder, read: (Response) -> T): T {
        val attempt = Attempt()
        return try {
            executeOnce(request, attempt, read)
        } catch (e: IOException) {
            if (attempt.freshConnection || e is InterruptedIOException || e is SSLException) throw e
            client.connectionPool.evictAll()
            executeOnce(request, Attempt(), read)
        }
    }

    private fun <T> executeOnce(request: Request.Builder, attempt: Attempt, read: (Response) -> T): T =
        client.newCall(request.tag(Attempt::class.java, attempt).build()).execute().use(read)

    /** Whether a call opened its own connection rather than reusing a pooled one. */
    private class Attempt {
        @Volatile var freshConnection = false
    }

    private object FreshConnectionListener : EventListener() {
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            call.request().tag(Attempt::class.java)?.freshConnection = true
        }
    }

    private fun urlHost(): String = if (host.contains(':')) "[$host]" else host

    private companion object {
        const val MAX_ICON_BYTES = 512 * 1024
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80
        const val READ_TIMEOUT_MS = 8_000L
    }
}
