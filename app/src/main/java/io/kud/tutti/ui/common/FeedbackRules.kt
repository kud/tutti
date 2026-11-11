package io.kud.tutti.ui.common

import io.kud.tutti.platform.data.VibrationLevel

/**
 * What happened, in the words of the interaction spec (2026-10-23). One event always feels the
 * same wherever it happens: call sites name the event, never a haptic constant.
 */
enum class FeedbackEvent {
    /** A discrete key press: round key, dock pin, OK, keyboard key. */
    KEY,

    /** A D-pad direction, a touchpad or volume step, a scene apply step, a drag slot crossed. */
    STEP,

    /**
     * A volume move crossed a landmark: a saved level, the high-volume guard's line, tonight's cap.
     * Firmer than [STEP] and plays instead of it, so a level is found blind, in the dark.
     */
    DETENT,

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
enum class HapticPattern { CLICK, TICK, DETENT, SELECT, LONG_PRESS, BUMP, CONFIRM, REJECT, DOUBLE_PULSE }

/** What one event plays, after the user's settings: a haptic or none, and the platform click or not. */
data class FeedbackPlan(val haptic: HapticPattern?, val sound: Boolean)

/**
 * The rule table, kept free of Android so it is unit-tested. "Essentials only" drops the
 * per-key click and the per-step tick and keeps state changes, walls, detents and outcomes. The click
 * sound plays only on a discrete key press: never on steps, swipes, the volume rail or outcomes.
 * Neither ever overrides Android: the system's own touch vibration and touch sounds switches
 * still apply on top, in [Feedback].
 */
object FeedbackRules {
    fun haptic(event: FeedbackEvent): HapticPattern = when (event) {
        FeedbackEvent.KEY -> HapticPattern.CLICK
        FeedbackEvent.STEP -> HapticPattern.TICK
        FeedbackEvent.DETENT -> HapticPattern.DETENT
        FeedbackEvent.SELECT -> HapticPattern.SELECT
        FeedbackEvent.LONG_PRESS -> HapticPattern.LONG_PRESS
        FeedbackEvent.BUMP -> HapticPattern.BUMP
        FeedbackEvent.CONFIRM -> HapticPattern.CONFIRM
        FeedbackEvent.REJECT -> HapticPattern.REJECT
        FeedbackEvent.SCENE_DONE -> HapticPattern.DOUBLE_PULSE
    }

    /** Kept under "Essentials only". */
    fun isEssential(event: FeedbackEvent): Boolean = event != FeedbackEvent.KEY && event != FeedbackEvent.STEP

    fun plan(event: FeedbackEvent, level: VibrationLevel, keySounds: Boolean): FeedbackPlan {
        val haptic = when (level) {
            VibrationLevel.OFF -> null
            VibrationLevel.ESSENTIALS -> if (isEssential(event)) haptic(event) else null
            VibrationLevel.ALL -> haptic(event)
        }
        return FeedbackPlan(haptic = haptic, sound = keySounds && event == FeedbackEvent.KEY)
    }
}

/** The phone's ringer, as AudioManager reports it. */
enum class Ringer { NORMAL, VIBRATE, SILENT }

/**
 * What the phone says at the intro's landing: everything the intro chime asks before it plays.
 * [touchSounds] is Android's "Touch sounds" switch, which already silences the key click.
 */
data class IntroSoundContext(
    val keySounds: Boolean,
    val touchSounds: Boolean,
    val coldStart: Boolean,
    val ringer: Ringer,
    val doNotDisturb: Boolean,
    val phoneMediaPlaying: Boolean,
)

/**
 * The intro chime (Erwann, 2026-11-11: « si on a du son activé, ça serait bien de pouvoir avoir
 * du son au splash screen »). It belongs to Key sounds, so it is off unless they are on and Android's
 * touch sounds are too; it plays once per cold start, never on a warm return or a recreated
 * activity; a silent or vibrating ringer and Do Not Disturb keep it quiet; and anything already
 * playing on the phone wins, since the chime takes no audio focus and must never sit on top of it.
 * Reduced motion is not a reason: sound is not motion.
 */
object IntroSoundRule {
    fun shouldPlay(context: IntroSoundContext): Boolean = with(context) {
        keySounds && touchSounds && coldStart && ringer == Ringer.NORMAL && !doNotDisturb && !phoneMediaPlaying
    }
}
