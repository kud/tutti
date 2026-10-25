package io.kud.tutti.quick

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

    /** Lets `tutti://scene/<id>` (NFC, Tasker) apply a scene from outside the app. Off by default. */
    val automationEnabled: Flow<Boolean> = store.data.map { it[AUTOMATION_ENABLED] ?: false }

    suspend fun setAutomationEnabled(enabled: Boolean) {
        store.edit { it[AUTOMATION_ENABLED] = enabled }
    }

    private companion object {
        val NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        val AUTOMATION_ENABLED = booleanPreferencesKey("automation_enabled")
    }
}
