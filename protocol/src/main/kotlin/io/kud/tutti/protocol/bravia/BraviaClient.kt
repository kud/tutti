package io.kud.tutti.protocol.bravia

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * BRAVIA REST (JSON-RPC over HTTP) with Pre-Shared Key auth (`X-Auth-PSK`).
 *
 * Call [discover] first: once a service's methods are known, calls to methods it does not
 * list fail fast with [BraviaError.Unsupported] and versions follow what the TV offers.
 * Method names, services, versions, params and auth levels checked against Sony's reference
 * (pro-bravia.sony.net, REST API reference), still unverified on the XR80 (docs/architecture.md).
 */
class BraviaClient(
    private val transport: BraviaTransport,
    private val psk: () -> String?,
) {
    private val ids = AtomicInteger(0)

    @Volatile
    var capabilities: Capabilities = Capabilities.EMPTY
        private set

    /**
     * Sony documents `guide.getSupportedApiInfo` (auth none) as the discovery call; the older
     * per-service `getMethodTypes` is not in the reference, so it is only the fallback.
     */
    suspend fun discover(): BraviaResult<Capabilities> {
        val guide = rawCall(
            GUIDE_PATH,
            "getSupportedApiInfo",
            listOf(buildJsonObject { putJsonArray("services") { BraviaService.entries.forEach { add(it.id) } } }),
            "1.0",
        )
        if (guide is BraviaResult.Ok) {
            val found = BraviaParsers.supportedApiInfo(guide.value)
            if (found.isNotEmpty()) return BraviaResult.Ok(Capabilities(found, SOURCE_GUIDE).also { capabilities = it })
        }
        val found = mutableMapOf<BraviaService, List<MethodInfo>>()
        var lastError: BraviaError? = null
        for (service in BraviaService.entries) {
            when (val result = rawCall(service.path, "getMethodTypes", listOf(JsonPrimitive("")), "1.0")) {
                is BraviaResult.Ok -> found[service] = BraviaParsers.methodTypes(result.value)
                is BraviaResult.Failure -> {
                    if (result.error == BraviaError.Unauthorized) return result
                    lastError = result.error
                }
            }
        }
        if (found.isEmpty()) return BraviaResult.Failure(lastError ?: BraviaError.Malformed("no service answered"))
        return BraviaResult.Ok(Capabilities(found, SOURCE_METHOD_TYPES).also { capabilities = it })
    }

    /** Generic call, gated by [capabilities]. [version] defaults to the TV's highest, then to Sony's documented one. */
    suspend fun call(
        service: BraviaService,
        method: String,
        params: List<JsonElement> = emptyList(),
        version: String? = null,
    ): BraviaResult<JsonArray> {
        val caps = capabilities
        if (caps.isKnown(service) && !caps.supports(service, method)) {
            return BraviaResult.Failure(BraviaError.Unsupported(method))
        }
        val chosen = version ?: caps.version(service, method) ?: DEFAULT_VERSIONS[method] ?: "1.0"
        return rawCall(service.path, method, params, chosen)
    }

    suspend fun powerStatus() = call(BraviaService.SYSTEM, "getPowerStatus").map(BraviaParsers::powerStatus)

    suspend fun setPower(on: Boolean) =
        call(BraviaService.SYSTEM, "setPowerStatus", listOf(buildJsonObject { put("status", on) })).map { }

    suspend fun systemInformation() = call(BraviaService.SYSTEM, "getSystemInformation").map(BraviaParsers::systemInformation)

    suspend fun powerSavingMode() = call(BraviaService.SYSTEM, "getPowerSavingMode").map(BraviaParsers::powerSavingMode)

    /** Sony modes: `off`, `low`, `high`, `pictureOff`. */
    suspend fun setPowerSavingMode(mode: String) =
        call(BraviaService.SYSTEM, "setPowerSavingMode", listOf(buildJsonObject { put("mode", mode) })).map { }

    /** Wake-on-LAN: whether the TV powers on from a network packet (BRAVIA Connect's "Remote start", unverified equivalence). */
    suspend fun wolMode() = call(BraviaService.SYSTEM, "getWolMode").map(BraviaParsers::wolMode)

    suspend fun setWolMode(enabled: Boolean) =
        call(BraviaService.SYSTEM, "setWolMode", listOf(buildJsonObject { put("enabled", enabled) })).map { }

    /** Scene select for the current input; null when it has none. */
    suspend fun sceneSetting() = call(BraviaService.VIDEO_SCREEN, "getSceneSetting").map(BraviaParsers::sceneSetting)

    /** Sony values: `auto`, `auto24pSync`, `general`. */
    suspend fun setSceneSetting(value: String) =
        call(BraviaService.VIDEO_SCREEN, "setSceneSetting", listOf(buildJsonObject { put("value", value) })).map { }

    suspend fun remoteControllerInfo() =
        call(BraviaService.SYSTEM, "getRemoteControllerInfo").map(BraviaParsers::remoteControllerInfo)

    /** Empty target = every picture setting the current input exposes. */
    suspend fun pictureSettings(target: String = "") =
        call(BraviaService.VIDEO, "getPictureQualitySettings", listOf(target(target))).map(BraviaParsers::settings)

    suspend fun setPictureSetting(target: String, value: String) =
        call(BraviaService.VIDEO, "setPictureQualitySettings", listOf(settings(target, value))).map { }

    /**
     * Not in Sony's reference (only setSoundSettings v1.1 is): call it only when discovery lists
     * it. e.g. target `outputTerminal` (speaker / audioSystem).
     */
    suspend fun soundSettings(target: String = "") =
        call(BraviaService.AUDIO, "getSoundSettings", listOf(target(target))).map(BraviaParsers::settings)

    suspend fun setSoundSetting(target: String, value: String) =
        call(BraviaService.AUDIO, "setSoundSettings", listOf(settings(target, value))).map { }

    suspend fun speakerSettings(target: String = "") =
        call(BraviaService.AUDIO, "getSpeakerSettings", listOf(target(target))).map(BraviaParsers::settings)

    suspend fun setSpeakerSetting(target: String, value: String) =
        call(BraviaService.AUDIO, "setSpeakerSettings", listOf(settings(target, value))).map { }

    suspend fun volumeInformation() = call(BraviaService.AUDIO, "getVolumeInformation").map(BraviaParsers::volumeInformation)

    suspend fun externalInputs() =
        call(BraviaService.AV_CONTENT, "getCurrentExternalInputsStatus").map(BraviaParsers::externalInputs)

    suspend fun playingContent() = call(BraviaService.AV_CONTENT, "getPlayingContentInfo").map(BraviaParsers::playingContent)

    /** e.g. `extInput:hdmi?port=2`. */
    suspend fun setPlayContent(uri: String) =
        call(BraviaService.AV_CONTENT, "setPlayContent", listOf(buildJsonObject { put("uri", uri) })).map { }

    suspend fun applications() = call(BraviaService.APP_CONTROL, "getApplicationList").map(BraviaParsers::applications)

    suspend fun setActiveApp(uri: String) =
        call(BraviaService.APP_CONTROL, "setActiveApp", listOf(buildJsonObject { put("uri", uri) })).map { }

    /** IRCC over SOAP, for keys the v2 remote lacks. [code] comes from [remoteControllerInfo]. */
    suspend fun sendIrcc(code: String): BraviaResult<Unit> {
        val headers = authHeaders() + mapOf(
            "Content-Type" to "text/xml; charset=UTF-8",
            "SOAPACTION" to "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"",
        )
        return try {
            val reply = transport.post(IRCC_PATH, Ircc.envelope(code), headers)
            when {
                reply.status == 401 || reply.status == 403 -> BraviaResult.Failure(BraviaError.Unauthorized)
                reply.status !in 200..299 -> BraviaResult.Failure(BraviaError.Http(reply.status))
                else -> BraviaResult.Ok(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BraviaResult.Failure(BraviaError.Transport(e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun rawCall(
        path: String,
        method: String,
        params: List<JsonElement>,
        version: String,
    ): BraviaResult<JsonArray> {
        val id = ids.incrementAndGet()
        val reply = try {
            transport.post(
                path,
                JsonRpc.request(method, id, params, version),
                authHeaders() + ("Content-Type" to "application/json; charset=UTF-8"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BraviaResult.Failure(BraviaError.Transport(e.message ?: e.javaClass.simpleName))
        }
        if (reply.status == 401 || reply.status == 403) return BraviaResult.Failure(BraviaError.Unauthorized)
        // Sony answers RPC errors with 200 and sometimes with 4xx/5xx and a JSON body: parse first.
        return when (val response = JsonRpc.parse(reply.body)) {
            is JsonRpc.Response.Success -> BraviaResult.Ok(response.result)
            is JsonRpc.Response.Error ->
                if (response.code == 401 || response.code == 403) {
                    BraviaResult.Failure(BraviaError.Unauthorized)
                } else {
                    BraviaResult.Failure(BraviaError.Rpc(response.code, response.message))
                }
            is JsonRpc.Response.Malformed ->
                if (reply.status !in 200..299) {
                    BraviaResult.Failure(BraviaError.Http(reply.status))
                } else {
                    BraviaResult.Failure(BraviaError.Malformed(response.detail))
                }
        }
    }

    private fun authHeaders(): Map<String, String> = psk()?.takeIf { it.isNotEmpty() }?.let { mapOf(PSK_HEADER to it) } ?: emptyMap()

    private fun target(target: String) = buildJsonObject { put("target", target) }

    private fun settings(target: String, value: String) = buildJsonObject {
        putJsonArray("settings") {
            addJsonObject {
                put("target", target)
                put("value", value)
            }
        }
    }

    companion object {
        const val PSK_HEADER = "X-Auth-PSK"
        const val IRCC_PATH = "/sony/ircc"
        const val GUIDE_PATH = "/sony/guide"
        const val SOURCE_GUIDE = "getSupportedApiInfo"
        const val SOURCE_METHOD_TYPES = "getMethodTypes"

        /** Used only before [discover] has run, or when a service did not answer it. */
        val DEFAULT_VERSIONS: Map<String, String> = mapOf(
            "getSoundSettings" to "1.1",
            "setSoundSettings" to "1.1",
            "getVolumeInformation" to "1.0",
            "getCurrentExternalInputsStatus" to "1.0",
        )
    }
}

object Ircc {
    /**
     * The TV's sleep-timer key, if its getRemoteControllerInfo lists one. Sony documents no sleep
     * timer in REST; the IRCC name varies by model and is unverified on the XR80, so this matches
     * names, never a hard-coded code.
     */
    fun sleepTimerCode(codes: List<IrccCode>): String? =
        codes.firstOrNull { it.name.equals("SleepTimer", ignoreCase = true) || it.name.equals("Sleep", ignoreCase = true) }?.value

    /**
     * The TV's IRCC keys as a shortcut list: one per name (first code wins), blank or
     * code-less entries dropped, sorted by [displayName].
     */
    fun shortcuts(codes: List<IrccCode>): List<IrccCode> = codes
        .filter { it.name.isNotBlank() && it.value.isNotBlank() }
        .distinctBy { it.name }
        .sortedBy { displayName(it.name).lowercase() }

    /** Sony names its keys in CamelCase or snake case ("ActionMenu", "Tv_Radio"): spaced out for reading. */
    fun displayName(name: String): String = name
        .replace('_', ' ')
        .replace(Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun envelope(code: String): String {
        val safe = code.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        return """<?xml version="1.0"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>$safe</IRCCCode></u:X_SendIRCC></s:Body>""" +
            """</s:Envelope>"""
    }
}
