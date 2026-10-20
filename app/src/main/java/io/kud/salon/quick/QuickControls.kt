package io.kud.salon.quick

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

    suspend fun volumeUp()

    suspend fun volumeDown()

    /** Absolute bar volume, 0–100. The implementation clamps to the active cap. */
    suspend fun setVolume(level: Int)

    suspend fun toggleMute()

    suspend fun applyScene(id: String)
}

data class QuickState(
    /** Both control channels needed for quick actions are up (bar TCP at least). */
    val connected: Boolean = false,
    /** The TV is on. The notification shows only while this and [connected] hold. */
    val power: Boolean = false,
    /** Bar volume, 0–100. */
    val volume: Int = 0,
    /** Effective ceiling: 100, or the active scene's volume cap (Nuit: 20). */
    val volumeLimit: Int = 100,
    val muted: Boolean = false,
    val activeSceneId: String? = null,
    /** The active scene was applied, then changed by hand ("modifiée"). */
    val activeSceneModified: Boolean = false,
    /** In strip order. */
    val scenes: List<QuickScene> = emptyList(),
) {
    val activeScene: QuickScene? get() = scenes.firstOrNull { it.id == activeSceneId }
}

/** Mirrors `io.kud.salon.protocol.scenes.Scene`'s id, name and colour. */
data class QuickScene(
    val id: String,
    val name: String,
    /** ARGB seed, as `Scene.color`. */
    val color: Long,
)
