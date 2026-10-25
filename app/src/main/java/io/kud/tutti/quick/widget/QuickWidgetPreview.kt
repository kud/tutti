package io.kud.tutti.quick.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import io.kud.tutti.R

/**
 * Android 15+ generated preview for the widget picker: the same example layout as
 * `previewLayout` (`layout/quick_widget_preview.xml`), handed to the launcher once per app
 * version. The system rate-limits the call (a couple an hour), so a refusal is left for the next
 * version rather than retried; `previewLayout` still covers the picker meanwhile.
 */
object QuickWidgetPreview {
    private const val TAG = "QuickWidgetPreview"
    private const val PREFS = "quick_widget_preview"
    private const val KEY_VERSION = "published_version"

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        publishGenerated(context.applicationContext)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun publishGenerated(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        }.getOrDefault(0L)
        if (prefs.getLong(KEY_VERSION, -1L) == version) return
        val published = runCatching {
            AppWidgetManager.getInstance(context).setWidgetPreview(
                ComponentName(context, QuickWidgetReceiver::class.java),
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                RemoteViews(context.packageName, R.layout.quick_widget_preview),
            )
        }.getOrElse { e ->
            Log.w(TAG, "Widget preview not published: ${e.javaClass.simpleName}")
            false
        }
        if (published) prefs.edit { putLong(KEY_VERSION, version) }
    }
}
