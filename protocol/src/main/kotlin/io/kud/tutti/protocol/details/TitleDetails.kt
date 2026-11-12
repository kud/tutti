package io.kud.tutti.protocol.details

import io.kud.tutti.protocol.cast.CastArtwork
import io.kud.tutti.protocol.cast.CastNowPlaying
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * "Title details" in the playback sheet (Erwann, 2026-11-12: « le plus de métadata quand je lis un
 * film… l'image, possiblement les acteurs »): what Cinemeta, Stremio's public catalogue, says about
 * the title Cast reports. Opt-in, off by default, and only in the github flavour (docs/fdroid.md):
 * the request names what is playing to a third party, which the artwork alone never did beyond the
 * host the TV itself already asked.
 *
 * The id comes only from evidence: the IMDb id embedded in a Cinemeta poster or background URL the
 * Cast status already named ([idFrom]). No search by title: a title search is a guess, and a wrong
 * guess shows another film's cast with full confidence. TMDB artwork carries no IMDb id, so an item
 * enriched there gets no details; that is the honest gap, not a bug.
 *
 * Everything here is viewing data like the title (CastNowPlaying): never logged, never on disk.
 */
object Cinemeta {
    /** The only host the details are asked of. Images it names are fetched only through [CastArtwork]. */
    const val HOST = "v3-cinemeta.strem.io"

    /**
     * A series meta lists every episode and runs to a few hundred KiB for a long show; past this it
     * is not a meta we want to hold in memory.
     */
    const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * `https://images.metahub.space/<kind>/<size>/<imdb id>/img`, exactly: the shape Cinemeta's metas
     * name (CastArtwork's doc). Anything looser is not evidence of an id.
     */
    private val METAHUB = Regex("""^https://images\.metahub\.space/(?:poster|background|logo)/(?:small|medium|large)/(tt\d{5,10})/img$""")

    /** The IMDb id a Cinemeta artwork URL embeds, or null when the URL is not exactly that shape. */
    fun idFrom(artworkUrl: String?): String? {
        if (artworkUrl == null || !CastArtwork.isAllowed(artworkUrl)) return null
        return METAHUB.matchEntire(artworkUrl.trim())?.groupValues?.get(1)
    }

    /**
     * Which catalogues to ask, in order. An episode line in the Cast secondary means a series (and
     * the metahub URL then names the series' id); otherwise a film first, then a series, since a
     * series' own artwork can play without an episode line.
     */
    fun typesFor(episode: EpisodeRef?): List<String> = if (episode != null) listOf("series") else listOf("movie", "series")

    fun url(type: String, id: String): String {
        require(type == "movie" || type == "series") { "type" }
        require(Regex("""tt\d{5,10}""").matches(id)) { "id" }
        return "https://$HOST/meta/$type/$id.json"
    }

    /** What to ask for the item playing: its id and which catalogues, or null when there is no evidence. */
    fun requestFor(playing: CastNowPlaying?): DetailsRequest? {
        val id = idFrom(playing?.imageUrl) ?: return null
        val episode = EpisodeRef.parse(playing?.secondary)
        return DetailsRequest(id, typesFor(episode), episode)
    }
}

/** One lookup: the IMDb id, the catalogues to try in order, and the episode the Cast line names. */
data class DetailsRequest(val id: String, val types: List<String>, val episode: EpisodeRef?) {
    override fun toString(): String = "DetailsRequest(id=<redacted>, types=$types, episode=${if (episode == null) "none" else "<redacted>"})"
}

/** Season and episode as the Cast secondary names them ("S1:E5 – <episode title>"). */
data class EpisodeRef(val season: Int, val episode: Int) {
    companion object {
        // Strict: "S<n>:E<n>" at the very start, then nothing, or a dash and the episode's title.
        private val LINE = Regex("""^S(\d{1,3}):E(\d{1,4})(?:\s+[–—-]\s+.*)?$""")

        fun parse(secondary: String?): EpisodeRef? {
            val match = LINE.matchEntire(secondary?.trim() ?: return null) ?: return null
            val season = match.groupValues[1].toInt()
            val episode = match.groupValues[2].toInt()
            return EpisodeRef(season, episode)
        }
    }
}

/** One video of a series meta: only what the sheet shows. */
data class CinemetaEpisode(val season: Int, val episode: Int, val title: String?, val overview: String?)

/** The fields of a Cinemeta meta tutti reads, each null or empty when absent or malformed. */
data class CinemetaMeta(
    val id: String,
    val type: String?,
    val name: String?,
    val year: String?,
    val runtime: String?,
    val genres: List<String>,
    val imdbRating: String?,
    val description: String?,
    val director: List<String>,
    val cast: List<String>,
    val poster: String?,
    val videos: List<CinemetaEpisode>,
) {
    override fun toString(): String = "CinemetaMeta(<redacted>, videos=${videos.size})"
}

/** What the playback sheet's Details section shows. Any field may be missing. */
data class TitleDetails(
    val title: String?,
    val year: String?,
    val runtime: String?,
    val genres: List<String>,
    /** IMDb's rating as Cinemeta relays it, "7.8": always shown labelled as IMDb's. */
    val imdbRating: String?,
    val synopsis: String?,
    val episode: EpisodeDetails?,
    val director: List<String>,
    val cast: List<String>,
    /** A poster on an allowed artwork host ([CastArtwork.isAllowed]), else null. */
    val poster: String?,
) {
    val isEmpty: Boolean
        get() = title == null && year == null && runtime == null && genres.isEmpty() && imdbRating == null &&
            synopsis == null && episode == null && director.isEmpty() && cast.isEmpty()

    override fun toString(): String = "TitleDetails(<redacted>)"

    companion object {
        const val MAX_GENRES = 3
        const val MAX_CAST = 12
        const val MAX_DIRECTORS = 3
        const val MAX_TEXT = 1200
        const val MAX_NAME = 120

        /** The fields to show for [meta], with the episode [ref] names when the series lists exactly one. */
        fun of(meta: CinemetaMeta, ref: EpisodeRef?): TitleDetails = TitleDetails(
            title = meta.name,
            year = meta.year,
            runtime = meta.runtime,
            genres = meta.genres.take(MAX_GENRES),
            imdbRating = meta.imdbRating,
            synopsis = meta.description,
            episode = ref?.let { episodeOf(meta, it) },
            director = meta.director.take(MAX_DIRECTORS),
            cast = meta.cast.take(MAX_CAST),
            poster = meta.poster?.takeIf { CastArtwork.isAllowed(it) },
        )

        /** The one video at that season and episode; none when it is missing or listed twice. */
        fun episodeOf(meta: CinemetaMeta, ref: EpisodeRef): EpisodeDetails? {
            val found = meta.videos.filter { it.season == ref.season && it.episode == ref.episode }
            val video = found.singleOrNull() ?: return null
            return EpisodeDetails(ref.season, ref.episode, video.title, video.overview)
        }
    }
}

data class EpisodeDetails(val season: Int, val episode: Int, val title: String?, val overview: String?) {
    override fun toString(): String = "EpisodeDetails(<redacted>)"
}

/**
 * Reads Cinemeta's `{"meta": {...}}`. Tolerant: any field missing, null or of the wrong type is
 * left out rather than failing the whole meta; only a missing or unusable `meta` (Cinemeta answers
 * `{}` or `{"meta":null}` for an id it doesn't hold in that catalogue) gives null. Never throws.
 */
object CinemetaParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(text: String, expectedId: String): CinemetaMeta? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val meta = root["meta"] as? JsonObject ?: return null
        // The meta must be about the id we asked for: a different one is not this title.
        val id = meta.text("imdb_id") ?: meta.text("id")
        if (id != expectedId) return null
        val name = meta.text("name", TitleDetails.MAX_NAME * 2)
        val genres = meta.names("genres").ifEmpty { meta.names("genre") }
        return CinemetaMeta(
            id = expectedId,
            type = meta.text("type"),
            name = name,
            year = meta.text("releaseInfo", 16) ?: meta.text("year", 16),
            runtime = meta.text("runtime", 24),
            genres = genres,
            imdbRating = rating(meta["imdbRating"]),
            description = meta.text("description", TitleDetails.MAX_TEXT),
            director = meta.names("director"),
            cast = meta.names("cast"),
            poster = meta.text("poster", CastArtwork.MAX_URL_LENGTH),
            videos = (meta["videos"] as? JsonArray).orEmpty().mapNotNull(::video),
        )
    }

    private fun video(element: JsonElement): CinemetaEpisode? {
        val video = element as? JsonObject ?: return null
        val season = video.int("season") ?: return null
        val episode = video.int("episode") ?: video.int("number") ?: return null
        return CinemetaEpisode(
            season,
            episode,
            title = video.text("name", TitleDetails.MAX_NAME * 2) ?: video.text("title", TitleDetails.MAX_NAME * 2),
            overview = video.text("overview", TitleDetails.MAX_TEXT) ?: video.text("description", TitleDetails.MAX_TEXT),
        )
    }

    /** "7.8" from "7.8" or 7.8, only when it reads as a rating out of ten. */
    private fun rating(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        val value = primitive.contentOrNull?.trim()?.toDoubleOrNull() ?: primitive.doubleOrNull ?: return null
        if (value <= 0.0 || value > 10.0) return null
        return String.format(java.util.Locale.ROOT, "%.1f", value)
    }

    private fun JsonObject.text(key: String, max: Int = TitleDetails.MAX_NAME): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString && primitive.contentOrNull == null) return null
        return clean(primitive.contentOrNull, max)
    }

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return (primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull())?.takeIf { it >= 0 }
    }

    /** A list of names from an array of strings, or a single string; blanks and repeats dropped. */
    private fun JsonObject.names(key: String): List<String> {
        val items = when (val value = this[key]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(value.takeIf { it.isString }?.contentOrNull)
            else -> emptyList()
        }
        return items.mapNotNull { clean(it, TitleDetails.MAX_NAME) }.distinct().take(32)
    }

    /** Trimmed, one line of spaces, at most [max] characters (cut at a word with an ellipsis), or null when blank. */
    internal fun clean(raw: String?, max: Int): String? {
        val text = raw?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        if (text.length <= max) return text
        val cut = text.substring(0, max).substringBeforeLast(' ').ifEmpty { text.substring(0, max) }
        return cut.trimEnd() + "…"
    }
}
