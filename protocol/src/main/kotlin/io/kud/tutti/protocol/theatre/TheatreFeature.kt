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
enum class TheatreFeature(
    val key: String,
    val kind: FeatureKind,
    val notifies: Boolean = true,
    /**
     * A Technical details readout, asked by [TheatreClient.refreshDetails] when that screen opens,
     * never on connect or in the periodic re-ask: the bar takes one request at a time, so nine
     * more `get`s there would delay every everyday answer behind values nobody is looking at.
     */
    val detail: Boolean = false,
) {
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

    // Technical details: read-only, never set by tutti, unverified on the Bar 9 until a report
    // answers them (docs/reverse/bravia-connect.md §2). Vocabularies unknown: shown raw.

    /** Internal temperature, as the bar spells it (unit unknown). */
    TEMPERATURE("system.temperature", FeatureKind.ReadOnly, notifies = false, detail = true),
    TIMEZONE("system.timezone", FeatureKind.ReadOnly, notifies = false, detail = true),
    LANGUAGE("system.language", FeatureKind.ReadOnly, notifies = false, detail = true),

    /** Sales region the firmware was built for. */
    REGION("system.destination", FeatureKind.ReadOnly, notifies = false, detail = true),
    MANUFACTURER("system.manufacturer", FeatureKind.ReadOnly, notifies = false, detail = true),

    /** The name the user gave the bar: shown on the phone, blanked in every log and report. */
    DEVICE_NAME("system.devicename", FeatureKind.ReadOnly, notifies = false, detail = true),

    /** AV sync on the TV path, distinct from [AV_SYNC]. Read-only here: a setter waits on a report. */
    TV_AV_SYNC("tv.avsync", FeatureKind.ReadOnly, notifies = false, detail = true),

    /** Wired or wireless. Never written: the wrong value cuts tutti's own link. */
    NETWORK_MODE("network.mode", FeatureKind.ReadOnly, notifies = false, detail = true),

    /** DHCP on or off. Never written, as [NETWORK_MODE]. */
    NETWORK_DHCP("network.dhcp", FeatureKind.ReadOnly, notifies = false, detail = true),
    ;

    /** Wire value for a toggle, or null when the feature is not a toggle. */
    fun toggleValue(on: Boolean): String? = (kind as? FeatureKind.Toggle)?.let { if (on) it.on else it.off }

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): TheatreFeature? = byKey[key]

        /** What tutti asks on connect and re-asks while connected. */
        val everyday: List<TheatreFeature> = entries.filterNot { it.detail }

        /** Asked only when Technical details opens. */
        val details: List<TheatreFeature> = entries.filter { it.detail }

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

        /** Keys whose value no log line or report may show: [NEVER_ASKED] plus the user's own name for the bar. */
        val BLANKED: Set<String> = NEVER_ASKED + DEVICE_NAME.key

        const val PORT = 33336
    }
}
