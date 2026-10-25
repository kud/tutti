package io.kud.tutti.protocol.apps

/**
 * The app on the TV now and the one before it, for long-press Back ("revenir à l'app d'avant",
 * the living room's alt-tab). Fed with every foreground package the remote v2 reports. The
 * launcher is a place you pass through, not an app you go back to: Netflix, Home, YouTube leaves
 * Netflix as the previous app. A null report (no package yet, a dropped link) changes nothing.
 * Going back is an ordinary launch, so once it lands the two swap and a second long press returns.
 * Pure.
 */
data class AppHistory(val current: String? = null, val previous: String? = null) {
    fun seen(packageName: String?): AppHistory = when {
        packageName.isNullOrBlank() -> this
        packageName in PASS_THROUGH -> this
        packageName == current -> this
        else -> AppHistory(current = packageName, previous = current)
    }

    /** The app long-press Back opens: the previous one, never the one already in front. */
    fun back(): String? = previous?.takeIf { it != current }

    companion object {
        /** Packages that are never "an app before": Google TV Home and the older Android TV launcher. */
        val PASS_THROUGH = setOf(AppsScreen.GOOGLE_TV_LAUNCHER, "com.google.android.tvlauncher", "com.android.tv.settings")
    }
}
