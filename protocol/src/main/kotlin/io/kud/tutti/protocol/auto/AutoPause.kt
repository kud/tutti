package io.kud.tutti.protocol.auto

import kotlinx.serialization.Serializable

/**
 * Pausing the TV for the phone, and playing it again afterwards (Erwann, 2026-11-12): a call that
 * rings or starts, or the phone laid face down. Each is its own opt-in; both share this one ledger,
 * so a call that comes while the phone lies face down neither pauses twice nor resumes early.
 *
 * The rule the whole ledger keeps: **tutti only ever resumes what tutti paused.** It pauses only
 * when something is showing in an app (the TV on, an app in front that isn't the launcher, and
 * Cast, when it reports at all, not already paused); it resumes only when the last reason has gone,
 * the TV is still on the same app, and Cast (when it reported both times) still shows the same
 * item, paused. Anything else drops the claim and says why, so a decision is never left pending.
 *
 * The keys are the TV's own media keys, Pause and Play (Remote v2 126 and 127), never the
 * Play/Pause toggle: sent to a screen with nothing paused they do nothing, where a toggle would
 * start something. Pure: the caller passes the time and a [Snapshot] and sends what comes back.
 */
object AutoPause {
    @Serializable
    enum class Cause(val label: String) { CALL("call"), FACE_DOWN("face down") }

    /**
     * What the TV shows now, as tutti knows it. [castPlaying] is null when Cast reports nothing (it
     * runs only while the remote is in front); [castItem] is an opaque key for the item Cast shows,
     * compared for equality only and never logged.
     */
    data class Snapshot(
        val tvOn: Boolean,
        val app: String?,
        val castPlaying: Boolean? = null,
        val castItem: String? = null,
    )

    /**
     * What tutti paused, and why it may still want to play it again. [pausedAt] is on the
     * monotonic clock (elapsed since boot), so a reboot reads as the clock going back and drops it.
     */
    @Serializable
    data class Claim(val app: String, val castItem: String?, val pausedAt: Long)

    /** Stored between calls (a call outlives the process woken for its ring), never synced. */
    @Serializable
    data class State(val causes: Set<Cause> = emptySet(), val claim: Claim? = null)

    sealed interface Decision {
        /** Send Pause; [why] is the dev log's line. */
        data class Pause(val why: String) : Decision

        /** Send Play, with the bar's gentle fade-in. */
        data class Resume(val why: String) : Decision

        /** Nothing to send; [why] says so in the dev log. */
        data class Nothing(val why: String) : Decision
    }

    data class Step(val state: State, val decision: Decision)

    /** Google TV Home and the older launcher, where there is nothing to pause. */
    val LAUNCHERS: Set<String> get() = io.kud.tutti.protocol.apps.AppHistory.PASS_THROUGH

    /** Pause and Play, as Android key codes (the Remote v2 sends them as they are). */
    const val KEY_PLAY = 126
    const val KEY_PAUSE = 127

    /**
     * Past this, a claim is stale: a call or a phone left face down for longer is not a pause to
     * undo, and Play hours later would surprise more than help.
     */
    const val MAX_CLAIM_MS: Long = 3 * 60 * 60_000L

    /** A reason to pause began ([cause]): a call rang or started, or the phone went face down. */
    fun begin(state: State, cause: Cause, now: Snapshot, at: Long): Step {
        if (cause in state.causes) return Step(state, Decision.Nothing("${cause.label}: already counted"))
        val causes = state.causes + cause
        // Already paused by tutti for another reason: this one joins it, nothing to send.
        if (state.claim != null) return Step(state.copy(causes = causes), Decision.Nothing("${cause.label}: already paused by tutti"))
        val why = pauseRefusal(now)
        if (why != null) return Step(state.copy(causes = causes), Decision.Nothing("${cause.label}: $why"))
        val claim = Claim(now.app!!, now.castItem, at)
        return Step(State(causes, claim), Decision.Pause("${cause.label}: pause"))
    }

    /** A reason ended: the call hung up, or the phone picked up again. Resumes when it was the last. */
    fun end(state: State, cause: Cause, now: Snapshot, at: Long): Step {
        if (cause !in state.causes) return Step(state, Decision.Nothing("${cause.label}: nothing to end"))
        val causes = state.causes - cause
        val claim = state.claim ?: return Step(State(causes, null), Decision.Nothing("${cause.label}: ended, tutti paused nothing"))
        if (causes.isNotEmpty()) {
            return Step(State(causes, claim), Decision.Nothing("${cause.label}: ended, still paused for ${causes.joinToString { it.label }}"))
        }
        val why = resumeRefusal(claim, now, at)
        return if (why != null) {
            Step(State(), Decision.Nothing("${cause.label}: ended, not resumed: $why"))
        } else {
            Step(State(), Decision.Resume("${cause.label}: ended, play"))
        }
    }

    /** Turning an option off drops its reason without playing anything: the user is changing settings, not finishing a call. */
    fun drop(state: State, cause: Cause): State {
        val causes = state.causes - cause
        return State(causes, state.claim.takeIf { causes.isNotEmpty() })
    }

    /** Why there is nothing to pause, or null when there is. */
    fun pauseRefusal(now: Snapshot): String? = when {
        !now.tvOn -> "tv off"
        now.app == null -> "no app known"
        now.app in LAUNCHERS -> "launcher in front"
        now.castPlaying == false -> "cast says not playing"
        else -> null
    }

    /** Why tutti must not play again, or null when it may. */
    fun resumeRefusal(claim: Claim, now: Snapshot, at: Long): String? = when {
        at - claim.pausedAt > MAX_CLAIM_MS -> "paused too long ago"
        at < claim.pausedAt -> "clock went back"
        !now.tvOn -> "tv off"
        now.app != claim.app -> "another app in front"
        claim.castItem != null && now.castItem != null && now.castItem != claim.castItem -> "another item"
        now.castPlaying == true -> "already playing"
        else -> null
    }
}

/**
 * The bar's gentle return after a resume: from [FROM_SHARE] of the level it was at, back up to that
 * level in [STEPS] steps [STEP_MS] apart (three seconds). A hand on the volume during the fade wins:
 * the caller stops at the first level that isn't the one it sent.
 */
object ResumeFade {
    const val STEPS = 6
    const val STEP_MS = 500L
    const val FROM_SHARE = 0.4

    /** Below this there is nothing worth fading: the levels would repeat. */
    const val MIN_LEVEL = 4

    /** The levels to send, the last one [target]; empty when [target] is too low to fade. */
    fun levels(target: Int): List<Int> {
        if (target < MIN_LEVEL) return emptyList()
        val from = (target * FROM_SHARE).toInt()
        return (0..STEPS).map { from + (target - from) * it / STEPS }.distinct()
    }
}
