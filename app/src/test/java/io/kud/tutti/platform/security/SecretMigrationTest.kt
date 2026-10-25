package io.kud.tutti.platform.security

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.di.preferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Losing the PSK or the pairing is the main risk of the device list (stage 1), so the copy runs
 * here on a real DataStore file. Values are invented stand-ins for encrypted blobs.
 */
class SecretMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun key(name: String) = stringPreferencesKey(name)

    private fun store(name: String = "secure.preferences_pb") = preferencesStore { java.io.File(folder.root, name) }

    @Test
    fun `old keys are copied byte for byte and stay readable`() = runBlocking {
        val store = store()
        store.edit {
            it[key("tv_psk")] = "blob-psk=="
            it[key("tv_remote_pin")] = "blob-remote=="
            it[key("tv_rest_pin")] = "blob-rest=="
        }
        store.edit { assertEquals(3, SecretMigration.apply(it)) }
        val prefs = store.data.first()
        assertEquals("blob-psk==", prefs[key("psk/tv")])
        assertEquals("blob-remote==", prefs[key("pin/tv/remote")])
        assertEquals("blob-rest==", prefs[key("pin/tv/rest")])
        assertEquals("blob-psk==", prefs[key("tv_psk")])
        assertEquals("blob-remote==", prefs[key("tv_remote_pin")])
        assertEquals("blob-rest==", prefs[key("tv_rest_pin")])
    }

    @Test
    fun `running it again changes nothing`() = runBlocking {
        val store = store()
        store.edit { it[key("tv_psk")] = "blob-psk==" }
        store.edit { SecretMigration.apply(it) }
        val once = store.data.first().asMap()
        store.edit { assertEquals(0, SecretMigration.apply(it)) }
        assertEquals(once, store.data.first().asMap())
    }

    @Test
    fun `a newer per-device value is never overwritten by the old one`() = runBlocking {
        val store = store()
        store.edit {
            it[key("tv_psk")] = "old=="
            it[key("psk/tv")] = "new=="
        }
        store.edit { SecretMigration.apply(it) }
        assertEquals("new==", store.data.first()[key("psk/tv")])
    }

    @Test
    fun `the copy survives reopening the file, as after a crash or an update`() = runBlocking {
        val first = store("a.preferences_pb")
        first.edit { it[key("tv_psk")] = "blob-psk==" }
        first.edit { SecretMigration.apply(it) }
        val prefs = preferencesStore { java.io.File(folder.root, "b.preferences_pb") }.also { b ->
            b.edit { target -> first.data.first().asMap().forEach { (k, v) -> target[stringPreferencesKey(k.name)] = v as String } }
        }.data.first()
        assertEquals("blob-psk==", prefs[key("psk/tv")])
        assertEquals("blob-psk==", prefs[key("tv_psk")])
    }

    @Test
    fun `nothing to copy on a fresh install`() = runBlocking {
        val store = store()
        store.edit { assertEquals(0, SecretMigration.apply(it)) }
        assertNull(store.data.first()[key("psk/tv")])
    }
}
