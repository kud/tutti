package io.kud.tutti.platform.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.protocol.apps.KnownApp
import io.kud.tutti.protocol.devices.DeviceList
import io.kud.tutti.protocol.devices.DeviceMigration
import io.kud.tutti.protocol.scenes.AppProfile
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneJson
import io.kud.tutti.protocol.scenes.SceneListSerializer
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
    /**
     * Every configured device (version 2, key `devices_v2`). Until [migrateDevices] has written it,
     * it reads as the migration of the version-1 `devices` key, so nothing ever sees an empty list
     * in between.
     */
    val deviceList: Flow<DeviceList> = store.data
        .map { prefs -> readList(prefs) ?: DeviceList() }
        .distinctUntilChanged()

    /** The TV and the bar as the rest of the app reads them: a view of [deviceList]. */
    val devices: Flow<Devices> = deviceList.map(DeviceViews::legacy).distinctUntilChanged()
    val prefs: Flow<AppPrefs> = field(PREFS, AppPrefs.serializer(), StoreJson) { AppPrefs() }
    val dock: Flow<DockConfig> = field(DOCK, DockConfig.serializer(), StoreJson) { DockConfig() }
    val scenes: Flow<List<Scene>> = field(SCENES, SceneListSerializer, SceneJson) { StarterScenes.all }
        .map(::localiseStarters)
    /** Apps the TV ever listed, kept so the Apps sheet works without IP control. */
    val apps: Flow<List<KnownApp>> = field(APPS, ListSerializer(KnownApp.serializer()), StoreJson) { emptyList() }
    val profiles: Flow<List<AppProfile>> = field(PROFILES, ListSerializer(AppProfile.serializer()), SceneJson) { emptyList() }

    suspend fun updateDevices(transform: (Devices) -> Devices) = updateDeviceList { list ->
        DeviceViews.applyLegacy(list, transform(DeviceViews.legacy(list)))
    }

    /**
     * One edit writes the list and, for one release, the version-1 `devices` key it implies, so a
     * downgrade still finds its TV and bar.
     */
    suspend fun updateDeviceList(transform: (DeviceList) -> DeviceList) {
        store.edit { prefs ->
            val next = transform(readList(prefs) ?: DeviceList())
            prefs[DEVICES_V2] = StoreJson.encodeToString(DeviceList.serializer(), next)
            prefs[DEVICES] = StoreJson.encodeToString(Devices.serializer(), DeviceViews.legacy(next))
        }
    }

    /** Writes the migrated list once (idempotent). The old `devices` key is left in place. */
    suspend fun migrateDevices() {
        val host = legacyHidHost()
        store.edit { prefs -> migrateDeviceList(prefs, host) }
    }

    private fun readList(prefs: Preferences): DeviceList? = resolveDeviceList(prefs, legacyHidHost())

    /** The host the version-1 Bluetooth screen remembered (`bluetooth_hid`), always the TV. */
    private fun legacyHidHost(): String? =
        context.getSharedPreferences("bluetooth_hid", Context.MODE_PRIVATE).getString("host", null)

    suspend fun updatePrefs(transform: (AppPrefs) -> AppPrefs) = update(PREFS, AppPrefs.serializer(), StoreJson, AppPrefs(), transform)

    suspend fun updateDock(transform: (DockConfig) -> DockConfig) = update(DOCK, DockConfig.serializer(), StoreJson, DockConfig(), transform)

    suspend fun updateScenes(transform: (List<Scene>) -> List<Scene>) =
        update(SCENES, SceneListSerializer, SceneJson, StarterScenes.all) { transform(localiseStarters(it)) }

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
        private val DEVICES_V2 = stringPreferencesKey("devices_v2")

        /** The device list as every reader sees it: version 2, else the migration of version 1. */
        internal fun resolveDeviceList(prefs: Preferences, legacyHidHost: String?): DeviceList? {
            val current = prefs[DEVICES_V2]?.let { runCatching { StoreJson.decodeFromString(DeviceList.serializer(), it) }.getOrNull() }
            return DeviceMigration.migrate(current, prefs[DEVICES], legacyHidHost)
        }

        /** [migrateDevices]'s one edit: writes version 2 once, leaves version 1 in place. */
        internal fun migrateDeviceList(prefs: MutablePreferences, legacyHidHost: String?) {
            if (prefs[DEVICES_V2] != null) return
            val migrated = resolveDeviceList(prefs, legacyHidHost) ?: return
            prefs[DEVICES_V2] = StoreJson.encodeToString(DeviceList.serializer(), migrated)
        }
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
