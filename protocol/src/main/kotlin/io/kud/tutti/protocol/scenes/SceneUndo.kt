package io.kud.tutti.protocol.scenes

/**
 * "Undo the last scene" (Iris's gesture audit, 2026-10-25): the values a scene was about to change,
 * as they stood just before it ran, and the scene that was active then. One level only: applying
 * another scene replaces it, undoing spends it.
 *
 * Only what the scene touches is kept ([touchedKeys]), so undoing never reverts a change made by
 * hand to something the scene left alone. Values unknown at capture are not restored.
 *
 * [takenAt] is a monotonic clock (elapsedRealtime in the app), never the wall clock.
 */
data class SceneUndo(
    val sceneId: String,
    /** The scene active before this one, so its pill (and its volume cap) come back too. */
    val previousSceneId: String?,
    val before: Map<SceneKey, SceneValue>,
    val takenAt: Long,
) {
    /**
     * The steps that bring the devices back to [before]. Never a wake: an undo restores settings,
     * it does not switch anything on (or off), so a scene that woke the TV leaves it on.
     */
    fun plan(snapshot: DeviceSnapshot): List<SceneStep> =
        SceneEngine.plan(Scene(id = UNDO_ID, name = "", color = 0, glyph = "", settings = before), snapshot.copy(powered = null))

    /**
     * Stale, and no longer offered: past [WINDOW_MS]; the scene it undoes is no longer the active
     * one (another scene ran, it was deleted, the TV was forgotten); or the room was switched off,
     * which ends the evening it belonged to.
     */
    fun stale(now: Long, activeSceneId: String?, powered: Boolean?): Boolean =
        now - takenAt !in 0..WINDOW_MS || activeSceneId != sceneId || powered == false

    companion object {
        /** Fifteen minutes: long enough to notice a scene got it wrong, short enough not to surprise. */
        const val WINDOW_MS: Long = 15 * 60_000L

        const val UNDO_ID = "undo"

        /**
         * What running [scene] would change. A volume cap can lower the volume, so it takes the volume;
         * an input or an app takes both, since either replaces what is on screen.
         */
        fun touchedKeys(scene: Scene): Set<SceneKey> {
            val keys = scene.settings.keys.toMutableSet()
            if (keys.remove(SceneKey.VOLUME_CAP)) keys += SceneKey.VOLUME
            if (scene.launchApp != null || SceneKey.TV_INPUT in keys || SceneKey.TV_APP in keys) {
                keys += SceneKey.TV_INPUT
                keys += SceneKey.TV_APP
            }
            return keys
        }

        /**
         * Taken just before [scene] runs, from what is known now. Null when there is nothing to go
         * back to: no known value it touches, and it is already the active scene.
         */
        fun capture(scene: Scene, snapshot: DeviceSnapshot, activeSceneId: String?, now: Long): SceneUndo? {
            val touched = touchedKeys(scene)
            val before = SceneEngine.capture(snapshot).filterKeys { it in touched }
            if (before.isEmpty() && activeSceneId == scene.id) return null
            return SceneUndo(scene.id, activeSceneId, before, now)
        }
    }
}
