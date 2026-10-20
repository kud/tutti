package io.kud.salon.ui.common

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalReducedMotion = staticCompositionLocalOf { false }

/** True when the system animator scale is off ("Supprimer les animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Expressive in motion: a quick spring that settles under ~250 ms; a short fade-like tween when motion is reduced. */
@Composable
fun <T> motion(): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) {
        tween(durationMillis = 120)
    } else {
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
    }
