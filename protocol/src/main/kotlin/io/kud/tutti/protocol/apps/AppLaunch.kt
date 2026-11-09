package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.AppCatalog
import io.kud.tutti.protocol.atv.AppEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One way of asking the TV to open an app. */
sealed interface LaunchRoute {
    /** Sony IP control's `appControl.setActiveApp`, with the uri the TV itself listed. */
    data class Rest(val uri: String) : LaunchRoute

    /** Android TV Remote v2 `RemoteAppLinkLaunchRequest`. */
    data class AppLink(val link: String) : LaunchRoute
}

enum class LaunchVerdict {
    /** The TV reported the app in the foreground. */
    CONFIRMED,

    /** A route was accepted but nothing can tell us what the TV shows (no remote link). */
    SENT,

    /** No route was accepted, or none brought the app up in time. */
    FAILED,
}

/**
 * Where IP control stands when a launch is planned, as the controller sees it. Kept apart from the
 * app's link enum so the plan stays pure; [CONNECTING] and [NOT_ANSWERING] are a link that is set
 * up and may come up, the rest will not change by waiting.
 */
enum class IpControl {
    /** No PSK and no code cookie: there is nothing to talk to the TV with. */
    NOT_SET_UP,

    /** Android's local-network permission is missing, so no socket can open. */
    NO_LAN_PERMISSION,

    /** Set up, first attempt under way (a fresh session, a resume). */
    CONNECTING,

    /** Set up, and the last attempt or poll went unanswered; the loop is in backoff. */
    NOT_ANSWERING,

    /** The TV refused the PSK or the code's cookie. */
    KEY_REFUSED,

    /** The TV presented another certificate than the pinned one. */
    CERTIFICATE_CHANGED,

    ONLINE,
}

/**
 * Why IP control's `setActiveApp` was not among the routes. Each says which side it is on, so a
 * trace tells "never set up" from "set up, but not up at that moment" (Erwann's Nuvio trace,
 * 2026-11-09, read "IP control offline" and could have been either).
 */
enum class RestSkip(val words: String) {
    NOT_SET_UP("IP control not set up"),
    NO_LAN_PERMISSION("local network permission missing"),
    CONNECTING("IP control set up, still connecting"),
    OFFLINE("IP control set up, not answering"),
    KEY_REFUSED("IP control key refused"),
    CERTIFICATE_CHANGED("IP control certificate changed"),
    NOT_LISTED("app never listed by the TV"),
}

/** The launch held for IP control to come up: how long, and whether it did. */
data class RestWait(val waitedMs: Long, val cameUp: Boolean)

/** One route tried: whether it went out, and what the TV showed when we stopped watching. */
data class LaunchAttempt(val route: LaunchRoute, val sent: Boolean, val foreground: String?)

data class LaunchResult(
    val packageName: String,
    val verdict: LaunchVerdict,
    val attempts: List<LaunchAttempt>,
    /** Why the REST route wasn't tried; null when it was, or when there is no IP control at all. */
    val restSkipped: RestSkip? = null,
    /** Set when the launch held for a set-up IP control that wasn't up yet. */
    val restWait: RestWait? = null,
) {
    val ok: Boolean get() = verdict != LaunchVerdict.FAILED

    /**
     * The developer trace: every route, the link sent and the foreground package seen. Package
     * names and public app links only; the REST uri is the TV's own app id, scrubbed anyway.
     */
    fun trace(): String = buildString {
        append("launch ").append(packageName).append(": ").append(verdict.name.lowercase())
        if (attempts.isEmpty()) append(" (no route)")
        restWait?.let { append("\nrest waited ").append(it.waitedMs).append(" ms for IP control: ").append(if (it.cameUp) "up" else "still down") }
        restSkipped?.let { append("\nrest setActiveApp skipped: ").append(it.words) }
        attempts.forEachIndexed { index, attempt ->
            append('\n').append(index + 1).append(". ")
            when (val route = attempt.route) {
                is LaunchRoute.Rest -> append("rest setActiveApp ").append(route.uri)
                is LaunchRoute.AppLink -> append("remote app link ").append(route.link)
            }
            append(if (attempt.sent) " - sent" else " - not sent")
            if (attempt.sent) append(", foreground ").append(attempt.foreground ?: "unknown")
        }
    }
}

