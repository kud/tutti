package io.kud.tutti.protocol.scenes

import io.kud.tutti.protocol.theatre.TheatreFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface SceneStep {
    /** Power the TV and the bar on. */
    data object WakeUp : SceneStep

    data class TvInput(val uri: String) : SceneStep

    data class TvApp(val appPackage: String) : SceneStep

    data class PictureMode(val mode: String) : SceneStep

    data class Bar(val feature: TheatreFeature, val value: String) : SceneStep

    data class LaunchApp(val appPackage: String) : SceneStep

    /** The TV's picture off (power saving `pictureOff`) or back on (`off`), sound untouched. */
    data class PictureOff(val on: Boolean) : SceneStep
}

sealed interface SceneMatch {
    data object Matches : SceneMatch

    /** Shown as an outlined pill marked "modifiée". */
    data class Modified(val keys: Set<SceneKey>) : SceneMatch
}

object SceneEngine {
    /**
     * Ordered steps that bring the devices to [scene], skipping what already matches:
     * wake, source, picture, bar toggles, bass, volume (clamped to the cap), final app.
     */
    fun plan(scene: Scene, snapshot: DeviceSnapshot): List<SceneStep> {
        val steps = mutableListOf<SceneStep>()
        val s = scene.settings
        if (snapshot.powered == false) steps += SceneStep.WakeUp

        fun differs(key: SceneKey): Boolean = s[key] != null && s[key] != snapshot[key]

        (s[SceneKey.TV_INPUT] as? SceneValue.Text)?.let { if (differs(SceneKey.TV_INPUT)) steps += SceneStep.TvInput(it.value) }
        (s[SceneKey.TV_APP] as? SceneValue.Text)?.let { if (differs(SceneKey.TV_APP)) steps += SceneStep.TvApp(it.value) }
        (s[SceneKey.PICTURE_MODE] as? SceneValue.Text)?.let { if (differs(SceneKey.PICTURE_MODE)) steps += SceneStep.PictureMode(it.value) }

        for (key in listOf(SceneKey.SOUND_FIELD, SceneKey.VOICE, SceneKey.NIGHT)) {
            val wanted = s[key] as? SceneValue.Flag ?: continue
            if (!differs(key)) continue
            val feature = DeviceSnapshot.BAR_FEATURES.getValue(key)
            steps += SceneStep.Bar(feature, feature.toggleValue(wanted.on)!!)
        }
        (s[SceneKey.BASS] as? SceneValue.Level)?.let {
            if (differs(SceneKey.BASS)) steps += SceneStep.Bar(TheatreFeature.BASS, it.value.toString())
        }

        val cap = (s[SceneKey.VOLUME_CAP] as? SceneValue.Level)?.value
        val wantedVolume = (s[SceneKey.VOLUME] as? SceneValue.Level)?.value
        val currentVolume = (snapshot[SceneKey.VOLUME] as? SceneValue.Level)?.value
        val targetVolume = when {
            wantedVolume != null -> if (cap != null) minOf(wantedVolume, cap) else wantedVolume
            cap != null && currentVolume != null && currentVolume > cap -> cap
            else -> null
        }
        if (targetVolume != null && targetVolume != currentVolume) {
            steps += SceneStep.Bar(TheatreFeature.VOLUME, targetVolume.toString())
        }

        scene.launchApp?.let { app ->
            if ((snapshot[SceneKey.TV_APP] as? SceneValue.Text)?.value != app) steps += SceneStep.LaunchApp(app)
        }
        // Last: an app coming to the front, or an input change, could light the screen again.
        (s[SceneKey.PICTURE_OFF] as? SceneValue.Flag)?.let { if (differs(SceneKey.PICTURE_OFF)) steps += SceneStep.PictureOff(it.on) }
        return steps
    }

    /**
     * Does the current state still match [scene]? Unknown values never count as a change;
     * the volume cap only counts when the volume is above it; the final app is not compared.
     */
    fun match(scene: Scene, snapshot: DeviceSnapshot): SceneMatch {
        val modified = mutableSetOf<SceneKey>()
        for ((key, wanted) in scene.settings) {
            val current = snapshot[key] ?: continue
            when (key) {
                SceneKey.VOLUME_CAP -> {
                    val cap = (wanted as? SceneValue.Level)?.value ?: continue
                    val volume = (current as? SceneValue.Level)?.value ?: continue
                    if (volume > cap) modified += key
                }
                else -> if (current != wanted) modified += key
            }
        }
        val volume = (snapshot[SceneKey.VOLUME] as? SceneValue.Level)?.value
        val cap = (scene.settings[SceneKey.VOLUME_CAP] as? SceneValue.Level)?.value
        if (volume != null && cap != null && volume > cap) modified += SceneKey.VOLUME_CAP
        return if (modified.isEmpty()) SceneMatch.Matches else SceneMatch.Modified(modified)
    }

    /**
     * "Capturer l'état actuel": every known value, each one a removable chip.
     * An HDMI source makes the foreground app meaningless, so it is left out then.
     */
    fun capture(snapshot: DeviceSnapshot): Map<SceneKey, SceneValue> {
        val captured = snapshot.values.filterKeys { it != SceneKey.VOLUME_CAP }.toMutableMap()
        val input = (captured[SceneKey.TV_INPUT] as? SceneValue.Text)?.value
        if (input != null && input.startsWith("extInput:")) captured.remove(SceneKey.TV_APP)
        return captured
    }
}

/** Performs one step against the real devices; returns false on failure. Implemented in `:app`. */
interface SceneActuator {
    suspend fun perform(step: SceneStep): Boolean
}

data class SceneProgress(val done: Int, val total: Int, val failed: List<SceneStep> = emptyList()) {
    val fraction: Float get() = if (total == 0) 1f else done.toFloat() / total

    val finished: Boolean get() = done == total
}

/** Runs a plan step by step. A failed step is recorded and the rest still runs. Drives the pill's fill. */
class SceneRunner(private val actuator: SceneActuator) {
    fun run(steps: List<SceneStep>): Flow<SceneProgress> = flow {
        val failed = mutableListOf<SceneStep>()
        emit(SceneProgress(0, steps.size))
        steps.forEachIndexed { index, step ->
            if (!actuator.perform(step)) failed += step
            emit(SceneProgress(index + 1, steps.size, failed.toList()))
        }
    }
}
