package io.kud.tutti.ui.settings

import io.kud.tutti.controller.TvSettingsPart
import io.kud.tutti.controller.TvSettingsState
import io.kud.tutti.controller.TvState
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.bravia.SettingCandidate
import io.kud.tutti.protocol.bravia.SystemInfo
import io.kud.tutti.protocol.bravia.TvSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV hub's rows, waiting parts, live lines and per-screen unavailable counts (Iris, 2026-10-26). */
class TvHubTest {

    private fun range(target: String, value: String = "0", available: Boolean = true) =
        TvSetting(target, currentValue = value, isAvailable = available, candidates = listOf(SettingCandidate("", min = 0.0, max = 50.0, step = 1.0)))

    private fun choice(target: String, value: String, available: Boolean = true) =
        TvSetting(target, currentValue = value, isAvailable = available, candidates = listOf(SettingCandidate(value), SettingCandidate("other")))

    private val loaded = TvSettingsState(
        loaded = true,
        picture = listOf(
            choice("pictureMode", "standard"),
            range("brightness", "32"),
            range("contrast", available = false),
            range("gamma"),
            range("blackLevel", available = false),
            range("blueGain"),
        ),
        sound = listOf(choice("outputTerminal", "audioSystem"), choice("soundMode", "standard", available = false)),
        speaker = listOf(choice("tvPosition", "tableTop", available = false)),
        powerSaving = "off",
        powerSavingSupported = true,
        wol = true,
        system = SystemInfo(model = "MODEL-1", product = null, generation = "6.0", name = null),
    )

    private val inputs = listOf(
        ExternalInput("extInput:hdmi?port=1", "HDMI 1", "", connected = true),
        ExternalInput("extInput:hdmi?port=2", "HDMI 2", "", connected = false),
    )

    @Test
    fun aLoadedTvListsEveryPartInSonysOrder() {
        val tv = TvState(settings = loaded, inputs = inputs)
        assertEquals(listOf(TvPage.PICTURE, TvPage.SOUND, TvPage.INPUTS, TvPage.POWER, TvPage.ABOUT), TvHub.rows(tv))
        assertTrue(TvHub.rows(tv).none { TvHub.waiting(tv, it) })
    }

    @Test
    fun partsTheTvDoesNotListHaveNoRow() {
        val tv = TvState(settings = TvSettingsState(loaded = true, picture = listOf(range("brightness"))))
        assertEquals(listOf(TvPage.PICTURE), TvHub.rows(tv))
        assertFalse(TvHub.has(tv, TvPage.ADVANCED_PICTURE))
        assertEquals(emptyList<TvPage>(), TvHub.rows(TvState(settings = TvSettingsState(loaded = true))))
    }

    @Test
    fun aFirstLoadWaitsPartByPart() {
        val first = TvSettingsState(loading = true, waiting = setOf(TvSettingsPart.SOUND, TvSettingsPart.POWER), picture = loaded.picture)
        val tv = TvState(settings = first, inputs = inputs)
        assertFalse(TvHub.waiting(tv, TvPage.PICTURE))
        assertTrue(TvHub.waiting(tv, TvPage.SOUND))
        assertTrue(TvHub.waiting(tv, TvPage.POWER))
        assertTrue(TvHub.waiting(tv, TvPage.ABOUT))
        // Inputs the remote already knows don't wait for the load's last part.
        assertFalse(TvHub.waiting(tv, TvPage.INPUTS))
        assertTrue(TvHub.waiting(tv.copy(inputs = emptyList()), TvPage.INPUTS))
        assertEquals(listOf(TvPage.PICTURE, TvPage.SOUND, TvPage.INPUTS, TvPage.POWER, TvPage.ABOUT), TvHub.rows(tv))
    }

    @Test
    fun aReloadKeepsItsRowsAndWaitsForNothing() {
        val tv = TvState(settings = loaded.copy(loading = true, waiting = TvSettingsPart.entries.toSet()), inputs = inputs)
        assertTrue(TvPage.entries.none { TvHub.waiting(tv, it) })
    }

    @Test
    fun unavailableIsCountedPerScreen() {
        assertEquals(1, TvHub.unavailable(loaded, TvPage.PICTURE))
        assertEquals(1, TvHub.unavailable(loaded, TvPage.ADVANCED_PICTURE))
        assertEquals(2, TvHub.unavailable(loaded, TvPage.SOUND))
        assertEquals(0, TvHub.unavailable(loaded, TvPage.POWER))
        assertEquals(0, TvHub.unavailable(loaded, TvPage.INPUTS))
    }

    @Test
    fun advancedPictureCountsWhatItShowsAsItOpens() {
        assertTrue(TvHub.has(TvState(settings = loaded), TvPage.ADVANCED_PICTURE))
        assertEquals(2, TvHub.advancedCount(loaded))
    }

    @Test
    fun pictureLineHasTheModeAndBrightness() {
        assertEquals(TvHub.PictureLine("standard", "32"), TvHub.pictureLine(TvState(settings = loaded, pictureMode = "cinema")))
        // Before the load, the mode the remote's poll knows.
        assertEquals(TvHub.PictureLine("cinema", null), TvHub.pictureLine(TvState(pictureMode = "cinema")))
        val dim = loaded.copy(picture = listOf(range("brightness", "10", available = false)))
        assertNull(TvHub.pictureLine(TvState(settings = dim)).brightness)
    }

    @Test
    fun soundPowerAndInputLines() {
        val sound = TvHub.soundLine(loaded)
        assertEquals("audioSystem", sound.output?.currentValue)
        assertEquals("standard", sound.soundMode?.currentValue)
        assertEquals(TvHub.PowerLine("off", true), TvHub.powerLine(loaded))
        assertEquals(TvHub.PowerLine(null, null), TvHub.powerLine(TvSettingsState(powerSaving = "low")))
        assertEquals("HDMI 2", TvHub.currentInput(TvState(inputs = inputs, input = "extInput:hdmi?port=2"))?.title)
        assertNull(TvHub.currentInput(TvState(inputs = inputs, input = "app:netflix")))
    }
}
