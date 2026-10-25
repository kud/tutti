package io.kud.tutti.ui.common

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import io.kud.tutti.platform.data.VibrationLevel

/** tutti's own feedback settings; Android's switches apply on top and are never overridden. */
@Immutable
data class FeedbackPrefs(val vibration: VibrationLevel = VibrationLevel.ALL, val keySounds: Boolean = false)

val LocalFeedbackPrefs = staticCompositionLocalOf { FeedbackPrefs() }

/**
 * Haptics are primary feedback (design.md), and sound is an opt-in extra on key presses only.
 * Call sites name a [FeedbackEvent]; [FeedbackRules] decides what plays. Always fired before any
 * network call.
 *
 * Both go through the View, on purpose: `performHapticFeedback` honours Android's touch vibration
 * switch and intensity, and `playSoundEffect` honours "Touch sounds" and plays at system volume.
 * Compose's `clickable` plays no click of its own (checked in foundation and ui 1.8.1: neither
 * calls `playSoundEffect`), so a key sound here never doubles up.
 *
 * The old `Haptics` method names stay as thin wrappers so call sites can move gradually.
 */
@Stable
class Feedback internal constructor(private val view: View, private val prefs: FeedbackPrefs) {
    fun perform(event: FeedbackEvent) {
        val plan = FeedbackRules.plan(event, prefs.vibration, prefs.keySounds)
        plan.haptic?.let(::vibrate)
        if (plan.sound) view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    fun tick() = perform(FeedbackEvent.STEP)

    fun select() = perform(FeedbackEvent.SELECT)

    fun click() = perform(FeedbackEvent.KEY)

    fun longPress() = perform(FeedbackEvent.LONG_PRESS)

    fun bump() = perform(FeedbackEvent.BUMP)

    fun confirm() = perform(FeedbackEvent.CONFIRM)

    fun reject() = perform(FeedbackEvent.REJECT)

    fun doublePulse() = perform(FeedbackEvent.SCENE_DONE)

    private fun vibrate(pattern: HapticPattern) {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val outcomes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        when (pattern) {
            HapticPattern.CLICK -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            HapticPattern.RELEASE -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
            HapticPattern.TICK -> view.performHapticFeedback(
                if (modern) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
            )
            HapticPattern.SELECT -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            HapticPattern.LONG_PRESS -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            HapticPattern.BUMP -> view.performHapticFeedback(
                if (modern) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK,
            )
            HapticPattern.CONFIRM -> view.performHapticFeedback(
                if (outcomes) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
            )
            HapticPattern.REJECT -> if (outcomes) view.performHapticFeedback(HapticFeedbackConstants.REJECT) else vibrate(HapticPattern.BUMP)
            HapticPattern.DOUBLE_PULSE -> {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                view.postDelayed({ view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }, DOUBLE_PULSE_GAP_MS)
            }
        }
    }

    private companion object {
        const val DOUBLE_PULSE_GAP_MS = 90L
    }
}

/** The name call sites already use; the class grew into [Feedback]. */
typealias Haptics = Feedback

@Composable
fun rememberFeedback(): Feedback {
    val view = LocalView.current
    val prefs = LocalFeedbackPrefs.current
    return remember(view, prefs) { Feedback(view, prefs) }
}

@Composable
fun rememberHaptics(): Feedback = rememberFeedback()

/** Android's own switches, read for the settings rows only: they say so when either is off. */
data class SystemFeedback(val vibration: Boolean, val sounds: Boolean) {
    companion object {
        fun read(context: Context): SystemFeedback {
            val resolver = context.contentResolver
            return SystemFeedback(
                vibration = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0,
                sounds = Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0,
            )
        }
    }
}

/**
 * A tap on a key aimed at an unreachable device points at the system chip: the key bumps, and
 * the chip pulses its layer once per [poke].
 */
@Stable
class ChipNudge {
    var pokes by mutableIntStateOf(0)
        private set

    fun poke() {
        pokes++
    }
}

val LocalChipNudge = staticCompositionLocalOf { ChipNudge() }
