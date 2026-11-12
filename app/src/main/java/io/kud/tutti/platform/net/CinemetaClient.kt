package io.kud.tutti.platform.net

import android.util.LruCache
import io.kud.tutti.protocol.details.Cinemeta
import io.kud.tutti.protocol.details.CinemetaMeta
import io.kud.tutti.protocol.details.CinemetaParser
import io.kud.tutti.protocol.details.DetailsRequest
import io.kud.tutti.protocol.details.TitleDetails
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks Cinemeta for the title Cast reports (`:protocol` `details/`), only when "Title details" is on
 * and "Only free services" is off: HTTPS to [Cinemeta.HOST] only, the system trust store, no
 * redirects, no cookies, no HTTP cache, no auth header, at most [Cinemeta.MAX_BYTES]. Parsed metas
 * live in a small memory LRU keyed by id and go with the process; nothing reaches the disk. Nothing
 * here logs: the id names what is playing, and a failure just means no Details section.
 */
internal object CinemetaClient {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val metas = LruCache<String, CinemetaMeta>(8)

    /** Ids that found nothing this process: not asked again until it restarts. */
    private val failed = LruCache<String, Boolean>(32)

    fun cached(request: DetailsRequest): TitleDetails? = metas.get(request.id)?.let { TitleDetails.of(it, request.episode) }

    suspend fun load(request: DetailsRequest): TitleDetails? {
        metas.get(request.id)?.let { return TitleDetails.of(it, request.episode) }
        if (failed.get(request.id) != null) return null
        val meta = withContext(Dispatchers.IO) {
            request.types.firstNotNullOfOrNull { type -> runCatching { fetch(type, request.id) }.getOrNull() }
        }
        if (meta == null) {
            failed.put(request.id, true)
            return null
        }
        metas.put(request.id, meta)
        return TitleDetails.of(meta, request.episode)
    }

    private fun fetch(type: String, id: String): CinemetaMeta? {
        val request = Request.Builder().url(Cinemeta.url(type, id)).header("Accept", "application/json").build()
        if (request.url.host != Cinemeta.HOST || !request.url.isHttps) return null
        val text = http.newCall(request).execute().use { response ->
            val body = response.body
            if (response.code != 200) return null
            if (body.contentLength() > Cinemeta.MAX_BYTES) return null
            val source = body.source()
            source.request(Cinemeta.MAX_BYTES + 1L)
            if (source.buffer.size > Cinemeta.MAX_BYTES) return null
            source.buffer.readUtf8()
        }
        return CinemetaParser.parse(text, id)
    }
}
