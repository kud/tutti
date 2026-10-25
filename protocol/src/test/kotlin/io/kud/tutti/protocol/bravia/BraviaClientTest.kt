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
    fun `discovery prefers the documented guide service`() = runTest {
        val transport = FakeTransport { path, method ->
            when {
                path == BraviaClient.GUIDE_PATH && method == "getSupportedApiInfo" -> ok(
                    "[[{\"service\":\"avContent\",\"protocols\":[\"xhrpost:jsonizer\"],\"apis\":[" +
                        "{\"name\":\"setPlayContent\",\"versions\":[{\"version\":\"1.0\",\"authLevel\":\"generic\"}]}," +
                        "{\"name\":\"getCurrentExternalInputsStatus\",\"versions\":[{\"version\":\"1.1\"},{\"version\":\"1.0\"}]}]}," +
                        "{\"service\":\"camera\",\"apis\":[{\"name\":\"x\",\"versions\":[{\"version\":\"1.0\"}]}]}]]",
                )
                else -> error("unexpected $path $method")
            }
        }
        val client = BraviaClient(transport) { "0000" }
        val caps = client.discover().getOrNull()
        assertNotNull(caps)
        assertEquals(BraviaClient.SOURCE_GUIDE, caps.source)
        assertEquals("1.1", caps.version(BraviaService.AV_CONTENT, "getCurrentExternalInputsStatus"))
        assertEquals(setOf(BraviaService.AV_CONTENT), caps.methods.keys)
        val (_, body, _) = transport.calls.single()
        assertTrue(body.contains("\"services\":[\"system\",\"audio\",\"avContent\",\"appControl\",\"video\",\"videoScreen\"]"))
    }

    @Test
    fun `discovery falls back to getMethodTypes when the guide is missing`() = runTest {
        val transport = FakeTransport { path, method ->
            when {
                path == BraviaClient.GUIDE_PATH -> HttpReply(404, "")
                method == "getMethodTypes" && path == "/sony/system" ->
                    HttpReply(200, "{\"results\":[[\"getPowerStatus\",[],[],\"1.0\"]],\"id\":1}")
                else -> HttpReply(200, "{\"error\":[12,\"No Such Method\"],\"id\":1}")
            }
        }
        val caps = BraviaClient(transport) { "0000" }.discover().getOrNull()
        assertNotNull(caps)
        assertEquals(BraviaClient.SOURCE_METHOD_TYPES, caps.source)
        assertTrue(caps.supports(BraviaService.SYSTEM, "getPowerStatus"))
    }

    @Test
    fun `sony common errors 404 and 501 read as unsupported`() {
        assertTrue(BraviaError.Rpc(404, "Not Found").isUnsupported)
        assertTrue(BraviaError.Rpc(501, "Not Implemented").isUnsupported)
        assertTrue(!BraviaError.Rpc(40005, "Display is turned off").isUnsupported)
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

    @Test
    fun `terminate apps sends the bare app control call`() = runTest {
        val transport = FakeTransport { _, _ -> ok("[]") }
        val client = BraviaClient(transport) { "0000" }
        assertEquals(BraviaResult.Ok(Unit), client.terminateApps())
        val (path, body, _) = transport.calls.single()
        assertEquals("/sony/appControl", path)
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("terminateApps", sent["method"]!!.jsonPrimitive.content)
        assertEquals("[]", sent["params"].toString())
    }
}
