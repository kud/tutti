package io.kud.tutti.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * Haptics are primary feedback (design.md): light tick per D-pad or swipe step, firmer click
 * on Select, soft bump at volume limits and caps, double pulse when a scene finishes.
 * Always fired before any network call. Richer constants are API-guarded (minSdk 29).
 */
@Stable
class Haptics internal constructor(private val view: View, private val enabled: Boolean) {
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        },
    )

    fun select() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    fun click() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    fun bump() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        },
    )

    fun doublePulse() {
        select()
        if (enabled) view.postDelayed({ view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }, DOUBLE_PULSE_GAP_MS)
    }

    private fun perform(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }

    private companion object {
        const val DOUBLE_PULSE_GAP_MS = 90L
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}
