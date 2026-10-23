package io.kud.tutti.platform.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.protocol.apps.KnownApp
import io.kud.tutti.protocol.scenes.AppProfile
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneJson
import io.kud.tutti.protocol.scenes.StarterScenes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.R
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Non-secret app data: devices (hosts only), scenes, per-app profiles, dock, preferences.
 * Backed up normally. Secrets live in SecureStore, never here.
 */
@Singleton
class TuttiStore @Inject constructor(
    @Named(TUTTI) private val store: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
) {
    val devices: Flow<Devices> = field(DEVICES, Devices.serializer(), StoreJson) { Devices() }
    val prefs: Flow<AppPrefs> = field(PREFS, AppPrefs.serializer(), StoreJson) { AppPrefs() }
    val dock: Flow<DockConfig> = field(DOCK, DockConfig.serializer(), StoreJson) { DockConfig() }
    val scenes: Flow<List<Scene>> = field(SCENES, ListSerializer(Scene.serializer()), SceneJson) { StarterScenes.all }
        .map(::localiseStarters)
    /** Apps the TV ever listed, kept so the Apps sheet works without IP control. */
    val apps: Flow<List<KnownApp>> = field(APPS, ListSerializer(KnownApp.serializer()), StoreJson) { emptyList() }
    val profiles: Flow<List<AppProfile>> = field(PROFILES, ListSerializer(AppProfile.serializer()), SceneJson) { emptyList() }

    suspend fun updateDevices(transform: (Devices) -> Devices) = update(DEVICES, Devices.serializer(), StoreJson, Devices(), transform)

    suspend fun updatePrefs(transform: (AppPrefs) -> AppPrefs) = update(PREFS, AppPrefs.serializer(), StoreJson, AppPrefs(), transform)

    suspend fun updateDock(transform: (DockConfig) -> DockConfig) = update(DOCK, DockConfig.serializer(), StoreJson, DockConfig(), transform)

    suspend fun updateScenes(transform: (List<Scene>) -> List<Scene>) =
        update(SCENES, ListSerializer(Scene.serializer()), SceneJson, StarterScenes.all) { transform(localiseStarters(it)) }

    /**
     * Starter scenes still carrying their shipped name read in the phone's language. A name he
     * changed is his and stays as typed.
     */
    private fun localiseStarters(scenes: List<Scene>): List<Scene> = scenes.map { scene ->
        val shipped = STARTER_NAMES[scene.id] ?: return@map scene
        if (scene.name == StarterScenes.all.first { it.id == scene.id }.name) scene.copy(name = context.getString(shipped)) else scene
    }

    suspend fun updateApps(transform: (List<KnownApp>) -> List<KnownApp>) =
        update(APPS, ListSerializer(KnownApp.serializer()), StoreJson, emptyList(), transform)

    suspend fun updateProfiles(transform: (List<AppProfile>) -> List<AppProfile>) =
        update(PROFILES, ListSerializer(AppProfile.serializer()), SceneJson, emptyList(), transform)

    private fun <T> field(
        key: Preferences.Key<String>,
        serializer: KSerializer<T>,
        json: kotlinx.serialization.json.Json,
        default: () -> T,
    ): Flow<T> = store.data
        .map { prefs -> prefs[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default() }
        .distinctUntilChanged()

    private suspend fun <T> update(
        key: Preferences.Key<String>,
        serializer: KSerializer<T>,
        json: kotlinx.serialization.json.Json,
        default: T,
        transform: (T) -> T,
    ) {
        store.edit { prefs ->
            val current = prefs[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default
            prefs[key] = json.encodeToString(serializer, transform(current))
        }
    }

    companion object {
        const val TUTTI = "tutti"
        private val DEVICES = stringPreferencesKey("devices")
        private val PREFS = stringPreferencesKey("prefs")
        private val DOCK = stringPreferencesKey("dock")
        private val SCENES = stringPreferencesKey("scenes")
        private val PROFILES = stringPreferencesKey("profiles")
        private val APPS = stringPreferencesKey("apps")
    }
}

private val STARTER_NAMES = mapOf(
    "starter-cinema" to R.string.scene_starter_cinema,
    "starter-serie" to R.string.scene_starter_serie,
    "starter-nuit" to R.string.scene_starter_nuit,
    "starter-musique" to R.string.scene_starter_musique,
    "starter-sport" to R.string.scene_starter_sport,
    "starter-jeu" to R.string.scene_starter_jeu,
    "starter-info" to R.string.scene_starter_info,
)
