package io.kud.salon.quick

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One DataStore per file per process: the delegate guarantees it. Nothing secret lives here. */
private val Context.quickDataStore: DataStore<Preferences> by preferencesDataStore(name = "quick_prefs")

/** Preferences owned by the quick surfaces. Cheap to construct; the store is shared. */
class QuickPreferences(context: Context) {
    private val store = context.applicationContext.quickDataStore

    /** The ongoing control notification. On by default; Android 13+ still needs the runtime permission. */
    val notificationEnabled: Flow<Boolean> = store.data.map { it[NOTIFICATION_ENABLED] ?: true }

    suspend fun setNotificationEnabled(enabled: Boolean) {
        store.edit { it[NOTIFICATION_ENABLED] = enabled }
    }

    private companion object {
        val NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
    }
}
