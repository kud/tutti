package io.kud.tutti.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * "Recalibrer dans BRAVIA Connect": calibration, Acoustic Center Sync and speaker placement
 * stay in Sony's app. It is found by its package name (its Play listing, manifest <queries>);
 * otherwise the Play Store opens on its listing. No screen deep link into Connect is known, so it
 * always opens on its home screen.
 */
fun openBraviaConnect(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(BRAVIA_CONNECT_PACKAGE)
        ?: Intent(Intent.ACTION_VIEW, STORE_LISTING.toUri())
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, WEB_LISTING.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val BRAVIA_CONNECT_PACKAGE = "jp.co.sony.hes.home"
private const val STORE_LISTING = "market://details?id=$BRAVIA_CONNECT_PACKAGE"
private const val WEB_LISTING = "https://play.google.com/store/apps/details?id=$BRAVIA_CONNECT_PACKAGE"
