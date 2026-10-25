package io.kud.tutti.quick

import io.kud.tutti.platform.data.VibrationLevel
import kotlinx.coroutines.flow.StateFlow

/**
 * The narrow slice of the living room that the quick-access surfaces need: the
 * Quick Settings tile, its overlay, the home-screen widget and the notification.
 *
 * The app's controller implements it (see [QuickModule]). The surfaces depend on
 * nothing else, so they can be built and reasoned about without the full remote.
 *
 * Contract for implementers:
 * - [state] is optimistic: an action updates it before the device answers,
 *   and reconciles (or rolls back) when it does. Surfaces never wait on the network.
 * - Actions are safe to call while disconnected: they are dropped or queued, never thrown.
 * - [applyScene] suspends until the scene has finished applying, so callers can
 *   give the "scene done" haptic. Every other action returns once sent.
 */
interface QuickControls {
    val state: StateFlow<QuickState>

    /** Keeps the device sessions open while a surface is visible. Pair with [release]. */
    fun retain() {}

    fun release() {}

    /** One Power for the whole room: switches the TV and the bar together. */
    suspend fun togglePower()

    /** One step on [QuickState.level]'s device: the bar when one is configured, else the TV. */
    suspend fun volumeUp()

    suspend fun volumeDown()

    /** Absolute level on [QuickState.level]'s device. The bar clamps to the active cap; the TV gets key presses. */
    suspend fun setVolume(level: Int)

    suspend fun toggleMute()

    /** Play/pause on the TV, over the remote link. */
    suspend fun playPause() {}

    suspend fun applyScene(id: String)
}

data class QuickState(
    /** Both control channels needed for quick actions are up (bar TCP at least). */
    val connected: Boolean = false,
    /** A TV or a bar is set up: actions are offered, and connect on tap, even while nothing is up. */
    val configured: Boolean = false,
    /** The TV is on. The notification shows only while this and [connected] hold. */
    val power: Boolean = false,
    /** Bar volume, 0–100. 0 while unknown: surfaces that must not show a made-up 0 read [level]. */
    val volume: Int = 0,
    /** Effective ceiling: 100, or the active cap (a scene's, Nuit: 20, or the cap for tonight). */
    val volumeLimit: Int = 100,
    /**
     * The bar's high-volume guard, where it stops a quick surface (they can't ask): null when it
     * stops nothing right now. Going above needs the app.
     */
    val guardLine: Int? = null,
    /** A quick press was just stopped at [guardLine]: the surfaces say going above needs the app. */
    val guardHeld: Boolean = false,
    val muted: Boolean = false,
    val activeSceneId: String? = null,
    /** The active scene was applied, then changed by hand ("modifiée"). */
    val activeSceneModified: Boolean = false,
    /** In strip order. */
    val scenes: List<QuickScene> = emptyList(),
    /** The app's Feedback settings, so the overlay vibrates exactly as the remote does. */
    val vibration: VibrationLevel = VibrationLevel.ALL,
    val keySounds: Boolean = false,
    /** The level the notification shows and the volume keys drive, with its device. Null while unknown. */
    val level: AudioLevel? = null,
    /** What the TV is showing, when its foreground app is known. */
    val nowShowing: QuickNowShowing? = null,
    /** Configured device names, for the notification's subtitle. */
    val tvName: String? = null,
    val barName: String? = null,
) {
    val activeScene: QuickScene? get() = scenes.firstOrNull { it.id == activeSceneId }
}

/** Mirrors `io.kud.tutti.protocol.scenes.Scene`'s id, name and colour. */
data class QuickScene(
    val id: String,
    val name: String,
    /** ARGB seed, as `Scene.color`. */
    val color: Long,
)

/** Whose volume a quick surface is showing and stepping. */
enum class VolumeTarget { BAR, TV }

/** A device's volume as last reported (or optimistically set), on its own scale 0..[max]. */
data class AudioLevel(val target: VolumeTarget, val level: Int, val max: Int, val muted: Boolean)

/**
 * The bar's level whenever a bar is configured, else the TV's; never the other
 * device's in its place. The TV's remote link reports `0/0` while its sound goes
 * to the bar, so a TV level with no range counts as unknown.
 */
fun audioLevelOf(barConfigured: Boolean, barVolume: Int?, barMax: Int, barMuted: Boolean, tvVolume: Int?, tvMax: Int?, tvMuted: Boolean): AudioLevel? =
    if (barConfigured) {
        barVolume?.let { AudioLevel(VolumeTarget.BAR, it, barMax, barMuted) }
    } else {
        if (tvVolume == null || tvMax == null || tvMax <= 0) null else AudioLevel(VolumeTarget.TV, tvVolume, tvMax, tvMuted)
    }

/** The TV's foreground app: its label from the app catalogue, and the icon tutti has cached for it. */
data class QuickNowShowing(val packageName: String, val label: String?, val iconPath: String?)

/**
 * The quick overlay's rail: [level] on 0..[range], drags capped at [limit]; null while the level
 * has not been reported, so the rail shows empty rather than a made-up 0. Reads [QuickState.level],
 * the same reported level as the notification: the bar's on 0..100 under the scene's cap, else
 * the TV's on its own scale.
 */
data class OverlayRail(val level: Int?, val range: Int, val limit: Int) {
    /** A step towards [up] would go past the end: bump instead of tick. Unknown is never at a limit. */
    fun atLimit(up: Boolean): Boolean = level != null && if (up) level >= limit else level <= 0
}

fun overlayRail(state: QuickState): OverlayRail {
    val reported = state.level
    return when (reported?.target) {
        VolumeTarget.TV -> OverlayRail(reported.level.coerceIn(0, reported.max), reported.max, reported.max)
        VolumeTarget.BAR -> OverlayRail(reported.level.coerceIn(0, BAR_RANGE), BAR_RANGE, barLimit(state))
        null -> OverlayRail(null, BAR_RANGE, barLimit(state))
    }
}

/** The overlay's drag stops at the cap, or at the high-volume guard while it is armed: it can't ask. */
private fun barLimit(state: QuickState): Int = minOf(state.volumeLimit, state.guardLine ?: BAR_RANGE).coerceIn(0, BAR_RANGE)

/** The bar's scale (TuttiController.MAX_VOLUME). */
private const val BAR_RANGE = 100
