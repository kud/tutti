package io.kud.tutti.protocol.cast

/**
 * What one Cast message (or the lack of one) says about what is playing. Silence is not "nothing
 * playing": a reconnect, a status between two items or an answer still on its way says nothing
 * either way, and only [CastHold] decides how long the last confirmed item outlives it.
 */
sealed interface CastReport {
    /** What to show if this report stood alone: the item for [Playing], null otherwise. */
    val nowPlaying: CastNowPlaying? get() = null

    /** The receiver reports an item with a title, playing, paused or buffering. */
    data class Playing(override val nowPlaying: CastNowPlaying) : CastReport

    /**
     * Confirmed: the item shown is over. Another app is in front, the item was stopped or failed
     * (IDLE with CANCELLED or ERROR), or a different media session is idle.
     */
    data object Stopped : CastReport

    /**
     * No usable answer: the session is (re)connecting or just ended, the status list is empty, the
     * app's transport closed or changed, the item is IDLE between two items, or a new item has no
     * title yet. The last confirmed item may stand for [CastHold.GRACE_MS].
     */
    data object Unknown : CastReport
}

/**
 * What the other planes say about the TV, for [CastHold.onContext]. [foreground] is the Remote v2
 * package (null when not reported); [source] is the tuner or input REST reports playing (null when
 * none, which is what an app in front gives).
 */
data class CastContext(
    val standby: Boolean = false,
    val foreground: String? = null,
    val source: String? = null,
)

/**
 * Keeps the last confirmed Cast item through short gaps, so the card doesn't fall back to the bare
 * app line and come back a second later (Erwann, 2026-10-28: « parfois ça re-switch vers "BRAVIA 8"
 * seulement, puis ça s'affiche YouTube »).
 *
 * - [CastReport.Playing] replaces what is shown at once.
 * - [CastReport.Stopped] clears it at once.
 * - [CastReport.Unknown] starts the grace window, if it isn't running yet: the item stays for
 *   [graceMillis] from the first unknown report, then goes. A later unknown never extends it, so
 *   stale data never outlives one window.
 * - A confirmed change elsewhere clears it at once ([onContext]): the TV on standby, another
 *   package in front (Home included: the launcher is a package), or a tuner or input playing.
 *
 * While held the item is unchanged: its position keeps running from the last reading through
 * [CastNowPlaying.positionAt], which already stops after [CastNowPlaying.MAX_RUN_MS], longer than
 * the window. Not thread-safe: the caller serialises.
 */
class CastHold(private val graceMillis: Long = GRACE_MS) {
    private var shown: CastNowPlaying? = null
    private var gapSince: Long? = null
    private var context = CastContext()
    private var foregroundAtReport: String? = null
    private var sourceAtReport: String? = null

    /** Folds [report] in; returns what to show now. */
    fun onReport(report: CastReport, nowMillis: Long): CastNowPlaying? {
        when (report) {
            is CastReport.Playing -> {
                shown = report.nowPlaying
                gapSince = null
                foregroundAtReport = context.foreground
                sourceAtReport = context.source
            }
            CastReport.Stopped -> clear()
            CastReport.Unknown -> if (shown != null && gapSince == null) gapSince = nowMillis
        }
        return at(nowMillis)
    }

    /**
     * Folds what the other planes say; returns true when that cleared the item. A package or source
     * that goes unreported (null) proves nothing; one that differs from when Cast last confirmed the
     * item does. A source that appears where there was none means the TV left the app for a tuner or
     * an input.
     */
    fun onContext(next: CastContext): Boolean {
        context = next
        if (shown == null) return false
        val foreground = foregroundAtReport
        val changed = next.standby ||
            (next.foreground != null && foreground != null && next.foreground != foreground) ||
            (next.source != null && next.source != sourceAtReport)
        if (changed) {
            clear()
            return true
        }
        if (foreground == null) foregroundAtReport = next.foreground
        return false
    }

    /** What to show at [nowMillis]: the item, or null once its grace window has run out. */
    fun at(nowMillis: Long): CastNowPlaying? {
        val since = gapSince
        if (since != null && nowMillis - since >= graceMillis) clear()
        return shown
    }

    /** When the held item goes, if nothing confirms it first; null when nothing is held. */
    val expiresAtMillis: Long? get() = gapSince?.let { it + graceMillis }

    fun clear() {
        shown = null
        gapSince = null
        foregroundAtReport = null
        sourceAtReport = null
    }

    companion object {
        /**
         * 12 s. The gaps it covers, from the sources' own cadence: a session that drops waits
         * [io.kud.tutti.protocol.cast.CastSession]'s first backoff (5 s) and then a TLS handshake,
         * device auth and two status round trips (about 1 to 2 s on a LAN), so about 7 s; an empty
         * or titleless status is asked again on the next heartbeat ([CastSession.HEARTBEAT_MS], 5 s),
         * not the 15 s refresh; a watcher that blinks off with the TV's power reading reconnects at
         * once. 12 s covers each with margin, and a real stop that no other plane confirms
         * (YouTube's player closed back to its own browse screen) shows for at most that long.
         */
        const val GRACE_MS = 12_000L
    }
}
