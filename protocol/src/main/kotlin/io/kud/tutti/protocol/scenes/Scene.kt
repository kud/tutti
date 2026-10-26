package io.kud.tutti.protocol.scenes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

/** Everything a scene can hold. A scene holds any subset. */
@Serializable
enum class SceneKey {
    /** BRAVIA input URI, e.g. `extInput:hdmi?port=2`. */
    TV_INPUT,

    /** Android TV app package brought to the foreground. */
    TV_APP,

    /** Picture mode value as the TV's own `getPictureQualitySettings` candidates spell it. */
    PICTURE_MODE,

    /** Where the TV sends its sound: the `outputTerminal` value the TV spells (`speaker`, `audioSystem`…). */
    TV_AUDIO_OUTPUT,

    /**
     * Screen off, sound on: the TV's power saving set to `pictureOff` (on) or back to `off`.
     * Applied last, so launching the scene's app can't light the screen again.
     */
    PICTURE_OFF,

    /** The bar's input (`main.input`: tv, hdmi1, spotify, bluetooth, airplay2). */
    BAR_INPUT,
    SOUND_FIELD,
    VOICE,
    NIGHT,

    /** 360 Spatial Sound Mapping (`audio.360ssm`). */
    SPATIAL_360,

    /** Voice Zoom 3 (`audio.voicezoom3`), only on a bar that answers it. */
    VOICE_ZOOM,
    BASS,
    VOLUME,

    /** Not a device state: a ceiling. Applying clamps the volume; while active the app enforces it. */
    VOLUME_CAP,

    /**
     * Not a device state: where the remote's keys go, a device id (the TV or a computer). Applied
     * last, as a hand-picked target holding until the TV's input changes.
     */
    TARGET,
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
    @Serializable(with = SceneSettingsSerializer::class)
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

/**
 * A scene's settings, read one entry at a time: a key this version does not know (a [SceneKey]
 * added later) or a value it cannot read (a [SceneValue] kind added later) is skipped, never the
 * whole scene. Without it, a scene saved by a newer version would fail to decode, the store would
 * fall back to the starter scenes, and the next edit would write them over his own.
 */
object SceneSettingsSerializer : KSerializer<Map<SceneKey, SceneValue>> {
    private val strict = MapSerializer(SceneKey.serializer(), SceneValue.serializer())
    override val descriptor: SerialDescriptor = strict.descriptor

    override fun serialize(encoder: Encoder, value: Map<SceneKey, SceneValue>) = strict.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Map<SceneKey, SceneValue> {
        val json = decoder as? JsonDecoder ?: return strict.deserialize(decoder)
        val entries = json.decodeJsonElement() as? JsonObject ?: return emptyMap()
        val known = SceneKey.entries.associateBy { it.name }
        return buildMap {
            for ((name, raw) in entries) {
                val key = known[name] ?: continue
                val value = runCatching { json.json.decodeFromJsonElement(SceneValue.serializer(), raw) }.getOrNull() ?: continue
                put(key, value)
            }
        }
    }
}

/**
 * The stored scene list, read one scene at a time: a scene this version cannot read at all is left
 * out rather than taking every other scene with it.
 */
object SceneListSerializer : KSerializer<List<Scene>> {
    private val strict = ListSerializer(Scene.serializer())
    override val descriptor: SerialDescriptor = strict.descriptor

    override fun serialize(encoder: Encoder, value: List<Scene>) = strict.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<Scene> {
        val json = decoder as? JsonDecoder ?: return strict.deserialize(decoder)
        val items = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return items.mapNotNull { runCatching { json.json.decodeFromJsonElement(Scene.serializer(), it) }.getOrNull() }
    }
}
