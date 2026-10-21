package io.kud.tutti.protocol.bravia

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeTransport(private val answer: (path: String, method: String) -> HttpReply) : BraviaTransport {
    val calls = mutableListOf<Triple<String, String, Map<String, String>>>()

    override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply {
        calls += Triple(path, body, headers)
        val method = if (path == BraviaClient.IRCC_PATH) "ircc" else Json.parseToJsonElement(body).jsonObject["method"]!!.jsonPrimitive.content
        return answer(path, method)
    }
}

class BraviaClientTest {
    private fun ok(result: String) = HttpReply(200, "{\"result\":$result,\"id\":1}")

    @Test
    fun `sends the psk and parses power`() = runTest {
        val transport = FakeTransport { _, _ -> ok("[{\"status\":\"active\"}]") }
        val client = BraviaClient(transport) { "0000" }
        assertEquals(BraviaResult.Ok(PowerStatus.ACTIVE), client.powerStatus())
        val (path, body, headers) = transport.calls.single()
        assertEquals("/sony/system", path)
        assertEquals("0000", headers[BraviaClient.PSK_HEADER])
        assertTrue(body.contains("\"version\":\"1.0\""))
    }

    @Test
    fun `discovery gates calls and picks the highest version`() = runTest {
        val transport = FakeTransport { path, method ->
            when {
                method == "getMethodTypes" && path == "/sony/audio" ->
                    HttpReply(200, "{\"results\":[[\"getSoundSettings\",[],[],\"1.0\"],[\"getSoundSettings\",[],[],\"1.1\"]],\"id\":1}")
                method == "getMethodTypes" -> HttpReply(200, "{\"results\":[],\"id\":1}")
                else -> ok("[[{\"target\":\"outputTerminal\",\"currentValue\":\"audioSystem\",\"candidate\":[{\"value\":\"speaker\"},{\"value\":\"audioSystem\"}]}]]")
            }
        }
        val client = BraviaClient(transport) { "0000" }
        val caps = client.discover().getOrNull()
        assertNotNull(caps)
        assertEquals("1.1", caps.version(BraviaService.AUDIO, "getSoundSettings"))

        val sound = client.soundSettings("outputTerminal")
        assertEquals("audioSystem", sound.getOrNull()?.single()?.currentValue)
        assertTrue(transport.calls.last().second.contains("\"version\":\"1.1\""))

        val before = transport.calls.size
        assertEquals(BraviaResult.Failure(BraviaError.Unsupported("getPictureQualitySettings")), client.pictureSettings())
        assertEquals(before, transport.calls.size)
    }

    @Test
    fun `auth failures are recognised in http and rpc form`() = runTest {
        assertEquals(
            BraviaResult.Failure(BraviaError.Unauthorized),
            BraviaClient(FakeTransport { _, _ -> HttpReply(403, "") }) { "bad" }.powerStatus(),
        )
        assertEquals(
            BraviaResult.Failure(BraviaError.Unauthorized),
            BraviaClient(FakeTransport { _, _ -> HttpReply(200, "{\"error\":[403,\"Forbidden\"],\"id\":1}") }) { "bad" }.powerStatus(),
        )
    }

    @Test
    fun `rpc errors survive a non-2xx status`() = runTest {
        val client = BraviaClient(FakeTransport { _, _ -> HttpReply(500, "{\"error\":[40005,\"Display Is Turned off\"],\"id\":1}") }) { "0000" }
        val result = client.pictureSettings()
        assertIs<BraviaResult.Failure>(result)
        val error = result.error
        assertIs<BraviaError.Rpc>(error)
        assertTrue(error.isIllegalState)
        assertEquals(
            BraviaResult.Failure(BraviaError.Http(502)),
            BraviaClient(FakeTransport { _, _ -> HttpReply(502, "<html>") }) { "0000" }.powerStatus(),
        )
    }

    @Test
    fun `transport exceptions become results`() = runTest {
        val client = BraviaClient(object : BraviaTransport {
            override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply = throw IOException("unreachable")
        }) { "0000" }
        assertEquals(BraviaResult.Failure(BraviaError.Transport("unreachable")), client.powerStatus())
    }

    @Test
    fun `ircc posts a soap envelope`() = runTest {
        val transport = FakeTransport { _, _ -> HttpReply(200, "") }
        assertEquals(BraviaResult.Ok(Unit), BraviaClient(transport) { "0000" }.sendIrcc("AAAAAQAAAAEAAAAvAw=="))
        val (path, body, headers) = transport.calls.single()
        assertEquals("/sony/ircc", path)
        assertTrue(body.contains("<IRCCCode>AAAAAQAAAAEAAAAvAw==</IRCCCode>"))
        assertEquals("\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"", headers["SOAPACTION"])
    }
}
