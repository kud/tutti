package io.kud.tutti.protocol.bravia

enum class BraviaService(val id: String) {
    SYSTEM("system"),
    AUDIO("audio"),
    AV_CONTENT("avContent"),
    APP_CONTROL("appControl"),
    VIDEO("video"),

    /** Scene select (getSceneSetting / setSceneSetting). */
    VIDEO_SCREEN("videoScreen"),
    ;

    val path: String get() = "/sony/$id"
}

sealed interface BraviaError {
    /** JSON-RPC error array `[code, message]`. */
    data class Rpc(val code: Int, val message: String) : BraviaError {
        /**
         * 12 No Such Method, 14 Unsupported Version, 15 Unsupported Operation, 404 no matching
         * API version, 501 Not Implemented (Sony's common error codes).
         */
        val isUnsupported: Boolean get() = code == 12 || code == 14 || code == 15 || code == 404 || code == 501

        /** 7 Illegal State, 40005 Display is turned off: usually the TV is in standby. */
        val isIllegalState: Boolean get() = code == 7 || code == 40005
    }

    data class Http(val status: Int) : BraviaError

    /** HTTP 401/403 or RPC 403: wrong or missing PSK. */
    data object Unauthorized : BraviaError

    /** Not offered by this TV, per `getMethodTypes`. */
    data class Unsupported(val method: String) : BraviaError

    data class Malformed(val detail: String) : BraviaError

    data class Transport(val detail: String) : BraviaError
}

sealed interface BraviaResult<out T> {
    data class Ok<T>(val value: T) : BraviaResult<T>

    data class Failure(val error: BraviaError) : BraviaResult<Nothing>
}

inline fun <T, R> BraviaResult<T>.map(transform: (T) -> R): BraviaResult<R> = when (this) {
    is BraviaResult.Ok -> BraviaResult.Ok(transform(value))
    is BraviaResult.Failure -> this
}

fun <T> BraviaResult<T>.getOrNull(): T? = (this as? BraviaResult.Ok<T>)?.value

enum class PowerStatus { ACTIVE, STANDBY, UNKNOWN }

data class SettingCandidate(
    val value: String,
    val title: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val isAvailable: Boolean = true,
)

/** One entry of get{PictureQuality,Sound,Speaker}Settings. A range setting has one candidate with min/max/step. */
data class TvSetting(
    val target: String,
    val currentValue: String?,
    val isAvailable: Boolean,
    val candidates: List<SettingCandidate>,
    val title: String? = null,
) {
    /**
     * Sony sets min, max and step to -1 when a target is not numeric, so a lone enum candidate
     * carries -1/-1 too: a range needs max above min, not just both present.
     */
    val isRange: Boolean
        get() {
            val only = candidates.singleOrNull() ?: return false
            val min = only.min ?: return false
            val max = only.max ?: return false
            return max > min
        }

    companion object {
        /** setSoundSettings' documented `outputTerminal` values (Sony's reference); there is no documented getter. */
        val OUTPUT_TERMINALS = listOf("speaker", "speaker_hdmi", "hdmi", "audioSystem")

        const val SCENE_TARGET = "sceneSetting"
        const val OUTPUT_TERMINAL = "outputTerminal"

        /** The two outputs a shortcut offers, from [OUTPUT_TERMINALS]: the HDMI audio system (the bar) and the TV's own speakers. */
        const val TERMINAL_AUDIO_SYSTEM = "audioSystem"
        const val TERMINAL_SPEAKER = "speaker"
    }
}

data class ExternalInput(
    val uri: String,
    val title: String,
    val label: String,
    val connected: Boolean,
    val icon: String? = null,
    val status: String? = null,
)

data class TvApp(val title: String, val uri: String, val icon: String? = null)

data class IrccCode(val name: String, val value: String)

data class VolumeInfo(val target: String, val volume: Int, val muted: Boolean, val min: Int?, val max: Int?)

/**
 * `avContent.getPlayingContentInfo`. Sony's current reference documents only the external-input
 * case ([uri] `extInput:hdmi?port=1`, [source], [title] such as "HDMI 2"). The tuner fields are
 * undocumented there; they are the ones Home Assistant's braviatv reads when [uri] starts with
 * `tv` (homeassistant/components/braviatv/coordinator.py, `async_update_playing`): [title] is then
 * the channel name, [programTitle] the programme, [dispNum] the channel number, [startDateTime]
 * the programme's start (ISO 8601) and [durationSec] its length. All optional: whether the XR80
 * sends them is unknown, and with an app playing the TV may answer with nothing at all.
 */
data class PlayingContent(
    val uri: String?,
    val source: String?,
    val title: String?,
    val programTitle: String? = null,
    val dispNum: String? = null,
    val startDateTime: String? = null,
    val durationSec: Int? = null,
) {
    /** The TV's own tuner (`tv:dvbt?…`, `tv:dvbs?…`), told apart the way Home Assistant does. */
    val isTuner: Boolean get() = uri?.startsWith("tv") == true

    val isExternalInput: Boolean get() = uri?.startsWith("extInput") == true
}

/** [mac] is `macAddr`, often the wired interface's: prefer [NetworkInterfaceInfo] for Wake-on-LAN. Never log it. */
data class SystemInfo(val model: String?, val product: String?, val generation: String?, val name: String?, val mac: String? = null)

/**
 * One entry of `system.getSystemSupportedFunction`: an [option] (e.g. `WOL`, `PIP`) and its
 * [value]. Some values identify the TV (the `WOL` value is its MAC address), so the UI shows
 * [shownValue], which is null for anything [io.kud.tutti.protocol.net.Redact] would scrub.
 */
data class SupportedFunction(val option: String, val value: String?) {
    val shownValue: String?
        get() = value?.takeIf { it.isNotBlank() && io.kud.tutti.protocol.net.Redact.text(it) == it }
}

/** One of the TV's network interfaces. Never log [mac]. */
data class NetworkInterfaceInfo(val name: String?, val mac: String?, val ipv4: String?)

data class MethodInfo(val name: String, val version: String)

/**
 * What the TV says it can do: `getMethodTypes` per service. The settings UI is built from
 * this, never from a hard-coded list, since the method set is unverified on the XR80.
 */
data class Capabilities(
    val methods: Map<BraviaService, List<MethodInfo>>,
    /** Which discovery call answered: `getSupportedApiInfo` or `getMethodTypes`. */
    val source: String? = null,
) {
    fun isKnown(service: BraviaService): Boolean = methods[service] != null

    fun supports(service: BraviaService, method: String): Boolean = methods[service]?.any { it.name == method } == true

    /** Highest offered version of [method], or null. */
    fun version(service: BraviaService, method: String): String? =
        methods[service]?.filter { it.name == method }?.map { it.version }?.maxWithOrNull(Comparator { a, b -> compareVersions(a, b) })

    companion object {
        val EMPTY = Capabilities(emptyMap())

        internal fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
