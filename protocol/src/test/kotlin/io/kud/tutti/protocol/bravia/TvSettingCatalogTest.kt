package io.kud.tutti.protocol.bravia

import io.kud.tutti.protocol.bravia.TvSettingCatalog.PictureSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvSettingCatalogTest {

    private fun setting(target: String, available: Boolean = true) =
        TvSetting(target, currentValue = "0", isAvailable = available, candidates = listOf(SettingCandidate("", min = -15.0, max = 15.0, step = 1.0)))

    /** The TV's own order is alphabetical; the screen follows Sony's menu instead. */
    private val alphabetical = listOf(
        "advContrastEnhancer", "autoLocalDimming", "blackLevel", "blueBias", "blueGain", "blueHue", "brightness",
        "color", "colorTemperature", "contrast", "magentaLightness", "magentaSaturation", "motionflowClearness",
        "motionflowMode", "motionflowSmoothnessCamera", "motionflowSmoothnessFilm", "peakLuminance", "pictureMode",
        "pixelShift", "redBias", "redGain", "sharpness", "somethingNew",
    ).map { setting(it) }

    @Test
    fun sectionsFollowSonysMenuOrderNotTheAlphabet() {
        val arranged = TvSettingCatalog.arrange(alphabetical, showUnavailable = true)
        assertEquals(
            listOf(
                PictureSection.PICTURE, PictureSection.BRIGHTNESS, PictureSection.COLOUR, PictureSection.WHITE_BALANCE,
                PictureSection.COLOUR_ADJUSTMENT, PictureSection.CLARITY, PictureSection.MOTION, PictureSection.VIDEO, PictureSection.OTHER,
            ),
            arranged.map { it.first },
        )
        val rows = arranged.toMap().mapValues { (_, list) -> list.map { it.target } }
        assertEquals(listOf("pictureMode"), rows[PictureSection.PICTURE])
        assertEquals(listOf("brightness", "contrast", "blackLevel", "advContrastEnhancer", "peakLuminance", "autoLocalDimming"), rows[PictureSection.BRIGHTNESS])
        assertEquals(listOf("redGain", "blueGain", "redBias", "blueBias"), rows[PictureSection.WHITE_BALANCE])
        assertEquals(listOf("blueHue", "magentaSaturation", "magentaLightness"), rows[PictureSection.COLOUR_ADJUSTMENT])
        assertEquals(
            listOf("motionflowMode", "motionflowSmoothnessFilm", "motionflowSmoothnessCamera", "motionflowClearness"),
            rows[PictureSection.MOTION],
        )
        assertEquals(listOf("somethingNew"), rows[PictureSection.OTHER])
    }

    @Test
    fun unavailableRowsAreLeftOutUnlessAskedForAndThenKeepTheirPlace() {
        val settings = listOf(setting("brightness"), setting("contrast", available = false), setting("gamma"), setting("pixelShift", available = false))
        assertEquals(
            listOf(PictureSection.BRIGHTNESS to listOf("brightness", "gamma")),
            TvSettingCatalog.arrange(settings, showUnavailable = false).map { (s, rows) -> s to rows.map { it.target } },
        )
        assertEquals(
            listOf(PictureSection.BRIGHTNESS to listOf("brightness", "contrast", "gamma"), PictureSection.VIDEO to listOf("pixelShift")),
            TvSettingCatalog.arrange(settings, showUnavailable = true).map { (s, rows) -> s to rows.map { it.target } },
        )
    }

    @Test
    fun unknownTargetsKeepTheTvsOrder() {
        val settings = listOf(setting("zeta"), setting("alpha"), setting("brightness"))
        assertEquals(listOf("zeta", "alpha"), TvSettingCatalog.arrange(settings, true).last().second.map { it.target })
    }

    @Test
    fun colourTargetsSplitIntoColourAndPart() {
        assertEquals(TvSettingCatalog.ColourTarget(TvSettingCatalog.Hue.BLUE, TvSettingCatalog.Part.GAIN), TvSettingCatalog.colourTarget("blueGain"))
        assertEquals(TvSettingCatalog.ColourTarget(TvSettingCatalog.Hue.MAGENTA, TvSettingCatalog.Part.LIGHTNESS), TvSettingCatalog.colourTarget("magentaLightness"))
        assertNull(TvSettingCatalog.colourTarget("hue"))
        assertNull(TvSettingCatalog.colourTarget("blueish"))
        assertNull(TvSettingCatalog.colourTarget("redGainExtra"))
    }

    @Test
    fun readableValues() {
        assertEquals("Medium", TvSettingCatalog.readable("medium"))
        assertEquals("Warm 1", TvSettingCatalog.readable("warm1"))
        assertEquals("Picture off", TvSettingCatalog.readable("pictureOff"))
        assertEquals("Speaker hdmi", TvSettingCatalog.readable("speaker_hdmi"))
        assertEquals("HDR10", TvSettingCatalog.readable("HDR10"))
        assertEquals("", TvSettingCatalog.readable(""))
    }
}
