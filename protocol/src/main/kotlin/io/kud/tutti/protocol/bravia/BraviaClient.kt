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
import io.kud.tutti.protocol.net.CertificatePinMismatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * BRAVIA REST (JSON-RPC over HTTP), authenticated by the IP-control cookie of Sony's "Normal"
 * authentication (`Cookie: auth=…`, see [IpRegistration]) when [cookie] gives one, else by the
 * Pre-Shared Key (`X-Auth-PSK`). Never both on one request.
 *
 * Call [discover] first: once a service's methods are known, calls to methods it does not
 * list fail fast with [BraviaError.Unsupported] and versions follow what the TV offers.
 * Method names, services, versions, params and auth levels checked against Sony's reference
 * (pro-bravia.sony.net, REST API reference), still unverified on the XR80 (docs/architecture.md).
 */
class BraviaClient(
    private val transport: BraviaTransport,
    /** The `auth` cookie's value; listed before [psk] so `BraviaClient(transport) { psk }` still reads as the PSK. */
    private val cookie: () -> String? = { null },
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
        // Without the PSK: discovery is auth "none", and it is the call that settles which
        // scheme the transport uses, so the key never goes out before that is known.
        val guide = rawCall(
            GUIDE_PATH,
            "getSupportedApiInfo",
            listOf(buildJsonObject { putJsonArray("services") { BraviaService.entries.forEach { add(it.id) } } }),
            "1.0",
            authenticated = false,
        )
        if (guide is BraviaResult.Ok) {
            val found = BraviaParsers.supportedApiInfo(guide.value)
            if (found.isNotEmpty()) return BraviaResult.Ok(Capabilities(found, SOURCE_GUIDE).also { capabilities = it })
        }
        val found = mutableMapOf<BraviaService, List<MethodInfo>>()
        var lastError: BraviaError? = null
        for (service in BraviaService.entries) {
            val bare = rawCall(service.path, "getMethodTypes", listOf(JsonPrimitive("")), "1.0", authenticated = false)
            // Some firmware may want the key even here; by now the guide call has settled the scheme.
            val result = if ((bare as? BraviaResult.Failure)?.error == BraviaError.Unauthorized) rawCall(service.path, "getMethodTypes", listOf(JsonPrimitive("")), "1.0") else bare
            when (result) {
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

    /** What the TV says it supports (`WOL`, `PIP`…). Read-only; values may identify the TV, see [SupportedFunction]. */
    suspend fun supportedFunctions() =
        call(BraviaService.SYSTEM, "getSystemSupportedFunction").map(BraviaParsers::supportedFunctions)

    /** Every interface (empty `netif` asks for all), with its MAC: what Wake-on-LAN needs. */
    suspend fun networkSettings() =
        call(BraviaService.SYSTEM, "getNetworkSettings", listOf(buildJsonObject { put("netif", "") })).map(BraviaParsers::networkSettings)

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

    /**
     * `appControl.terminateApps` (v1.0, no parameters): Sony documents it as terminating every
     * application the TV runs, never one by name, so the UI says "close apps", plural.
     */
    suspend fun terminateApps() = call(BraviaService.APP_CONTROL, "terminateApps").map { }

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
            BraviaResult.Failure(transportError(e))
        }
    }

    private suspend fun rawCall(
        path: String,
        method: String,
        params: List<JsonElement>,
        version: String,
        authenticated: Boolean = true,
    ): BraviaResult<JsonArray> {
        val id = ids.incrementAndGet()
        val reply = try {
            transport.post(
                path,
                JsonRpc.request(method, id, params, version),
                (if (authenticated) authHeaders() else emptyMap()) + ("Content-Type" to "application/json; charset=UTF-8"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BraviaResult.Failure(transportError(e))
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

    /**
     * Step one of the code flow: `actRegister` with no credentials. A 401 means the TV now shows
     * its 4-digit code ([Registration.CodeShown]); a 200 with a cookie means this client id was
     * already registered and the TV let it straight back in.
     */
    suspend fun requestCode(clientId: String, nickname: String = IpRegistration.NICKNAME, nowMs: Long = System.currentTimeMillis()) =
        register(clientId, nickname, emptyMap(), nowMs, previous = null, refused = Registration.CodeShown)

    /** Step two: the code typed in, as Basic auth. 401/403 is a wrong code ([Registration.Refused]). */
    suspend fun submitCode(clientId: String, code: String, nickname: String = IpRegistration.NICKNAME, nowMs: Long = System.currentTimeMillis()): BraviaResult<Registration> {
        if (!IpRegistration.validCode(code)) return BraviaResult.Ok(Registration.Refused)
        return register(clientId, nickname, mapOf(AUTHORIZATION to IpRegistration.basic(code)), nowMs, previous = null, refused = Registration.Refused)
    }

    /**
     * Renewal (unverified, see [IpRegistration]): `actRegister` again with only the cookie. A 200
     * with a fresh cookie replaces it; a 200 without one keeps [current] as it is; 401/403 means
     * the TV wants a new code.
     */
    suspend fun renew(current: AuthCookie, nickname: String = IpRegistration.NICKNAME, nowMs: Long = System.currentTimeMillis()) =
        register(current.clientId, nickname, mapOf(COOKIE to IpRegistration.cookieHeader(current.value)), nowMs, previous = current, refused = Registration.Refused)

    private suspend fun register(
        clientId: String,
        nickname: String,
        credentials: Map<String, String>,
        nowMs: Long,
        previous: AuthCookie?,
        refused: Registration,
    ): BraviaResult<Registration> {
        val reply = try {
            transport.post(
                IpRegistration.PATH,
                JsonRpc.request(IpRegistration.METHOD, ids.incrementAndGet(), IpRegistration.params(clientId, nickname), IpRegistration.VERSION),
                credentials + ("Content-Type" to "application/json; charset=UTF-8"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BraviaResult.Failure(transportError(e))
        }
        // Asking for the code, only a 401 means "shown": a 403 is a TV that won't register at all
        // (IP control off, or authentication set to the PSK alone).
        val asking = refused == Registration.CodeShown
        if (reply.status == 401 || (reply.status == 403 && !asking)) return BraviaResult.Ok(refused)
        if (reply.status == 403) return BraviaResult.Failure(BraviaError.Unauthorized)
        return when (val response = JsonRpc.parse(reply.body)) {
            is JsonRpc.Response.Error -> when {
                response.code == 401 || (response.code == 403 && !asking) -> BraviaResult.Ok(refused)
                response.code == 403 -> BraviaResult.Failure(BraviaError.Unauthorized)
                else -> BraviaResult.Failure(BraviaError.Rpc(response.code, response.message))
            }
            is JsonRpc.Response.Malformed ->
                BraviaResult.Failure(if (reply.status !in 200..299) BraviaError.Http(reply.status) else BraviaError.Malformed(response.detail))
            is JsonRpc.Response.Success -> {
                if (reply.status !in 200..299) return BraviaResult.Failure(BraviaError.Http(reply.status))
                val fresh = IpRegistration.parseSetCookie(reply.setCookies, clientId, nowMs)
                when {
                    fresh != null -> BraviaResult.Ok(Registration.Registered(fresh))
                    previous != null -> BraviaResult.Ok(Registration.Registered(previous))
                    else -> BraviaResult.Failure(BraviaError.Malformed("no auth cookie"))
                }
            }
        }
    }

    private fun transportError(e: Exception): BraviaError =
        if (generateSequence<Throwable>(e) { it.cause }.take(8).any { it is CertificatePinMismatch }) {
            BraviaError.PinMismatch
        } else {
            BraviaError.Transport(e.message ?: e.javaClass.simpleName)
        }

    private fun authHeaders(): Map<String, String> {
        cookie()?.takeIf { IpRegistration.validCookieValue(it) }?.let { return mapOf(COOKIE to IpRegistration.cookieHeader(it)) }
        return psk()?.takeIf { it.isNotEmpty() }?.let { mapOf(PSK_HEADER to it) } ?: emptyMap()
    }

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
        const val COOKIE = "Cookie"
        const val AUTHORIZATION = "Authorization"

        /** Headers that carry a secret: never logged, and a request with one is authenticated. */
        val CREDENTIAL_HEADERS: Set<String> = setOf(PSK_HEADER, COOKIE, AUTHORIZATION)

        fun carriesCredential(headers: Map<String, String>): Boolean =
            headers.keys.any { name -> CREDENTIAL_HEADERS.any { it.equals(name, ignoreCase = true) } }
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
