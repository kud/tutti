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
     * Add a host only with the app that needs it, and name it in docs/privacy.mdx.
     */
    val HOSTS = setOf("i.ytimg.com", "lh3.googleusercontent.com")

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

    /** The first allowed `images[].url` of a media `metadata` object, or null. */
    fun pick(metadata: JsonObject?): String? {
        val images = metadata?.get("images") as? JsonArray ?: return null
        return images.asSequence()
            .mapNotNull { (it as? JsonObject)?.string("url")?.trim() }
            .firstOrNull { isAllowed(it) }
    }
}
