package io.kud.tutti.protocol.share

import io.kud.tutti.protocol.cast.CastArtwork
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The picture Share sends with its sentence (Erwann, 2026-11-13: « mettre en embed la photo, la
 * cover art de ce qu'on est en train de regarder »): the artwork the card already shows, and only
 * when the card may show it, so the same gate (Cast artwork, Only free services) and the same
 * hosts ([CastArtwork.HOSTS]) decide. No artwork, a refused one, or none decoded within
 * [WAIT_MILLIS], and Share sends the sentence alone, as it always did.
 *
 * [candidates] lists what to try, best first: a larger form of the same picture on the same host
 * when its URL names one (a Cinemeta medium poster rather than its backdrop or small poster, a
 * YouTube `hqdefault` rather than a smaller thumbnail, a TMDB `w780` rather than a smaller width;
 * never a smaller one than the card's), then the card's own URL, which is usually already in
 * memory. The URLs are viewing data: never logged.
 */
object ShareArtwork {
    /** How long a tap on Share waits for a picture before it sends the text alone. */
    const val WAIT_MILLIS = 1_000L

    /** About this wide is decoded for a share: a chat preview, not a wallpaper. */
    const val WIDTH_PX = 720

    /** Under the app's cache directory; emptied before every share, so at most one file lives there. */
    const val DIR = "share"

    const val FILE_NAME = "tutti-share.jpg"

    const val MIME_TYPE = "image/jpeg"

    /** What to try for [cardUrl], best first; empty for no artwork or one the card may not show. */
    fun candidates(cardUrl: String?): List<String> {
        val url = cardUrl?.trim()?.takeIf { CastArtwork.isAllowed(it) } ?: return emptyList()
        return listOfNotNull(larger(url), url).distinct().filter { CastArtwork.isAllowed(it) }
    }

    /**
     * The picture to send, from [loads] started in [candidates]' order: the best one that decodes
     * within [waitMillis], else whichever has already finished when the wait runs out (the card's
     * own, usually from memory), else null and the text goes alone. A load still running is left
     * to finish, so its picture is in memory for the next share.
     */
    suspend fun <T : Any> pick(loads: List<Deferred<T?>>, waitMillis: Long = WAIT_MILLIS): T? =
        withTimeoutOrNull(waitMillis) { loads.firstNotNullOfOrNull { it.await() } }
            ?: loads.firstNotNullOfOrNull { load -> if (load.isCompleted && !load.isCancelled) load.await() else null }

    /** A larger form of [url] on the same host, or null when its shape names none. */
    internal fun larger(url: String): String? {
        METAHUB.matchEntire(url)?.let { m -> return "https://images.metahub.space/poster/medium/${m.groupValues[1]}/img" }
        YTIMG.matchEntire(url)?.let { m -> return "https://i.ytimg.com/vi/${m.groupValues[1]}/hqdefault.jpg" }
        TMDB.matchEntire(url)?.let { m -> return "https://image.tmdb.org/t/p/w780/${m.groupValues[1]}" }
        return null
    }

    // The poster (2:3) reads as cover art in a chat; the backdrop and the logo do not. Same id, same host.
    private val METAHUB = Regex("""^https://images\.metahub\.space/(?:poster/small|(?:background|logo)/(?:small|medium|large))/(tt\d{5,10})/img$""")

    // Every video has an `hqdefault` (480x360); `maxresdefault` only sometimes, so it is never
    // guessed, and a larger thumbnail the status already names is kept as it is.
    private val YTIMG = Regex("""^https://i\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/(?:default|mqdefault)\.(?:jpg|webp)(?:\?.*)?$""")

    private val TMDB = Regex("""^https://image\.tmdb\.org/t/p/(?:w92|w154|w185|w300|w342|w500)/([A-Za-z0-9_-]+\.(?:jpg|png|webp))$""")
}
