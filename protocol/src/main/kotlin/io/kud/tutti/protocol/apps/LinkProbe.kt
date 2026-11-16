package io.kud.tutti.protocol.apps

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A developer probe for app deep links (Settings › Advanced › Technical details): sends one link
 * as an Android TV Remote v2 `RemoteAppLinkLaunchRequest` and reports, in words, what the TV said
 * it showed before and after. Nothing here can see *inside* an app: when YouTube is already in
 * front, a link that opened Subscriptions and one the app ignored both read [LinkOutcome.SAME_APP],
 * so the person holding the remote is the last check.
 */
object LinkProbe {
    /** Longest link accepted; the proto field has no limit, the TV's intent parser does. */
    const val MAX_LENGTH = 2_048

    /** How long the foreground app is watched after a link goes out. */
    const val WATCH_MS = 3_000L

    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:\\S+$")

    /** A link with a scheme, no whitespace, within [MAX_LENGTH]; anything else is never sent. */
    fun valid(link: String): Boolean = link.length <= MAX_LENGTH && scheme.matches(link)

    /**
     * Candidate YouTube links to try on the TV, all unverified on a BRAVIA: which of them the
     * Android TV app routes to a tab is exactly what the probe is for.
     */
    val YOUTUBE_PRESETS: List<String> = listOf(
        "https://www.youtube.com/feed/subscriptions",
        "https://www.youtube.com/feed/history",
        "https://www.youtube.com/feed/library",
        "https://www.youtube.com/playlist?list=WL",
        "https://www.youtube.com/results?search_query=beans+on+toast",
        "https://www.youtube.com/@YouTube",
        "https://www.youtube.com/watch?v=jNQXAC9IVRw",
        "vnd.youtube://jNQXAC9IVRw",
        "vnd.youtube.launch://",
    )

    /**
     * Sends [link] through [send] and watches [foreground] for [watchMs]. [canObserve] false
     * means no remote link reports `app_info`, so the answer stops at "sent".
     */
    suspend fun probe(
        link: String,
        send: suspend (String) -> Boolean,
        foreground: Flow<String?>,
        canObserve: () -> Boolean,
        watchMs: Long = WATCH_MS,
    ): LinkProbeResult {
        if (!valid(link)) return LinkProbeResult(LinkOutcome.INVALID, before = null, after = null)
        val observing = canObserve()
        val before = if (observing) foreground.first() else null
        if (!send(link)) return LinkProbeResult(LinkOutcome.NOT_SENT, before, after = null)
        if (!observing) return LinkProbeResult(LinkOutcome.SENT_UNOBSERVED, before = null, after = null)
        var after = before
        // Watch the whole window: an app may pass through a splash or the launcher first.
        withTimeoutOrNull(watchMs) { foreground.collect { after = it } }
        val outcome = when {
            after == null -> LinkOutcome.NO_APP
            after == before -> LinkOutcome.SAME_APP
            else -> LinkOutcome.OPENED
        }
        return LinkProbeResult(outcome, before, after)
    }
}

enum class LinkOutcome {
    /** Not a link (no scheme, whitespace, too long): never sent. */
    INVALID,

    /** The remote link refused it (not paired, not ready, write failed). */
    NOT_SENT,

    /** Sent, but no remote link reports what the TV shows. */
    SENT_UNOBSERVED,

    /** Sent, and a different app came to the front. */
    OPENED,

    /** Sent, and the same app stayed in front: it may have navigated or ignored it. */
    SAME_APP,

    /** Sent, and the TV reported no app in front afterwards. */
    NO_APP,
}

/** What the probe saw. Package names only; the link itself is never kept or logged here. */
data class LinkProbeResult(val outcome: LinkOutcome, val before: String?, val after: String?)