/** Which routes to try, in order. Pure. */
object LaunchPlan {
    /**
     * Why the REST route can't go: IP control's own state first, then an app the TV never listed.
     * Null when it can go.
     */
    fun restSkip(ip: IpControl, restUri: String?): RestSkip? = when (ip) {
        IpControl.NOT_SET_UP -> RestSkip.NOT_SET_UP
        IpControl.NO_LAN_PERMISSION -> RestSkip.NO_LAN_PERMISSION
        IpControl.CONNECTING -> RestSkip.CONNECTING
        IpControl.NOT_ANSWERING -> RestSkip.OFFLINE
        IpControl.KEY_REFUSED -> RestSkip.KEY_REFUSED
        IpControl.CERTIFICATE_CHANGED -> RestSkip.CERTIFICATE_CHANGED
        IpControl.ONLINE -> if (restUri == null) RestSkip.NOT_LISTED else null
    }

    /**
     * Whether a launch should hold, briefly, for IP control to come up before it plans: only a
     * link that is set up and merely not up this instant. `setActiveApp` with the TV's own uri is
     * the one route that opens any installed app, sideloaded or not, so a launch that fires a
     * moment too early and falls back to `market://launch` loses the route that would have worked.
     */
    fun waitsForRest(ip: IpControl): Boolean = ip == IpControl.CONNECTING || ip == IpControl.NOT_ANSWERING

    /** How long a launch holds for IP control ([waitsForRest]): one HTTPS discover on a LAN, with room. */
    const val REST_WAIT_MS = 2_500L

    /**
     * IP control first when it answers and the TV ever listed the app (Sony's own launcher), then
     * every app link the catalogue knows for it, ending on `market://launch?id=`.
     */
    fun routes(packageName: String, restOnline: Boolean, restUri: String?, entry: AppEntry? = AppCatalog.byPackage(packageName)): List<LaunchRoute> {
        val links = entry?.launchLinks ?: listOf(AppEntry.marketLink(packageName))
        return buildList {
            if (restOnline && restUri != null) add(LaunchRoute.Rest(restUri))
            links.distinct().forEach { add(LaunchRoute.AppLink(it)) }
        }
    }
}

/**
 * Sends each route in turn and watches [foreground] (the remote link's `app_info`) for
 * [confirmMs]; moves on when the app doesn't come up. When nothing can be observed
 * ([canObserve] false), the first accepted route is the answer: firing every link blind could
 * open a store page on top of a launch that worked.
 */
class AppLauncher(
    private val send: suspend (LaunchRoute) -> Boolean,
    private val foreground: Flow<String?>,
    private val canObserve: () -> Boolean,
    private val confirmMs: Long = CONFIRM_MS,
) {
    suspend fun launch(packageName: String, routes: List<LaunchRoute>, restSkipped: RestSkip? = null): LaunchResult =
        attempt(packageName, routes).copy(restSkipped = restSkipped)

    private suspend fun attempt(packageName: String, routes: List<LaunchRoute>): LaunchResult {
        val attempts = mutableListOf<LaunchAttempt>()
        for (route in routes) {
            if (!send(route)) {
                attempts += LaunchAttempt(route, sent = false, foreground = null)
                continue
            }
            if (!canObserve()) {
                attempts += LaunchAttempt(route, sent = true, foreground = null)
                return LaunchResult(packageName, LaunchVerdict.SENT, attempts)
            }
            var last: String? = null
            val seen = withTimeoutOrNull(confirmMs) {
                foreground.first { app ->
                    last = app
                    app == packageName
                }
            }
            attempts += LaunchAttempt(route, sent = true, foreground = seen ?: last)
            if (seen != null) return LaunchResult(packageName, LaunchVerdict.CONFIRMED, attempts)
        }
        return LaunchResult(packageName, LaunchVerdict.FAILED, attempts)
    }

    companion object {
        /** How long a route gets to bring the app up before the next one is tried. */
        const val CONFIRM_MS = 3_000L
    }
}
