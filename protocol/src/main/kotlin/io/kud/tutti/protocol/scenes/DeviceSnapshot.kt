package io.kud.tutti.protocol.scenes

import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState

/** What is known right now, in scene terms. A missing key means unknown, not "off". */
data class DeviceSnapshot(
    val values: Map<SceneKey, SceneValue> = emptyMap(),
    val powered: Boolean? = null,
) {
    operator fun get(key: SceneKey): SceneValue? = values[key]

    companion object {
        /** Bar settings each scene key maps to. */
        val BAR_FEATURES: Map<SceneKey, TheatreFeature> = mapOf(
            SceneKey.SOUND_FIELD to TheatreFeature.SOUND_FIELD,
            SceneKey.VOICE to TheatreFeature.VOICE,
            SceneKey.NIGHT to TheatreFeature.NIGHT,
            SceneKey.BASS to TheatreFeature.BASS,
            SceneKey.VOLUME to TheatreFeature.VOLUME,
        )

        fun from(
            theatre: TheatreState?,
            tvInput: String? = null,
            currentApp: String? = null,
            pictureMode: String? = null,
            tvPowered: Boolean? = null,
        ): DeviceSnapshot {
            val values = mutableMapOf<SceneKey, SceneValue>()
            tvInput?.let { values[SceneKey.TV_INPUT] = SceneValue.Text(it) }
            currentApp?.let { values[SceneKey.TV_APP] = SceneValue.Text(it) }
            pictureMode?.let { values[SceneKey.PICTURE_MODE] = SceneValue.Text(it) }
            if (theatre != null) {
                for ((key, feature) in BAR_FEATURES) {
                    val value = if (feature.toggleValue(true) != null) {
                        theatre.isOn(feature)?.let { SceneValue.Flag(it) }
                    } else {
                        theatre.level(feature)?.let { SceneValue.Level(it) }
                    }
                    if (value != null) values[key] = value
                }
            }
            val barPowered = theatre?.isOn(TheatreFeature.POWER)
            val powered = when {
                tvPowered == false || barPowered == false -> false
                tvPowered == true && (barPowered == true || theatre == null) -> true
                else -> null
            }
            return DeviceSnapshot(values, powered)
        }
    }
}
