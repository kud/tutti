package io.kud.tutti.protocol.cast

/**
 * The Cast plane's lines for developer mode's event log (Erwann, 2026-11-11: « Parfois je vois le
 * metadata, parfois je vois pas »): enough to tell a session drop from a lost transport, a silent
 * receiver from a status without a title, and a hold cleared by another plane from one that ran
 * out. Built from classes, states and presence flags only: never a title, an episode, an image
 * URL, a content id, a transport or session id, or a position. Each line still goes through
 * [io.kud.tutti.protocol.diagnostic.LogScrubber] when recorded.
 */
object CastLogLines {
    fun connecting(): String = "connecting"

    fun ready(millis: Long): String = "session ready in $millis ms"

    fun ended(end: CastEnd, retryMillis: Long): String = "session ended (${endClass(end)}), next try in ${retryMillis / 1000} s"

    /** The app in front changed. Only the receiver's app id: the transport id is a session handle, not a fact. */
    fun app(previous: CastApp?, next: CastApp?): String = when {
        next == null -> "no media app in front (was ${previous?.appId ?: "none"})"
        previous == null -> "media app ${next.appId} in front"
        previous.appId == next.appId -> "media app ${next.appId}: new transport"
        else -> "media app ${previous.appId} -> ${next.appId}"
    }

    fun transportClosed(app: CastApp?): String = "media app ${app?.appId ?: "none"}: transport closed, asking the receiver again"

    fun askReceiver(): String = "no media app known, asking the receiver again"

    /**
     * One media status: "PLAYING, title yes, position yes, duration yes, images yes, first image
     * allowed yes, answer, same session: playing". [hadTitle] and [artwork] say what was there and
     * whether the image passed [CastArtwork.isAllowed], never the title or the URL.
     */
    fun media(reading: CastReading?, hadTitle: Boolean, report: CastReport, artwork: CastArtworkSeen? = null): String {
        val outcome = when (report) {
            is CastReport.Playing -> "playing"
            CastReport.Stopped -> "stopped"
            CastReport.Unknown -> "unknown"
        }
        if (reading == null) return "media status empty: $outcome"
        return listOf(
            reading.playerState,
            "title ${yes(hadTitle)}",
            "position ${yes(reading.currentTime != null)}",
            if (reading.mediaIncluded) "duration ${yes(reading.duration != null)}" else "no media",
            "images ${yes(artwork?.listed == true)}",
            "first image allowed ${yes(artwork?.firstAllowed == true)}",
            if (reading.answer) "answer" else "unasked",
            if (reading.sameSession) "same session" else "new session",
        ).joinToString() + ": $outcome"
    }

    /** Why a held item went before its grace ran out: the plane that proved it over. */
    fun clearedBy(reason: CastHold.ClearReason, foreground: String?): String = when (reason) {
        CastHold.ClearReason.STANDBY -> "shown item cleared: TV on standby"
        CastHold.ClearReason.FOREGROUND -> "shown item cleared: ${foreground ?: "another package"} in front"
        CastHold.ClearReason.SOURCE -> "shown item cleared: a tuner or input is playing"
    }

    fun graceRanOut(): String = "shown item dropped: no Cast report for ${CastHold.GRACE_MS / 1000} s"

    private fun endClass(end: CastEnd): String = when (end) {
        is CastEnd.Untrusted -> "untrusted, ${end.reason}"
        is CastEnd.Closed -> "closed, ${end.kind}"
        CastEnd.Malformed -> "malformed"
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"
}
