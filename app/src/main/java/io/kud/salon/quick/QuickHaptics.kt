package io.kud.salon.quick

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * design.md § Visual: haptics are primary feedback. Light tick per step, firmer
 * click on a press, soft bump at the volume limit or cap, double pulse when a
 * scene finishes. View-based so it honours the system touch-feedback setting.
 */
internal object QuickHaptics {
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun click(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun bump(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun sceneDone(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view.postDelayed({ view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }, 90L)
        }
    }
}
