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
    fun automaticChoicesSyncAndTheOpenNightWindowStays() {
        val mine = AppPrefs(
            pauseForCalls = true,
            faceDownPause = true,
            nightHours = NightHoursPrefs(enabled = true, start = 23 * 60, end = 6 * 60),
            nightSession = io.kud.tutti.protocol.auto.NightHours.Session(endsAt = 1L),
        )
        val prefs = SyncGroups.read(raw(mine)).getValue("preferences") as JsonObject
        assertFalse("nightSession" in prefs)
        val other = SyncGroups.apply(raw(), mapOf("preferences" to prefs))
        val theirs = prefsOf(other)
        assertEquals(true, theirs.pauseForCalls)
        assertEquals(true, theirs.faceDownPause)
        assertEquals(NightHoursPrefs(enabled = true, start = 23 * 60, end = 6 * 60), theirs.nightHours)
        assertEquals(null, theirs.nightSession)
    }

    @Test
    fun carriesTheFourGroupsAndNothingPerPhone() {
        val groups = SyncGroups.read(raw(AppPrefs(onboarded = true, developerMode = true, lastBarVolume = 12, theme = ThemeMode.DARK)))
        assertEquals(setOf("preferences", "remote", "scenes", "profiles"), groups.keys)
        val prefs = groups.getValue("preferences") as JsonObject
        for (key in SyncGroups.LOCAL_PREFS.keys) assertFalse("$key must stay on the phone", key in prefs)
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

    @Test
    fun theVibrationChoiceTravels() {
        val prefs = SyncGroups.read(raw(AppPrefs(vibration = VibrationLevel.ESSENTIALS, haptics = true))).getValue("preferences") as JsonObject
        assertEquals(JsonPrimitive("ESSENTIALS"), prefs["vibration"])
        val after = prefsOf(SyncGroups.apply(raw(AppPrefs(vibration = VibrationLevel.ALL)), mapOf("preferences" to prefs)))
        assertEquals(VibrationLevel.ESSENTIALS, after.vibrationLevel)
    }

    /** Files written before the single Off · Essentials · Everything choice (2026-11-12). */
    @Test
    fun anOlderFilesSwitchAndLevelLandOnTheSingleChoice() {
        fun take(vararg synced: Pair<String, String>, mine: AppPrefs = AppPrefs(vibration = VibrationLevel.ESSENTIALS)): VibrationLevel {
            val group = JsonObject(synced.associate { (k, v) -> k to (if (v == "true" || v == "false") JsonPrimitive(v.toBoolean()) else JsonPrimitive(v)) })
            return prefsOf(SyncGroups.apply(raw(mine), mapOf("preferences" to group))).vibrationLevel
        }
        // The switch alone, from before the level existed: it decides, whatever this phone had.
        assertEquals(VibrationLevel.OFF, take("haptics" to "false"))
        assertEquals(VibrationLevel.ALL, take("haptics" to "true"))
        // The switch with its level, as the two-row screen wrote them.
        assertEquals(VibrationLevel.OFF, take("haptics" to "false", "vibration" to "OFF"))
        assertEquals(VibrationLevel.ESSENTIALS, take("haptics" to "true", "vibration" to "ESSENTIALS", mine = AppPrefs(vibration = VibrationLevel.OFF)))
        assertEquals(VibrationLevel.ALL, take("haptics" to "true", "vibration" to "ALL"))
        // A file that says nothing about vibration keeps this phone's choice.
        assertEquals(VibrationLevel.ESSENTIALS, take("theme" to "DARK"))
    }

    @Test
    fun appDestinationsAndPinLinksTravelAndRefusedLinksAreDropped() {
        val yt = "com.google.android.youtube.tv"
        val subs = "https://www.youtube.com/feed/subscriptions"
        val sender = raw(
            AppPrefs(appDestinations = mapOf(yt to subs)),
            DockConfig(slots = listOf(DockSlot(primary = DockAction.LaunchApp(yt, "YouTube", subs)))),
        )
        val groups = SyncGroups.read(sender)
        val received = SyncGroups.apply(raw(), groups)
        assertEquals(mapOf(yt to subs), prefsOf(received).appDestinations)
        val dock = StoreJson.decodeFromString(DockConfig.serializer(), received.dock!!)
        assertEquals(DockAction.LaunchApp(yt, "YouTube", subs), dock.slots.first().primary)

        // A file written by hand: a refused default goes, a refused pin link goes, the pin stays.
        val hostile = SyncGroups.read(
            raw(
                AppPrefs(appDestinations = mapOf(yt to "market://details?id=x", "com.example.tv" to "intent://x")),
                DockConfig(slots = listOf(DockSlot(primary = DockAction.LaunchApp(yt, "YouTube", "javascript:alert(1)")))),
            ),
        )
        val cleaned = SyncGroups.apply(raw(), hostile)
        assertTrue(prefsOf(cleaned).appDestinations.isEmpty())
        val pin = StoreJson.decodeFromString(DockConfig.serializer(), cleaned.dock!!).slots.first().primary
        assertEquals(DockAction.LaunchApp(yt, "YouTube"), pin)
    }
}
