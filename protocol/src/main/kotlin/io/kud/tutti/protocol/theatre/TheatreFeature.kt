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

    /** Audio return from the TV. ARC or eARC is negotiated with the TV; the bar reports which. */
    HDMI_ARC("hdmi.audioreturnchannel", FeatureKind.Choice(listOf("off", "arc", "earc"))),
    AUTO_STANDBY("system.autostandby", FeatureKind.Toggle()),

    /** Network/Bluetooth standby: lets the bar wake from the network or a Bluetooth device. */
    NET_BT_STANDBY("system.netbtstandby", FeatureKind.Toggle()),
    AUTO_UPDATE("system.autoupdate", FeatureKind.Toggle()),

    /** The model as the bar names it (e.g. HT-A9000). `system.modelname` belongs to the HTTP plane, not this port. */
    MODEL("system.modeltype", FeatureKind.ReadOnly, notifies = false),
    FIRMWARE_VERSION("system.version", FeatureKind.ReadOnly, notifies = false),

    /**
     * Vocabulary undocumented: displayed raw, never set through [TheatreClient.set]. The only
     * write is the pairing sequence in [BluetoothPairing], observed on the Theatre Quad.
     */
    BLUETOOTH_MODE("bluetooth.mode", FeatureKind.ReadOnly),
    BLUETOOTH_QUALITY("bluetooth.connectionquality", FeatureKind.Choice(listOf("prioritysound", "priorityconnection"))),
    ;

    /** Wire value for a toggle, or null when the feature is not a toggle. */
    fun toggleValue(on: Boolean): String? = (kind as? FeatureKind.Toggle)?.let { if (on) it.on else it.off }

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): TheatreFeature? = byKey[key]

        /**
         * Features the bar answers but tutti never asks for: identifiers, and the switch that
         * would cut tutti's own socket. Kept here so a test can hold the line.
         */
        val NEVER_ASKED: Set<String> = setOf(
            "system.serialnumber",
            "network.macaddress",
            "network.ipaddress",
            "system.externalcontrol",
        )

        const val PORT = 33336
    }
}
