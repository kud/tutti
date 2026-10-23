package io.kud.tutti.platform.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Prefs stored before the Feedback group had only the `haptics` switch. */
class FeedbackPrefsMigrationTest {
    private fun read(json: String) = StoreJson.decodeFromString(AppPrefs.serializer(), json)

    @Test
    fun `haptics on reads as everything`() {
        assertEquals(VibrationLevel.ALL, read("""{"haptics":true}""").vibrationLevel)
    }

    @Test
    fun `haptics off reads as off`() {
        assertEquals(VibrationLevel.OFF, read("""{"haptics":false}""").vibrationLevel)
    }

    @Test
    fun `nothing stored reads as everything, with key sounds off`() {
        val prefs = read("{}")
        assertEquals(VibrationLevel.ALL, prefs.vibrationLevel)
        assertFalse(prefs.keySounds)
    }

    @Test
    fun `a chosen level wins over the old switch`() {
        assertEquals(VibrationLevel.ESSENTIALS, read("""{"haptics":true,"vibration":"ESSENTIALS"}""").vibrationLevel)
        assertEquals(VibrationLevel.ALL, read("""{"haptics":false,"vibration":"ALL"}""").vibrationLevel)
    }

    @Test
    fun `the level survives a round trip`() {
        val prefs = AppPrefs(vibration = VibrationLevel.ESSENTIALS, keySounds = true)
        val back = read(StoreJson.encodeToString(AppPrefs.serializer(), prefs))
        assertEquals(VibrationLevel.ESSENTIALS, back.vibrationLevel)
        assertEquals(true, back.keySounds)
    }
}
