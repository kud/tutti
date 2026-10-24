package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.V2Key
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One way of asking the TV for its own apps screen, over the remote link. */
sealed interface AppsScreenRoute {
    /** `RemoteAppLinkLaunchRequest` carrying an `intent:` URI. */
    data class Link(val link: String) : AppsScreenRoute

    /** A key inject. */
    data class Key(val keyCode: Int) : AppsScreenRoute
}

enum class AppsScreenVerdict {
    /** The TV reported its launcher in the foreground after a route. */
    OPENED,

    /** Sent, but nothing could tell: the launcher was already in front, so a change can't show. */
    SENT,

    /** Sent, and the launcher never came up. */
    FAILED,

    /** The link refused every route. */
    NOT_SENT,
}

data class AppsScreenAttempt(val route: AppsScreenRoute, val sent: Boolean, val foreground: String?)

data class AppsScreenResult(val verdict: AppsScreenVerdict, val attempts: List<AppsScreenAttempt>) {
    /** The developer trace, like [LaunchResult.trace]: public component names and keycodes only. */
    fun trace(): String = buildString {
        append("apps screen: ").append(verdict.name.lowercase())
        attempts.forEachIndexed { index, attempt ->
            append('\n').append(index + 1).append(". ")
            when (val route = attempt.route) {
                is AppsScreenRoute.Link -> append("remote app link ").append(route.link)
                is AppsScreenRoute.Key -> append("key ").append(route.keyCode)
            }
            append(if (attempt.sent) " - sent" else " - not sent")
            if (attempt.sent) append(", foreground ").append(attempt.foreground ?: "unknown")
        }
    }
}

/**
 * Google TV's "Your apps" screen. Every name here is **unverified on the XR80** (see
 * docs/architecture.md): the launcher package is Google TV Home's public id; the apps activity
 * is a community-reported name no public source confirms; `ACTION_ALL_APPS` and the keycode are
 * AOSP's, and AOSP turns KEYCODE_ALL_APPS into exactly that action aimed at the home package.
 */
object AppsScreen {
    const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"

    /** Unconfirmed: if the class is wrong the TV starts nothing and the next route runs. */
    const val APPS_ACTIVITY = "$GOOGLE_TV_LAUNCHER/.coreapps.CoreAppsActivity"

    /** `Intent.ACTION_ALL_APPS`. */
    const val ACTION_ALL_APPS = "android.intent.action.ALL_APPS"

    /** `Intent.FLAG_ACTIVITY_NEW_TASK`: the TV's remote service starts it from outside any task. */
    private const val NEW_TASK = 0x10000000

    /** An `Intent.toUri(URI_INTENT_SCHEME)` string for an explicit component. */
    fun componentLink(component: String): String {
        require(component.count { it == '/' } == 1 && !component.startsWith('/') && !component.endsWith('/')) { "not a component: $component" }
        return "intent:#Intent;launchFlags=0x${NEW_TASK.toString(16)};component=$component;end"
    }

    /** An `Intent.toUri(URI_INTENT_SCHEME)` string for an action aimed at one package. */
    fun actionLink(action: String, packageName: String): String =
        "intent:#Intent;action=$action;launchFlags=0x${NEW_TASK.toString(16)};package=$packageName;end"

    /**
     * Most specific first: the apps activity itself, then the action the keycode would produce
     * (it also covers a remote service that drops keycode 284 before it reaches the policy), then
     * the keycode as it shipped before.
     */
    fun routes(): List<AppsScreenRoute> = listOf(
        AppsScreenRoute.Link(componentLink(APPS_ACTIVITY)),
        AppsScreenRoute.Link(actionLink(ACTION_ALL_APPS, GOOGLE_TV_LAUNCHER)),
        AppsScreenRoute.Key(V2Key.ALL_APPS),
    )
}

/**
 * Sends each route and watches [foreground] (the remote link's `app_info`) for [confirmMs],
 * moving on when the launcher doesn't come up. The foreground is a package, so a screen inside
 * the launcher is invisible: with the launcher already in front ([current] equal to
 * [launcherPackage]) nothing can confirm a route, and every route is sent in turn. They all aim
 * at the same screen of the app already showing, so a second one can at worst open it again.
 */
class AppsScreenOpener(
    private val send: suspend (AppsScreenRoute) -> Boolean,
    private val foreground: Flow<String?>,
    private val current: () -> String?,
    private val launcherPackage: String = AppsScreen.GOOGLE_TV_LAUNCHER,
    private val confirmMs: Long = CONFIRM_MS,
) {
    suspend fun open(routes: List<AppsScreenRoute> = AppsScreen.routes()): AppsScreenResult {
        val attempts = mutableListOf<AppsScreenAttempt>()
        val blind = current() == launcherPackage
        for (route in routes) {
            if (!send(route)) {
                attempts += AppsScreenAttempt(route, sent = false, foreground = null)
                continue
            }
            if (blind) {
                attempts += AppsScreenAttempt(route, sent = true, foreground = null)
                continue
            }
            var last: String? = null
            val seen = withTimeoutOrNull(confirmMs) {
                foreground.first { app ->
                    last = app
                    app == launcherPackage
                }
            }
            attempts += AppsScreenAttempt(route, sent = true, foreground = seen ?: last)
            if (seen != null) return AppsScreenResult(AppsScreenVerdict.OPENED, attempts)
        }
        val verdict = when {
            attempts.none { it.sent } -> AppsScreenVerdict.NOT_SENT
            blind -> AppsScreenVerdict.SENT
            else -> AppsScreenVerdict.FAILED
        }
        return AppsScreenResult(verdict, attempts)
    }

    companion object {
        /** How long a route gets to bring the launcher up before the next one is tried. */
        const val CONFIRM_MS = 2_000L
    }
}
