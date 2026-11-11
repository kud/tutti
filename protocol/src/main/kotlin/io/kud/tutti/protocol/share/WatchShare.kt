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
        val secondary = m.secondary?.trim()?.takeIf { it.isNotEmpty() && it != title }?.let { episodeLabel(it, language) ?: it }
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

    /**
     * A secondary that opens with an episode marker reads as "S1:E5, « The Place I Lie »": the
     * marker as [Episode.label], the episode's name quoted like a title. Nuvio's Cast status gives
     * "S1:E5 – The Place I Lie" (seen on Erwann's BRAVIA, 2026-11-11), and its dash read as a
     * typo inside the sentence's parentheses. Anything else is left as it came.
     */
    private fun episodeLabel(secondary: String, language: ShareLanguage): String? {
        val episode = Episode.parse(secondary) ?: return null
        val name = Episode.nameAfter(secondary)
        return if (name == null) episode.label else "${episode.label}, ${quoted(name, language)}"
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
 * A season and episode, read only from a marker at the very start of a Cast secondary: "S1:E5"
 * (Nuvio's form) or "S01E05", then the end, a space or a separator. Nothing else is read as one, so
 * a secondary without a marker is never guessed at. Kept for a future exact-episode Trakt link;
 * today's link still searches the series title, since an episode URL needs Trakt's slug, which
 * tutti does not know.
 */
data class Episode(val season: Int, val episode: Int) {
    val label: String get() = "S$season:E$episode"

    companion object {
        private val MARKER = Regex("^S(\\d{1,3}):?E(\\d{1,4})(?=$|[\\s–—:·-])", RegexOption.IGNORE_CASE)
        private val SEPARATORS = Regex("^[\\s–—:·-]+")

        fun parse(secondary: String?): Episode? {
            val m = MARKER.find(secondary?.trim() ?: return null) ?: return null
            val season = m.groupValues[1].toInt()
            val episode = m.groupValues[2].toInt()
            return if (episode > 0) Episode(season, episode) else null
        }

        /** The episode's name after the marker, separators dropped; null when there is none. */
        internal fun nameAfter(secondary: String): String? {
            val m = MARKER.find(secondary.trim()) ?: return null
            return secondary.trim().substring(m.range.last + 1).replace(SEPARATORS, "").trim().ifEmpty { null }
        }
    }
}

/**
 * Where the Trakt button goes (Erwann, 2026-11-11: « à côté de Share, j'ai un bouton Trakt qui me
 * permet d'ouvrir Trakt au bon endroit pour continuer sur mon téléphone »).
 */
sealed interface TraktTarget {
    /**
     * A title is known: Trakt's search on it ([TitleLinks.trakt]). For an episode that is the
     * series title, never the episode's name; [episode] is what the Cast secondary's marker says,
     * when it says one.
     */
    data class Search(val title: String, val episode: Episode? = null) : TraktTarget {
        override fun toString(): String = "TraktTarget.Search(title=<redacted>, episode=${if (episode == null) "none" else "<redacted>"})"
    }

    /**
     * An app is in front but names nothing it plays (Nuvio before its Cast status arrives, Netflix):
     * Trakt itself, at its own start. Apps that scrobble to Trakt (NuvioTV does, once connected:
     * https://mintlify.wiki/NuvioMedia/NuvioTV/configuration/integrations) put the episode in
     * progress there, so Trakt knows what tutti does not.
     */
    data object Home : TraktTarget
}

/** One way to open Trakt, tried in [TitleLinks.traktSteps]'s order. */
sealed interface TraktStep {
    /** A VIEW of [url] addressed to the Trakt app's package, so no browser can take it. */
    data class AppLink(val url: String) : TraktStep {
        override fun toString(): String = "TraktStep.AppLink(<redacted>)"
    }

    /** The Trakt app's launch intent, at its own start. */
    data object LaunchApp : TraktStep

    /** A plain VIEW of [url], for the browser. */
    data class Web(val url: String) : TraktStep {
        override fun toString(): String = "TraktStep.Web(<redacted>)"
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
     * Trakt Web's search, `https://app.trakt.tv/search?m=media&q=<title>`, the title form-encoded
     * (spaces as `+`): the shape verified by Erwann in Firefox, 2026-11-11
     * (`https://app.trakt.tv/search?m=media&q=Black+bird`). The older `trakt.tv/search?query=`
     * answers 301 to `app.trakt.tv/search?query=`, which landed on the wrong results for Erwann the
     * same day. `m=media` searches shows and films together; a shows-only mode
     * was not found in Trakt Web's code (checked 2026-11-11), so an episode searches as media too,
     * on its series title alone ([TraktTarget.Search.title]).
     */
    fun trakt(title: String): String = "${TRAKT_HOME}search?m=media&q=${URLEncoder.encode(title.trim(), Charsets.UTF_8)}"

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

    /**
     * The Trakt button's target: the search when a title is known, Trakt's start when only an app
     * is in front, and nothing for a tuner channel without a programme (Trakt does not follow live
     * TV) or when nothing plays (an input, the home screen, standby: [Watching] is null).
     */
    fun traktTarget(watching: Watching?): TraktTarget? {
        val title = searchTitle(watching)
        if (title != null) return TraktTarget.Search(title, (watching as? Watching.Media)?.let { Episode.parse(it.secondary) })
        return if (watching is Watching.App && watching.name.isNotBlank()) TraktTarget.Home else null
    }

    /**
     * Trakt's start when no title is known: the official Android app (`tv.trakt.trakt`) when it is
     * installed, which the phone opens by its launch intent, else Trakt Web's home. `trakt.tv/`
     * answers 302 to `https://app.trakt.tv/` ("Trakt Web: Home"; checked 2026-11-11). Trakt Web has
     * no checked per-user "continue watching" URL: `app.trakt.tv/users/<slug>/progress` renders the
     * same "Continue Watching" shell for any slug, an unknown one included, so whether `me` there
     * means the signed-in account could not be verified, and the home page it is.
     */
    const val TRAKT_PACKAGE: String = "tv.trakt.trakt"
    const val TRAKT_HOME: String = "https://app.trakt.tv/"

    /**
     * How the phone opens [target], first step that works wins. Which links the Trakt app
     * (`tv.trakt.trakt`, the Play listing "trakt.TV Time To Watch") handles is not published: both
     * hosts' `/.well-known/assetlinks.json` delegate `handle_all_urls` to it, yet a search link
     * opened Firefox on Erwann's phone with the app installed (2026-11-11). So the app is asked for
     * the link by package first ([TraktStep.AppLink]: the phone resolves it, and an app that does
     * not handle it is skipped, never guessed into), then launched at its start, and the web is
     * the last resort, used only without the app.
     */
    fun traktSteps(target: TraktTarget): List<TraktStep> = when (target) {
        is TraktTarget.Search -> trakt(target.title).let { listOf(TraktStep.AppLink(it), TraktStep.LaunchApp, TraktStep.Web(it)) }
        TraktTarget.Home -> listOf(TraktStep.LaunchApp, TraktStep.Web(TRAKT_HOME))
    }

    /**
     * The links offered: Trakt when its switch is on and [traktTarget] has somewhere to go,
     * JustWatch when its switch is on and a title is known.
     */
    fun offered(watching: Watching?, trakt: Boolean, justWatch: Boolean): Set<TitleLink> = buildSet {
        if (trakt && traktTarget(watching) != null) add(TitleLink.TRAKT)
        if (justWatch && searchTitle(watching) != null) add(TitleLink.JUSTWATCH)
    }

    private fun encode(s: String): String = URLEncoder.encode(s.trim(), Charsets.UTF_8).replace("+", "%20")
}

enum class TitleLink { TRAKT, JUSTWATCH }
