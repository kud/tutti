package io.kud.tutti.protocol.picture

import io.kud.tutti.protocol.apps.AppHistory
import kotlinx.serialization.Serializable

/**
 * "The picture follows what's on" (Erwann, 2026-11-12: « j'aimerais que l'image suive le contenu »).
 * Off by default, synced with the other preferences.
 *
 * Why tutti and not the TV: a Sony Google TV keeps picture settings per input, and every app runs
 * on the same one, so Netflix and YouTube share one picture mode on the set itself. Its "Auto
 * picture mode" and the calibrated modes switch on signals the TV gets (a console's ALLM, a
 * supported app's own flag), not on which app is in front. Inputs are mostly covered already (an
 * HDMI port remembers its mode when "Target inputs" is per input, and ALLM picks Game), so they
 * take a rule only when he sets one. Apps are the gap, so apps carry the suggestions.
 *
 * [modes] and [inputs] hold a TV picture mode value (one of the TV's own `pictureMode`
 * candidates) or [LEAVE]. An app with neither falls back to [PictureFollow.SUGGESTED] while
 * [suggestions] is on.
 */
@Serializable
data class PictureFollowConfig(
    val enabled: Boolean = false,
    /** By app package. */
    val apps: Map<String, String> = emptyMap(),
    /** By the TV's source URI (`extInput:hdmi?port=2`). */
    val inputs: Map<String, String> = emptyMap(),
    val suggestions: Boolean = true,
) {
    companion object {
        /** "Leave the picture as it is" for this app or input, even when a suggestion exists. */
        const val LEAVE = "-"
    }
}

/** What is on the screen, as picture rules see it. */
sealed interface PictureContext {
    data class App(val packageName: String) : PictureContext
    data class Input(val uri: String) : PictureContext

    /** Google TV Home and the like: a place you pass through, which neither sets nor restores. */
    data object PassThrough : PictureContext

    /** Nothing known yet (no report, a dropped link): changes nothing. */
    data object Unknown : PictureContext
}

/**
 * Where tutti stands with the TV's picture mode. Holding means tutti changed the mode and owes the
 * TV [baseline] back once the screen leaves every rule; [expected] is what the TV should then show.
 * A mode seen that tutti did not set while holding is a change by hand: [paused] until the TV goes
 * to standby, so tutti never fights him.
 */
data class PictureFollowState(
    val baseline: String? = null,
    val expected: String? = null,
    /** When the last write went out; the poll may still report the old mode until [PictureFollow.SETTLE_MS]. */
    val wroteAt: Long? = null,
    val paused: Boolean = false,
) {
    val holding: Boolean get() = baseline != null
}

object PictureFollow {
    /** Two REST polls (10 s each) and some: a poll that started before the write can still read the old mode. */
    const val SETTLE_MS = 25_000L

    /**
     * Well-known apps, each with modes in order of preference; the first the TV offers wins. Film
     * and series services lean Cinema (Sony's creator-intent preset), YouTube keeps Standard.
     * The values are Sony's documented ones and unverified on the XR80, so a mode the TV doesn't
     * list is never suggested.
     */
    val SUGGESTED: Map<String, List<String>> = mapOf(
        "com.netflix.ninja" to listOf("cinema"),
        "com.amazon.amazonvideo.livingroom" to listOf("cinema"),
        "com.disney.disneyplus" to listOf("cinema"),
        "com.apple.atve.androidtv.appletv" to listOf("cinema"),
        "com.sonypicturescore" to listOf("cinema"),
        "com.nuvio.app" to listOf("cinema"),
        "com.nuvio.tv" to listOf("cinema"),
        "com.google.android.youtube.tv" to listOf("standard"),
    )

    enum class Reason { RULE, RESTORE }

    /** What tutti should write, if anything. */
    data class Write(val mode: String, val reason: Reason)

    /** One step's outcome: the next state, at most one write, and at most one developer log line. */
    data class Step(val state: PictureFollowState, val write: Write? = null, val log: String? = null)

    /**
     * The context from the TV's two reports: REST's source URI wins when it names an external input
     * (it needs the PSK; Remote v2 only knows packages), else the app in front.
     */
    fun contextOf(sourceUri: String?, foregroundApp: String?): PictureContext = when {
        sourceUri != null && sourceUri.startsWith("extInput:") -> PictureContext.Input(sourceUri)
        foregroundApp.isNullOrBlank() -> PictureContext.Unknown
        foregroundApp in AppHistory.PASS_THROUGH -> PictureContext.PassThrough
        else -> PictureContext.App(foregroundApp)
    }

