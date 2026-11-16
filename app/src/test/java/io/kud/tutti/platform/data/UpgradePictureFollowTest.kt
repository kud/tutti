package io.kud.tutti.platform.data

import io.kud.tutti.protocol.picture.PictureFollowConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upgrade to "the picture follows what's on" (2026-11-12): `AppPrefs.pictureFollow`. Prefs
 * written before it read with the feature off and every other setting kept; once set, it
 * round-trips and travels with the synced preferences. Values are invented.
 */
class UpgradePictureFollowTest {
    private val before =
        """{"haptics":true,"vibration":"ESSENTIALS","theme":"DARK","hiddenApps":["com.example.hidden"],""" +
            """"onboarded":true,"volumeGuardLevel":40,"appDestinations":{"com.example.app":"https://example.com"}}"""

    private fun decode(json: String): AppPrefs = StoreJson.decodeFromString(AppPrefs.serializer(), json)

    @Test
    fun `prefs stored before it read with it off and keep everything else`() {
        val read = decode(before)
        assertFalse(read.pictureFollow.enabled)
        assertEquals(PictureFollowConfig(), read.pictureFollow)
        assertEquals(setOf("com.example.hidden"), read.hiddenApps)
        assertEquals(VibrationLevel.ESSENTIALS, read.vibration)
        assertEquals(40, read.volumeGuardLevel)
        assertEquals("https://example.com", read.appDestinations["com.example.app"])
    }

    @Test
    fun `it round-trips and syncs with the preferences`() {
        val set = decode(before).copy(
            pictureFollow = PictureFollowConfig(
                enabled = true,
                apps = mapOf("com.example.films" to "cinema", "com.example.clips" to PictureFollowConfig.LEAVE),
                inputs = mapOf("extInput:hdmi?port=3" to "game"),
                suggestions = false,
            ),
        )
        assertEquals(set, decode(StoreJson.encodeToString(AppPrefs.serializer(), set)))

        fun raw(prefs: AppPrefs) = SyncGroups.Raw(StoreJson.encodeToString(AppPrefs.serializer(), prefs), null, null, null)
        val theirs = SyncGroups.read(raw(set))
        val mine = SyncGroups.apply(raw(AppPrefs()), theirs)
        val after = decode(mine.prefs!!)
        assertTrue(after.pictureFollow.enabled)
        assertEquals(set.pictureFollow, after.pictureFollow)
    }
}
