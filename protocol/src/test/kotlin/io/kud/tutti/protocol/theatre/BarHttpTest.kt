package io.kud.tutti.protocol.theatre

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bar's HTTP 54545 plane: what tutti sends (four reads, nothing else) and how it reads the answer. Invented values only. */
class BarHttpTest {
    @Test
    fun `the request is one http_get for the four readout keys and nothing else`() {
        val json = Json.parseToJsonElement(BarHttpCodec.request()).jsonObject
        assertEquals("http_get", json["type"]!!.jsonPrimitive.content)
        val keys = json["packet"]!!.jsonArray.flatMap { group -> group.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(
            listOf("network.connectiontype", "network.internet", "wlan.strength", "airplay.accessoryname"),
            keys,
        )
    }

    @Test
    fun `no readout key is on the never-asked list, which holds every write and identifier`() {
        for (key in BarHttpKey.entries) assertFalse(key.key in BarHttpKey.NEVER_ASKED, key.name)
        for (write in listOf("fw.update", "fw.request_update", "fw.upload", "fw.check_update", "wlan.connect", "network.applyconfig")) {
            assertTrue(write in BarHttpKey.NEVER_ASKED, write)
        }
        val body = BarHttpCodec.request()
        for (never in BarHttpKey.NEVER_ASKED) assertFalse("\"$never\"" in body, never)
    }

    @Test
    fun `answers, refusals and missing keys are told apart`() {
        val reply = """
            {"type":"http_get_result","packet":[[
              {"feature":"network.connectiontype","value":"wireless"},
              {"feature":"network.internet","value":"NAK"},
              {"feature":"wlan.strength","value":3}
            ]]}
        """.trimIndent()
        val readouts = BarHttpCodec.parse(reply)!!
        assertEquals(BarHttpValue.Answered("wireless"), readouts[BarHttpKey.NETWORK_TYPE])
        assertEquals(BarHttpValue.Refused, readouts[BarHttpKey.INTERNET])
        assertEquals("3", readouts.value(BarHttpKey.WIFI_STRENGTH))
        assertEquals(BarHttpValue.NotReported, readouts[BarHttpKey.AIRPLAY_NAME])
    }

    @Test
    fun `keys tutti did not ask are dropped, even if the bar volunteers them`() {
        val reply = """{"type":"http_get_result","packet":[[{"feature":"inet4.ipaddress","value":"192.0.2.40"}],{"feature":"wlan.strength","value":"ERR"}]}"""
        val readouts = BarHttpCodec.parse(reply)!!
        assertEquals(setOf(BarHttpKey.WIFI_STRENGTH), readouts.values.keys)
        assertEquals(BarHttpValue.Refused, readouts[BarHttpKey.WIFI_STRENGTH])
    }

    @Test
    fun `anything that is not an http_get_result is no answer`() {
        assertNull(BarHttpCodec.parse("<html>not here</html>"))
        assertNull(BarHttpCodec.parse("""{"type":"http_set_result","packet":[{"value":"ACK"}]}"""))
        assertNull(BarHttpCodec.parse("""{"type":"http_get_result"}"""))
        assertNull(BarHttpCodec.parse("[]"))
    }

    @Test
    fun `the client posts the codec's request and parses the reply`() = runTest {
        val bodies = mutableListOf<String>()
        val client = BarHttpClient(
            object : BarHttpTransport {
                override suspend fun post(body: String): String {
                    bodies += body
                    return """{"type":"http_get_result","packet":[[{"feature":"airplay.accessoryname","value":"Test Bar"}]]}"""
                }
            },
        )
        val readouts = client.readouts()!!
        assertEquals(listOf(BarHttpCodec.request()), bodies)
        assertEquals("Test Bar", readouts.value(BarHttpKey.AIRPLAY_NAME))
    }
}
