package io.kud.tutti.protocol.bravia

enum class BraviaService(val id: String) {
    SYSTEM("system"),
    AUDIO("audio"),
    AV_CONTENT("avContent"),
    APP_CONTROL("appControl"),
    VIDEO("video"),
    ;

    val path: String get() = "/sony/$id"
}

sealed interface BraviaError {
    /** JSON-RPC error array `[code, message]`. */
    data class Rpc(val code: Int, val message: String) : BraviaError {
        /** 12 No Such Method, 14 Unsupported Version, 15 Unsupported. */
        val isUnsupported: Boolean get() = code == 12 || code == 14 || code == 15

        /** 7 Illegal State, 40005 Display Is Turned Off: usually the TV is in standby. */
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
    val isRange: Boolean get() = candidates.size == 1 && candidates[0].min != null && candidates[0].max != null
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

data class PlayingContent(val uri: String?, val source: String?, val title: String?)

data class SystemInfo(val model: String?, val product: String?, val generation: String?, val name: String?)

data class MethodInfo(val name: String, val version: String)

/**
 * What the TV says it can do: `getMethodTypes` per service. The settings UI is built from
 * this, never from a hard-coded list, since the method set is unverified on the XR80.
 */
data class Capabilities(val methods: Map<BraviaService, List<MethodInfo>>) {
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
