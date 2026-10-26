package io.kud.tutti.protocol.diagnostic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A raw-ish picture of a reply, enough to fix a parser without carrying the user's data:
 * keys and value types, values only for keys known to be harmless (model, version, target,
 * current value…), an array as its length and its first element. Values under identifying
 * keys (serial, MAC, ids, IPs, names) are replaced and handed to [sensitive], so the report
 * can scrub them anywhere else they turn up.
 *
 * [public] lists keys that identify nobody in this one reply although they usually would: the
 * `name` of each getRemoteControllerInfo entry is a button ("Red", "Play", "Mute"), and handing
 * those to the scrub list once redacted half the words of a report.
 */
object JsonShape {
    fun of(element: JsonElement, sensitive: MutableSet<String>, maxLength: Int = 700, public: Set<String> = emptySet()): String {
        val shape = Shape(public.mapTo(HashSet()) { it.lowercase() })
        shape.collect(element, null, sensitive)
        val text = shape.render(element, null, 0)
        return if (text.length > maxLength) text.take(maxLength) + "…" else text
    }

    fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return k in SENSITIVE || k.endsWith("id") || "mac" in k || "serial" in k || "addr" in k ||
            "token" in k || "secret" in k || "password" in k || k.startsWith("ip")
    }

    private class Shape(private val public: Set<String>) {
        private fun hidden(key: String?): Boolean = key != null && isSensitiveKey(key) && key.lowercase() !in public

        fun collect(element: JsonElement, key: String?, sensitive: MutableSet<String>) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> collect(v, k, sensitive) }
                is JsonArray -> element.forEach { collect(it, key, sensitive) }
                is JsonPrimitive -> if (element !is JsonNull && hidden(key)) {
                    element.content.takeIf { it.isNotBlank() }?.let(sensitive::add)
                }
            }
        }

        fun render(element: JsonElement, key: String?, depth: Int): String = when {
            depth > MAX_DEPTH -> "…"
            element is JsonNull -> "null"
            element is JsonObject -> element.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + render(v, k, depth + 1) }
            element is JsonArray -> when (element.size) {
                0 -> "[]"
                1 -> "[" + render(element[0], key, depth + 1) + "]"
                else -> "[${element.size}x " + render(element[0], key, depth + 1) + "]"
            }
            element is JsonPrimitive -> primitive(element, key)
            else -> "?"
        }

        private fun primitive(p: JsonPrimitive, key: String?): String {
            if (hidden(key)) return "<redacted>"
            p.booleanOrNull?.let { if (!p.isString) return it.toString() }
            val keep = key != null && key.lowercase() in KEEP
            return when {
                p.isString && keep -> "\"" + p.content.take(48) + "\""
                p.isString -> "str(${p.content.length})"
                keep -> p.content
                else -> "num"
            }
        }
    }

    private const val MAX_DEPTH = 6

    private val SENSITIVE = setOf("cid", "duid", "name", "serial", "ssid", "bssid", "psk", "key", "uuid", "netif", "hwaddr", "email", "account")

    /** Keys whose values help fix a mismatch and identify nobody. */
    private val KEEP = setOf(
        "model", "modelname", "product", "productname", "productcategory", "generation", "interfaceversion",
        "fwversion", "region", "area", "language", "status", "target", "currentvalue", "value", "source",
        "mode", "type", "version", "volume", "mute", "minvolume", "maxvolume", "connection", "title",
        "iscurrent", "isavailable", "min", "max", "step", "format",
    )
}
