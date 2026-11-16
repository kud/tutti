package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import io.kud.tutti.R
import io.kud.tutti.protocol.atv.AppCatalog

/**
 * How an official logo becomes a coloured tile (Erwann, 2026-10-25: "tout en couleur partout").
 * [fill] covers the whole tile; [glyph] tints a single-colour mark (null keeps the logo's own
 * colours); [scale] is the logo's share of the tile along its longer side. [crop] marks the app's own
 * Play Store icon: a full square, drawn edge to edge and cropped like the phone's icon.
 */
class BrandFace(@DrawableRes val drawable: Int, val fill: Int, val glyph: Int?, val scale: Float, val crop: Boolean = false)

/** The app's own 512 px Play Store icon, full bleed; [ground] is its edge colour, behind the corners. */
private fun playIcon(@DrawableRes drawable: Int, ground: Long) = BrandFace(drawable, ground.toInt(), null, 1f, crop = true)

/** A Simple Icons mark on its own brand hex, in white or near-black, whichever reads (56%). */
private fun mark(@DrawableRes drawable: Int, hex: Long): BrandFace? {
    val fill = hex.toInt()
    val glyph = BrandColours.glyphOn(fill) ?: return null
    return BrandFace(drawable, fill, glyph, 0.56f)
}

/**
 * Official logos, keyed by [AppCatalog] id: Simple Icons marks with their recorded brand hex, and
 * full-colour logos from svgl and Wikimedia Commons in their own colours, and the app's own Play Store
 * icon where the logo alone didn't match the app. Every path or pixel is the source's own, unaltered;
 * we only scale it, and tint the single-colour marks as a whole. Never draw, trace or redraw a logo:
 * a catalog app with no logo from a recorded source keeps its letter tile until one is found.
 * Provenance: docs/brand-icons.md.
 */
private val faces: Map<String, BrandFace?> = mapOf(
    "netflix" to mark(R.drawable.brand_netflix, 0xFFE50914),
    "youtube" to mark(R.drawable.brand_youtube, 0xFFFF0000),
    "appletv" to mark(R.drawable.brand_appletv, 0xFF000000),
    "max" to mark(R.drawable.brand_hbomax, 0xFF000000),
    "paramount" to mark(R.drawable.brand_paramountplus, 0xFF0064FF),
    "mubi" to mark(R.drawable.brand_mubi, 0xFF000000),
    "crunchyroll" to mark(R.drawable.brand_crunchyroll, 0xFFFF5E00),
    "twitch" to mark(R.drawable.brand_twitch, 0xFF9146FF),
    "spotify" to mark(R.drawable.brand_spotify, 0xFF1ED760),
    "deezer" to mark(R.drawable.brand_deezer, 0xFFA238FF),
    "ytmusic" to mark(R.drawable.brand_youtubemusic, 0xFFFF0000),
    "jellyfin" to mark(R.drawable.brand_jellyfin, 0xFF00A4DC),
    "plex" to mark(R.drawable.brand_plex, 0xFFEBAF00),
    "kodi" to mark(R.drawable.brand_kodi, 0xFF17B2E7),
    "vlc" to mark(R.drawable.brand_vlcmediaplayer, 0xFFFF8800),
    // Simple Icons 16.36.0 (2026-10-27): apps Erwann's TV lists that aren't in the catalog.
    "channel4" to mark(R.drawable.brand_channel4, 0xFFAAFF89),
    "itvx" to mark(R.drawable.brand_itvx, 0xFFDEEB52),
    "localsend" to mark(R.drawable.brand_localsend, 0xFF008080),
    // Full-colour logos drawn for a light ground keep it; Put.io's own square is its ground.
    "primevideo" to BrandFace(R.drawable.logo_primevideo, BrandColours.WHITE, null, 0.8f),
    "disney" to BrandFace(R.drawable.logo_disneyplus, BrandColours.WHITE, null, 0.8f),
    "putio" to BrandFace(R.drawable.logo_putio, 0xFF323232.toInt(), null, 1f),
    // An official file on Wikimedia Commons: france.tv's black wordmark wants a light ground (a
    // neutral, not a brand colour).
    "francetv" to BrandFace(R.drawable.logo_francetv, BrandColours.WHITE, null, 0.8f),
    // The app's own Play Store icon, byte for byte (Erwann, 2026-10-25): the Commons reproductions
    // and Molotov's old wordmark didn't match the apps. Cropped edge to edge like the phone's icon.
    "molotov" to playIcon(R.drawable.play_molotov, 0xFFFFC107),
    "canal" to playIcon(R.drawable.play_canalplus, 0xFF2F2F2F),
    "tf1" to playIcon(R.drawable.play_tf1plus, 0xFF1937FF),
    "m6" to playIcon(R.drawable.play_m6plus, 0xFF000000),
    "arte" to playIcon(R.drawable.play_arte, 0xFFFC4700),
    // Play Store icons for TV apps outside the catalog (2026-11-06), keyed in [tvOnly] below.
    "channel5" to playIcon(R.drawable.play_channel5, 0xFF1F467D),
    "bbciplayer" to playIcon(R.drawable.play_bbciplayer, 0xFF000000),
    "bbcsounds" to playIcon(R.drawable.play_bbcsounds, 0xFF340100),
    "calman" to playIcon(R.drawable.play_calman, 0xFF4B646B),
    "amazonmusic" to playIcon(R.drawable.play_amazonmusic, 0xFF44EAF2),
    "tubi" to playIcon(R.drawable.play_tubi, 0xFF8001D8),
    "plutotv" to playIcon(R.drawable.play_plutotv, 0xFFFFF200),
    "rakutentv" to playIcon(R.drawable.play_rakutentv, 0xFFBF0000),
    "sonypicturescore" to playIcon(R.drawable.play_sonypicturescore, 0xFFFFFFFF),
    "dazn" to playIcon(R.drawable.play_dazn, 0xFF080E12),
    "now" to playIcon(R.drawable.play_now, 0xFF054A4F),
    "uktv" to playIcon(R.drawable.play_uktv, 0xFF111832),
    "discoveryplus" to playIcon(R.drawable.play_discoveryplus, 0xFF050F3F),
    "redbulltv" to playIcon(R.drawable.play_redbulltv, 0xFF00162B),
    "skynews" to playIcon(R.drawable.play_skynews, 0xFFFFFFFF),
    "zdf" to playIcon(R.drawable.play_zdf, 0xFF131B22),
    "ard" to playIcon(R.drawable.play_ard, 0xFF003480),
)

