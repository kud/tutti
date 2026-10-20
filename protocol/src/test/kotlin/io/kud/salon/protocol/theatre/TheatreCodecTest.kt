package io.kud.salon.protocol.theatre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TheatreCodecTest {
    @Test
    fun `encodes a get without a value`() {
        val line = TheatreCodec.encode(TheatreOutbound.GetRequest(3, "main.power"))
        assertEquals("{\"id\":3,\"type\":\"get\",\"feature\":\"main.power\"}\n", line)
    }

    @Test
    fun `encodes levels as numbers and toggles as strings`() {
        assertEquals(
            "{\"id\":2,\"type\":\"set\",\"feature\":\"main.volumestep\",\"value\":50}\n",
            TheatreCodec.encode(TheatreOutbound.SetRequest(2, "main.volumestep", "50", numeric = true)),
        )
        assertEquals(
            "{\"id\":1,\"type\":\"set\",\"feature\":\"audio.voiceenhancer\",\"value\":\"upon\"}\n",
            TheatreCodec.encode(TheatreOutbound.SetRequest(1, "audio.voiceenhancer", "upon", numeric = false)),
        )
    }

    @Test
    fun `decodes a get result, an ack and a notify`() {
        assertEquals(
            TheatreInbound.Result(3, "main.power", "off"),
            TheatreCodec.decode("{\"feature\": \"main.power\", \"id\": 3, \"type\": \"result\", \"value\": \"off\"}"),
        )
        val ack = TheatreCodec.decode("{\"id\": 3, \"type\": \"result\", \"value\": \"ACK\"}")
        assertIs<TheatreInbound.Result>(ack)
        assertTrue(ack.isAck)
        assertEquals(
            TheatreInbound.Notify("main.volumestep", "21"),
            TheatreCodec.decode("{\"feature\": \"main.volumestep\", \"type\": \"notify\", \"value\": \"21\"}"),
        )
    }

    @Test
    fun `numeric values decode to their string form`() {
        assertEquals(
            TheatreInbound.Notify("main.rearvolumestep", "-5"),
            TheatreCodec.decode("{\"feature\":\"main.rearvolumestep\",\"type\":\"notify\",\"value\":-5}"),
        )
    }

    @Test
    fun `malformed lines never throw`() {
        val lines = listOf(
            "",
            "   ",
            "not json",
            "{\"id\":1,",
            "[1,2,3]",
            "\"string\"",
            "{\"id\":1,\"value\":\"ACK\"}",
            "{\"type\":\"notify\",\"value\":\"on\"}",
            "{\"type\":\"notify\",\"feature\":\"main.power\",\"value\":null}",
            "{\"type\":\"bogus\",\"feature\":\"main.power\"}",
            "{\"type\":{\"nested\":true}}",
        )
        lines.forEach { assertIs<TheatreInbound.Malformed>(TheatreCodec.decode(it), it) }
    }

    @Test
    fun `a result with a non-numeric id keeps a null id`() {
        assertEquals(
            TheatreInbound.Result(null, "main.power", "on"),
            TheatreCodec.decode("{\"id\":\"x\",\"type\":\"result\",\"feature\":\"main.power\",\"value\":\"on\"}"),
        )
    }
}
