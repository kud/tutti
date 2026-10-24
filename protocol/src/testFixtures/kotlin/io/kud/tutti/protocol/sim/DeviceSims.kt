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
 * Model, MAC and apps are invented.
 */
class FakeBraviaTv(
    identity: ClientIdentity = SimKeys.tv,
    @Volatile var psk: String = "0000",
) : AutoCloseable {
    @Volatile var power: String = "active"
    @Volatile var volume: Int = 12
    @Volatile var authFailureStatus: Int = 403

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

        override fun serve(link: Link) {
            while (true) {
                val request = readRequest(link.input) ?: return
                if (!link.received()) continue
                val (status, body) = answer(request)
                val bytes = body.encodeToByteArray()
                val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                link.write(head.encodeToByteArray() + bytes)
            }
        }
    }

    private class Request(val path: String, val headers: Map<String, String>, val body: String)

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
        return Request(start.split(' ')[1], headers, body)
    }

    private fun answer(request: Request): Pair<Int, String> {
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
        if (request.headers["x-auth-psk"] != psk) return error(authFailureStatus, "Forbidden", status = authFailureStatus)
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
            "audio" to "getVolumeInformation" -> ok(buildJsonArray {
                add(buildJsonObject {
                    put("target", "speaker")
                    put("volume", volume)
                    put("mute", false)
                    put("minVolume", 0)
                    put("maxVolume", 100)
                })
            })
            "audio" to "setAudioVolume" -> {
                params?.get("volume")?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { volume = it }
                ok()
            }
            "avContent" to "getPlayingContentInfo" -> ok(buildJsonObject {
                put("uri", "extInput:hdmi?port=1")
                put("source", "extInput:hdmi")
                put("title", "HDMI 1")
            })
            else -> error(12, "No Such Method")
        }
    }

    private fun supportedApis(): JsonArray = buildJsonArray {
        val methods = mapOf(
            "system" to listOf("getPowerStatus", "setPowerStatus", "getSystemInformation"),
            "audio" to listOf("getVolumeInformation", "setAudioVolume"),
            "avContent" to listOf("getPlayingContentInfo"),
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

    override fun serve(link: Link) {
        val reader = BufferedReader(InputStreamReader(link.input, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: return
            lines += line
            if (!link.received()) continue
            val request = Json.parseToJsonElement(line) as JsonObject
            val id = request["id"]?.jsonPrimitive?.intOrNull
            val feature = request["feature"]?.jsonPrimitive?.content ?: continue
            when (request["type"]?.jsonPrimitive?.content) {
                "get" -> reply(link, id, feature, values[feature] ?: "ERR")
                "set" -> {
                    val value = request["value"]?.jsonPrimitive?.content ?: continue
                    when {
                        feature in refused -> reply(link, id, feature = null, value = "NAK")
                        !values.containsKey(feature) -> reply(link, id, feature = null, value = "ERR")
                        else -> {
                            values[feature] = value
                            reply(link, id, feature = null, value = "ACK")
                            notifyAll(feature, value)
                        }
                    }
                }
            }
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
