package io.kud.tutti.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Dark-first Material 3 theme with dynamic colour (Android 12+).
 * [trueBlack] swaps background and surface for pure black, for OLED.
 */
@Composable
fun TuttiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    trueBlack: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    val scheme = if (darkTheme && trueBlack) {
        base.copy(background = Color.Black, surface = Color.Black)
    } else {
        base
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
