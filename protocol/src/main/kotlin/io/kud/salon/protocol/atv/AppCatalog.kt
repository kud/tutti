package io.kud.salon.protocol.atv

data class AppEntry(
    val id: String,
    val name: String,
    val packageName: String,
    /** Explicit launch URI; defaults to the Play Store launch link. */
    val link: String? = null,
) {
    /** `market://launch?id=<package>` boots the installed app (or its store page). Verified on Google TV. */
    val launchLink: String get() = link ?: "market://launch?id=$packageName"
}

/**
 * Curated Google TV apps. Data copied from kud/gtv (src/apps.ts), best-effort package names
 * from Play Store URLs.
 */
object AppCatalog {
    val apps: List<AppEntry> = listOf(
        AppEntry("netflix", "Netflix", "com.netflix.ninja"),
        AppEntry("youtube", "YouTube", "com.google.android.youtube.tv"),
        AppEntry("primevideo", "Prime Video", "com.amazon.amazonvideo.livingroom"),
        AppEntry("plex", "Plex", "com.plexapp.android"),
        AppEntry("putio", "Put.io", "io.put.putio"),
        AppEntry("arte", "Arte", "tv.arte.plus7"),
        AppEntry("disney", "Disney+", "com.disney.disneyplus"),
        AppEntry("spotify", "Spotify", "com.spotify.tv.android"),
        AppEntry("twitch", "Twitch", "tv.twitch.android.app"),
        AppEntry("max", "Max", "com.wbd.stream"),
        AppEntry("appletv", "Apple TV", "com.apple.atve.androidtv.appletv"),
        AppEntry("crunchyroll", "Crunchyroll", "com.crunchyroll.crunchyroid"),
        AppEntry("paramount", "Paramount+", "com.cbs.ott"),
        AppEntry("peacock", "Peacock", "com.peacocktv.peacockandroid"),
        // Dedicated Android TV packages, distinct from the phone apps.
        AppEntry("ytmusic", "YouTube Music", "com.google.android.youtube.tvmusic"),
        AppEntry("jellyfin", "Jellyfin", "org.jellyfin.androidtv"),
        AppEntry("kodi", "Kodi", "org.xbmc.kodi"),
        AppEntry("vlc", "VLC", "org.videolan.vlc"),
        AppEntry("deezer", "Deezer", "deezer.android.tv"),
    )

    /** By catalog id or display name, case-insensitive. */
    fun find(query: String): AppEntry? {
        val q = query.trim().lowercase()
        return apps.firstOrNull { it.id == q || it.name.lowercase() == q }
    }

    fun byPackage(packageName: String): AppEntry? = apps.firstOrNull { it.packageName == packageName }
}
