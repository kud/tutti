package io.kud.tutti.ui.common

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * The system's "Remove animations" signal. Curves live in [io.kud.tutti.ui.motion.Motion]; this file
 * only says whether to travel at all.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** True when the system animator scale is off ("Supprimer les animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
