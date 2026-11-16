package io.kud.tutti.platform.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.di.preferencesStore
import io.kud.tutti.protocol.privacy.NonFreeFeature
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
 * The upgrade to the Cast artwork switch (`AppPrefs.castArtwork`, 2026-11-12), on a real DataStore
 * file like [UpgradeTitleLinksTest], read through [StoredPrefs] as `TuttiStore` reads it. The artwork
 * was on unconditionally before the switch, so an install that finished setup keeps it on; a fresh
 * install starts off; once written, the stored value is the person's and the rule never fires again.
 * Trakt, JustWatch and IMDb keep what was stored. Values are invented.
 */
class UpgradeCastArtworkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val prefsKey = stringPreferencesKey("prefs")

    /** What the builds before the switch wrote: setup done, the title links as the person set them. */
    private val beforeSwitch =
        """{"vibration":"ESSENTIALS","keySounds":true,"theme":"DARK","padMode":"DPAD","onboarded":true,""" +
            """"developerMode":false,"lastBarVolume":18,"traktLink":true,"justWatchLink":false,"imdbLink":true,""" +
            """"eveningDim":true}"""

    private fun decode(json: String): AppPrefs = StoreJson.decodeFromString(StoredPrefs, json)

    private fun encode(prefs: AppPrefs): String = StoreJson.encodeToString(StoredPrefs, prefs)

    @Test
    fun `an install set up before the switch keeps the artwork on, and its links as they were`() = runBlocking {
        val tutti = preferencesStore { File(folder.root, "tutti.preferences_pb") }
        tutti.edit { it[prefsKey] = beforeSwitch }

        val read = decode(tutti.data.first()[prefsKey]!!)
        assertTrue(read.castArtwork)
        assertTrue(read.allows(NonFreeFeature.CAST_ARTWORK))
        assertTrue(read.traktLink)
        assertFalse(read.justWatchLink)
        assertTrue(read.imdbLink)
        assertFalse(read.titleDetails)
        assertEquals(ThemeMode.DARK, read.theme)
        assertTrue(read.eveningDim)

        // Any later write stores the field, so turning it off sticks.
        tutti.edit { it[prefsKey] = encode(read.copy(castArtwork = false)) }
        val written = tutti.data.first()[prefsKey]!!
        assertTrue(written, "\"castArtwork\":false" in written)
        val off = decode(written)
        assertFalse(off.castArtwork)
        assertTrue(off.traktLink && off.imdbLink)

        tutti.edit { it[prefsKey] = encode(off.copy(castArtwork = true)) }
        assertTrue(decode(tutti.data.first()[prefsKey]!!).castArtwork)
    }

    @Test
    fun `a fresh install starts off, and stays off once setup writes its settings`() = runBlocking {
        assertFalse(decode("{}").castArtwork)
        assertFalse(AppPrefs().castArtwork)

        val tutti = preferencesStore { File(folder.root, "fresh.preferences_pb") }
        // Onboarding finishing is the first write that says onboarded, and it carries the field.
        tutti.edit { it[prefsKey] = encode(AppPrefs().copy(onboarded = true)) }
        val after = decode(tutti.data.first()[prefsKey]!!)
        assertTrue(after.onboarded)
        assertFalse(after.castArtwork)
    }

    @Test
    fun `stored prefs from before setup finished read the new default`() {
        assertFalse(decode("""{"theme":"DARK","onboarded":false}""").castArtwork)
        assertFalse(decode("""{"theme":"DARK"}""").castArtwork)
    }

    @Test
    fun `only free services still wins over the kept artwork`() {
        val strict = decode(beforeSwitch.dropLast(1) + ""","onlyFreeServices":true}""")
        assertTrue(strict.castArtwork)
        assertFalse(strict.allows(NonFreeFeature.CAST_ARTWORK))
    }
}
