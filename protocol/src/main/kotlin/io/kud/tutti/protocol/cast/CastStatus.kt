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
    /**
     * True when [positionSec] is not what the receiver said but our inference from its last
     * trusted reading (see [CastStatusReducer]): the receiver's own number was at the end.
     */
    val inferred: Boolean = false,
    /**
     * Artwork for what is playing, already checked by [CastArtwork.isAllowed]; null when the
     * status names none on an allowed host. Viewing data like the title: never logged.
     */
    val imageUrl: String? = null,
) {
    /**
     * Whether the reading itself holds together: a position, inside the duration when there is
     * one, and short of the end while playing. A receiver that reports `currentTime` at or past
     * the duration while it plays (or in another unit) fails this, and then no time is shown.
     */
    val readingTrusted: Boolean
        get() {
            val at = positionSec ?: return false
            val duration = durationSec ?: return true
            return if (state == CastPlayerState.PLAYING) at < duration else at <= duration + END_SLACK_SEC
        }

    /**
     * The position now, or null when it can't be trusted. While playing it runs on from the last
     * reading for at most [MAX_RUN_MS] (the session re-reads every 15 s, so a fresh one is due long
     * before), and never reaches the end: a playing item at its end means the receiver or our
     * reading is wrong, so no time beats a confident "43:09 / 43:09". Paused, it holds the reading.
     */
    fun positionAt(nowMillis: Long): Double? {
        if (!readingTrusted) return null
        val base = positionSec ?: return null
        if (state != CastPlayerState.PLAYING) return durationSec?.let { base.coerceIn(0.0, it) } ?: base.coerceAtLeast(0.0)
        val age = nowMillis - readAtMillis
        if (age > MAX_RUN_MS) return null
        val at = (base + age.coerceAtLeast(0) / 1000.0 * playbackRate).coerceAtLeast(0.0)
        val duration = durationSec ?: return at
        return at.takeIf { it < duration }
    }

    /** 0..1 once both ends are known and the duration is positive. */
    fun progressAt(nowMillis: Long): Float? {
        val duration = durationSec?.takeIf { it > 0 } ?: return null
        val at = positionAt(nowMillis) ?: return null
        return (at / duration).toFloat().coerceIn(0f, 1f)
    }

    override fun toString(): String =
        "CastNowPlaying(title=<redacted>, secondary=${if (secondary == null) "none" else "<redacted>"}, state=$state, " +
            "position=$positionSec, duration=$durationSec, image=${if (imageUrl == null) "none" else "<redacted>"})"

    companion object {
        /** How long a playing position may run on from one reading: two refreshes and some. */
        const val MAX_RUN_MS = 40_000L

        /** A paused item may sit a hair past its reported duration (rounding), not more. */
        const val END_SLACK_SEC = 1.0
    }
}

/**
 * The raw numbers of one `MEDIA_STATUS`, for the diagnostic: never a title, an id or a URL.
 * [playerState] is one of the Cast states, "none" or "other"; [duration] is what this status
 * carried ([mediaIncluded] says whether it had a `media` object at all); [answer] is true for a
 * reply to our GET_STATUS (a non-zero requestId), false for one the TV sent unasked; [sameSession]
 * is false when the mediaSessionId changed since the previous status.
 */
data class CastReading(
    val atMillis: Long,
    val playerState: String,
    val currentTime: Double?,
    val duration: Double?,
    val mediaIncluded: Boolean,
    val playbackRate: Double?,
    val answer: Boolean,
    val sameSession: Boolean,
)

/** The last [capacity] readings, oldest first, as diagnostic lines. Numbers only: safe in a report. */
class CastReadings(private val capacity: Int = 8) {
    private val lock = Any()
    private val readings = ArrayDeque<CastReading>()

    fun record(reading: CastReading) = synchronized(lock) {
        readings.addLast(reading)
        while (readings.size > capacity) readings.removeFirst()
    }

    fun all(): List<CastReading> = synchronized(lock) { readings.toList() }

    /**
     * "cast reading 01" to "age 12.0 s, gap 15.0 s, PLAYING, currentTime 812.4, duration 2589.0,
     * rate 1.0, answer, same session"; the gap is the time since the reading before.
     */
    fun report(nowMillis: Long): List<Pair<String, String>> {
        val list = all()
        if (list.isEmpty()) return listOf("cast readings" to "none this session")
        return list.mapIndexed { i, r ->
            val duration = when {
                !r.mediaIncluded -> "no media"
                r.duration == null -> "duration none"
                else -> "duration ${r.duration}"
            }
            "cast reading %02d".format(i + 1) to listOf(
                "age ${seconds(nowMillis - r.atMillis)}",
                if (i == 0) "first" else "gap ${seconds(r.atMillis - list[i - 1].atMillis)}",
                r.playerState,
                "currentTime ${r.currentTime ?: "none"}",
                duration,
                "rate ${r.playbackRate ?: "none"}",
                if (r.answer) "answer" else "unasked",
                if (r.sameSession) "same session" else "new session",
            ).joinToString()
        }
    }

    private fun seconds(millis: Long) = String.format(java.util.Locale.ROOT, "%.1f s", millis / 1000.0)
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
    val imageUrl: String? = null,
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
    private var lastSessionId: Long? = null

    /** The raw numbers of the last status folded, for the diagnostic; null after an empty one. */
    var lastReading: CastReading? = null
        private set

