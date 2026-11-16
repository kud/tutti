package io.kud.tutti.ui.theme

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb

/**
 * The activity window's own background, under Compose. The XML theme gives it the system's day or
 * night (`values-night/themes.xml`); tutti's Light / Dark / True black preference can differ, and
 * the window shows before Compose draws, behind the keyboard's resize and around a closing dialog.
 * So the window follows the theme Compose draws, and the colour is kept for the next cold start.
 */
object WindowBackground {
    private const val FILE = "window"
    private const val KEY = "background"

    /** Paints the window in the background the theme last drew, if there is one. */
    fun restore(activity: Activity) {
        val prefs = activity.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(KEY)) activity.window.setBackgroundDrawable(prefs.getInt(KEY, 0).toDrawable())
    }

    /** Keeps the window, and the colour kept for next time, on the current theme's background. */
    @Composable
    fun Follow(activity: Activity) {
        val argb = MaterialTheme.colorScheme.background.toArgb()
        LaunchedEffect(argb) {
            activity.window.setBackgroundDrawable(argb.toDrawable())
            activity.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putInt(KEY, argb) }
        }
    }
}
