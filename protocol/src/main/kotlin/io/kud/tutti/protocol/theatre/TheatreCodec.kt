package io.kud.tutti.protocol.theatre

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Newline-delimited JSON, one object per line. */
object TheatreCodec {
    /** Encodes one request, newline included. */
    fun encode(message: TheatreOutbound): String {
        val json = buildJsonObject {
            put("id", message.id)
            when (message) {
                is TheatreOutbound.GetRequest -> {
                    put("type", "get")
                    put("feature", message.feature)
                }
                is TheatreOutbound.SetRequest -> {
                    put("type", "set")
                    put("feature", message.feature)
                    val number = if (message.numeric) message.value.toIntOrNull() else null
                    if (number != null) put("value", number) else put("value", message.value)
                }
            }
        }
        return json.toString() + "\n"
    }

    /**
     * Decodes one JSON object. Never throws: anything unexpected becomes [TheatreInbound.Malformed].
     *
     * Tolerant where the Bar 9 might differ from the Theatre Quad that documented the protocol,
     * each case tested: `type` in any case; `id` as a number or a numeric string; `value` as a
     * string, number or boolean (kept as its text); a message with no `type` is a result when it
     * carries an `id`, and a notify when it carries a feature and a value but no `id`.
     */
    fun decode(line: String): TheatreInbound {
        val text = line.trim().trim('\u0000').trim()
        if (text.isEmpty()) return TheatreInbound.Malformed(line, "empty line")
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return TheatreInbound.Malformed(line, "not JSON")
        } catch (e: IllegalArgumentException) {
            return TheatreInbound.Malformed(line, "not JSON")
        }
        val obj = element as? JsonObject ?: return TheatreInbound.Malformed(line, "not an object")
        val feature = obj["feature"].stringOrNull()
        val value = obj["value"].stringOrNull()
        val id = obj["id"].idOrNull()
        val type = obj["type"].stringOrNull()?.trim()?.lowercase()
        return when {
            type == "result" -> TheatreInbound.Result(id = id, feature = feature, value = value)
            type == "notify" -> notify(line, feature, value)
            type != null -> TheatreInbound.Malformed(line, "unknown type")
            id != null && value != null -> TheatreInbound.Result(id = id, feature = feature, value = value)
            id == null && feature != null && value != null -> TheatreInbound.Notify(feature, value)
            else -> TheatreInbound.Malformed(line, "missing type")
        }
    }

    private fun notify(line: String, feature: String?, value: String?): TheatreInbound =
        if (feature == null || value == null) {
            TheatreInbound.Malformed(line, "notify without feature or value")
        } else {
            TheatreInbound.Notify(feature, value)
        }

    /** A number, or a string holding one (`"12"`). */
    private fun JsonElement?.idOrNull(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.intOrNull ?: primitive.content.trim().toIntOrNull()
    }

    private fun JsonElement?.stringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content
    }
}
