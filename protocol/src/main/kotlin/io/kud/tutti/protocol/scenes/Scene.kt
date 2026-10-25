package io.kud.tutti.protocol.scenes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything a scene can hold. A scene holds any subset. */
@Serializable
enum class SceneKey {
    /** BRAVIA input URI, e.g. `extInput:hdmi?port=2`. */
    TV_INPUT,

    /** Android TV app package brought to the foreground. */
    TV_APP,

    /** Picture mode value as the TV's own `getPictureQualitySettings` candidates spell it. */
    PICTURE_MODE,

    /**
     * Screen off, sound on: the TV's power saving set to `pictureOff` (on) or back to `off`.
     * Applied last, so launching the scene's app can't light the screen again.
     */
    PICTURE_OFF,
    SOUND_FIELD,
    VOICE,
    NIGHT,
    BASS,
    VOLUME,

    /** Not a device state: a ceiling. Applying clamps the volume; while active the app enforces it. */
    VOLUME_CAP,
}

@Serializable
sealed interface SceneValue {
    @Serializable
    @SerialName("flag")
    data class Flag(val on: Boolean) : SceneValue

    @Serializable
    @SerialName("level")
    data class Level(val value: Int) : SceneValue

    @Serializable
    @SerialName("text")
    data class Text(val value: String) : SceneValue
}

@Serializable
data class Scene(
    val id: String,
    val name: String,
    /** ARGB seed for the pill's tonal colour. */
    val color: Long,
    /** Material Symbols name. */
    val glyph: String,
    val settings: Map<SceneKey, SceneValue> = emptyMap(),
    /** Final action: an app package to launch once everything else is applied. */
    val launchApp: String? = null,
)

/** "When Netflix is in front, apply Cinéma" (while connected). */
@Serializable
data class AppProfile(val appPackage: String, val sceneId: String)

object AppProfiles {
    fun sceneFor(currentApp: String?, profiles: List<AppProfile>): String? =
        currentApp?.let { app -> profiles.firstOrNull { it.appPackage == app }?.sceneId }
}

/** The JSON used to persist scenes. Tolerant of fields added later. */
val SceneJson: Json = Json { ignoreUnknownKeys = true }
