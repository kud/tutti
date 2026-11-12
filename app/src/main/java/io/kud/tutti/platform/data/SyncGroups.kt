package io.kud.tutti.platform.data

import io.kud.tutti.protocol.scenes.AppProfile
import io.kud.tutti.protocol.scenes.SceneJson
import io.kud.tutti.protocol.scenes.SceneListSerializer
import io.kud.tutti.protocol.scenes.StarterScenes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What settings sync carries, as groups of JSON, between the stored values and the sync file
 * (docs/adr/0001-settings-sync.md). Only these four stored keys ever reach it: `prefs`, `dock`,
 * `scenes` and `profiles`. Devices, hosts, the TV's app list and everything in SecureStore have no
 * way in, and nothing here can write them.
 */
internal object SyncGroups {
    const val PREFERENCES = "preferences"
    const val REMOTE = "remote"
    const val SCENES = "scenes"
    const val PROFILES = "profiles"

    /**
     * Preferences that stay on this phone: setup done, developer mode, the bar's last volume (a
     * reading, not a setting), the one-time tips already shown, tonight's cap (a timed state), and
     * the layout, which travels in [REMOTE] with the dock.
     */
    val LOCAL_PREFS = setOf("onboarded", "developerMode", "lastBarVolume", "hints", "tonightCap", "layout")

    /** The four stored strings, as DataStore holds them (null when never written). */
    data class Raw(val prefs: String?, val dock: String?, val scenes: String?, val profiles: String?)

    private val profilesSerializer = ListSerializer(AppProfile.serializer())

    fun read(raw: Raw): Map<String, JsonElement> {
        val prefs = prefsObject(raw.prefs)
        val dock = decode(raw.dock, DockConfig.serializer(), StoreJson) ?: DockConfig()
        val scenes = decode(raw.scenes, SceneListSerializer, SceneJson) ?: StarterScenes.all
        val profiles = decode(raw.profiles, profilesSerializer, SceneJson) ?: emptyList()
        return mapOf(
            PREFERENCES to JsonObject(prefs.filterKeys { it !in LOCAL_PREFS }),
            REMOTE to buildJsonObject {
                put("layout", prefs.getValue("layout"))
                put("dock", StoreJson.encodeToJsonElement(DockConfig.serializer(), dock))
            },
            SCENES to SceneJson.encodeToJsonElement(SceneListSerializer, scenes),
            PROFILES to SceneJson.encodeToJsonElement(profilesSerializer, profiles),
        )
    }

    /**
     * [raw] with [incoming] groups taken in. A group that doesn't decode here is left out and the
     * stored value kept: a bad file never empties a setting. Unknown groups are ignored.
     */
    fun apply(raw: Raw, incoming: Map<String, JsonElement>): Raw {
        var prefs = prefsObject(raw.prefs)
        var prefsChanged = false
        var dock = raw.dock

        (incoming[PREFERENCES] as? JsonObject)?.let { synced ->
            // A key the other phone doesn't know (an older tutti) keeps this phone's value.
            val next = JsonObject(prefs + synced.filterKeys { it !in LOCAL_PREFS })
            if (decodes(next, AppPrefs.serializer(), StoreJson)) {
                prefs = next
                prefsChanged = true
            }
        }
        (incoming[REMOTE] as? JsonObject)?.let { remote ->
            remote["layout"]?.takeIf { decodes(it, RemoteLayout.serializer(), StoreJson) }?.let {
                prefs = JsonObject(prefs + ("layout" to it))
                prefsChanged = true
            }
            remote["dock"]?.let { element ->
                runCatching { StoreJson.decodeFromJsonElement(DockConfig.serializer(), element) }.getOrNull()
                    ?.let { dock = StoreJson.encodeToString(DockConfig.serializer(), it.safeLinks()) }
            }
        }
        // The scene list reader is forgiving (it skips what it can't read), so anything but a list is refused here.
        val scenes = (incoming[SCENES] as? JsonArray)?.let { element ->
            runCatching { SceneJson.decodeFromJsonElement(SceneListSerializer, element) }.getOrNull()
                ?.let { SceneJson.encodeToString(SceneListSerializer, it) }
        } ?: raw.scenes
        val profiles = (incoming[PROFILES] as? JsonArray)?.let { element ->
            runCatching { SceneJson.decodeFromJsonElement(profilesSerializer, element) }.getOrNull()
                ?.let { SceneJson.encodeToString(profilesSerializer, it) }
        } ?: raw.profiles
        val prefsText = if (prefsChanged) {
            StoreJson.encodeToString(AppPrefs.serializer(), StoreJson.decodeFromJsonElement(AppPrefs.serializer(), prefs).safeDestinations())
        } else {
            raw.prefs
        }
        return Raw(prefsText, dock, scenes, profiles)
    }

    private fun prefsObject(text: String?): JsonObject {
        val prefs = decode(text, AppPrefs.serializer(), StoreJson) ?: AppPrefs()
        return StoreJson.encodeToJsonElement(AppPrefs.serializer(), prefs).jsonObject
    }

    private fun <T> decode(text: String?, serializer: KSerializer<T>, json: Json): T? =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> decodes(element: JsonElement, serializer: KSerializer<T>, json: Json): Boolean =
        runCatching { json.decodeFromJsonElement(serializer, element) }.isSuccess
}
