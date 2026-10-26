package io.kud.tutti.protocol.bravia

import io.kud.tutti.protocol.bravia.TvSettingCatalog.PictureSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun soundAndSpeakerFollowSonysOrderThenTheTvs() {
        val settings = listOf(
            setting("zeta"), setting("subwooferPhase"), setting("soundMode", available = false), setting("alpha"),
            setting("outputTerminal"), setting("tvPosition"),
        )
        assertEquals(
            listOf("outputTerminal", "soundMode", "tvPosition", "subwooferPhase", "zeta", "alpha"),
            TvSettingCatalog.arrangeSound(settings, showUnavailable = true).map { it.target },
        )
        assertEquals(
            listOf("outputTerminal", "tvPosition", "subwooferPhase", "zeta", "alpha"),
            TvSettingCatalog.arrangeSound(settings, showUnavailable = false).map { it.target },
        )
        assertTrue(TvSettingCatalog.isNamed("subwooferPower"))
        assertTrue(TvSettingCatalog.isNamed("blueGain"))
        assertFalse(TvSettingCatalog.isNamed("zeta"))
    }

    @Test
    fun colourTargetsSplitIntoColourAndPart() {
        assertEquals(TvSettingCatalog.ColourTarget(TvSettingCatalog.Hue.BLUE, TvSettingCatalog.Part.GAIN), TvSettingCatalog.colourTarget("blueGain"))
        assertEquals(TvSettingCatalog.ColourTarget(TvSettingCatalog.Hue.MAGENTA, TvSettingCatalog.Part.LIGHTNESS), TvSettingCatalog.colourTarget("magentaLightness"))
        assertNull(TvSettingCatalog.colourTarget("hue"))
        assertNull(TvSettingCatalog.colourTarget("blueish"))
        assertNull(TvSettingCatalog.colourTarget("redGainExtra"))
    }

    /** Erwann's XR80 leftovers (2026-10-26), shown raw under "Other picture settings" on dev.14. */
    @Test
    fun theXr80LeftoversHaveTheirPlace() {
        val leftovers = listOf("blueOffset", "contentType", "greenOffset", "redOffset", "sensorAutoAdjustment", "redBias", "redGain", "lightSensor", "colorSpace")
            .map { setting(it) }
        val rows = TvSettingCatalog.arrange(leftovers, showUnavailable = true).toMap().mapValues { (_, list) -> list.map { it.target } }
        assertNull(rows[PictureSection.OTHER])
        assertEquals(listOf("redGain", "redBias", "redOffset", "greenOffset", "blueOffset"), rows[PictureSection.WHITE_BALANCE])
        assertEquals(listOf("lightSensor", "sensorAutoAdjustment"), rows[PictureSection.PICTURE])
        assertEquals(listOf("colorSpace", "contentType"), rows[PictureSection.VIDEO])
        assertEquals(TvSettingCatalog.ColourTarget(TvSettingCatalog.Hue.GREEN, TvSettingCatalog.Part.OFFSET), TvSettingCatalog.colourTarget("greenOffset"))
        assertTrue(listOf("blueOffset", "contentType", "sensorAutoAdjustment").all { TvSettingCatalog.isNamed(it) })
    }

    /** The phone's order (Erwann, 2026-10-26): the everyday handful first, everything else behind Advanced, nothing twice or lost. */
    @Test
    fun everydayLeadsAndTheRestFoldsBehindAdvanced() {
        val everyday = TvSettingCatalog.everyday(alphabetical, showUnavailable = true).map { it.target }
        assertEquals(listOf("pictureMode", "brightness", "contrast", "color", "colorTemperature", "motionflowMode"), everyday)
        val advanced = TvSettingCatalog.advanced(alphabetical, showUnavailable = true)
        val folded = advanced.flatMap { (_, rows) -> rows.map { it.target } }
        assertTrue(folded.none { it in everyday })
        assertEquals(alphabetical.map { it.target }.toSet(), (everyday + folded).toSet())
        assertEquals(alphabetical.size, everyday.size + folded.size)
        assertEquals(PictureSection.WHITE_BALANCE, advanced.first { it.second.any { r -> r.target == "redGain" } }.first)
        // An unavailable everyday row hides like any other.
        val dim = listOf(setting("pictureMode"), setting("brightness", available = false))
        assertEquals(listOf("pictureMode"), TvSettingCatalog.everyday(dim, showUnavailable = false).map { it.target })
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
