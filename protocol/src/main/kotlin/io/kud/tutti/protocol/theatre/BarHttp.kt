package io.kud.tutti.protocol.theatre

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The bar's HTTP management plane, port 54545: `POST /fcgi-bin/request.fcgi` with
 * `{"type":"http_get","packet":[["key",…]]}`, answered by
 * `{"type":"http_get_result","packet":[[{"feature":"key","value":"…"},…]]}`.
 * Shapes from the MIT bravia-quad-homeassistant HTTP client; unverified on the Bar 9.
 *
 * No authentication exists here, so tutti only ever reads, and only the keys in [BarHttpKey]:
 * the request is built from that enum alone, so no other key can leave the phone.
 * `fw.check_update` is deliberately absent: whether it only checks, or also contacts Sony and
 * stages anything, is unknown (docs/reverse/bravia-connect.md §6).
 */
enum class BarHttpKey(val key: String) {
    /** Wired or wireless, as the bar spells it. */
    NETWORK_TYPE("network.connectiontype"),

    /** Whether the bar reaches the internet. */
    INTERNET("network.internet"),

    /** Wi-Fi signal, as the bar spells it (scale unknown). */
    WIFI_STRENGTH("wlan.strength"),

    /** The name the bar advertises on AirPlay: shown on the phone, never logged. */
    AIRPLAY_NAME("airplay.accessoryname"),
    ;

    companion object {
        /**
         * Keys the bar answers here that tutti never sends: addresses, the Wi-Fi name, nearby
         * networks, the key pair, and every write (firmware, Wi-Fi, WPS, IP configuration). A test
         * holds [BarHttpKey] clear of them.
         */
        val NEVER_ASKED: Set<String> = setOf(
            "network.devicename",
            "network.macaddress_wired",
            "network.macaddress_wireless",
            "inet4.ipaddress",
            "inet6.ipaddress",
            "ssid.name",
            "wlan.scan",
            "wlan.esslist",
            "system.publickey",
            "fw.check_update",
            "fw.upload",
            "fw.request_update",
            "fw.update",
            "wlan.connect",
            "wlan.connect_manual",
            "wlan.reconnect",
            "wps.connectpbc",
            "wps.connectpin",
            "wps.refreshpin",
            "network.applyconfig",
            "inet4.dhcp",
            "inet6.enabled",
            "inet6.dhcp",
        )

        /** Keys whose value never goes in a log or report. */
        val BLANKED: Set<String> = setOf(AIRPLAY_NAME.key)

        const val PORT = 54545
        const val PATH = "/fcgi-bin/request.fcgi"
    }
}

/** One key's answer: a value, a refusal (`NAK`/`ERR`), or nothing in the reply. */
sealed interface BarHttpValue {
    data class Answered(val value: String) : BarHttpValue

    data object Refused : BarHttpValue

    data object NotReported : BarHttpValue
}

/** What one read returned, per key. Missing keys are [BarHttpValue.NotReported]. */
data class BarHttpReadouts(val values: Map<BarHttpKey, BarHttpValue> = emptyMap()) {
    operator fun get(key: BarHttpKey): BarHttpValue = values[key] ?: BarHttpValue.NotReported

    fun value(key: BarHttpKey): String? = (get(key) as? BarHttpValue.Answered)?.value
}

object BarHttpCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** The `http_get` body for [keys], in one packet group. Only [BarHttpKey]s can be asked. */
    fun request(keys: Collection<BarHttpKey> = BarHttpKey.entries): String = buildJsonObject {
        put("type", "http_get")
        putJsonArray("packet") {
            add(JsonArray(keys.map { JsonPrimitive(it.key) }))
        }
    }.toString()

    /**
     * Parses an `http_get_result`, keeping only [BarHttpKey]s. Null when the body is not one
     * (not JSON, another `type`, no packet): a listener on 54545 that is not this plane.
     * Groups may be arrays of objects or bare objects; values may be strings or numbers.
     */
    fun parse(body: String): BarHttpReadouts? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        if ((root["type"] as? JsonPrimitive)?.contentOrNull != "http_get_result") return null
        val packet = root["packet"] as? JsonArray ?: return null
        val items = packet.flatMap { group ->
            when (group) {
                is JsonArray -> group.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(group)
                else -> emptyList()
            }
        }
        val byKey = BarHttpKey.entries.associateBy { it.key }
        val values = buildMap {
            for (item in items) {
                val key = (item["feature"] as? JsonPrimitive)?.contentOrNull?.let(byKey::get) ?: continue
                val raw = (item["value"] as? JsonPrimitive)?.contentOrNull?.trim()
                val value = when {
                    raw.isNullOrEmpty() -> BarHttpValue.NotReported
                    REFUSALS.any { raw.equals(it, ignoreCase = true) } -> BarHttpValue.Refused
                    else -> BarHttpValue.Answered(raw)
                }
                put(key, value)
            }
        }
        return BarHttpReadouts(values)
    }

    private val REFUSALS = setOf("NAK", "ERR")
}

/**
 * The optional 54545 management plane answered HTTP but does not accept this read protocol.
 * Distinct from a network failure: callers may present it as unsupported rather than a broken bar.
 */
class BarHttpUnavailable(val status: Int) : java.io.IOException("HTTP $status")

/** HTTP POST to the bar's 54545. `:app` implements it: plain HTTP, private LAN addresses only. */
interface BarHttpTransport {
    /**
     * Posts [body] as JSON to [BarHttpKey.PATH] and returns the reply body. Throws
     * [BarHttpUnavailable] when the HTTP endpoint answers non-2xx, or an I/O error when unreachable.
     */
    suspend fun post(body: String): String
}

class BarHttpClient(private val transport: BarHttpTransport) {
    /** One request for every readout. Null when the bar did not answer as this plane does. Throws on network failure. */
    suspend fun readouts(): BarHttpReadouts? = BarHttpCodec.parse(transport.post(BarHttpCodec.request()))
}
