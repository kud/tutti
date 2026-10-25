package io.kud.tutti.di

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a corrupt store starts empty instead of crashing`() = runBlocking {
        val file = folder.newFile("broken.preferences_pb").apply { writeBytes(byteArrayOf(0x0a, 0x7f, 0x01, 0x02)) }
        val store = preferencesStore { file }
        val prefs = store.data.first()
        assertTrue(prefs.asMap().isEmpty())
        assertTrue(prefs[stringPreferencesKey("anything")] == null)
    }
}
