package io.kud.tutti.protocol.atv

data class AppEntry(
    val id: String,
    val name: String,
    val packageName: String,
    /**
     * Deep links the app answers to, tried first. Only forms documented by Home Assistant's
     * Android TV Remote integration (its "Launching apps" table, 2026-10); never guessed.
     */
    val links: List<String> = emptyList(),
    /**
     * The phone app's own package, where it differs from [packageName], for the phone-icon
     * fallback (`PhoneIcons`). Only names whose Google Play page (gl=FR) shows "Phone" among the
     * devices it installs on; never guessed. When the TV package itself installs on phones it
     * already counts, so this stays empty.
     */
    val phonePackages: List<String> = emptyList(),
) {
    /** Every link to try, in order: the deep links, then the Play Store launch link as fallback. */
    val launchLinks: List<String> get() = links + marketLink(packageName)

    /** The first link tried. */
    val launchLink: String get() = launchLinks.first()

    companion object {
        /**
         * What androidtvremote2 sends for a bare package id. Home Assistant documents that a Play
         * Store change "is currently preventing this method from working for many apps", so it is
         * the last resort, not the default.
         */
        fun marketLink(packageName: String) = "market://launch?id=$packageName"
    }
}

/**
 * Curated Google TV apps, the ones a BRAVIA in France is likely to carry. Every package name was
 * checked on its Google Play page (gl=FR) and shows "TV" among the devices it installs on
 * (2026-10-23). Started from kud/gtv (src/apps.ts). The phone packages were checked the same way
 * (2026-10-25): every TV package not listed with one also shows "Phone" on its own page, except
 * Prime Video's, Spotify's and Put.io's, which are TV only; Put.io's phone app is not verified.
 */
object AppCatalog {
    val apps: List<AppEntry> = listOf(
        AppEntry("netflix", "Netflix", "com.netflix.ninja", listOf("netflix://"), phonePackages = listOf("com.netflix.mediaclient")),
        AppEntry("youtube", "YouTube", "com.google.android.youtube.tv", listOf("https://www.youtube.com", "vnd.youtube.launch://"), phonePackages = listOf("com.google.android.youtube")),
        AppEntry("primevideo", "Prime Video", "com.amazon.amazonvideo.livingroom", listOf("https://app.primevideo.com"), phonePackages = listOf("com.amazon.avod.thirdpartyclient")),
        AppEntry("disney", "Disney+", "com.disney.disneyplus", listOf("https://www.disneyplus.com")),
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
        AppEntry("twitch", "Twitch", "tv.twitch.android.app", listOf("twitch://home")),
        AppEntry("spotify", "Spotify", "com.spotify.tv.android", phonePackages = listOf("com.spotify.music")),
        AppEntry("deezer", "Deezer", "deezer.android.app"),
        // Dedicated Android TV packages, distinct from the phone apps.
        AppEntry("ytmusic", "YouTube Music", "com.google.android.youtube.tvmusic", phonePackages = listOf("com.google.android.apps.youtube.music")),
        AppEntry("jellyfin", "Jellyfin", "org.jellyfin.androidtv", phonePackages = listOf("org.jellyfin.mobile")),
        AppEntry("plex", "Plex", "com.plexapp.android", listOf("plex://")),
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
