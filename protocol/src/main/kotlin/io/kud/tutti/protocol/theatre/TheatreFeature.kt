package io.kud.tutti.protocol.theatre

/** How a feature's value is spelled on the wire. All values travel as strings internally. */
sealed interface FeatureKind {
    data class Toggle(val on: String = "on", val off: String = "off") : FeatureKind

    /** Sent as a JSON number. */
    data class Level(val min: Int, val max: Int) : FeatureKind

    data class Choice(val options: List<String>) : FeatureKind

    data object ReadOnly : FeatureKind
}

/**
 * BRAVIA Theatre IP control features (TCP 33336). Vocabularies from the MIT
 * bravia-quad-homeassistant docs, reimplemented. Verified on the Theatre Quad by its
 * authors; on the Bar 9 only by one community report. See docs/architecture.md.
 *
 * [notifies] is false for features the device does not push: poll them with `get`.
 */
enum class TheatreFeature(val key: String, val kind: FeatureKind, val notifies: Boolean = true) {
    POWER("main.power", FeatureKind.Toggle()),
    VOLUME("main.volumestep", FeatureKind.Level(0, 100)),
    MUTE("main.mute", FeatureKind.Toggle()),
    INPUT("main.input", FeatureKind.Choice(listOf("tv", "hdmi1", "spotify", "bluetooth", "airplay2"))),

    /** -10..10 with a subwoofer; 0..2 (min, mid, max) without one. */
    BASS("main.bassstep", FeatureKind.Level(-10, 10)),
    REAR_LEVEL("main.rearvolumestep", FeatureKind.Level(-10, 10)),
    VOICE("audio.voiceenhancer", FeatureKind.Toggle(on = "upon", off = "upoff")),
    SOUND_FIELD("audio.soundfield", FeatureKind.Toggle()),
    NIGHT("audio.nightmode", FeatureKind.Toggle()),
    DRC("audio.drangecomp", FeatureKind.Choice(listOf("auto", "on", "off")), notifies = false),
    AUTO_VOLUME("audio.aav", FeatureKind.Toggle(), notifies = false),
    SPATIAL_360("audio.360ssm", FeatureKind.Toggle()),
    IMAX("audio.imaxmode", FeatureKind.Choice(listOf("auto", "on", "off"))),
    AV_SYNC("audio.avsync", FeatureKind.Level(0, 300)),
    DUAL_MONO("audio.dualmono", FeatureKind.Choice(listOf("main", "sub", "main_sub"))),

    /** Unverified on the Bar 9: show only if a `get` probe answers. */
    VOICE_ZOOM("audio.voicezoom3", FeatureKind.Toggle()),
    VOICE_ZOOM_LEVEL("audio.voicezoom3step", FeatureKind.Level(0, 2)),
    HDMI_CEC("hdmi.cec", FeatureKind.Toggle()),
    HDMI_PASSTHROUGH("hdmi.passthrough", FeatureKind.Choice(listOf("auto", "on", "off"))),
    HDMI_STANDBY_LINK("hdmi.standbylink", FeatureKind.Choice(listOf("auto", "on", "off"))),
    AUTO_STANDBY("system.autostandby", FeatureKind.Toggle()),
    AUTO_UPDATE("system.autoupdate", FeatureKind.Toggle()),
    MODEL_NAME("system.modelname", FeatureKind.ReadOnly, notifies = false),
    FIRMWARE_VERSION("system.version", FeatureKind.ReadOnly, notifies = false),

    /** Vocabulary undocumented: displayed raw, never set. */
    BLUETOOTH_MODE("bluetooth.mode", FeatureKind.ReadOnly),
    BLUETOOTH_QUALITY("bluetooth.connectionquality", FeatureKind.Choice(listOf("prioritysound", "priorityconnection"))),
    ;

    /** Wire value for a toggle, or null when the feature is not a toggle. */
    fun toggleValue(on: Boolean): String? = (kind as? FeatureKind.Toggle)?.let { if (on) it.on else it.off }

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): TheatreFeature? = byKey[key]

        const val PORT = 33336
    }
}
