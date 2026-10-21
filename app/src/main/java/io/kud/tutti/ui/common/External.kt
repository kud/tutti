package io.kud.tutti.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * "Recalibrer dans BRAVIA Connect": calibration, Acoustic Center Sync and speaker placement
 * stay in Sony's app. Its package name is not something we can verify, so the installed app
 * is found by its launcher label (manifest <queries> on MAIN/LAUNCHER); otherwise the Play
 * Store opens on a search for it.
 */
@Suppress("DEPRECATION")
fun openBraviaConnect(context: Context) {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val installed = pm.queryIntentActivities(launcher, 0)
        .firstOrNull { it.loadLabel(pm).toString().contains(BRAVIA_CONNECT, ignoreCase = true) }
    val intent = installed?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        ?: Intent(Intent.ACTION_VIEW, STORE_SEARCH.toUri())
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, WEB_SEARCH.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val BRAVIA_CONNECT = "BRAVIA Connect"
private const val STORE_SEARCH = "market://search?q=BRAVIA%20Connect&c=apps"
private const val WEB_SEARCH = "https://play.google.com/store/search?q=BRAVIA%20Connect&c=apps"
