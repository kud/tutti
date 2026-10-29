package io.kud.tutti.protocol.apps

/** One per-app command in the playback sheet: a link the TV's app may route to one of its tabs. */
enum class AppShortcut(val link: String) {
    SUBSCRIPTIONS("https://www.youtube.com/feed/subscriptions"),
    HISTORY("https://www.youtube.com/feed/history"),
    WATCH_LATER("https://www.youtube.com/playlist?list=WL"),
    LIBRARY("https://www.youtube.com/feed/library"),
}

/**
 * The commands offered for the app in front, sent as Remote v2 app links. Shipped untested on the
 * BRAVIA (Erwann, 2026-10-29: « Essayons à l'aveugle ? »): a link the app ignores only brings
 * YouTube to the front, which is harmless, and Erwann's report decides which stay.
 */
object AppShortcuts {
    private val youtube = setOf("com.google.android.youtube.tv", "com.google.android.youtube")

    fun forPackage(packageName: String?): List<AppShortcut> =
        if (packageName in youtube) AppShortcut.entries.toList() else emptyList()
}
