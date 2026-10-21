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

    /** Decodes one line. Never throws: anything unexpected becomes [TheatreInbound.Malformed]. */
    fun decode(line: String): TheatreInbound {
        val text = line.trim()
        if (text.isEmpty()) return TheatreInbound.Malformed(line, "empty line")
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return TheatreInbound.Malformed(line, "not JSON")
        }
        val obj = element as? JsonObject ?: return TheatreInbound.Malformed(line, "not an object")
        val feature = obj["feature"].stringOrNull()
        val value = obj["value"].stringOrNull()
        return when (obj["type"].stringOrNull()) {
            "result" -> TheatreInbound.Result(id = (obj["id"] as? JsonPrimitive)?.intOrNull, feature = feature, value = value)
            "notify" ->
                if (feature == null || value == null) {
                    TheatreInbound.Malformed(line, "notify without feature or value")
                } else {
                    TheatreInbound.Notify(feature, value)
                }
            null -> TheatreInbound.Malformed(line, "missing type")
            else -> TheatreInbound.Malformed(line, "unknown type")
        }
    }

    private fun JsonElement?.stringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content
    }
}
