package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.CastStatusParser.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URI

/**
 * The one exception to "images come only from the TV" (Erwann, 2026-10-28: « Moi je suis chaud
 * d'afficher la thumbnail »): the artwork a Cast media status names in `metadata.images[].url`,
 * fetched only over HTTPS, on the default port, from [HOSTS], with no cookies or credentials, and
 * at most [MAX_BYTES]. Anything else in that list is ignored. The URL carries what is playing (a
 * YouTube video id), so it is viewing data like the title: never logged, never stored on disk.
 */
object CastArtwork {
    /**
     * - `i.ytimg.com`: YouTube's video thumbnails, which YouTube's receiver names in its status.
     * - `lh3.googleusercontent.com`: Google's image CDN, where YouTube Music's album and track art
     *   lives (from public client libraries, not yet seen in a status from the BRAVIA).
     * - `image.tmdb.org`: Nuvio's posters and backdrops once TMDB enrichment has run. Nuvio's media
     *   session names the poster, else the backdrop, as its artwork URI (tapframe/NuvioTV 8eb7315,
     *   `PlayerMediaSessionMetadata.kt`), and its TMDB images are `https://image.tmdb.org/t/p/...`.
     * - `images.metahub.space`: Nuvio's posters and backdrops from its default addon, Cinemeta
     *   (`v3-cinemeta.strem.io`), whose meta names `images.metahub.space/poster/small/<imdb id>/img`
     *   and `.../background/medium/<imdb id>/img` (fetched 2026-11-11: a 200 with no redirect, the
     *   poster about 31 KiB; a medium background can pass [MAX_BYTES] and then stays the icon).
     * Add a host only with the app that needs it, and name it in docs/privacy.mdx.
     */
    val HOSTS = setOf("i.ytimg.com", "lh3.googleusercontent.com", "image.tmdb.org", "images.metahub.space")

    /** A thumbnail is tens of KiB; anything past this is not one. */
    const val MAX_BYTES = 512 * 1024

    /** Longer than any real thumbnail URL; a guard against a status stuffed with junk. */
    const val MAX_URL_LENGTH = 2048

    fun isAllowed(url: String?): Boolean {
        if (url == null || url.length > MAX_URL_LENGTH) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            host in HOSTS
    }

    /**
     * The picture of what is playing, for any tile that leads with one (Erwann, 2026-11-06: the
     * content's own artwork wins over the app's icon or banner, which stays the fallback). Cast is
     * the only plane tutti receives that names one: Remote v2 sends a package and REST a channel or
     * input, neither with an image. Re-checked here so a caller can't widen what the reducer kept.
     */
    fun of(playing: CastNowPlaying?): String? = playing?.imageUrl?.takeIf { isAllowed(it) }

    /** The first allowed `images[].url` of a media `metadata` object, or null. */
    fun pick(metadata: JsonObject?): String? {
        val images = metadata?.get("images") as? JsonArray ?: return null
        return images.asSequence()
            .mapNotNull { (it as? JsonObject)?.string("url")?.trim() }
            .firstOrNull { isAllowed(it) }
    }
}
