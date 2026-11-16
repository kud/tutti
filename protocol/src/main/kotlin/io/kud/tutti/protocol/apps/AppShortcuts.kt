package io.kud.tutti.protocol.apps

/** One per-app command in the playback sheet: a link the TV's app may route to one of its tabs. */
enum class AppShortcut(val link: String) {
    /**
     * YouTube's search, with an empty query (Erwann, 2026-11-06: « ajoute la recherche YouTube »).
     * Unverified on the BRAVIA: a third-party TV assistant sends `results?search_query=` to the TV
     * app; with nothing to search the hope is its search screen, ready for tutti's keyboard.
     */
    SEARCH("https://www.youtube.com/results?search_query="),
    SUBSCRIPTIONS("https://www.youtube.com/feed/subscriptions"),
    HISTORY("https://www.youtube.com/feed/history"),
    LIBRARY("https://www.youtube.com/feed/library"),
}

/**
 * The commands offered for the app in front, sent as Remote v2 app links. Shipped untested on the
 * BRAVIA (Erwann, 2026-10-29: « Essayons à l’aveugle ? »); the three tabs open there, search is still unverified. Watch
 * later (`playlist?list=WL`) only brought YouTube forward and was dropped: it sits under Library.
 */
object AppShortcuts {
    private val youtube = setOf("com.google.android.youtube.tv", "com.google.android.youtube")

    fun forPackage(packageName: String?): List<AppShortcut> =
        if (packageName in youtube) AppShortcut.entries.toList() else emptyList()
}
