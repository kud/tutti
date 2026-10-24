package io.kud.tutti.protocol.diagnostic

import java.io.IOException

/** The planes docs/architecture.md names, in the order the diagnostic walks them. */
enum class Plane(val label: String) {
    DISCOVERY("discovery"),
    REST("bravia rest"),
    REMOTE("android tv remote v2"),
    BAR("soundbar tcp 33336"),
    BAR_HTTP("soundbar http 54545"),
    CLOUD("sony cloud"),
}

enum class StepStatus { PENDING, RUNNING, OK, FAILED, SKIPPED }

/**
 * One probe. [facts] are what the device reported (models, versions, response shapes):
 * they may hold anything the device sent, so they are scrubbed again when the report is
 * rendered, never trusted to be clean.
 */
data class StepResult(
    val id: String,
    val plane: Plane,
    val status: StepStatus = StepStatus.PENDING,
    val millis: Long? = null,
    val errorClass: String? = null,
    val message: String? = null,
    val facts: List<Pair<String, String>> = emptyList(),
)

/** Every step, in order. The UI lists them all as pending before the run starts. */
object DiagnosticSteps {
    const val DISCOVERY = "discovery.mdns"
    const val REST_INTERFACE = "rest.interface"
    const val REST_AUTH = "rest.auth"
    const val REST_METHODS = "rest.methods"
    const val REMOTE_IDENTITY = "remote.identity"
    const val REMOTE_PAIRING_PORT = "remote.pairing_port"
    const val REMOTE_TLS = "remote.tls"
    const val REMOTE_SESSION = "remote.session"
    const val BAR_CONNECT = "bar.connect"
    const val BAR_FEATURES = "bar.features"
    const val BAR_VOICE_ZOOM = "bar.voicezoom"
    const val BAR_HTTP = "bar.http"
    const val CLOUD = "cloud.plane"

    /** Read-only REST calls after discovery: (step id, service, method). */
    val REST_READS: List<RestRead> = listOf(
        RestRead("rest.power", "system", "getPowerStatus"),
        RestRead("rest.power_saving", "system", "getPowerSavingMode"),
        RestRead("rest.supported_functions", "system", "getSystemSupportedFunction"),
        RestRead("rest.ircc_codes", "system", "getRemoteControllerInfo"),
        RestRead("rest.volume", "audio", "getVolumeInformation"),
        RestRead("rest.sound", "audio", "getSoundSettings", target = true),
        RestRead("rest.speaker", "audio", "getSpeakerSettings", target = true),
        RestRead("rest.picture", "video", "getPictureQualitySettings", target = true),
        RestRead("rest.inputs", "avContent", "getCurrentExternalInputsStatus"),
        RestRead("rest.playing", "avContent", "getPlayingContentInfo", raw = true),
        RestRead("rest.apps", "appControl", "getApplicationList"),
    )

    val ALL: List<Pair<String, Plane>> = buildList {
        add(DISCOVERY to Plane.DISCOVERY)
        add(REST_INTERFACE to Plane.REST)
        add(REST_AUTH to Plane.REST)
        add(REST_METHODS to Plane.REST)
        REST_READS.forEach { add(it.id to Plane.REST) }
        add(REMOTE_IDENTITY to Plane.REMOTE)
        add(REMOTE_PAIRING_PORT to Plane.REMOTE)
        add(REMOTE_TLS to Plane.REMOTE)
        add(REMOTE_SESSION to Plane.REMOTE)
        add(BAR_CONNECT to Plane.BAR)
        add(BAR_FEATURES to Plane.BAR)
        add(BAR_VOICE_ZOOM to Plane.BAR)
        add(BAR_HTTP to Plane.BAR_HTTP)
        add(CLOUD to Plane.CLOUD)
    }

    fun initial(): List<StepResult> = ALL.map { (id, plane) -> StepResult(id, plane) }
}

/**
 * [target]: the method takes `[{"target":""}]` (every setting). [raw]: in developer mode the
 * report also keeps the reply itself, scrubbed, not only its shape (the playing content, whose
 * answer while an app plays is undocumented).
 */
data class RestRead(
    val id: String,
    val service: String,
    val method: String,
    val target: Boolean = false,
    val raw: Boolean = false,
)

/** Short, stable error classes, so two reports can be compared at a glance. */
object ErrorClass {
    fun of(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val names = chain.map { it.javaClass.simpleName }
        return when {
            "PinMismatchException" in names -> "pin_mismatch"
            chain.any { it is IOException && it.message == "not a private LAN address" } -> "not_lan"
            "SSLHandshakeException" in names -> "tls_handshake"
            names.any { it.startsWith("SSL") } -> "tls"
            "ConnectException" in names -> "connection_refused"
            "SocketTimeoutException" in names -> "timeout"
            "NoRouteToHostException" in names -> "no_route"
            "UnknownHostException" in names -> "unknown_host"
            "EOFException" in names -> "closed_by_peer"
            "SocketException" in names -> "socket"
            else -> names.first().ifEmpty { "error" }
        }
    }

    /** "SSLHandshakeException: … <- PinMismatchException: …", capped. Scrubbed later. */
    fun describe(error: Throwable): String =
        generateSequence(error) { it.cause }.take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message ?: "-"}" }
            .take(300)
}
