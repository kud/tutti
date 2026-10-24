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

/** One route tried: whether it went out, and what the TV showed when we stopped watching. */
data class LaunchAttempt(val route: LaunchRoute, val sent: Boolean, val foreground: String?)

data class LaunchResult(val packageName: String, val verdict: LaunchVerdict, val attempts: List<LaunchAttempt>) {
    val ok: Boolean get() = verdict != LaunchVerdict.FAILED

    /**
     * The developer trace: every route, the link sent and the foreground package seen. Package
     * names and public app links only; the REST uri is the TV's own app id, scrubbed anyway.
     */
    fun trace(): String = buildString {
        append("launch ").append(packageName).append(": ").append(verdict.name.lowercase())
        if (attempts.isEmpty()) append(" (no route)")
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
    suspend fun launch(packageName: String, routes: List<LaunchRoute>): LaunchResult {
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
