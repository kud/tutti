package io.kud.tutti.protocol.atv

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
 * Curated Google TV apps, the ones a BRAVIA in France is likely to carry. Every package name was
 * checked on its Google Play page (gl=FR) and shows "TV" among the devices it installs on
 * (2026-10-23). Started from kud/gtv (src/apps.ts).
 */
object AppCatalog {
    val apps: List<AppEntry> = listOf(
        AppEntry("netflix", "Netflix", "com.netflix.ninja"),
        AppEntry("youtube", "YouTube", "com.google.android.youtube.tv"),
        AppEntry("primevideo", "Prime Video", "com.amazon.amazonvideo.livingroom"),
        AppEntry("disney", "Disney+", "com.disney.disneyplus"),
        AppEntry("appletv", "Apple TV", "com.apple.atve.androidtv.appletv"),
        AppEntry("canal", "Canal+", "com.canal.android.canal"),
        // Renamed back from Max in 2025; the id stays so saved choices keep resolving.
        AppEntry("max", "HBO Max", "com.wbd.stream"),
        AppEntry("francetv", "france.tv", "fr.francetv.pluzz"),
        AppEntry("tf1", "TF1+", "fr.tf1.mytf1"),
        AppEntry("m6", "M6+", "fr.m6.m6replay"),
        AppEntry("arte", "Arte", "tv.arte.plus7"),
        AppEntry("molotov", "Molotov", "tv.molotov.app"),
        // The international Paramount+ (com.cbs.ott is the US app, not offered on TV in France).
        AppEntry("paramount", "Paramount+", "com.cbs.ca"),
        AppEntry("mubi", "MUBI", "com.mubi"),
        AppEntry("crunchyroll", "Crunchyroll", "com.crunchyroll.crunchyroid"),
        AppEntry("twitch", "Twitch", "tv.twitch.android.app"),
        AppEntry("spotify", "Spotify", "com.spotify.tv.android"),
        AppEntry("deezer", "Deezer", "deezer.android.app"),
        // Dedicated Android TV packages, distinct from the phone apps.
        AppEntry("ytmusic", "YouTube Music", "com.google.android.youtube.tvmusic"),
        AppEntry("jellyfin", "Jellyfin", "org.jellyfin.androidtv"),
        AppEntry("plex", "Plex", "com.plexapp.android"),
        AppEntry("kodi", "Kodi", "org.xbmc.kodi"),
        AppEntry("vlc", "VLC", "org.videolan.vlc"),
        AppEntry("putio", "Put.io", "io.put.putio"),
    )

    /** By catalog id or display name, case-insensitive. */
    fun find(query: String): AppEntry? {
        val q = query.trim().lowercase()
        return apps.firstOrNull { it.id == q || it.name.lowercase() == q }
    }

    fun byPackage(packageName: String): AppEntry? = apps.firstOrNull { it.packageName == packageName }
}
