package io.kud.tutti.protocol.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class CastPlayerState { PLAYING, PAUSED, BUFFERING, IDLE }

/**
 * What the TV reports playing through Cast. [title] and [secondary] are personal viewing
 * data: [toString] never prints them, and nothing logs them.
 */
data class CastNowPlaying(
    val title: String,
    /** Artist or channel, else subtitle or series. */
    val secondary: String?,
    val state: CastPlayerState,
    /** Seconds, as read at [readAtMillis]. */
    val positionSec: Double?,
    /** Seconds; null for live content. */
    val durationSec: Double?,
    val playbackRate: Double,
    val readAtMillis: Long,
) {
    /** The position now, run forward from the last report while playing, held inside the duration. */
    fun positionAt(nowMillis: Long): Double? {
        val base = positionSec ?: return null
        val run = if (state == CastPlayerState.PLAYING) (nowMillis - readAtMillis).coerceAtLeast(0) / 1000.0 * playbackRate else 0.0
        val at = (base + run).coerceAtLeast(0.0)
        return durationSec?.let { at.coerceAtMost(it) } ?: at
    }

    /** 0..1 once both ends are known and the duration is positive. */
    fun progressAt(nowMillis: Long): Float? {
        val duration = durationSec?.takeIf { it > 0 } ?: return null
        val at = positionAt(nowMillis) ?: return null
        return (at / duration).toFloat().coerceIn(0f, 1f)
    }

    override fun toString(): String =
        "CastNowPlaying(title=<redacted>, secondary=${if (secondary == null) "none" else "<redacted>"}, state=$state, " +
            "position=$positionSec, duration=$durationSec)"
}

/** The app the receiver reports in front, if it speaks the media namespace. */
data class CastApp(val appId: String, val transportId: String, val sessionId: String?) {
    override fun toString(): String = "CastApp(appId=$appId)"
}

/** One media session's last known fields; [CastStatusReducer] merges partial updates into it. */
internal data class MediaSnapshot(
    val mediaSessionId: Long?,
    val title: String?,
    val secondary: String?,
    val duration: Double?,
)

object CastNamespaces {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"
}

/** Parses receiver and media status JSON. Total: anything unexpected reads as "nothing reported". */
object CastStatusParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    fun type(payload: JsonObject): String? = payload.string("type")

    /** The first running application that offers the media namespace, from a RECEIVER_STATUS. */
    fun mediaApp(payload: JsonObject): CastApp? {
        val apps = (payload["status"] as? JsonObject)?.get("applications") as? JsonArray ?: return null
        return apps.asSequence().mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { app ->
            val namespaces = (app["namespaces"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("name") }.orEmpty()
            val transport = app.string("transportId")
            val id = app.string("appId")
            if (transport != null && id != null && CastNamespaces.MEDIA in namespaces) CastApp(id, transport, app.string("sessionId")) else null
        }
    }

    internal fun mediaEntries(payload: JsonObject): List<JsonObject> =
        (payload["status"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    internal fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/**
 * Folds MEDIA_STATUS messages into what the card shows. A status may leave out `media` when
 * only the position or state changed, so the title and duration of the same session are kept.
 */
class CastStatusReducer {
    private var media: MediaSnapshot? = null

    fun reset() {
        media = null
    }

    /** The new view, or null when nothing worth showing is reported (no title, idle, no session). */
    fun onMediaStatus(payload: JsonObject, nowMillis: Long): CastNowPlaying? = with(CastStatusParser) {
        val entry = mediaEntries(payload).firstOrNull() ?: run { media = null; return null }
        val sessionId = entry.long("mediaSessionId")
        val metadata = entry["media"].obj()?.get("metadata").obj()
        val previous = media?.takeIf { it.mediaSessionId == sessionId }
        val mediaObj = entry["media"].obj()
        val snapshot = MediaSnapshot(
            mediaSessionId = sessionId,
            title = metadata?.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.title,
            secondary = metadata?.let { m ->
                listOf("artist", "subtitle", "seriesTitle", "albumName").firstNotNullOfOrNull { k -> m.string(k)?.trim()?.takeIf { it.isNotEmpty() } }
            } ?: previous?.secondary,
            duration = mediaObj?.number("duration")?.takeIf { it > 0 } ?: previous?.duration.takeIf { mediaObj == null },
        )
        media = snapshot
        val state = when (entry.string("playerState")) {
            "PLAYING" -> CastPlayerState.PLAYING
            "PAUSED" -> CastPlayerState.PAUSED
            "BUFFERING", "LOADING" -> CastPlayerState.BUFFERING
            else -> CastPlayerState.IDLE
        }
        val title = snapshot.title
        if (state == CastPlayerState.IDLE || title == null) return null
        CastNowPlaying(
            title = title,
            secondary = snapshot.secondary,
            state = state,
            positionSec = entry.number("currentTime")?.takeIf { it >= 0 },
            durationSec = snapshot.duration,
            playbackRate = entry.number("playbackRate")?.takeIf { it > 0 } ?: 1.0,
            readAtMillis = nowMillis,
        )
    }
}
