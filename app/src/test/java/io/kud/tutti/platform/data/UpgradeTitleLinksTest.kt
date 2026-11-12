package io.kud.tutti.platform.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.di.preferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The upgrade to the Trakt and JustWatch switches (`AppPrefs.traktLink`, `AppPrefs.justWatchLink`),
 * on a real DataStore file, like [UpgradeEveningDimTest]: `prefs` is what the builds before them
 * wrote, copied literally. Every setting survives, both switches read off, and each turns on alone
 * through the same write any settings change makes. Values are invented.
 */
class UpgradeTitleLinksTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val prefsKey = stringPreferencesKey("prefs")

    private val beforeTitleLinks =
        """{"haptics":true,"vibration":"ESSENTIALS","keySounds":true,"theme":"DARK","trueBlack":true,""" +
            """"hiddenApps":["com.example.hidden"],"padMode":"DPAD","onboarded":true,"developerMode":true,""" +
            """"lastBarVolume":18,"hints":{"shown":["pad-two-fingers"],"lastDay":20750},""" +
            """"barInputNames":{"hdmi1":"Console"},"hiddenBarInputs":["bt"],"volumeGuard":true,"volumeGuardLevel":40,""" +
            """"volumePresets":[12,null,30],"tonightCap":{"level":25,"until":1793000000000},"eveningDim":true}"""

    private fun decode(json: String): AppPrefs = StoreJson.decodeFromString(AppPrefs.serializer(), json)

    @Test
    fun `settings stored before the switches survive, and both read off`() = runBlocking {
        val tutti = preferencesStore { File(folder.root, "tutti.preferences_pb") }
        tutti.edit { it[prefsKey] = beforeTitleLinks }

        val read = decode(tutti.data.first()[prefsKey]!!)
        assertKept(read)
        assertFalse(read.traktLink)
        assertFalse(read.justWatchLink)
        assertFalse(read.imdbLink)

        tutti.edit { it[prefsKey] = StoreJson.encodeToString(AppPrefs.serializer(), read.copy(traktLink = true)) }
        val written = tutti.data.first()[prefsKey]!!
        assertTrue(written, "\"traktLink\":true" in written)
        val again = decode(written)
        assertKept(again)
        assertTrue(again.traktLink)
        assertFalse(again.justWatchLink)

        tutti.edit { it[prefsKey] = StoreJson.encodeToString(AppPrefs.serializer(), again.copy(traktLink = false, justWatchLink = true)) }
        val last = decode(tutti.data.first()[prefsKey]!!)
        assertKept(last)
        assertFalse(last.traktLink)
        assertTrue(last.justWatchLink)
        assertFalse(last.imdbLink)

        tutti.edit { it[prefsKey] = StoreJson.encodeToString(AppPrefs.serializer(), last.copy(imdbLink = true)) }
        val imdb = decode(tutti.data.first()[prefsKey]!!)
        assertKept(imdb)
        assertTrue(imdb.imdbLink)
        assertTrue(imdb.justWatchLink)
    }

    @Test
    fun `nothing stored reads both off`() {
        val prefs = decode("{}")
        assertFalse(prefs.traktLink)
        assertFalse(prefs.justWatchLink)
        assertFalse(prefs.imdbLink)
    }

    private fun assertKept(prefs: AppPrefs) {
        assertEquals(VibrationLevel.ESSENTIALS, prefs.vibrationLevel)
        assertTrue(prefs.keySounds)
        assertEquals(ThemeMode.DARK, prefs.theme)
        assertTrue(prefs.trueBlack)
        assertEquals(setOf("com.example.hidden"), prefs.hiddenApps)
        assertEquals(PadMode.DPAD, prefs.padMode)
        assertTrue(prefs.onboarded)
        assertTrue(prefs.developerMode)
        assertEquals(18, prefs.lastBarVolume)
        assertEquals(HintMemory(setOf("pad-two-fingers"), 20750L), prefs.hints)
        assertEquals(mapOf("hdmi1" to "Console"), prefs.barInputNames)
        assertEquals(setOf("bt"), prefs.hiddenBarInputs)
        assertTrue(prefs.volumeGuard)
        assertEquals(40, prefs.volumeGuardLevel)
        assertEquals(listOf(12, null, 30), prefs.volumePresets)
        assertEquals(TonightCap(25, 1_793_000_000_000L), prefs.tonightCap)
        assertTrue(prefs.eveningDim)
    }
}
