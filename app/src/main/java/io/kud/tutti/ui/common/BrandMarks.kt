package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import io.kud.tutti.R
import io.kud.tutti.protocol.atv.AppCatalog

/**
 * How an official logo becomes a coloured tile (Erwann, 2026-10-25: "tout en couleur partout").
 * [fill] covers the whole tile; [glyph] tints a single-colour mark (null keeps the logo's own
 * colours); [scale] is the logo's share of the tile's width.
 */
class BrandFace(@DrawableRes val drawable: Int, val fill: Int, val glyph: Int?, val scale: Float)

/** A Simple Icons mark on its own brand hex, in white or near-black, whichever reads (Iris: 56%). */
private fun mark(@DrawableRes drawable: Int, hex: Long): BrandFace? {
    val fill = hex.toInt()
    val glyph = BrandColours.glyphOn(fill) ?: return null
    return BrandFace(drawable, fill, glyph, 0.56f)
}

/**
 * Official logos, keyed by [AppCatalog] id: Simple Icons marks with their recorded brand hex, and
 * full-colour logos from svgl in their own colours. Every path is the source's own, unaltered; we
 * only scale it, and tint the single-colour marks as a whole. Never draw, trace or redraw a logo:
 * a catalog app with no logo from a recorded source keeps its letter tile until one is found.
 * Provenance per logo: docs/brand-icons.md.
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
    // Full-colour logos drawn for a light ground keep it; Put.io's own square is its ground.
    "primevideo" to BrandFace(R.drawable.logo_primevideo, BrandColours.WHITE, null, 0.8f),
    "disney" to BrandFace(R.drawable.logo_disneyplus, BrandColours.WHITE, null, 0.8f),
    "putio" to BrandFace(R.drawable.logo_putio, 0xFF323232.toInt(), null, 1f),
)

fun brandFace(packageName: String?): BrandFace? =
    packageName?.let { AppCatalog.byPackage(it) }?.let { faces[it.id] }

/** A brand logo's own colour where it stands alone as a glyph (the bar's Spotify source), else null. */
fun brandColour(@DrawableRes drawable: Int): Int? = when (drawable) {
    R.drawable.brand_spotify -> 0xFF1ED760.toInt()
    else -> null
}
