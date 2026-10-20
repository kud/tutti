package io.kud.salon.protocol.bravia

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Sony's JSON-RPC dialect: `{method, id, params: [...], version}` → `{result | results | error, id}`. */
object JsonRpc {
    fun request(method: String, id: Int, params: List<JsonElement>, version: String): String = buildJsonObject {
        put("method", method)
        put("id", id)
        put("params", JsonArray(params))
        put("version", version)
    }.toString()

    sealed interface Response {
        val id: Int?

        /** `result`, or `results` for getMethodTypes. */
        data class Success(override val id: Int?, val result: JsonArray) : Response

        data class Error(override val id: Int?, val code: Int, val message: String) : Response

        data class Malformed(override val id: Int?, val detail: String) : Response
    }

    fun parse(body: String): Response {
        val element = try {
            Json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            return Response.Malformed(null, "not JSON")
        }
        val obj = element as? JsonObject ?: return Response.Malformed(null, "not an object")
        val id = (obj["id"] as? JsonPrimitive)?.intOrNull
        val error = obj["error"]
        if (error != null && error !is JsonNull) {
            val array = error as? JsonArray ?: return Response.Malformed(id, "error is not an array")
            val code = (array.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return Response.Malformed(id, "error without code")
            val message = (array.getOrNull(1) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
            return Response.Error(id, code, message)
        }
        val result = (obj["result"] ?: obj["results"]) as? JsonArray ?: return Response.Malformed(id, "no result array")
        return Response.Success(id, result)
    }
}
