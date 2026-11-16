package io.kud.tutti.platform.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.di.preferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The upgrade to app destinations (2026-11-11): `AppPrefs.appDestinations` and
 * `DockAction.LaunchApp.link`, on a real DataStore file like [UpgradeTitleLinksTest]. What the
 * builds before them wrote is copied literally: every setting, hidden app and pin survives, no app
 * gains a destination, and an app pin follows the app ("Same as the app"). Values are invented.
 */
class UpgradeAppDestinationsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val prefsKey = stringPreferencesKey("prefs")

    private val before =
        """{"haptics":true,"vibration":"ESSENTIALS","keySounds":true,"theme":"DARK","trueBlack":true,""" +
            """"hiddenApps":["com.example.hidden"],"padMode":"DPAD","onboarded":true,"developerMode":true,""" +
            """"volumeGuard":true,"volumeGuardLevel":40,"eveningDim":true,"traktLink":true}"""

    private val subscriptions = "https://www.youtube.com/feed/subscriptions"

    private fun decode(json: String): AppPrefs = StoreJson.decodeFromString(AppPrefs.serializer(), json)

    @Test
    fun `settings stored before destinations survive, with none set`() = runBlocking {
        val tutti = preferencesStore { File(folder.root, "tutti.preferences_pb") }
        tutti.edit { it[prefsKey] = before }

        val read = decode(tutti.data.first()[prefsKey]!!)
        assertKept(read)
        assertTrue(read.appDestinations.isEmpty())

        val set = read.copy(appDestinations = mapOf("com.google.android.youtube.tv" to subscriptions))
        tutti.edit { it[prefsKey] = StoreJson.encodeToString(AppPrefs.serializer(), set) }
        val again = decode(tutti.data.first()[prefsKey]!!)
        assertKept(again)
        assertEquals(subscriptions, again.appDestinations["com.google.android.youtube.tv"])
    }

    @Test
    fun `an app pin stored before destinations follows the app, and a destination round-trips`() {
        val old = """{"type":"app","packageName":"com.google.android.youtube.tv","label":"YouTube"}"""
        val pin = StoreJson.decodeFromString(DockAction.serializer(), old)
        assertEquals(DockAction.LaunchApp("com.google.android.youtube.tv", "YouTube"), pin)
        assertNull((pin as DockAction.LaunchApp).link)

        val chosen = pin.copy(link = subscriptions)
        assertEquals(chosen, StoreJson.decodeFromString(DockAction.serializer(), StoreJson.encodeToString(DockAction.serializer(), chosen)))
    }

    private fun assertKept(p: AppPrefs) {
        assertEquals(setOf("com.example.hidden"), p.hiddenApps)
        assertEquals(VibrationLevel.ESSENTIALS, p.vibration)
        assertEquals(ThemeMode.DARK, p.theme)
        assertEquals(PadMode.DPAD, p.padMode)
        assertEquals(40, p.volumeGuardLevel)
        assertTrue(p.onboarded && p.developerMode && p.eveningDim && p.traktLink && p.trueBlack)
    }
}
