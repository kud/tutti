package io.kud.tutti.protocol.bravia

/**
 * Where each picture target the TV reports belongs, in the order Sony's own Picture menu gives it
 * (Iris, 2026-10-26): the screen groups and orders by this instead of the TV's alphabetical list.
 * Only targets the TV actually lists are ever shown; a target here that the XR80 never sends costs
 * nothing. Anything unknown lands in [PictureSection.OTHER], after the rest, under its raw name.
 */
object TvSettingCatalog {

    enum class PictureSection { PICTURE, BRIGHTNESS, COLOUR, WHITE_BALANCE, COLOUR_ADJUSTMENT, CLARITY, MOTION, VIDEO, OTHER }

    /** The six colours Sony's white balance and colour adjustment name, in its menu order. */
    enum class Hue(val key: String) { RED("red"), GREEN("green"), BLUE("blue"), CYAN("cyan"), MAGENTA("magenta"), YELLOW("yellow") }

    /** What a per-colour target adjusts: white balance takes gain and bias, colour adjustment the other three. */
    enum class Part(val key: String) { GAIN("Gain"), BIAS("Bias"), HUE("Hue"), SATURATION("Saturation"), LIGHTNESS("Lightness") }

    /** A per-colour target split into its colour and part: `blueGain` is (BLUE, GAIN). */
    data class ColourTarget(val hue: Hue, val part: Part)

    private val named: Map<PictureSection, List<String>> = mapOf(
        PictureSection.PICTURE to listOf("pictureMode", "autoPictureMode", "lightSensor"),
        PictureSection.BRIGHTNESS to listOf(
            "brightness", "contrast", "gamma", "blackLevel", "blackAdjust", "advContrastEnhancer",
            "peakLuminance", "autoLocalDimming", "xtendedDynamicRange", "autoLuminanceLevel", "autoToneCurve",
        ),
        PictureSection.COLOUR to listOf("color", "hue", "colorTemperature", "liveColor", "autoWhiteBalance"),
        PictureSection.CLARITY to listOf("sharpness", "realityCreation", "randomNoiseReduction", "digitalNoiseReduction", "smoothGradation"),
        PictureSection.MOTION to listOf(
            "motionflowMode", "motionflowSmoothnessFilm", "motionflowSmoothnessCamera", "motionflowClearness", "cineMotion", "filmMode",
        ),
        PictureSection.VIDEO to listOf("hdrMode", "colorSpace", "hdmiVideoRange", "pixelShift"),
    )

    private val index: Map<String, Pair<PictureSection, Int>> =
        named.flatMap { (section, targets) -> targets.mapIndexed { i, t -> t to (section to i) } }.toMap()

    /** Every target with a fixed place, for the test that each one has words in both languages. */
    val namedTargets: Set<String> get() = index.keys

    fun colourTarget(target: String): ColourTarget? {
        val hue = Hue.entries.firstOrNull { target.startsWith(it.key) } ?: return null
        val part = Part.entries.firstOrNull { target == hue.key + it.key } ?: return null
        return ColourTarget(hue, part)
    }

    fun sectionOf(target: String): PictureSection {
        index[target]?.let { return it.first }
        val colour = colourTarget(target) ?: return PictureSection.OTHER
        return if (colour.part == Part.GAIN || colour.part == Part.BIAS) PictureSection.WHITE_BALANCE else PictureSection.COLOUR_ADJUSTMENT
    }

    /**
     * Sony's order inside a section: the named list's order; white balance gain then bias, red,
     * green, blue; colour adjustment by colour, then hue, saturation, lightness. Unknown targets
     * keep the TV's order after everything known.
     */
    private fun rank(target: String): Int {
        index[target]?.let { return it.second }
        val colour = colourTarget(target) ?: return Int.MAX_VALUE
        return when (colour.part) {
            Part.GAIN, Part.BIAS -> colour.part.ordinal * Hue.entries.size + colour.hue.ordinal
            else -> colour.hue.ordinal * Part.entries.size + colour.part.ordinal
        }
    }

    /**
     * The picture settings in sections, Sony's order, empty sections dropped. Rows the TV marks
     * unavailable stay in their place when [showUnavailable], and are left out otherwise.
     */
    fun arrange(settings: List<TvSetting>, showUnavailable: Boolean): List<Pair<PictureSection, List<TvSetting>>> {
        val shown = if (showUnavailable) settings else settings.filter { it.isAvailable }
        return shown
            .withIndex()
            .groupBy { sectionOf(it.value.target) }
            .toSortedMap()
            .map { (section, rows) -> section to rows.sortedWith(compareBy({ rank(it.value.target) }, { it.index })).map { it.value } }
    }

    /**
     * A value the screen has no words for, made readable rather than shown raw: `warm1` reads
     * "Warm 1", `pictureOff` "Picture off". English only, so it is the fallback after the
     * translated words and the TV's own title.
     */
    fun readable(value: String): String {
        if (value.isEmpty()) return value
        val words = value
            .replace(Regex("(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Za-z])|[_-]+"), " ")
            .trim()
            .split(Regex("\\s+"))
        return words.mapIndexed { i, w ->
            when {
                w.length > 1 && w.all { it.isUpperCase() || it.isDigit() } -> w // HDR, HDMI
                i == 0 -> w.replaceFirstChar { it.uppercaseChar() }
                else -> w.lowercase()
            }
        }.joinToString(" ")
    }
}
