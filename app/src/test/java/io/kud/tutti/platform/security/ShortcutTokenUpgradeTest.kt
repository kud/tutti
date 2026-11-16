package io.kud.tutti.platform.security

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.di.preferencesStore
import io.kud.tutti.protocol.devices.SecretKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The launcher-shortcut token is a new key in the secure store. An update must keep every secret
 * already there byte for byte (PSK, cookie, pins, version-1 twins), and the token must then stay
 * the same across restarts, migrations and a forgotten device, or pinned shortcuts would break.
 * The cipher is stood in for by a reversible marker; values are invented.
 */
class ShortcutTokenUpgradeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun key(name: String) = stringPreferencesKey(name)

    private fun store(name: String = "secure.preferences_pb") = preferencesStore { java.io.File(folder.root, name) }

    private val open: (String) -> String? = { it.removePrefix("sealed:").takeIf { _ -> it.startsWith("sealed:") } }
    private val seal: (String) -> String = { "sealed:$it" }

    @Test
    fun `an existing install keeps its secrets and gains a token`() = runBlocking {
        val store = store()
        store.edit {
            it[key("tv_psk")] = "blob-psk=="
            it[key("psk/tv")] = "blob-psk=="
            it[key("pin/tv/remote")] = "blob-remote=="
            it[key("ipcookie/tv")] = "blob-cookie=="
        }
        val before = store.data.first().asMap().mapKeys { it.key.name }
        var token = ""
        store.edit { token = SecureStore.ensureShortcutToken(it, open, seal) { "fresh-token" } }
        val after = store.data.first().asMap().mapKeys { it.key.name }
        assertEquals("fresh-token", token)
        assertEquals(before, after - SecureStore.SHORTCUT_TOKEN)
        assertEquals("sealed:fresh-token", after[SecureStore.SHORTCUT_TOKEN])
    }

    @Test
    fun `the token survives migration, forgetting the tv and being asked again`() = runBlocking {
        val reopened = store()
        reopened.edit {
            it[key("tv_psk")] = "blob-psk=="
            SecureStore.ensureShortcutToken(it, open, seal) { "kept-token" }
        }
        reopened.edit { SecretMigration.apply(it) }
        reopened.edit { prefs -> SecretKeys.all("tv").forEach { prefs.remove(key(it)) } }
        var token = ""
        reopened.edit { token = SecureStore.ensureShortcutToken(it, open, seal) { "should-not-be-made" } }
        assertEquals("kept-token", token)
    }

    @Test
    fun `an unreadable token is replaced rather than locking shortcuts out for good`() = runBlocking {
        val store = store()
        store.edit { it[key(SecureStore.SHORTCUT_TOKEN)] = "garbage-from-a-lost-keystore-key" }
        var token = ""
        store.edit { token = SecureStore.ensureShortcutToken(it, open, seal) { "new-token" } }
        assertEquals("new-token", token)
        assertNotEquals("garbage-from-a-lost-keystore-key", store.data.first()[key(SecureStore.SHORTCUT_TOKEN)])
    }
}
