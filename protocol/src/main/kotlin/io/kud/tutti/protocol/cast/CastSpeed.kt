package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.CastStatusParser.long
import io.kud.tutti.protocol.cast.CastStatusParser.number
import io.kud.tutti.protocol.cast.CastStatusParser.string
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.abs

/**
 * Whether the app in front says it takes a playback rate, from `supportedMediaCommands` in its
 * MEDIA_STATUS (Cast's `COMMAND_PLAYBACK_RATE`, 8192; the Web Receiver's `ALL_BASIC_MEDIA`
 * includes it, and Android TV receivers declare their own set through Cast Connect, so it varies
 * by app). [UNKNOWN] when the status carries no mask at all: worth one try, which settles it.
 */
enum class CastRateSupport { ADVERTISED, NOT_ADVERTISED, UNKNOWN }

/** How one SET_PLAYBACK_RATE went. Classes only: safe to log. */
sealed interface CastSpeedResult {
    /** The receiver's own status reports the rate asked for. */
    data object Accepted : CastSpeedResult

    /** An error reply (INVALID_REQUEST, INVALID_PLAYER_STATE, …), with its reason when it gave one. */
    data class Refused(val type: String, val reason: String?) : CastSpeedResult

    /** A status came back at another rate: the receiver took the request and did nothing with it. */
    data class Ignored(val reportedRate: Double?) : CastSpeedResult

    /** No reply in time. */
    data object NoAnswer : CastSpeedResult

    /** Nothing to send to: no media app, or no media session id yet. */
    data object NoSession : CastSpeedResult
}

/**
 * The Cast media namespace's playback rate, as a sender that did not start the session drives it:
 * the session already CONNECTs to the app's transport for its status, and any connected sender may
 * send media commands naming the `mediaSessionId` that status carries. Pure: message, support and
 * reply classification, tested in `:protocol`.
 */
object CastSpeed {
    /** What the picker offers, slowest first. */
    val RATES: List<Double> = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

    /** `MediaStatus.COMMAND_PLAYBACK_RATE` in Google's Cast SDK. */
    const val COMMAND_PLAYBACK_RATE: Long = 8192L

    /** How long a reply may take before the request counts as unanswered. */
    const val ANSWER_MS: Long = 5_000L

    private const val TOLERANCE = 0.01

    fun support(supportedMediaCommands: Long?): CastRateSupport = when {
        supportedMediaCommands == null -> CastRateSupport.UNKNOWN
        (supportedMediaCommands and COMMAND_PLAYBACK_RATE) != 0L -> CastRateSupport.ADVERTISED
        else -> CastRateSupport.NOT_ADVERTISED
    }

    /** The mask from the first entry of a MEDIA_STATUS, or null when it carries none. */
    fun supportedCommands(payload: JsonObject): Long? =
        CastStatusParser.mediaEntries(payload).firstOrNull()?.long("supportedMediaCommands")

    /** The media session a MEDIA_STATUS names: what every media command must carry. */
    fun mediaSessionId(payload: JsonObject): Long? =
        CastStatusParser.mediaEntries(payload).firstOrNull()?.long("mediaSessionId")

    /** The SET_PLAYBACK_RATE request, as Google's own senders send it. */
    fun request(mediaSessionId: Long, requestId: Int, rate: Double): String =
        """{"type":"SET_PLAYBACK_RATE","requestId":$requestId,"mediaSessionId":$mediaSessionId,"playbackRate":${format(rate)}}"""

    /**
     * What a media-namespace message says about our request [requestId] for [rate]; null when it
     * is not the reply to it. A MEDIA_STATUS reply is judged on its rate: one the receiver did not
     * move to (or none, read as 1) is [CastSpeedResult.Ignored].
     */
    fun classify(payload: JsonObject, requestId: Int, rate: Double): CastSpeedResult? {
        if (payload.long("requestId") != requestId.toLong()) return null
        val type = payload.string("type") ?: return null
        if (type != "MEDIA_STATUS") return CastSpeedResult.Refused(type, payload.string("reason"))
        val reported = CastStatusParser.mediaEntries(payload).firstOrNull()?.number("playbackRate")
        return if (same(reported ?: 1.0, rate)) CastSpeedResult.Accepted else CastSpeedResult.Ignored(reported)
    }

    /** The offered rate a reported one stands for, so the picker can tick it; null when it is none of them. */
    fun offered(rate: Double): Double? = RATES.firstOrNull { same(it, rate) }

    fun same(a: Double, b: Double): Boolean = abs(a - b) < TOLERANCE

    /** "1.5", "0.75", "2": the shortest decimal, the same in every locale. */
    fun format(rate: Double): String =
        String.format(Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.')

    /** For the event log: the receiver's app id, the package in front, the rate and how it went. Never a title. */
    fun logLine(appId: String?, foreground: String?, rate: Double, result: CastSpeedResult): String {
        val outcome = when (result) {
            CastSpeedResult.Accepted -> "accepted"
            is CastSpeedResult.Refused -> "refused (${result.type}${result.reason?.let { ", $it" } ?: ""})"
            is CastSpeedResult.Ignored -> "ignored (rate stayed ${result.reportedRate?.let(::format) ?: "unreported"})"
            CastSpeedResult.NoAnswer -> "no answer in ${ANSWER_MS / 1000} s"
            CastSpeedResult.NoSession -> "no media session"
        }
        return "speed ${format(rate)}x for ${appId ?: "no app"} (${foreground ?: "package unknown"}): $outcome"
    }
}
