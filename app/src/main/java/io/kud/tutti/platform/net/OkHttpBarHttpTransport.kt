package io.kud.tutti.platform.net

import io.kud.tutti.protocol.theatre.BarHttpKey
import io.kud.tutti.protocol.theatre.BarHttpUnavailable
import io.kud.tutti.protocol.theatre.BarHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The bar's HTTP management plane (54545) over OkHttp: plain HTTP, so only ever to a private
 * LAN address, checked before every request. No headers beyond the content type, no cookies,
 * no redirects followed (a redirect could leave the LAN), and no logging interceptor: replies
 * can hold the AirPlay name. Reads only; the body comes from [io.kud.tutti.protocol.theatre.BarHttpCodec].
 */
class OkHttpBarHttpTransport internal constructor(
    private val host: String,
    private val port: Int,
    private val checkHost: (String) -> Unit,
    private val requireAccess: () -> Unit = {},
) : BarHttpTransport {
    constructor(host: String, requireAccess: () -> Unit) : this(host, BarHttpKey.PORT, ::requireLan, requireAccess)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // LAN only: never through a system or app proxy.
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    override suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        requireAccess()
        checkHost(host)
        val urlHost = if (host.contains(':')) "[$host]" else host
        val request = Request.Builder()
            .url("http://$urlHost:$port${BarHttpKey.PATH}")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BarHttpUnavailable(response.code)
            val declared = response.body.contentLength()
            if (declared > MAX_BYTES) throw IOException("reply too large")
            response.body.string().also { if (it.length > MAX_BYTES) throw IOException("reply too large") }
        }
    }

    private companion object {
        val JSON = "application/json; charset=UTF-8".toMediaType()
        const val MAX_BYTES = 64 * 1024
    }
}