/**
 * TV packages outside [AppCatalog] that carry a brand above. The first three are as the TV's
 * diagnostic reported them (2026-10-27): the BRAVIA's Channel 4 and ITVX tiles are Sony's own
 * launcher wrappers, not the broadcasters' packages. The rest (2026-11-06) are each app's Android TV
 * package, its Google Play listing (gl=GB) showing "TV" among its devices. Keyed here rather than in
 * the catalog so an app the TV doesn't carry never joins the Apps grid, and a wrapper never shows
 * twice. Package only, never a title; a Sony wrapper not listed here keeps its letter.
 */
@VisibleForTesting
internal val tvOnly: Map<String, String> = mapOf(
    "com.sony.dtv.all4launcher" to "channel4",
    "com.sony.dtv.itvlauncher" to "itvx",
    "org.localsend.localsend_app" to "localsend",
    "com.channel4.ondemand" to "channel4",
    "air.ITVMobilePlayer" to "itvx",
    "com.channel5.my5" to "channel5",
    "bbc.iplayer.android" to "bbciplayer",
    "uk.co.bbc.sounds" to "bbcsounds",
    "com.sony.dtv.calibrationmonitor" to "calman",
    "com.amazon.music.tv" to "amazonmusic",
    "com.tubitv" to "tubi",
    "tv.pluto.android" to "plutotv",
    "tv.wuaki.apptv" to "rakutentv",
    "com.sonypicturescore" to "sonypicturescore",
    "com.dazn" to "dazn",
    "com.bskyb.nowtv.beta" to "now",
    "uk.co.uktv.dave" to "uktv",
    "com.discovery.dplay" to "discoveryplus",
    "com.nousguide.android.rbtv" to "redbulltv",
    "com.sky.news.androidtv" to "skynews",
    "com.zdf.android.mediathek" to "zdf",
    "de.swr.avp.ard.tv" to "ard",
)

/**
 * The brand face for an app: by catalog package, by a TV-only package, then by the exact catalog
 * name the TV gives it ([name], case-insensitive). The name is a last resort for a package the
 * catalog doesn't know but whose title is a catalog app's own (a second "Plex"): the mark names
 * the app the TV says it is, and the Apps grid shows the package under any name that appears twice.
 */
fun brandFace(packageName: String?, name: String? = null): BrandFace? {
    val id = packageName?.let { AppCatalog.byPackage(it)?.id ?: tvOnly[it] }
        ?: name?.let { AppCatalog.find(it)?.id }
    return id?.let { faces[it] }
}

/**
 * A system app with no free mark wears a monochrome Material symbol for what it is, on the letter
 * tile's own ground, instead of a letter (2026-10-27). Keyed by package only: a title is too
 * loose to say what an app does. Never a brand; the tile's label still names it.
 */
@DrawableRes
fun systemSymbol(packageName: String?): Int? = when (packageName) {
    "com.sony.dtv.notificationcenter" -> R.drawable.ic_notifications
    "com.sony.dtv.smarthelp" -> R.drawable.ic_help
    "com.sony.dtv.ecodashboard" -> R.drawable.ic_bolt
    "com.vewd.core.browser" -> R.drawable.ic_language
    "com.cxinventor.file.explorer" -> R.drawable.ic_folder
    "com.google.android.play.games" -> R.drawable.ic_game
    else -> null
}

/** A brand logo's own colour where it stands alone as a glyph (the bar's Spotify source), else null. */
fun brandColour(@DrawableRes drawable: Int): Int? = when (drawable) {
    R.drawable.brand_spotify -> 0xFF1ED760.toInt()
    else -> null
}
