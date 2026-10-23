package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import io.kud.tutti.R
import io.kud.tutti.protocol.atv.AppCatalog

/**
 * Official brand marks from Simple Icons, keyed by [AppCatalog] id. Each path is the project's
 * own, unaltered; we only scale and tint it. A catalog app missing here is not in Simple Icons and
 * keeps its letter tile: never draw one. Provenance per mark: docs/brand-icons.md.
 */
private val marks: Map<String, Int> = mapOf(
    "netflix" to R.drawable.brand_netflix,
    "youtube" to R.drawable.brand_youtube,
    "appletv" to R.drawable.brand_appletv,
    "max" to R.drawable.brand_hbomax,
    "paramount" to R.drawable.brand_paramountplus,
    "mubi" to R.drawable.brand_mubi,
    "crunchyroll" to R.drawable.brand_crunchyroll,
    "twitch" to R.drawable.brand_twitch,
    "spotify" to R.drawable.brand_spotify,
    "deezer" to R.drawable.brand_deezer,
    "ytmusic" to R.drawable.brand_youtubemusic,
    "jellyfin" to R.drawable.brand_jellyfin,
    "plex" to R.drawable.brand_plex,
    "kodi" to R.drawable.brand_kodi,
    "vlc" to R.drawable.brand_vlcmediaplayer,
)

@DrawableRes
fun brandMark(packageName: String?): Int? =
    packageName?.let { AppCatalog.byPackage(it) }?.let { marks[it.id] }
