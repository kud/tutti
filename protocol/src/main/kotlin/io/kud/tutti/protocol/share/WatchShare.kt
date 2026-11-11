package io.kud.tutti.protocol.share

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * What the TV is showing, as far as a sentence can tell it (Erwann, 2026-11-11: « un bouton share
 * pour juste expliquer ce qu'on regarde et où on en est »). Titles, channels and programmes are
 * viewing data like [io.kud.tutti.protocol.cast.CastNowPlaying]'s: [toString] never prints them,
 * and nothing logs them, the diagnostic event log included.
 */
sealed interface Watching {
    /**
     * Something Cast reports by name: [title], its artist or channel ([secondary]), the app it
     * plays in when the TV names one ([app]), and where it is, in whole seconds, when that can be
     * trusted ([positionSec]; [durationSec] is null for live content). [videoId] is a YouTube video
     * id, only from [WatchShare.youTubeVideoId].
     */
    data class Media(
        val title: String,
        val secondary: String? = null,
        val app: String? = null,
        val positionSec: Long? = null,
        val durationSec: Long? = null,
        val videoId: String? = null,
    ) : Watching {
        override fun toString(): String =
            "Watching.Media(title=<redacted>, secondary=${if (secondary == null) "none" else "<redacted>"}, " +
                "app=$app, position=$positionSec, duration=$durationSec, video=${if (videoId == null) "none" else "<redacted>"})"
    }

    /** The TV's own tuner: the channel's [number] and [name], and the [programme] when the EPG names it. */
    data class Channel(val number: String? = null, val name: String? = null, val programme: String? = null) : Watching {
        override fun toString(): String =
            "Watching.Channel(number=${if (number == null) "none" else "<redacted>"}, name=${if (name == null) "none" else "<redacted>"}, " +
                "programme=${if (programme == null) "none" else "<redacted>"})"
    }

    /** An app in front that reports nothing it plays (Netflix and most others): only its name. */
    data class App(val name: String) : Watching
}

/** The two languages the UI speaks; anything but French reads as English, as `values/` does. */
enum class ShareLanguage {
    EN,
    FR,
    ;

    companion object {
        fun of(language: String?): ShareLanguage = if (language?.lowercase(Locale.ROOT) == "fr") FR else EN
    }
}

/**
 * The share sentence and the YouTube link. Pure: the share sheet is the only way it leaves the
 * phone, and only when he picks a target.
 */
object WatchShare {
    private const val NBSP = ' '

    /**
     * The text handed to the share sheet: one natural sentence, then, for a YouTube video whose
     * id is known, its link at the current second on its own line. Null when the TV shows nothing
     * a sentence can name (a channel with neither name, number nor programme, a blank app name).
     */
    fun text(watching: Watching, language: ShareLanguage): String? {
        val sentence = sentence(watching, language) ?: return null
        val link = (watching as? Watching.Media)?.videoId?.let { youTubeLink(it, watching.positionSec) }
        return if (link == null) sentence else "$sentence\n$link"
    }

    fun sentence(watching: Watching, language: ShareLanguage): String? = when (watching) {
        is Watching.Media -> media(watching, language)
        is Watching.Channel -> channel(watching, language)
        is Watching.App -> watching.name.trim().takeIf { it.isNotEmpty() }?.let {
            if (language == ShareLanguage.FR) "Je regarde $it." else "I'm watching $it."
        }
    }

    private fun media(m: Watching.Media, language: ShareLanguage): String? {
        val title = m.title.trim().takeIf { it.isNotEmpty() } ?: return null
        val secondary = m.secondary?.trim()?.takeIf { it.isNotEmpty() && it != title }
        val app = m.app?.trim()?.takeIf { it.isNotEmpty() }
        val time = time(m.positionSec, m.durationSec, language)
        return buildString {
            if (language == ShareLanguage.FR) {
                append("Je regarde ").append(quoted(title, language))
                secondary?.let { append(" (").append(it).append(')') }
                app?.let { append(" sur ").append(it) }
                time?.let { append(", j'en suis à ").append(it) }
            } else {
                append("I'm watching ").append(quoted(title, language))
                secondary?.let { append(" (").append(it).append(')') }
                app?.let { append(" on ").append(it) }
                time?.let { append(", ").append(it) }
            }
            append('.')
        }
    }

    /**
     * "23:41 sur 1:02:10" / "23:41 into 1:02:10". A position alone (live content) says nothing worth
     * sharing, so it is left out, as is a position past the duration (a reading not to trust).
     */
    private fun time(position: Long?, duration: Long?, language: ShareLanguage): String? {
        val at = position?.takeIf { it >= 0 } ?: return null
        val total = duration?.takeIf { it > 0 && at <= it } ?: return null
        return if (language == ShareLanguage.FR) "${clock(at)} sur ${clock(total)}" else "${clock(at)} into ${clock(total)}"
    }

    private fun channel(c: Watching.Channel, language: ShareLanguage): String? {
        val head = listOfNotNull(c.number?.trim(), c.name?.trim()).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
        val programme = c.programme?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            programme != null && head != null ->
                if (language == ShareLanguage.FR) "Je regarde ${quoted(programme, language)} sur $head." else "I'm watching ${quoted(programme, language)} on $head."
            programme != null ->
                if (language == ShareLanguage.FR) "Je regarde ${quoted(programme, language)} à la télé." else "I'm watching ${quoted(programme, language)} on TV."
            head != null -> if (language == ShareLanguage.FR) "Je regarde $head." else "I'm watching $head."
            else -> null
        }
    }

    private fun quoted(s: String, language: ShareLanguage): String =
        if (language == ShareLanguage.FR) "«$NBSP$s$NBSP»" else "“$s”"

    /** 0:07, 23:41, 1:02:10: minutes and seconds, with hours only from one hour up. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /**
     * The YouTube video id, read from the artwork URL YouTube's receiver names in its Cast status
     * (`metadata.images[].url`, already parsed for [io.kud.tutti.protocol.cast.CastArtwork] and seen
     * on the BRAVIA): `https://i.ytimg.com/vi/<id>/<name>.jpg`, or `vi_webp`. This is the shape
     * YouTube's own thumbnail URLs take (https://developers.google.com/youtube/v3/docs/thumbnails:
     * "https://i.ytimg.com/vi/<videoId>/default.jpg"). tutti does not read the status's `contentId`:
     * the Cast docs call it only a "service-specific identifier"
     * (https://developers.google.com/cast/docs/reference/web_receiver/cast.framework.messages.MediaInformation#contentId),
     * so it would be a guess. Anything that is not an 11-character id on that host is null.
     */
    fun youTubeVideoId(imageUrl: String?): String? {
        if (imageUrl == null || imageUrl.length > 2048) return null
        val uri = runCatching { URI(imageUrl) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "i.ytimg.com" || uri.rawUserInfo != null) return null
        val parts = uri.path?.split('/')?.filter { it.isNotEmpty() } ?: return null
        if (parts.size < 3 || parts[0] !in setOf("vi", "vi_webp")) return null
        return parts[1].takeIf { VIDEO_ID.matches(it) }
    }

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * `https://youtu.be/<id>?t=<seconds>`, the form YouTube's own Share › "Start at" produces
     * (https://support.google.com/youtube/answer/57741); without a position, or at 0, no `t`.
     */
    fun youTubeLink(videoId: String, positionSec: Long?): String? {
        if (!VIDEO_ID.matches(videoId)) return null
        val t = positionSec?.takeIf { it > 0 }
        return if (t == null) "https://youtu.be/$videoId" else "https://youtu.be/$videoId?t=$t"
    }
}

/**
 * Optional "look this title up" links (Erwann, 2026-11-11: « des liens qui vont vers Trakt…
 * JustWatch… on devrait pouvoir le enable ou disable »): plain web searches opened on the phone.
 * No API, no account, no key, and tutti itself makes no request: the phone's browser, or an app
 * that claims the link, does.
 */
object TitleLinks {
    /**
     * The title worth searching for: a Cast title, or the tuner's programme. Never a channel name
     * or an app name alone, which would search for the wrong thing.
     */
    fun searchTitle(watching: Watching?): String? = when (watching) {
        is Watching.Media -> watching.title.trim().takeIf { it.isNotEmpty() }
        is Watching.Channel -> watching.programme?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    /**
     * Trakt's web search, `https://trakt.tv/search?query=<title>` (checked 2026-11-11: it answers
     * 301 to `https://app.trakt.tv/search?query=<title>`, the "Trakt Web: Search" page). Both hosts
     * publish `/.well-known/assetlinks.json` with `delegate_permission/common.handle_all_urls` for
     * `tv.trakt.trakt` (the official Android app), so Android may hand the link to that app when it
     * is installed; whether the app routes `/search` itself is not verified, and the browser
     * opens it otherwise.
     */
    fun trakt(title: String): String = "https://trakt.tv/search?query=${encode(title)}"

    /**
     * JustWatch's search, which lives under a country path with a localised word (checked
     * 2026-11-11, each answering 200; `fr/search` and `nl/zoeken` answer 404, so the word is not
     * guessable): `https://www.justwatch.com/<path>?q=<title>`. The phone's country picks the path;
     * a country not in [JUSTWATCH] falls back by language, then to the US.
     */
    fun justWatch(title: String, country: String?, language: String?): String {
        val path = JUSTWATCH[country?.uppercase(Locale.ROOT)]
            ?: JUSTWATCH_BY_LANGUAGE[language?.lowercase(Locale.ROOT)]
            ?: JUSTWATCH.getValue("US")
        return "https://www.justwatch.com/$path?q=${encode(title)}"
    }

    /** Verified search paths, by ISO country. JustWatch spells the UK `uk`, not `gb`. */
    val JUSTWATCH: Map<String, String> = mapOf(
        "FR" to "fr/recherche",
        "BE" to "be/recherche",
        "DE" to "de/Suche",
        "CH" to "ch/Suche",
        "ES" to "es/buscar",
        "IT" to "it/cerca",
        "GB" to "uk/search",
        "US" to "us/search",
        "CA" to "ca/search",
    )

    private val JUSTWATCH_BY_LANGUAGE: Map<String, String> = mapOf(
        "fr" to "fr/recherche",
        "de" to "de/Suche",
        "es" to "es/buscar",
        "it" to "it/cerca",
    )

    /** The links offered: each only when its switch is on and a title is known. */
    fun offered(watching: Watching?, trakt: Boolean, justWatch: Boolean): Set<TitleLink> {
        if (searchTitle(watching) == null) return emptySet()
        return buildSet {
            if (trakt) add(TitleLink.TRAKT)
            if (justWatch) add(TitleLink.JUSTWATCH)
        }
    }

    private fun encode(s: String): String = URLEncoder.encode(s.trim(), Charsets.UTF_8).replace("+", "%20")
}

enum class TitleLink { TRAKT, JUSTWATCH }
