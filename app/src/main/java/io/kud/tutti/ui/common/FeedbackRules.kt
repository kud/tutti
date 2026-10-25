package io.kud.tutti.ui.common

import io.kud.tutti.platform.data.VibrationLevel

/**
 * What happened, in the words of Iris's interaction spec (2026-10-23). One event always feels the
 * same wherever it happens: call sites name the event, never a haptic constant.
 */
enum class FeedbackEvent {
    /** A discrete key press: round key, dock pin, OK, keyboard key. */
    KEY,

    /** A D-pad direction, a touchpad or volume step, a scene apply step, a drag slot crossed. */
    STEP,

    /** A key released under "3D relief": a lighter echo of [KEY]'s click. */
    KEY_UP,

    /** A toggle or selection changed: a tile, a switch, the pad mode. */
    SELECT,

    /** A long press reached its second action. */
    LONG_PRESS,

    /** A wall: a limit or cap, a sheet's top stop, a tap on something disabled or unreachable. */
    BUMP,

    /** An action went through: paired, saved, added. */
    CONFIRM,

    /** An action failed or a setting was refused. */
    REJECT,

    /** A scene finished. */
    SCENE_DONE,
}

/** The haptic a [FeedbackEvent] plays; [Feedback] maps each to an API-guarded constant. */
enum class HapticPattern { CLICK, RELEASE, TICK, SELECT, LONG_PRESS, BUMP, CONFIRM, REJECT, DOUBLE_PULSE }

/** What one event plays, after the user's settings: a haptic or none, and the platform click or not. */
data class FeedbackPlan(val haptic: HapticPattern?, val sound: Boolean)

/**
 * The rule table, kept free of Android so it is unit-tested. "Essentials only" drops the
 * per-key click and the per-step tick and keeps state changes, walls and outcomes. The click
 * sound plays only on a discrete key press: never on steps, swipes, the volume rail or outcomes.
 * Neither ever overrides Android: the system's own touch vibration and touch sounds switches
 * still apply on top, in [Feedback].
 */
object FeedbackRules {
    fun haptic(event: FeedbackEvent): HapticPattern = when (event) {
        FeedbackEvent.KEY -> HapticPattern.CLICK
        FeedbackEvent.KEY_UP -> HapticPattern.RELEASE
        FeedbackEvent.STEP -> HapticPattern.TICK
        FeedbackEvent.SELECT -> HapticPattern.SELECT
        FeedbackEvent.LONG_PRESS -> HapticPattern.LONG_PRESS
        FeedbackEvent.BUMP -> HapticPattern.BUMP
        FeedbackEvent.CONFIRM -> HapticPattern.CONFIRM
        FeedbackEvent.REJECT -> HapticPattern.REJECT
        FeedbackEvent.SCENE_DONE -> HapticPattern.DOUBLE_PULSE
    }

    /** Kept under "Essentials only". */
    fun isEssential(event: FeedbackEvent): Boolean =
        event != FeedbackEvent.KEY && event != FeedbackEvent.KEY_UP && event != FeedbackEvent.STEP

    fun plan(event: FeedbackEvent, level: VibrationLevel, keySounds: Boolean): FeedbackPlan {
        val haptic = when (level) {
            VibrationLevel.OFF -> null
            VibrationLevel.ESSENTIALS -> if (isEssential(event)) haptic(event) else null
            VibrationLevel.ALL -> haptic(event)
        }
        return FeedbackPlan(haptic = haptic, sound = keySounds && event == FeedbackEvent.KEY)
    }
}
