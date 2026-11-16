package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.AppCatalog

/**
 * The name of the app the remote v2 reports in front, for the now-showing card, the system chip
 * and the media notification alike, so the three never disagree (2026-11-06: a sideloaded player
 * had its name and icon on the lock screen while the card said "Home screen"). The TV's own list
 * ([AppLibrary], persisted) names it first, as the Apps sheet does, then the curated catalogue.
 * The launcher and the settings are places, not apps ([AppHistory.PASS_THROUGH]): null, so the
 * card reads the home screen. An app neither list knows is null too, never a raw package id.
 */
object ForegroundName {
    fun of(packageName: String?, apps: List<KnownApp>): String? {
        if (packageName.isNullOrBlank() || packageName in AppHistory.PASS_THROUGH) return null
        return apps.firstOrNull { it.packageName == packageName }?.name?.trim()?.ifEmpty { null }
            ?: AppCatalog.byPackage(packageName)?.name
    }
}
