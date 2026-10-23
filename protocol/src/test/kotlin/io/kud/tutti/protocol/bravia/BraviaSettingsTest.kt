package io.kud.tutti.protocol.bravia

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The advanced TV settings, shaped after Sony's REST reference examples (pro-bravia.sony.net). */
class BraviaSettingsTest {
    private class Recorder(private val result: String) : BraviaTransport {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply {
            calls += path to body
            return HttpReply(200, "{\"result\":$result,\"id\":1}")
        }
    }

    private fun parse(json: String) = Json.parseToJsonElement(json) as JsonArray

    @Test
    fun `an enum with sony's -1 bounds is not a range`() {
        val settings = BraviaParsers.settings(
            parse(
                """[[{"target":"pictureMode","currentValue":"standard","isAvailable":true,
                "candidate":[{"value":"standard","max":-1,"min":-1,"step":-1}]},
                {"target":"color","currentValue":"4","isAvailable":true,"candidate":[{"value":"","max":100,"min":0,"step":1}]}]]""",
            ),
        )
        assertFalse(settings[0].isRange)
        assertTrue(settings[1].isRange)
    }

    @Test
    fun `wol mode parses both states`() {
        assertEquals(true, BraviaParsers.wolMode(parse("""[{"enabled":true}]""")))
        assertEquals(false, BraviaParsers.wolMode(parse("""[{"enabled":false}]""")))
        assertNull(BraviaParsers.wolMode(parse("[]")))
    }

    @Test
    fun `scene setting becomes a choice, or nothing when the input has none`() {
        val scene = BraviaParsers.sceneSetting(
            parse("""[{"candidate":[{"value":"auto"},{"value":"auto24pSync"},{"value":"general"}],"currentValue":"auto"}]"""),
        )
        assertEquals(TvSetting.SCENE_TARGET, scene?.target)
        assertEquals("auto", scene?.currentValue)
        assertEquals(listOf("auto", "auto24pSync", "general"), scene?.candidates?.map { it.value })
        assertFalse(scene!!.isRange)
        assertNull(BraviaParsers.sceneSetting(parse("""[{"candidate":[],"currentValue":""}]""")))
    }

    @Test
    fun `the sleep timer key comes only from the tv's own list`() {
        assertEquals("AAAA", Ircc.sleepTimerCode(listOf(IrccCode("Home", "BBBB"), IrccCode("SleepTimer", "AAAA"))))
        assertEquals("CCCC", Ircc.sleepTimerCode(listOf(IrccCode("sleep", "CCCC"))))
        assertNull(Ircc.sleepTimerCode(listOf(IrccCode("Home", "BBBB"))))
    }

    @Test
    fun `set wol mode posts the documented params to system`() = runTest {
        val transport = Recorder("[]")
        val client = BraviaClient(transport) { "0000" }
        assertIs<BraviaResult.Ok<Unit>>(client.setWolMode(false))
        val (path, body) = transport.calls.single()
        assertEquals("/sony/system", path)
        val request = Json.parseToJsonElement(body).jsonObject
        assertEquals("setWolMode", request["method"]!!.jsonPrimitive.content)
        assertEquals("1.0", request["version"]!!.jsonPrimitive.content)
        assertEquals("false", request["params"]!!.jsonArray[0].jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `scene setting goes to the videoScreen service`() = runTest {
        val transport = Recorder("[]")
        val client = BraviaClient(transport) { "0000" }
        client.setSceneSetting("auto24pSync")
        val (path, body) = transport.calls.single()
        assertEquals("/sony/videoScreen", path)
        assertEquals("auto24pSync", Json.parseToJsonElement(body).jsonObject["params"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `discovery maps videoScreen and leaves undocumented getters ungated out`() {
        val found = BraviaParsers.supportedApiInfo(
            parse(
                """[[{"service":"videoScreen","apis":[{"name":"getSceneSetting","versions":[{"version":"1.0"}]}]},
                {"service":"audio","apis":[{"name":"setSoundSettings","versions":[{"version":"1.1","authLevel":"generic"}]}]}]]""",
            ),
        )
        val caps = Capabilities(found)
        assertTrue(caps.supports(BraviaService.VIDEO_SCREEN, "getSceneSetting"))
        assertTrue(caps.supports(BraviaService.AUDIO, "setSoundSettings"))
        assertFalse(caps.supports(BraviaService.AUDIO, "getSoundSettings"))
    }
}
