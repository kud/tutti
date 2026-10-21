package io.kud.tutti.protocol.bravia

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsonRpcTest {
    @Test
    fun `builds the sony request shape`() {
        assertEquals(
            "{\"method\":\"getPowerStatus\",\"id\":1,\"params\":[],\"version\":\"1.0\"}",
            JsonRpc.request("getPowerStatus", 1, emptyList(), "1.0"),
        )
        assertEquals(
            "{\"method\":\"getMethodTypes\",\"id\":2,\"params\":[\"\"],\"version\":\"1.0\"}",
            JsonRpc.request("getMethodTypes", 2, listOf(JsonPrimitive("")), "1.0"),
        )
    }

    @Test
    fun `parses result and results`() {
        val ok = JsonRpc.parse("{\"result\":[{\"status\":\"active\"}],\"id\":1}")
        assertIs<JsonRpc.Response.Success>(ok)
        assertEquals(1, ok.id)
        assertIs<JsonRpc.Response.Success>(JsonRpc.parse("{\"results\":[[\"getPowerStatus\",[],[],\"1.0\"]],\"id\":2}"))
    }

    @Test
    fun `parses error arrays`() {
        assertEquals(JsonRpc.Response.Error(3, 12, "getFoo"), JsonRpc.parse("{\"error\":[12,\"getFoo\"],\"id\":3}"))
        assertEquals(JsonRpc.Response.Error(4, 40005, "Display Is Turned off"), JsonRpc.parse("{\"error\":[40005,\"Display Is Turned off\"],\"id\":4}"))
        assertEquals(JsonRpc.Response.Error(null, 7, ""), JsonRpc.parse("{\"error\":[7,null]}"))
        assertEquals(JsonRpc.Response.Error(5, 403, "Forbidden"), JsonRpc.parse("{\"error\":[403,\"Forbidden\"],\"id\":5}"))
    }

    @Test
    fun `malformed bodies are reported, not thrown`() {
        listOf("", "<html>", "[]", "{\"id\":1}", "{\"error\":\"oops\",\"id\":1}", "{\"error\":[],\"id\":1}", "{\"result\":{},\"id\":1}")
            .forEach { assertIs<JsonRpc.Response.Malformed>(JsonRpc.parse(it), it) }
    }
}
