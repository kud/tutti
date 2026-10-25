package io.kud.tutti.ui.common

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * The system's "Remove animations" signal. Curves live in [io.kud.tutti.ui.motion.Motion]; this file
 * only says whether to travel at all.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * True when the system animator scale is off ("Supprimer les animations"). Followed live through a
 * [ContentObserver], so changing the setting while tutti runs takes effect without a restart.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduced = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/**
 * An indeterminate bar for "working on it". Under reduced motion it stands still at a third, the
 * same still form the spinners take (a 0.75 arc), so the wait is still shown without the sweep.
 */
@Composable
fun BusyBar(modifier: Modifier = Modifier) {
    if (LocalReducedMotion.current) {
        LinearProgressIndicator(progress = { 1f / 3f }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier)
    }
}
