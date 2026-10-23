package io.kud.tutti.protocol.theatre

/**
 * The bar settings a shortcut may set, and the values it may set them to. The picker offers a
 * feature only once the bar has answered for it ([TheatreState.supports]); the rest are counted,
 * never shown greyed out. Power, the anchors' job, and the system and HDMI plumbing stay out.
 */
object BarShortcuts {
    val FEATURES: List<TheatreFeature> = listOf(
        TheatreFeature.NIGHT,
        TheatreFeature.VOICE,
        TheatreFeature.SOUND_FIELD,
        TheatreFeature.SPATIAL_360,
        TheatreFeature.AUTO_VOLUME,
        TheatreFeature.MUTE,
        TheatreFeature.BASS,
        TheatreFeature.REAR_LEVEL,
        TheatreFeature.VOLUME,
        TheatreFeature.INPUT,
        TheatreFeature.DRC,
        TheatreFeature.IMAX,
        TheatreFeature.DUAL_MONO,
        TheatreFeature.VOICE_ZOOM,
        TheatreFeature.VOICE_ZOOM_LEVEL,
    )

    data class Offer(val offered: List<TheatreFeature>, val hidden: Int)

    fun offer(state: TheatreState): Offer {
        val (offered, hidden) = FEATURES.partition(state::supports)
        return Offer(offered, hidden.size)
    }

    /** Wire values a shortcut can set [feature] to, in the order the picker steps through them. */
    fun values(feature: TheatreFeature): List<String> = when (val kind = feature.kind) {
        is FeatureKind.Toggle -> listOf(kind.on, kind.off)
        is FeatureKind.Level -> (kind.min..kind.max step step(feature)).map(Int::toString)
        is FeatureKind.Choice -> kind.options
        FeatureKind.ReadOnly -> emptyList()
    }

    private fun step(feature: TheatreFeature): Int = if (feature == TheatreFeature.VOLUME) 5 else 1

    /**
     * Where the picker starts for [feature]: the bar's current value when a shortcut could set it,
     * else the middle of a level (0 dB for bass), else the first value.
     */
    fun initial(feature: TheatreFeature, state: TheatreState): String {
        val values = values(feature)
        state.value(feature)?.takeIf { it in values }?.let { return it }
        val kind = feature.kind
        if (kind is FeatureKind.Level) {
            val middle = if (0 in kind.min..kind.max) 0 else (kind.min + kind.max) / 2
            return values.minBy { kotlin.math.abs(it.toInt() - middle) }
        }
        return values.first()
    }
}
