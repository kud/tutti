package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.CastStatusParser.long
import io.kud.tutti.protocol.cast.CastStatusParser.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * The text tracks a MEDIA_STATUS lists and which are on: track ids only, never a name. [known]
 * is false until a status carried `media.tracks`, which many receivers leave out.
 */
data class CastTracks(val text: List<CastTextTrack> = emptyList(), val active: List<Long> = emptyList(), val known: Boolean = false) {
    val captionsOn: Boolean get() = text.any { it.id in active }
}

data class CastTextTrack(val id: Long, val language: String?)

/**
 * "Say again?" (Erwann, 2026-11-12, « Hein ? »): one tap in the Playback sheet jumps back a few
 * seconds and turns captions and the bar's voice enhancement on for the span replayed, then puts
 * both back as they were. Pure rules: the requests, what to turn on, what to restore.
 *
 * Two Cast media commands, both at a tap only, on the transport of the app whose MEDIA_STATUS we
 * follow, after device auth (architecture § 7): `SEEK` to an absolute `currentTime`, and
 * `EDIT_TRACKS_INFO` with `activeTrackIds` when the status lists text tracks. Captions go through
 * Cast rather than the Captions key because the key opens the TV's picker and says nothing about
 * what was on, so it could never be put back exactly.
 */
object CastSayAgain {
    /** The rewind choices, in seconds (Settings); [DEFAULT_SECONDS] until one is picked. */
    val SECONDS: List<Int> = listOf(10, 15, 20, 30)
    const val DEFAULT_SECONDS: Int = 10

    /** `MediaStatus.COMMAND_SEEK` in Google's Cast SDK. */
    const val COMMAND_SEEK: Long = 2L

    /** `MediaStatus.COMMAND_EDIT_TRACKS`. */
    const val COMMAND_EDIT_TRACKS: Long = 4096L

    /** The least time the help stays on: long enough to hear the line again with the words. */
    const val HOLD_MIN_MS: Long = 30_000L

    /** Past the replayed span, so its last word isn't cut. */
    const val HOLD_TAIL_MS: Long = 3_000L

    fun seconds(stored: Int?): Int = stored?.takeIf { it in SECONDS } ?: DEFAULT_SECONDS

    fun seekSupport(supportedMediaCommands: Long?): CastRateSupport = support(supportedMediaCommands, COMMAND_SEEK)

    fun tracksSupport(supportedMediaCommands: Long?): CastRateSupport = support(supportedMediaCommands, COMMAND_EDIT_TRACKS)

    private fun support(mask: Long?, bit: Long): CastRateSupport = when {
        mask == null -> CastRateSupport.UNKNOWN
        (mask and bit) != 0L -> CastRateSupport.ADVERTISED
        else -> CastRateSupport.NOT_ADVERTISED
    }

    /** Whether the chip shows: a trusted position on an item that doesn't say no to SEEK, from an app that hasn't refused one. */
    fun offered(cast: CastNowPlaying?, refused: Set<String>, now: Long): Boolean {
        if (cast == null) return false
        if (cast.seekSupport == CastRateSupport.NOT_ADVERTISED) return false
        if (cast.appId != null && cast.appId in refused) return false
        if (cast.state != CastPlayerState.PLAYING && cast.state != CastPlayerState.PAUSED) return false
        return cast.positionAt(now) != null
    }

    /** Where to seek: [seconds] back from [positionSec], never before the start. */
    fun target(positionSec: Double, seconds: Int): Double = (positionSec - seconds).coerceAtLeast(0.0)

    /** How long the help stays: [HOLD_MIN_MS], or the replayed span at its rate and a tail when that is longer. */
    fun holdMs(seconds: Int, rate: Double): Long {
        val speed = (rate.takeIf { it > 0.01 } ?: 1.0).coerceAtLeast(0.25)
        val span = (seconds * 1000 / speed).toLong() + HOLD_TAIL_MS
        return maxOf(HOLD_MIN_MS, span)
    }

    fun seekRequest(mediaSessionId: Long, requestId: Int, currentTime: Double): String =
        """{"type":"SEEK","requestId":$requestId,"mediaSessionId":$mediaSessionId,"currentTime":${String.format(Locale.ROOT, "%.3f", currentTime)}}"""

    fun tracksRequest(mediaSessionId: Long, requestId: Int, activeTrackIds: List<Long>): String =
        """{"type":"EDIT_TRACKS_INFO","requestId":$requestId,"mediaSessionId":$mediaSessionId,"activeTrackIds":[${activeTrackIds.joinToString(",")}]}"""

