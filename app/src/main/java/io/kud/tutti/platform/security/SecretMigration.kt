package io.kud.tutti.platform.security

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.protocol.devices.SecretKeys

/**
 * Version 1 → per-device secret keys, inside one DataStore edit. The encrypted blobs are copied as
 * they are (the cipher binds nothing to the key name), so nothing is decrypted, and nothing is
 * removed: the old keys stay readable for one release. Returns how many keys were copied.
 */
internal object SecretMigration {
    fun apply(prefs: MutablePreferences): Int {
        val present = prefs.asMap().keys.map { it.name }.toSet()
        val copies = SecretKeys.copies(present)
        for ((old, new) in copies) {
            val value = prefs[stringPreferencesKey(old)] ?: continue
            prefs[stringPreferencesKey(new)] = value
        }
        return copies.size
    }
}