    /**
     * The mode [context] asks for, or null for none. [candidates] are the TV's own `pictureMode`
     * values (empty when not read yet): a suggestion needs the TV to list its mode, a mode he
     * picked himself came from that list already. [sceneApps] are apps whose per-app profile runs
     * a scene that sets the picture mode: the scene owns those.
     */
    fun modeFor(config: PictureFollowConfig, context: PictureContext, candidates: Collection<String>, sceneApps: Set<String> = emptySet()): String? {
        val chosen = when (context) {
            is PictureContext.App -> {
                if (context.packageName in sceneApps) return null
                config.apps[context.packageName] ?: suggestion(config, context.packageName, candidates)
            }
            is PictureContext.Input -> config.inputs[context.uri]
            PictureContext.PassThrough, PictureContext.Unknown -> null
        }
        return chosen?.takeIf { it != PictureFollowConfig.LEAVE && (candidates.isEmpty() || it in candidates) }
    }

    fun suggestion(config: PictureFollowConfig, packageName: String, candidates: Collection<String>): String? =
        if (!config.suggestions || candidates.isEmpty()) null else SUGGESTED[packageName]?.firstOrNull { it in candidates }

    /**
     * The screen moved to [context] while the TV shows [current]. Pass-through and unknown hold
     * what is there; a rule sets its mode, remembering the mode it replaced; leaving every rule
     * gives that mode back, unless he changed it by hand meanwhile.
     */
    fun onContext(
        state: PictureFollowState,
        config: PictureFollowConfig,
        context: PictureContext,
        current: String?,
        candidates: Collection<String>,
        now: Long,
        sceneApps: Set<String> = emptySet(),
    ): Step {
        if (!config.enabled) return off(state)
        if (state.paused) return Step(state)
        if (context == PictureContext.PassThrough || context == PictureContext.Unknown) return Step(state)
        // Without a reading there is nothing to give back later: wait for the poll.
        if (current == null) return Step(state)
        val target = modeFor(config, context, candidates, sceneApps)
        val label = if (context is PictureContext.Input) "input" else "app"
        return when {
            target != null && target == current -> Step(state)
            target != null -> Step(
                state.copy(baseline = state.baseline ?: current, expected = target, wroteAt = now),
                Write(target, Reason.RULE),
                "picture follows: $label rule, $current -> $target",
            )
            state.holding && current == state.expected -> restore(state, "no rule for this $label")
            // Holding, but the TV no longer shows what tutti set: he moved it, so it is his now.
            state.holding -> Step(PictureFollowState(), log = "picture follows: mode moved by hand, nothing to give back")
            else -> Step(state)
        }
    }

    /** The poll read [observed]. A mode tutti didn't set, past the settle window, is his: stop. */
    fun onObserved(state: PictureFollowState, observed: String?, now: Long): Step {
        if (!state.holding || state.paused || observed == null) return Step(state)
        if (observed == state.expected) return Step(if (state.wroteAt == null) state else state.copy(wroteAt = null))
        val settling = state.wroteAt != null && now - state.wroteAt < SETTLE_MS
        if (settling) return Step(state)
        return Step(PictureFollowState(paused = true), log = "picture follows: changed by hand to $observed, paused until standby")
    }

    /** A write failed: the TV still shows what it showed, so go back to [before] the step that sent it. */
    fun onWriteFailed(before: PictureFollowState, write: Write): Step =
        Step(before.copy(wroteAt = null), log = "picture follows: ${write.reason.name.lowercase()} to ${write.mode} failed")

    /** Standby ends the session: a pause by hand lifts. What tutti owes back stays owed. */
    fun onStandby(state: PictureFollowState): PictureFollowState = state.copy(paused = false, wroteAt = null)

    /** Turned off: give back what tutti changed, then forget. */
    private fun off(state: PictureFollowState): Step =
        if (state.holding && !state.paused) {
            restore(state, "turned off")
        } else {
            Step(PictureFollowState())
        }

    private fun restore(state: PictureFollowState, why: String): Step {
        val back = state.baseline ?: return Step(PictureFollowState())
        return Step(
            PictureFollowState(),
            Write(back, Reason.RESTORE),
            "picture follows: $why, ${state.expected} -> $back",
        )
    }
}
