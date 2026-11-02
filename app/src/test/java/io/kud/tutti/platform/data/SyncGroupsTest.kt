package io.kud.tutti.platform.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncGroupsTest {

    private fun raw(prefs: AppPrefs = AppPrefs(), dock: DockConfig? = null) = SyncGroups.Raw(
        prefs = StoreJson.encodeToString(AppPrefs.serializer(), prefs),
        dock = dock?.let { StoreJson.encodeToString(DockConfig.serializer(), it) },
        scenes = null,
        profiles = null,
    )

    private fun prefsOf(raw: SyncGroups.Raw) = StoreJson.decodeFromString(AppPrefs.serializer(), raw.prefs!!)

    @Test
    fun carriesTheFourGroupsAndNothingPerPhone() {
        val groups = SyncGroups.read(raw(AppPrefs(onboarded = true, developerMode = true, lastBarVolume = 12, theme = ThemeMode.DARK)))
        assertEquals(setOf("preferences", "remote", "scenes", "profiles"), groups.keys)
        val prefs = groups.getValue("preferences") as JsonObject
        for (key in SyncGroups.LOCAL_PREFS) assertFalse("$key must stay on the phone", key in prefs)
        assertEquals(JsonPrimitive("DARK"), prefs["theme"])
        // No device, host or secret has a way in: the text never names one.
        val text = groups.values.joinToString { it.toString() }
        assertFalse(text.contains("host"))
        assertFalse(text.contains("devices"))
    }

    @Test
    fun anotherPhonesSettingsComeInAndThisPhonesOwnStay() {
        val theirs = SyncGroups.read(raw(AppPrefs(theme = ThemeMode.LIGHT, volumeGuardLevel = 40, layout = RemoteLayout(hand = Hand.LEFT))))
        val mine = raw(AppPrefs(onboarded = true, developerMode = true, lastBarVolume = 22, theme = ThemeMode.DARK))
        val after = prefsOf(SyncGroups.apply(mine, theirs))
        assertEquals(ThemeMode.LIGHT, after.theme)
        assertEquals(40, after.volumeGuardLevel)
        assertEquals(Hand.LEFT, after.layout.hand)
        assertTrue(after.onboarded)
        assertTrue(after.developerMode)
        assertEquals(22, after.lastBarVolume)
    }

    @Test
    fun aGroupThatDoesNotReadKeepsTheStoredValue() {
        val mine = raw(AppPrefs(theme = ThemeMode.DARK))
        val bad = mapOf("preferences" to JsonObject(mapOf("theme" to JsonPrimitive("PLAID"))), "scenes" to JsonPrimitive(3))
        val after = SyncGroups.apply(mine, bad)
        assertEquals(ThemeMode.DARK, prefsOf(after).theme)
        assertNull(after.scenes)
    }

    @Test
    fun readingWhatWasAppliedGivesTheSameGroups() {
        val theirs = SyncGroups.read(raw(AppPrefs(theme = ThemeMode.LIGHT, keySounds = true), DockConfig()))
        val after = SyncGroups.apply(raw(), theirs)
        assertEquals(theirs, SyncGroups.read(after))
    }
}
