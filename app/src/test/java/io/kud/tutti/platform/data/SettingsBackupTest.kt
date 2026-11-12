package io.kud.tutti.platform.data

import io.kud.tutti.protocol.layout.LayoutCodec
import io.kud.tutti.protocol.layout.LayoutRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Backup: the sync groups, written once to a file he chooses (2026-11-12). */
class SettingsBackupTest {

    private fun raw(prefs: AppPrefs = AppPrefs(), dock: DockConfig? = null) = SyncGroups.Raw(
        prefs = StoreJson.encodeToString(AppPrefs.serializer(), prefs),
        dock = dock?.let { StoreJson.encodeToString(DockConfig.serializer(), it) },
        scenes = null,
        profiles = null,
    )

    private fun prefsOf(raw: SyncGroups.Raw) = StoreJson.decodeFromString(AppPrefs.serializer(), raw.prefs!!)

    private fun groupsOf(text: String) = (SettingsBackup.read(text) as SettingsBackup.Read.Ok).groups

    private val yt = "com.google.android.youtube.tv"

    @Test
    fun `what travels comes back the same`() {
        val dock = DockConfig(slots = listOf(DockSlot(primary = DockAction.LaunchApp(yt, "YouTube"))))
        val sender = raw(
            AppPrefs(theme = ThemeMode.DARK, keySounds = true, hiddenApps = setOf("com.example.tv"), appDestinations = mapOf(yt to "https://www.youtube.com/feed/subscriptions")),
            dock,
        )
        val received = SyncGroups.apply(raw(), groupsOf(SettingsBackup.export(sender, "0.3.0", 1L)))
        val prefs = prefsOf(received)
        assertEquals(ThemeMode.DARK, prefs.theme)
        assertTrue(prefs.keySounds)
        assertEquals(setOf("com.example.tv"), prefs.hiddenApps)
        assertEquals(prefsOf(sender).appDestinations, prefs.appDestinations)
        assertEquals(dock.slots.first(), StoreJson.decodeFromString(DockConfig.serializer(), received.dock!!).slots.first())
        // Everything the file holds, read back, is the same groups again.
        assertEquals(SyncGroups.read(sender), SyncGroups.read(received))
    }

    @Test
    fun `nothing that stays on this phone is in the file`() {
        val text = SettingsBackup.export(raw(AppPrefs(onboarded = true, developerMode = true, lastBarVolume = 31)), null, 1L)
        for (local in SyncGroups.LOCAL_PREFS - "layout") assertFalse(local, "\"$local\"" in text)
        for (word in listOf("psk", "token", "host", "deviceId", "mac\"", "pairing", "certificate")) {
            assertFalse(word, text.contains(word, ignoreCase = true))
        }
        assertFalse(Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""").containsMatchIn(text))
    }

    @Test
    fun `refused links are dropped as sync drops them, and this phone keeps what stays here`() {
        val hostile = SettingsBackup.export(
            raw(
                AppPrefs(appDestinations = mapOf(yt to "market://details?id=x")),
                DockConfig(slots = listOf(DockSlot(primary = DockAction.LaunchApp(yt, "YouTube", "javascript:alert(1)")))),
            ),
            null,
            1L,
        )
        val here = raw(AppPrefs(onboarded = true, developerMode = true))
        val cleaned = SyncGroups.apply(here, groupsOf(hostile))
        assertTrue(prefsOf(cleaned).appDestinations.isEmpty())
        assertTrue(prefsOf(cleaned).onboarded)
        assertTrue(prefsOf(cleaned).developerMode)
        val pin = StoreJson.decodeFromString(DockConfig.serializer(), cleaned.dock!!).slots.first().primary
        assertEquals(DockAction.LaunchApp(yt, "YouTube"), pin)
    }

    @Test
    fun `an older layout file is told apart and still imports as a layout`() {
        val layout = LayoutCodec.encode(LayoutTransfer.export(RemoteLayout(), DockConfig(), emptyList()).file)
        assertEquals(SettingsBackup.Read.LayoutFile, SettingsBackup.read(layout))
        val read = LayoutCodec.decode(layout)
        assertTrue(read is LayoutRead.Ok)
        LayoutTransfer.import((read as LayoutRead.Ok).file, read.skipped, RemoteLayout(), emptyList())
    }

    @Test
    fun `anything else is not ours`() {
        assertEquals(SettingsBackup.Read.NotOurs, SettingsBackup.read("{}"))
        assertEquals(SettingsBackup.Read.NotOurs, SettingsBackup.read("hello"))
    }
}