    /**
     * The reply to request [requestId]: an error type is a refusal, a MEDIA_STATUS an acceptance
     * (the seek's landing shows on the timeline; there is nothing finer to check). Null when not ours.
     */
    fun classify(payload: JsonObject, requestId: Int): CastSpeedResult? {
        if (payload.long("requestId") != requestId.toLong()) return null
        val type = payload.string("type") ?: return null
        return if (type == "MEDIA_STATUS") CastSpeedResult.Accepted else CastSpeedResult.Refused(type, payload.string("reason"))
    }

    /**
     * Folds a MEDIA_STATUS into [previous]: `activeTrackIds` is read from every entry (absent means
     * none on), the text tracks only when `media.tracks` is there (a status may leave `media` out).
     */
    fun tracks(payload: JsonObject, previous: CastTracks): CastTracks {
        val entry = CastStatusParser.mediaEntries(payload).firstOrNull() ?: return previous
        val active = (entry["activeTrackIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
        val listed = ((entry["media"] as? JsonObject)?.get("tracks") as? JsonArray)?.mapNotNull { it as? JsonObject }
        val text = listed?.filter { it.string("type") == "TEXT" }?.mapNotNull { t -> t.long("trackId")?.let { CastTextTrack(it, t.string("language")) } }
        return CastTracks(text ?: previous.text, active, known = previous.known || listed != null)
    }

    /**
     * The active ids that turn captions on, or null when there is nothing to do (already on, or no
     * text track listed). Picks the track in [language] (the phone's), else the first; keeps the
     * audio and video tracks that were active.
     */
    fun captionsOn(tracks: CastTracks, language: String?): List<Long>? {
        if (tracks.text.isEmpty() || tracks.captionsOn) return null
        val pick = tracks.text.firstOrNull { language != null && it.language?.substringBefore('-')?.equals(language, ignoreCase = true) == true }
            ?: tracks.text.first()
        return tracks.active + pick.id
    }

    /**
     * What one tap does, given what an earlier tap still holds ([holding]): the seek target, the
     * captions and voice to turn on, and what to put back afterwards. A second tap inside the hold
     * keeps the first tap's "before", so the restore is still what he had.
     */
    fun plan(positionSec: Double, seconds: Int, rate: Double, tracks: CastTracks, voiceOn: Boolean?, language: String?, holding: Restore?): Plan {
        val captions = captionsOn(tracks, language)
        val restore = Restore(
            tracksBefore = holding?.tracksBefore ?: captions?.let { tracks.active },
            tracksSet = captions ?: holding?.tracksSet,
            voiceBefore = holding?.voiceBefore ?: voiceOn?.takeIf { !it },
        )
        return Plan(target(positionSec, seconds), captions, voiceOn == false, holdMs(seconds, rate), restore)
    }

    /** What one tap sends: [captions] null leaves them; [voice] true turns the bar's voice enhancement on. */
    data class Plan(val seekTo: Double, val captions: List<Long>?, val voice: Boolean, val holdMs: Long, val restore: Restore)

    /**
     * What to put back when the hold ends: the active tracks from before (only if we changed them,
     * [tracksSet]), and voice enhancement off (only if it was off, [voiceBefore] false).
     */
    data class Restore(val tracksBefore: List<Long>?, val tracksSet: List<Long>?, val voiceBefore: Boolean?)

    /**
     * The tracks to send at the end of the hold, or null to leave them: only when they still read
     * what we set. Captions he changed himself meanwhile are his.
     */
    fun restoreTracks(restore: Restore, now: CastTracks): List<Long>? {
        val before = restore.tracksBefore ?: return null
        val set = restore.tracksSet ?: return null
        return before.takeIf { now.active.toSet() == set.toSet() }
    }

    /** The voice state to send at the end, or null to leave it: only off again, and only if it is still on as we left it. */
    fun restoreVoice(restore: Restore, now: Boolean?): Boolean? =
        if (restore.voiceBefore == false && now == true) false else null

    /** For the event log: app, package, seconds back and how it went; never a title or a position. */
    fun logLine(appId: String?, foreground: String?, seconds: Int, result: CastSpeedResult, captions: String, voice: String): String {
        val outcome = when (result) {
            CastSpeedResult.Accepted -> "seek accepted"
            is CastSpeedResult.Refused -> "seek refused (${result.type}${result.reason?.let { ", $it" } ?: ""})"
            is CastSpeedResult.Ignored -> "seek ignored"
            CastSpeedResult.NoAnswer -> "seek: no answer in ${CastSpeed.ANSWER_MS / 1000} s"
            CastSpeedResult.NoSession -> "no media session"
        }
        return "say again ${seconds}s for ${appId ?: "no app"} (${foreground ?: "package unknown"}): $outcome; captions $captions; voice $voice"
    }
}
