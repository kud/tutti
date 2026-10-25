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
 * The upgrade to the "Salle obscure" switch (`AppPrefs.eveningDim`), on a real DataStore file.
 * `prefs` holds what the builds before it wrote, copied literally rather than re-encoded by
 * today's classes: the test reads it the way `TuttiStore.prefs` does, checks every setting
 * survived and the new switch reads off, then writes it back the way any settings change does
 * (`TuttiStore.updatePrefs`), turns the switch on, and reads everything again. Values are invented.
 */
class UpgradeEveningDimTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val prefsKey = stringPreferencesKey("prefs")

    private val beforeEveningDim =
        """{"haptics":true,"vibration":"ESSENTIALS","keySounds":true,"theme":"DARK","trueBlack":true,""" +
            """"hiddenApps":["com.example.hidden"],"padMode":"DPAD","onboarded":true,"developerMode":true,""" +
            """"lastBarVolume":18,"hints":{"shown":["pad-two-fingers"],"lastDay":20750},""" +
            """"barInputNames":{"hdmi1":"Console"},"hiddenBarInputs":["bt"],"volumeGuard":true,"volumeGuardLevel":40,""" +
            """"volumePresets":[12,null,30],"tonightCap":{"level":25,"until":1793000000000}}"""

    private fun decode(json: String): AppPrefs = StoreJson.decodeFromString(AppPrefs.serializer(), json)

    @Test
    fun `settings stored before the switch survive, and the switch reads off`() = runBlocking {
        val tutti = preferencesStore { File(folder.root, "tutti.preferences_pb") }
        tutti.edit { it[prefsKey] = beforeEveningDim }

        val read = decode(tutti.data.first()[prefsKey]!!)
        assertKept(read)
        assertFalse(read.eveningDim)

        // A settings change writes the whole object back through today's classes.
        tutti.edit { it[prefsKey] = StoreJson.encodeToString(AppPrefs.serializer(), read.copy(eveningDim = true)) }
        val written = tutti.data.first()[prefsKey]!!
        assertTrue(written, "\"eveningDim\":true" in written)
        val again = decode(written)
        assertKept(again)
        assertTrue(again.eveningDim)
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
    }
}
