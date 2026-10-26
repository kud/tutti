package io.kud.tutti.protocol.bravia

/**
 * Where each picture target the TV reports belongs, in the order Sony's own Picture menu gives it
 * (Iris, 2026-10-26): the screen groups and orders by this instead of the TV's alphabetical list.
 * On a phone the handful people reach for leads ([everyday]) and the rest has its own screen,
 * Advanced picture ([advanced]), Sony's order inside (Erwann, 2026-10-26: "Put the expert settings behind Advanced").
 * Only targets the TV actually lists are ever shown; a target here that the XR80 never sends costs
 * nothing. Anything unknown lands in [PictureSection.OTHER], after the rest, under its raw name.
 */
object TvSettingCatalog {

    enum class PictureSection { PICTURE, BRIGHTNESS, COLOUR, WHITE_BALANCE, COLOUR_ADJUSTMENT, CLARITY, MOTION, VIDEO, OTHER }

    /** The six colours Sony's white balance and colour adjustment name, in its menu order. */
    enum class Hue(val key: String) { RED("red"), GREEN("green"), BLUE("blue"), CYAN("cyan"), MAGENTA("magenta"), YELLOW("yellow") }

    /**
     * What a per-colour target adjusts: white balance takes gain, bias and offset (the XR80 lists
     * `redOffset`, -20 to +20, beside them), colour adjustment the other three.
     */
    enum class Part(val key: String) {
        GAIN("Gain"), BIAS("Bias"), OFFSET("Offset"), HUE("Hue"), SATURATION("Saturation"), LIGHTNESS("Lightness");

        val isWhiteBalance: Boolean get() = this == GAIN || this == BIAS || this == OFFSET
    }

    /** A per-colour target split into its colour and part: `blueGain` is (BLUE, GAIN). */
    data class ColourTarget(val hue: Hue, val part: Part)

    private val named: Map<PictureSection, List<String>> = mapOf(
        PictureSection.PICTURE to listOf("pictureMode", "autoPictureMode", "lightSensor", "sensorAutoAdjustment"),
        PictureSection.BRIGHTNESS to listOf(
            "brightness", "contrast", "gamma", "blackLevel", "blackAdjust", "advContrastEnhancer",
            "peakLuminance", "autoLocalDimming", "xtendedDynamicRange", "autoLuminanceLevel", "autoToneCurve",
        ),
        PictureSection.COLOUR to listOf("color", "hue", "colorTemperature", "liveColor", "autoWhiteBalance"),
        PictureSection.CLARITY to listOf("sharpness", "realityCreation", "randomNoiseReduction", "digitalNoiseReduction", "smoothGradation"),
        PictureSection.MOTION to listOf(
            "motionflowMode", "motionflowSmoothnessFilm", "motionflowSmoothnessCamera", "motionflowClearness", "cineMotion", "filmMode",
        ),
        PictureSection.VIDEO to listOf("hdrMode", "colorSpace", "hdmiVideoRange", "contentType", "pixelShift"),
    )

    private val index: Map<String, Pair<PictureSection, Int>> =
        named.flatMap { (section, targets) -> targets.mapIndexed { i, t -> t to (section to i) } }.toMap()

    /**
     * Sound (getSoundSettings) and speaker (getSpeakerSettings) targets in Sony's menu order:
     * the documented ones (`outputTerminal`; TV position and the subwoofer's four), then sound mode,
     * which Sony's menu has and the TV may list. Unknown targets follow in the TV's order.
     */
    private val soundOrder: List<String> = listOf(
        TvSetting.OUTPUT_TERMINAL, "soundMode",
        "tvPosition", "subwooferLevel", "subwooferFreq", "subwooferPhase", "subwooferPower",
    )

    /** Every target with a fixed place, for the test that each one has words in both languages. */
    val namedTargets: Set<String> get() = index.keys + soundOrder

    /** Whether the screen has words for [target], or shows it as the TV names it. */
    fun isNamed(target: String): Boolean = target in index || target in soundOrder || colourTarget(target) != null

    /** A sound or speaker list in Sony's order, unavailable rows left out unless [showUnavailable]. */
    fun arrangeSound(settings: List<TvSetting>, showUnavailable: Boolean): List<TvSetting> =
        settings
            .filter { showUnavailable || it.isAvailable }
            .withIndex()
            .sortedWith(compareBy({ soundOrder.indexOf(it.value.target).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }

    fun colourTarget(target: String): ColourTarget? {
        val hue = Hue.entries.firstOrNull { target.startsWith(it.key) } ?: return null
        val part = Part.entries.firstOrNull { target == hue.key + it.key } ?: return null
        return ColourTarget(hue, part)
    }

    fun sectionOf(target: String): PictureSection {
        index[target]?.let { return it.first }
        val colour = colourTarget(target) ?: return PictureSection.OTHER
        return if (colour.part.isWhiteBalance) PictureSection.WHITE_BALANCE else PictureSection.COLOUR_ADJUSTMENT
    }

    /**
     * Sony's order inside a section: the named list's order; white balance gain, bias, then offset,
     * each red, green, blue; colour adjustment by colour, then hue, saturation, lightness. Unknown targets
     * keep the TV's order after everything known.
     */
    private fun rank(target: String): Int {
        index[target]?.let { return it.second }
        val colour = colourTarget(target) ?: return Int.MAX_VALUE
        return when (colour.part) {
            Part.GAIN, Part.BIAS, Part.OFFSET -> colour.part.ordinal * Hue.entries.size + colour.hue.ordinal
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
     * The picture settings people reach for, in the order they reach for them: the mode, then how
     * bright, then how colourful, then the few a film or a match makes them touch. The TV's scene
     * select goes after the mode on screen; it comes from its own call, not this list.
     */
    val everydayTargets: List<String> = listOf("pictureMode", "brightness", "contrast", "color", "colorTemperature", "motionflowMode")

    /** The everyday picture rows the TV lists, in [everydayTargets] order; unavailable ones only when [showUnavailable]. */
    fun everyday(settings: List<TvSetting>, showUnavailable: Boolean): List<TvSetting> =
        everydayTargets.mapNotNull { target -> settings.firstOrNull { it.target == target && (showUnavailable || it.isAvailable) } }

    /** Everything else, in sections and Sony's order, for Advanced picture: [arrange] without the everyday rows. */
    fun advanced(settings: List<TvSetting>, showUnavailable: Boolean): List<Pair<PictureSection, List<TvSetting>>> =
        arrange(settings.filter { it.target !in everydayTargets }, showUnavailable)

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
