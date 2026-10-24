package io.kud.tutti.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
 *
 * Without dynamic colour (Android 11 and below) the scheme is tutti's own, from the brand palette
 * (design/branding: ink, graphite, ivory, amber), never Material's stock purple, which belongs to
 * no one and read as a stray system colour on Erwann's OnePlus (2026-10-24).
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
        darkTheme -> BrandDark
        else -> BrandLight
    }
    val scheme = if (darkTheme && trueBlack) {
        base.copy(background = Color.Black, surface = Color.Black)
    } else {
        base
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Ivory on ink; amber is the one accent (the mark's OK button), with ink on it. */
private val BrandDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFE3A35B),
    onPrimary = Color(0xFF1C1B22),
    primaryContainer = Color(0xFF5A3D17),
    onPrimaryContainer = Color(0xFFFFDDB8),
    inversePrimary = Color(0xFF85561C),
    secondary = Color(0xFFD8C4AE),
    onSecondary = Color(0xFF3A2E21),
    secondaryContainer = Color(0xFF3F3A36),
    onSecondaryContainer = Color(0xFFF2EDE4),
    tertiary = Color(0xFFBEC6DC),
    onTertiary = Color(0xFF283141),
    tertiaryContainer = Color(0xFF3E4759),
    onTertiaryContainer = Color(0xFFDAE2F9),
    background = Color(0xFF1C1B22),
    onBackground = Color(0xFFF2EDE4),
    surface = Color(0xFF1C1B22),
    onSurface = Color(0xFFF2EDE4),
    surfaceVariant = Color(0xFF45423F),
    onSurfaceVariant = Color(0xFFCFC6BA),
    surfaceTint = Color(0xFFE3A35B),
    inverseSurface = Color(0xFFF2EDE4),
    inverseOnSurface = Color(0xFF1C1B22),
    outline = Color(0xFF999086),
    outlineVariant = Color(0xFF4B4741),
    surfaceBright = Color(0xFF3B3A41),
    surfaceDim = Color(0xFF1C1B22),
    surfaceContainerLowest = Color(0xFF16151B),
    surfaceContainerLow = Color(0xFF222128),
    surfaceContainer = Color(0xFF26252C),
    surfaceContainerHigh = Color(0xFF302F38),
    surfaceContainerHighest = Color(0xFF3A3942),
)

/** Ink on ivory; the amber deepened so it holds 4.5:1 as text and as a button on light surfaces. */
private val BrandLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF85561C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB8),
    onPrimaryContainer = Color(0xFF2C1700),
    inversePrimary = Color(0xFFE3A35B),
    secondary = Color(0xFF6D5C4A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E1D5),
    onSecondaryContainer = Color(0xFF1C1B22),
    tertiary = Color(0xFF555F71),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9E3F8),
    onTertiaryContainer = Color(0xFF121C2B),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE8E1D7),
    onSurfaceVariant = Color(0xFF4D4740),
    surfaceTint = Color(0xFF85561C),
    inverseSurface = Color(0xFF302F38),
    inverseOnSurface = Color(0xFFF2EDE4),
    outline = Color(0xFF7E766C),
    outlineVariant = Color(0xFFD0C7BA),
    surfaceBright = Color(0xFFFBF8F3),
    surfaceDim = Color(0xFFDDD7CE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EC),
    surfaceContainer = Color(0xFFF2EDE4),
    surfaceContainerHigh = Color(0xFFECE6DC),
    surfaceContainerHighest = Color(0xFFE6DFD4),
)
