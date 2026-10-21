package io.kud.tutti.platform.net

import io.kud.tutti.protocol.bravia.BraviaTransport
import io.kud.tutti.protocol.bravia.HttpReply
import io.kud.tutti.protocol.net.CertificatePin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * BRAVIA REST over OkHttp, LAN only.
 *
 * HTTPS first, pinned trust-on-first-use: the first certificate seen is persisted through
 * [savePin] only after a request has succeeded, and every later request must match it.
 * Plain HTTP is used only when HTTPS is refused outright (port closed) AND no pin has ever
 * been recorded for this TV, and only towards a private address. Once a pin exists, HTTPS
 * is the only way in. Headers (they carry the PSK) are never logged: there is no logging
 * interceptor on this client.
 */
class OkHttpBraviaTransport(
    private val host: String,
    initialPin: CertificatePin?,
    private val savePin: suspend (CertificatePin) -> Unit,
) : BraviaTransport {
    @Volatile private var firstSeen: CertificatePin? = null
    @Volatile private var useHttp = false
    private val trust = PinningTrustManager(initialPin) { firstSeen = it }

    private val client: OkHttpClient by lazy {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust)
            // The TV's self-signed certificate never names its IP; identity is the pin, checked
            // again here so a session can never outlive a pin change.
            .hostnameVerifier { _, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                val pin = trust.expected ?: firstSeen
                leaf != null && pin != null && pin.matches(leaf)
            }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply =
        withContext(Dispatchers.IO) {
            requireLan(host)
            if (!useHttp) {
                try {
                    return@withContext execute("https", path, body, headers).also { persistFirstSeen() }
                } catch (e: ConnectException) {
                    // Port 443 closed. Fall back to HTTP only if this TV was never seen over HTTPS.
                    if (trust.expected != null) throw e
                    useHttp = true
                }
            }
            execute("http", path, body, headers)
        }

    private suspend fun persistFirstSeen() {
        val seen = firstSeen ?: return
        if (trust.expected == null) {
            trust.expected = seen
            savePin(seen)
        }
    }

    private fun execute(scheme: String, path: String, body: String, headers: Map<String, String>): HttpReply {
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val request = Request.Builder()
            .url("$scheme://${urlHost()}$path")
            .post(body.toRequestBody(contentType?.toMediaTypeOrNull()))
            .apply { headers.forEach { (name, value) -> if (!name.equals("Content-Type", true)) header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            return HttpReply(response.code, response.body.string())
        }
    }

    private fun urlHost(): String = if (host.contains(':')) "[$host]" else host
}
