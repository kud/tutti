package io.kud.tutti.protocol.sim

import io.kud.tutti.protocol.net.ClientIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BRAVIA REST JSON-RPC, over HTTP and HTTPS at once (two ports, one TV). Authenticated
 * methods want `X-Auth-PSK: [psk]`; a wrong or missing one gets [authFailureStatus] with Sony's
 * error body. [sleep] closes both ports, which is also how a TV still booting looks.
 * With [code] set, Sony's "Normal" authentication works too: `accessControl.actRegister` without
 * credentials answers 401 and "shows" the code ([codesShown]), with `Basic base64(":" + code)` it
 * answers 200 and a fresh `auth` cookie, and with a cookie it issued it renews it (when
 * [renewsByCookie]). Model, MAC, apps, codes and cookies are invented.
 */
class FakeBraviaTv(
    identity: ClientIdentity = SimKeys.tv,
    @Volatile var psk: String = "0000",
) : AutoCloseable {
    @Volatile var power: String = "active"
    @Volatile var volume: Int = 12
    @Volatile var muted: Boolean = false
    @Volatile var authFailureStatus: Int = 403

    /** `off`, `low`, `high` or `pictureOff`: the screen dark with the sound on. */
    @Volatile var powerSaving: String = "off"

    /** Wake-on-LAN, the menu's "Remote start" (unverified equivalence). */
    @Volatile var wol: Boolean = false

    /** What is on screen: an input uri, or null inside an app, where the TV reports no content. */
    @Volatile var playing: String? = "extInput:hdmi?port=1"

    /** Whether something is plugged in and on behind each HDMI port, as `connection` reports it. */
    val hdmiConnected: MutableMap<Int, Boolean> = ConcurrentHashMap(mapOf(1 to true, 2 to false, 3 to false, 4 to true))

    /** Each port's user label (the TV's "Edit inputs"), empty when unset. */
    val hdmiLabels: MutableMap<Int, String> = ConcurrentHashMap(mapOf(4 to "Computer"))

    /** `getApplicationList`: title to uri, invented apps. */
    val apps: MutableList<Pair<String, String>> = CopyOnWriteArrayList(
        listOf(
            "Example Video" to "com.sony.dtv.com.example.video.com.example.video.MainActivity",
            "Example Music" to "com.sony.dtv.com.example.music.com.example.music.MainActivity",
        ),
    )

    /** Uris `setActiveApp` launched, in order. */
    val launched = CopyOnWriteArrayList<String>()

    /** How many times `terminateApps` closed everything. */
    val terminated = java.util.concurrent.atomic.AtomicInteger(0)

    /** `getRemoteControllerInfo`'s IRCC keys: invented codes. */
    val irccKeys: MutableList<Pair<String, String>> = CopyOnWriteArrayList(
        listOf("Home" to "AAAAAQAAAAEAAABgAw==", "SleepTimer" to "AAAAAQAAAAEAAAA2Aw==", "Netflix" to "AAAAAgAAABoAAAB8Aw=="),
    )

    /** IRCC codes received on `/sony/ircc`, in order. */
    val ircc = CopyOnWriteArrayList<String>()

    /** Picture settings by target; brightness is a range, the mode a choice. */
    val picture: MutableMap<String, String> = ConcurrentHashMap(mapOf("pictureMode" to "standard", "brightness" to "25"))

    /** The sound output (`speaker`, `audioSystem`, …), write-and-read on this model. */
    @Volatile var outputTerminal: String = "audioSystem"

    /** Scene select for the current input. */
    @Volatile var scene: String = "auto"

    /** The 4-digit code this TV "shows"; null when "Normal" authentication is off. */
    @Volatile var code: String? = null

    /** Cookies this TV has handed out and still accepts. */
    val cookies: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile var renewsByCookie: Boolean = true
    val codesShown = java.util.concurrent.atomic.AtomicInteger(0)
    private val issued = java.util.concurrent.atomic.AtomicInteger(0)

    /** Files served to a GET, by path, as the TV's `/DIAL/icon/…` app icons are. Anything else is 404. */
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()

    /** Whether a GET for [files] wants the PSK too, as some firmware may. */
    @Volatile var filesNeedPsk: Boolean = false

    /** GET paths in the order they arrived. */
    val gets = CopyOnWriteArrayList<String>()

    /** Method names in the order they arrived, authorised or not. */
    val calls = CopyOnWriteArrayList<String>()

    /** Methods that answer `[12, "No Such Method"]`, as on a model without them. */
    val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val http = Port("bravia-http", tls = null)
    val https = Port("bravia-https", tls = identity)

    fun start() = apply {
        http.start()
        https.start()
    }

    fun sleep() {
        http.sleep()
        https.sleep()
    }

    fun wake() {
        http.wake()
        https.wake()
    }

    override fun close() {
        http.close()
        https.close()
    }

    private companion object {
        val POWER_SAVING_MODES = setOf("off", "low", "high", "pictureOff")
        val OUTPUT_TERMINALS = listOf("speaker", "speaker_hdmi", "hdmi", "audioSystem")
        val PICTURE_MODES = listOf("standard", "cinema", "game", "vivid")
    }

    inner class Port(name: String, tls: ClientIdentity?) : SimServer(name) {
        private val tlsServer = tls?.let { identity ->
            object : TlsSimServer("$name-tls", identity, needClientAuth = false) {
                override fun serve(link: Link) = Unit
                fun wrapPublic(raw: java.net.Socket) = wrap(raw)
            }
        }

        fun changeCertificate(to: ClientIdentity) {
            tlsServer?.changeCertificate(to) ?: error("$this has no TLS")
        }

        override fun wrap(raw: java.net.Socket): java.net.Socket = tlsServer?.wrapPublic(raw) ?: raw

        /** Requests that carried the PSK header on this port, for "never in clear" checks. */
        val pskSeen = java.util.concurrent.atomic.AtomicInteger(0)

        /** Requests that carried a cookie or an Authorization header on this port. */
        val credentialSeen = java.util.concurrent.atomic.AtomicInteger(0)

        override fun serve(link: Link) {
            while (true) {
                val request = readRequest(link.input) ?: return
                if (request.headers.containsKey("x-auth-psk")) pskSeen.incrementAndGet()
                if (request.headers.containsKey("cookie") || request.headers.containsKey("authorization")) credentialSeen.incrementAndGet()
                if (!link.received()) continue
                var setCookie: String? = null
                val (status, bytes, type) = if (request.method == "GET") file(request) else if (request.path == "/sony/ircc") irccAnswer(request) else answer(request).let { (status, body, cookie) ->
                    setCookie = cookie
                    Triple(status, body.encodeToByteArray(), "application/json")
                }
                val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                    (setCookie?.let { "Set-Cookie: $it\r\n" } ?: "") +
                    "Content-Type: $type\r\nContent-Length: ${bytes.size}\r\n\r\n"
                link.write(head.encodeToByteArray() + bytes)
            }
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        fun line(): String? {
            val out = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (out.isEmpty()) null else out.toString()
                if (c == '\n'.code) return out.toString().trimEnd('\r')
                out.append(c.toChar())
            }
        }
        val start = line() ?: return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line() ?: return null
            if (header.isEmpty()) break
            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = input.readNBytes(length).decodeToString()
        return Request(start.split(' ')[0], start.split(' ')[1], headers, body)
    }

    private fun file(request: Request): Triple<Int, ByteArray, String> {
        gets += request.path
        if (filesNeedPsk && !authorised(request)) return Triple(authFailureStatus, ByteArray(0), "text/plain")
        val bytes = files[request.path] ?: return Triple(404, "<html>Not Found</html>".encodeToByteArray(), "text/html")
        return Triple(200, bytes, "image/png")
    }

    /** `/sony/ircc`: a SOAP envelope carrying one `IRCCCode`, authenticated like the RPCs. */
    private fun irccAnswer(request: Request): Triple<Int, ByteArray, String> {
        calls += "X_SendIRCC"
        if (!authorised(request)) return Triple(authFailureStatus, ByteArray(0), "text/xml")
        val code = Regex("<IRCCCode>([^<]*)</IRCCCode>").find(request.body)?.groupValues?.get(1)
            ?: return Triple(500, ByteArray(0), "text/xml")
        ircc += code
        val ok = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:X_SendIRCCResponse xmlns:u="urn:schemas-sony-com:service:IRCC:1"></u:X_SendIRCCResponse></s:Body></s:Envelope>"""
        return Triple(200, ok.encodeToByteArray(), "text/xml")
    }

    private fun authorised(request: Request): Boolean {
        if (request.headers["x-auth-psk"] == psk) return true
        val cookie = request.headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("auth=") }?.removePrefix("auth=")
        return cookie != null && cookie in cookies
    }

    private fun issue(): String = "auth=" + "simcookie${issued.incrementAndGet()}".also { cookies += it } + "; Path=/sony/; Max-Age=1209600"

    /** `actRegister`: 401 and the code on screen, or the cookie for the right code or a cookie it knows. */
    private fun register(request: Request, id: Int): Triple<Int, String, String?> {
        val expected = code ?: return Triple(200, "{\"error\":[403,\"Forbidden\"],\"id\":$id}", null)
        val ok = "{\"result\":[],\"id\":$id}"
        val basic = request.headers["authorization"]
        if (basic != null) {
            val sent = runCatching { java.util.Base64.getDecoder().decode(basic.removePrefix("Basic ")).decodeToString() }.getOrNull()
            return if (sent == ":$expected") Triple(200, ok, issue()) else Triple(401, "", null)
        }
        if (request.headers.containsKey("cookie")) {
            return if (renewsByCookie && authorised(request)) Triple(200, ok, issue()) else Triple(401, "", null)
        }
        codesShown.incrementAndGet()
        return Triple(401, "", null)
    }

    private fun answer(request: Request): Triple<Int, String, String?> {
        val rpc = Json.parseToJsonElement(request.body) as JsonObject
        if (rpc["method"]?.jsonPrimitive?.content == "actRegister") {
            calls += "actRegister"
            return register(request, rpc["id"]?.jsonPrimitive?.intOrNull ?: 0)
        }
        return answerRpc(request).let { Triple(it.first, it.second, null) }
    }

    private fun answerRpc(request: Request): Pair<Int, String> {
        val rpc = Json.parseToJsonElement(request.body) as JsonObject
        val method = rpc["method"]!!.jsonPrimitive.content
        val id = rpc["id"]?.jsonPrimitive?.intOrNull ?: 0
        calls += method
        fun ok(vararg result: JsonElement) = 200 to buildJsonObject {
            put("result", JsonArray(result.toList()))
            put("id", id)
        }.toString()
        fun error(code: Int, message: String, status: Int = 200) = status to buildJsonObject {
            putJsonArray("error") {
                add(JsonPrimitive(code))
                add(JsonPrimitive(message))
            }
            put("id", id)
        }.toString()
        val service = request.path.removePrefix("/sony/")
        if (method == "getSupportedApiInfo") return ok(supportedApis())
        // No authentication, as on the TV: the diagnostic's first REST probe.
        if (service == "system" && method == "getInterfaceInformation") {
            return ok(buildJsonObject {
                put("productCategory", "tv")
                put("productName", "BRAVIA")
                put("modelName", "K-00SIM00")
                put("serverName", "")
                put("interfaceVersion", "6.3.0")
            })
        }
        if (!authorised(request)) return error(authFailureStatus, "Forbidden", status = authFailureStatus)
        if (method in missing) return error(12, "No Such Method")
        val params = (rpc["params"] as? JsonArray)?.firstOrNull() as? JsonObject
        return when (service to method) {
            "system" to "getPowerStatus" -> ok(buildJsonObject { put("status", power) })
            "system" to "setPowerStatus" -> {
                power = if (params?.get("status")?.jsonPrimitive?.booleanOrNull == true) "active" else "standby"
                ok()
            }
            "system" to "getSystemInformation" -> ok(buildJsonObject {
                put("product", "TV")
                put("model", "K-00SIM00")
                put("macAddr", "02:00:00:00:00:01")
            })
            "system" to "getPowerSavingMode" -> ok(buildJsonObject { put("mode", powerSaving) })
            "system" to "setPowerSavingMode" -> {
                val mode = params?.get("mode")?.jsonPrimitive?.contentOrNull
                if (mode !in POWER_SAVING_MODES) return error(3, "Illegal Argument")
                powerSaving = mode!!
                ok()
            }
            "system" to "getWolMode" -> ok(buildJsonObject { put("enabled", wol) })
            "system" to "setWolMode" -> {
                wol = params?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: return error(3, "Illegal Argument")
                ok()
            }
            "system" to "getNetworkSettings" -> ok(buildJsonArray {
                add(buildJsonObject {
                    put("netif", "eth0")
                    put("hwAddr", "02:00:00:00:00:02")
                    put("ipAddrV4", "")
                })
                add(buildJsonObject {
                    put("netif", "wlan0")
                    put("hwAddr", "02:00:00:00:00:01")
                    put("ipAddrV4", "127.0.0.1")
                })
            })
            "system" to "getRemoteControllerInfo" -> ok(
                buildJsonObject {
                    put("bundled", true)
                    put("type", "IR_REMOTE_BUNDLE_TYPE_AEP_N")
                },
                buildJsonArray {
                    irccKeys.forEach { (name, value) ->
                        add(buildJsonObject {
                            put("name", name)
                            put("value", value)
                        })
                    }
                },
            )
            "audio" to "getVolumeInformation" -> ok(buildJsonArray {
                add(buildJsonObject {
                    put("target", "speaker")
                    put("volume", volume)
                    put("mute", muted)
                    put("minVolume", 0)
                    put("maxVolume", 100)
                })
            })
            "audio" to "setAudioVolume" -> {
                params?.get("volume")?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { volume = it }
                ok()
            }
            "audio" to "setAudioMute" -> {
                muted = params?.get("status")?.jsonPrimitive?.booleanOrNull ?: return error(3, "Illegal Argument")
                ok()
            }
            "audio" to "getSoundSettings" -> ok(buildJsonArray {
                add(setting("outputTerminal", outputTerminal, OUTPUT_TERMINALS))
            })
            "audio" to "setSoundSettings" -> {
                val (target, value) = firstSetting(params) ?: return error(3, "Illegal Argument")
                if (target != "outputTerminal" || value !in OUTPUT_TERMINALS) return error(3, "Illegal Argument")
                outputTerminal = value
                ok()
            }
            "video" to "getPictureQualitySettings" -> {
                val target = params?.get("target")?.jsonPrimitive?.contentOrNull.orEmpty()
                ok(buildJsonArray {
                    if (target.isEmpty() || target == "pictureMode") add(setting("pictureMode", picture["pictureMode"], PICTURE_MODES))
                    if (target.isEmpty() || target == "brightness") add(buildJsonObject {
                        put("target", "brightness")
                        put("currentValue", picture["brightness"])
                        put("isAvailable", true)
                        putJsonArray("candidate") {
                            add(buildJsonObject {
                                put("min", 0)
                                put("max", 50)
                                put("step", 1)
                            })
                        }
                    })
                })
            }
            "video" to "setPictureQualitySettings" -> {
                val (target, value) = firstSetting(params) ?: return error(3, "Illegal Argument")
                val valid = when (target) {
                    "pictureMode" -> value in PICTURE_MODES
                    "brightness" -> value.toIntOrNull() in 0..50
                    else -> false
                }
                if (!valid) return error(3, "Illegal Argument")
                picture[target] = value
                ok()
            }
            "videoScreen" to "getSceneSetting" -> ok(setting("sceneSetting", scene, listOf("auto", "auto24pSync", "general")))
            "videoScreen" to "setSceneSetting" -> {
                scene = params?.get("value")?.jsonPrimitive?.contentOrNull ?: return error(3, "Illegal Argument")
                ok()
            }
            "avContent" to "getPlayingContentInfo" -> {
                val uri = playing ?: return error(7, "Illegal State")
                val port = uri.substringAfter("port=").toIntOrNull()
                ok(buildJsonObject {
                    put("uri", uri)
                    put("source", uri.substringBefore('?'))
                    put("title", "HDMI $port")
                })
            }
            "avContent" to "getCurrentExternalInputsStatus" -> ok(buildJsonArray {
                hdmiConnected.keys.sorted().forEach { port ->
                    add(buildJsonObject {
                        put("uri", "extInput:hdmi?port=$port")
                        put("title", "HDMI $port")
                        put("label", hdmiLabels[port].orEmpty())
                        put("icon", "meta:hdmi")
                        put("connection", hdmiConnected[port] == true)
                        put("status", if (hdmiConnected[port] == true) "true" else "false")
                    })
                }
            })
            "avContent" to "setPlayContent" -> {
                val uri = params?.get("uri")?.jsonPrimitive?.contentOrNull ?: return error(3, "Illegal Argument")
                val port = uri.removePrefix("extInput:hdmi?port=").toIntOrNull()
                if (port == null || port !in hdmiConnected) return error(3, "Illegal Argument")
                playing = uri
                ok()
            }
            "appControl" to "getApplicationList" -> ok(buildJsonArray {
                apps.forEach { (title, uri) ->
                    add(buildJsonObject {
                        put("title", title)
                        put("uri", uri)
                        put("icon", "")
                    })
                }
            })
            "appControl" to "setActiveApp" -> {
                val uri = params?.get("uri")?.jsonPrimitive?.contentOrNull ?: return error(3, "Illegal Argument")
                if (apps.none { it.second == uri }) return error(3, "Illegal Argument")
                launched += uri
                playing = null
                ok()
            }
            "appControl" to "terminateApps" -> {
                terminated.incrementAndGet()
                ok()
            }
            else -> error(12, "No Such Method")
        }
    }

    private fun setting(target: String, current: String?, values: List<String>) = buildJsonObject {
        put("target", target)
        put("currentValue", current)
        put("isAvailable", true)
        putJsonArray("candidate") { values.forEach { add(buildJsonObject { put("value", it) }) } }
    }

    private fun firstSetting(params: JsonObject?): Pair<String, String>? {
        val first = (params?.get("settings") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val target = first["target"]?.jsonPrimitive?.contentOrNull ?: return null
        val value = first["value"]?.jsonPrimitive?.contentOrNull ?: return null
        return target to value
    }

    private fun supportedApis(): JsonArray = buildJsonArray {
        val methods = mapOf(
            "system" to listOf(
                "getPowerStatus", "setPowerStatus", "getSystemInformation", "getInterfaceInformation", "getPowerSavingMode", "setPowerSavingMode",
                "getWolMode", "setWolMode", "getNetworkSettings", "getRemoteControllerInfo",
            ),
            "audio" to listOf("getVolumeInformation", "setAudioVolume", "setAudioMute", "getSoundSettings", "setSoundSettings"),
            "avContent" to listOf("getPlayingContentInfo", "getCurrentExternalInputsStatus", "setPlayContent"),
            "appControl" to listOf("getApplicationList", "setActiveApp", "terminateApps"),
            "video" to listOf("getPictureQualitySettings", "setPictureQualitySettings"),
            "videoScreen" to listOf("getSceneSetting", "setSceneSetting"),
        )
        methods.forEach { (service, names) ->
            add(buildJsonObject {
                put("service", service)
                putJsonArray("apis") {
                    names.filter { it !in missing }.forEach { name ->
                        add(buildJsonObject {
                            put("name", name)
                            putJsonArray("versions") { add(buildJsonObject { put("version", "1.0") }) }
                        })
                    }
                }
            })
        }
    }
}

/**
 * The soundbar's port 33336: newline-delimited JSON. `get` answers the value, or `ERR` for a
 * feature it does not have; `set` answers `ACK` (or `NAK` for a feature in [refused]) and then
 * notifies every client. No authentication, as on the real bar.
 */
class FakeTheatreBar(initial: Map<String, String> = DEFAULTS) : SimServer("theatre-bar") {
    val values = ConcurrentHashMap(initial)
    val refused: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every line received, verbatim. */
    val lines = CopyOnWriteArrayList<String>()

    /**
     * Per feature: how long a `set`'s ACK, and the notify after it, take to come. The value
     * changes at once, as the Bar 9's 360 Spatial did while its ACK came late (2026-10-25).
     */
    val ackDelayMillis: MutableMap<String, Long> = ConcurrentHashMap()

    /** Features that never notify, DRC and Auto Volume on the real bar: the app polls them. */
    val quiet: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("audio.drangecomp", "audio.aav")) }

    /**
     * The Bar 9's habit (Erwann's wire log, 2026-10-24): it takes [busyMillis] to answer, and a
     * request that arrives before it has answered the last is dropped, unanswered ([dropped]).
     */
    @Volatile var busyMillis: Long = 0
    val dropped = java.util.concurrent.atomic.AtomicInteger()

    override fun serve(link: Link) {
        val reader = BufferedReader(InputStreamReader(link.input, Charsets.UTF_8))
        var busyUntil = 0L
        while (true) {
            val line = reader.readLine() ?: return
            lines += line
            if (!link.received()) continue
            val busy = busyMillis
            if (busy > 0) {
                val now = System.nanoTime() / 1_000_000
                if (now < busyUntil) {
                    dropped.incrementAndGet()
                    continue
                }
                busyUntil = now + busy
            }
            val request = Json.parseToJsonElement(line) as JsonObject
            val id = request["id"]?.jsonPrimitive?.intOrNull
            val feature = request["feature"]?.jsonPrimitive?.content ?: continue
            when (request["type"]?.jsonPrimitive?.content) {
                "get" -> later(busy) { reply(link, id, feature, values[feature] ?: "ERR") }
                "set" -> {
                    val value = request["value"]?.jsonPrimitive?.content ?: continue
                    when {
                        feature in refused -> later(busy) { reply(link, id, feature = null, value = "NAK") }
                        !values.containsKey(feature) -> later(busy) { reply(link, id, feature = null, value = "ERR") }
                        else -> {
                            values[feature] = value
                            val acknowledge = {
                                reply(link, id, feature = null, value = "ACK")
                                if (feature !in quiet) notifyAll(feature, value)
                            }
                            later(maxOf(busy, ackDelayMillis[feature] ?: 0)) { acknowledge() }
                        }
                    }
                }
            }
        }
    }

    /** Runs [answer] now, or on its own thread after [delayMillis]. */
    private fun later(delayMillis: Long, answer: () -> Unit) {
        if (delayMillis <= 0) return answer()
        kotlin.concurrent.thread(isDaemon = true, name = "theatre-bar-later") {
            Thread.sleep(delayMillis)
            runCatching { answer() }
        }
    }

    /** A change made on the bar itself (its remote, its buttons): pushed to every client. */
    fun change(feature: String, value: String) {
        values[feature] = value
        notifyAll(feature, value)
    }

    private fun reply(link: Link, id: Int?, feature: String?, value: String) {
        val json = buildJsonObject {
            put("type", "result")
            id?.let { put("id", it) }
            feature?.let { put("feature", it) }
            put("value", value)
        }
        link.write((json.toString() + "\n").encodeToByteArray())
    }

    private fun notifyAll(feature: String, value: String) {
        val line = buildJsonObject {
            put("type", "notify")
            put("feature", feature)
            put("value", value)
        }.toString() + "\n"
        links.forEach { runCatching { it.write(line.encodeToByteArray()) } }
    }

    companion object {
        val DEFAULTS = mapOf(
            "main.power" to "on",
            "main.volumestep" to "20",
            "main.mute" to "off",
            "main.input" to "tv",
            "audio.nightmode" to "off",
        )
    }
}