    /** The media session of the last item reported playing; an IDLE from another one is a confirmed end. */
    private var shownSessionId: Long? = null

    fun reset() {
        media = null
        lastSessionId = null
        anchor = null
        shownSessionId = null
    }

    /** The new view, or null when nothing worth showing is reported (no title, idle, no session). */
    fun onMediaStatus(payload: JsonObject, nowMillis: Long): CastNowPlaying? = report(payload, nowMillis).nowPlaying

    /**
     * The same status, classified for [CastHold]: [CastReport.Playing] with a title;
     * [CastReport.Stopped] for an IDLE that ends the item shown (CANCELLED or ERROR, or another
     * media session); [CastReport.Unknown] for everything that says nothing either way (an empty
     * status list, IDLE between two items of the same session, a new item without its title yet).
     */
    fun report(payload: JsonObject, nowMillis: Long): CastReport = with(CastStatusParser) {
        val entry = mediaEntries(payload).firstOrNull() ?: run { media = null; lastReading = null; anchor = null; return CastReport.Unknown }
        val sessionId = entry.long("mediaSessionId")
        val rawState = entry.string("playerState")
        lastReading = CastReading(
            atMillis = nowMillis,
            playerState = rawState?.takeIf { it in RAW_STATES } ?: if (rawState == null) "none" else "other",
            currentTime = entry.number("currentTime"),
            duration = entry["media"].obj()?.number("duration"),
            mediaIncluded = entry["media"] is JsonObject,
            playbackRate = entry.number("playbackRate"),
            answer = (payload.long("requestId") ?: 0L) != 0L,
            sameSession = lastSessionId == null || lastSessionId == sessionId,
        )
        lastSessionId = sessionId
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
            imageUrl = CastArtwork.pick(metadata) ?: previous?.imageUrl,
        )
        media = snapshot
        val state = when (rawState) {
            "PLAYING" -> CastPlayerState.PLAYING
            "PAUSED" -> CastPlayerState.PAUSED
            "BUFFERING", "LOADING" -> CastPlayerState.BUFFERING
            else -> CastPlayerState.IDLE
        }
        val title = snapshot.title
        if (state == CastPlayerState.IDLE) {
            anchor = null
            val shown = shownSessionId
            val ended = entry.string("idleReason") in ENDING_IDLE || (shown != null && sessionId != null && sessionId != shown)
            return if (ended) CastReport.Stopped else CastReport.Unknown
        }
        if (title == null) {
            anchor = null
            return CastReport.Unknown
        }
        shownSessionId = sessionId
        val view = settle(
            CastNowPlaying(
                title = title,
                secondary = snapshot.secondary,
                state = state,
                positionSec = entry.number("currentTime")?.takeIf { it >= 0 },
                durationSec = snapshot.duration,
                playbackRate = entry.number("playbackRate")?.takeIf { it > 0 } ?: 1.0,
                readAtMillis = nowMillis,
                imageUrl = snapshot.imageUrl,
            ),
            sessionId,
        )
        CastReport.Playing(view)
    }

    /**
     * The last reading whose position held together, and, when it was not playing, the moment
     * PLAYING was first seen after it. YouTube's receiver (seen on the BRAVIA, 2026-10-26) gives
     * a sane `currentTime` while paused but, once playing, `currentTime == duration` on every
     * status, frozen. From this anchor we infer the position instead.
     */
    private data class Anchor(
        val sessionId: Long?,
        val positionSec: Double,
        val durationSec: Double?,
        val readAtMillis: Long,
        val playing: Boolean,
        val resumedAtMillis: Long? = null,
    )

    private var anchor: Anchor? = null

    /**
     * INFERENCE, not a reading: a PLAYING status whose position can't be trusted gets
     * anchor position + time since it started running × rate, marked [CastNowPlaying.inferred].
     * Started running = the anchor's own reading when it was playing, else the first PLAYING
     * status after it (receivers push one on resume; when we only see it on our next poll the
     * time runs up to one refresh behind). The anchor goes on a new session, a new duration, any
     * state but PLAYING that can't be trusted (buffering after a seek, idle), a new trusted
     * reading, and the moment the inference would reach the end: no time beats a wrong one.
     */
    private fun settle(view: CastNowPlaying, sessionId: Long?): CastNowPlaying {
        val now = view.readAtMillis
        if (view.readingTrusted) {
            anchor = Anchor(sessionId, view.positionSec ?: return view, view.durationSec, now, view.state == CastPlayerState.PLAYING)
            return view
        }
        val a = anchor
        if (view.state != CastPlayerState.PLAYING || a == null || a.sessionId != sessionId || a.durationSec != view.durationSec) {
            anchor = null
            return view
        }
        val startedAt = when {
            a.playing -> a.readAtMillis
            a.resumedAtMillis != null -> a.resumedAtMillis
            else -> now.also { anchor = a.copy(resumedAtMillis = it) }
        }
        val at = a.positionSec + (now - startedAt).coerceAtLeast(0) / 1000.0 * view.playbackRate
        val duration = view.durationSec
        if (duration != null && at >= duration) {
            anchor = null
            return view
        }
        return view.copy(positionSec = at, inferred = true)
    }

    private companion object {
        val RAW_STATES = setOf("PLAYING", "PAUSED", "BUFFERING", "LOADING", "IDLE")

        /** Idle reasons that end the item for good; FINISHED and INTERRUPTED usually lead to the next one. */
        val ENDING_IDLE = setOf("CANCELLED", "ERROR")
    }
}
