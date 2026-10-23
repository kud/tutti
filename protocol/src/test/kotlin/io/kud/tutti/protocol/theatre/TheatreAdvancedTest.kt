package io.kud.tutti.protocol.theatre

import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.TcpConnector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Probe gating, refusals and the Bluetooth pairing sequence for the bar's system settings. */
class TheatreAdvancedTest {
    private val arc = TheatreFeature.HDMI_ARC.key

    private fun TheatreState.on(vararg events: TheatreEvent): TheatreState =
        events.fold(this) { state, event -> TheatreReducer.reduce(state, event) }

    private fun get(id: Int, feature: String) =
        TheatreEvent.Sent(TheatreOutbound.GetRequest(id, feature), now = 0, timeoutMillis = 1_000)

    private fun result(id: Int, value: String?, feature: String? = null) =
        TheatreEvent.Received(TheatreInbound.Result(id, feature, value))

    private val start = TheatreState(connected = true)

    @Test
    fun `a feature is supported once the bar answers its get with a value`() {
        assertFalse(start.supports(TheatreFeature.HDMI_ARC))
        val state = start.on(get(1, arc), result(1, "earc", feature = arc))
        assertTrue(state.supports(TheatreFeature.HDMI_ARC))
        assertEquals("earc", state.value(TheatreFeature.HDMI_ARC))
    }

    @Test
    fun `a notify proves support too`() {
        val state = start.on(TheatreEvent.Received(TheatreInbound.Notify(TheatreFeature.NET_BT_STANDBY.key, "on")))
        assertTrue(state.supports(TheatreFeature.NET_BT_STANDBY))
    }

    @Test
    fun `ERR or NAK to a get marks the feature refused and never stores it as a value`() {
        for (refusal in listOf("ERR", "NAK", "nak")) {
            val state = start.on(get(1, arc), result(1, refusal, feature = arc))
            assertFalse(state.supports(TheatreFeature.HDMI_ARC), refusal)
            assertTrue(arc in state.refused, refusal)
            assertTrue(state.outstanding.isEmpty(), refusal)
        }
    }

    @Test
    fun `a late refusal with an unknown id changes nothing`() {
        val state = start.on(result(42, "ERR", feature = arc))
        assertEquals(start, state)
    }

    @Test
    fun `a later real value clears the refusal`() {
        val state = start.on(get(1, arc), result(1, "ERR"), get(2, arc), result(2, "arc", feature = arc))
        assertTrue(state.supports(TheatreFeature.HDMI_ARC))
        assertFalse(arc in state.refused)
    }

    @Test
    fun `a get answered with no value stays unsupported`() {
        val state = start.on(get(1, arc), result(1, null))
        assertFalse(state.supports(TheatreFeature.HDMI_ARC))
        assertTrue(state.refused.isEmpty())
    }

    @Test
    fun `pairing switches to bluetooth first, then mode Off and RX`() {
        val steps = BluetoothPairing.steps(currentInput = "tv")
        assertEquals(
            listOf(
                TheatreFeature.INPUT to "bluetooth",
                TheatreFeature.BLUETOOTH_MODE to "Off",
                TheatreFeature.BLUETOOTH_MODE to "RX",
            ),
            steps.map { it.feature to it.value },
        )
        assertEquals(500L, steps[1].pauseAfterMillis)
    }

    @Test
    fun `pairing skips the input switch when already on bluetooth`() {
        val steps = BluetoothPairing.steps(currentInput = "bluetooth")
        assertEquals(listOf("Off", "RX"), steps.map { it.value })
    }

    @Test
    fun `tutti never asks for the serial, the addresses or external control`() {
        val asked = TheatreFeature.entries.map { it.key }.toSet()
        assertTrue((asked intersect TheatreFeature.NEVER_ASKED).isEmpty())
    }

    @Test
    fun `the model comes from system modeltype, the key this port answers`() {
        assertEquals("system.modeltype", TheatreFeature.MODEL.key)
    }

    @Test
    fun `refresh asks nothing on the never-asked list and pairing writes the observed sequence`() = runTest {
        val connection = FakeConnection()
        val client = TheatreClient(FakeConnector(connection), backgroundScope, clock = { testScheduler.currentTime })
        client.connect("192.0.2.10")
        runCurrent()
        val refreshed = connection.sentFeatures()
        assertTrue(refreshed.isNotEmpty())
        assertTrue((refreshed.toSet() intersect TheatreFeature.NEVER_ASKED).isEmpty())

        connection.sent.clear()
        val pairing = launch { client.startBluetoothPairing() }
        runCurrent()
        assertEquals(listOf("main.input", "bluetooth.mode"), connection.sentFeatures())
        advanceUntilIdle()
        pairing.join()
        val lines = connection.sent.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("bluetooth", "Off", "RX"), lines.map { it["value"]!!.jsonPrimitive.content })
        assertTrue(lines.all { it["type"]!!.jsonPrimitive.content == "set" })
        assertEquals("RX", client.state.value.value(TheatreFeature.BLUETOOTH_MODE))
        client.disconnect()
    }

    @Test
    fun `a probe answered ERR is not support`() = runTest {
        val connection = FakeConnection()
        val client = TheatreClient(FakeConnector(connection), backgroundScope, clock = { testScheduler.currentTime })
        client.connect("192.0.2.10")
        runCurrent()
        connection.sent.clear()
        val answer = launch {
            connection.awaitSent()
            val id = Json.parseToJsonElement(connection.sent.last()).jsonObject["id"]!!.jsonPrimitive.content
            connection.push("""{"id":$id,"type":"result","feature":"audio.voicezoom3","value":"ERR"}""")
        }
        assertFalse(client.probe(TheatreFeature.VOICE_ZOOM))
        answer.join()
        client.disconnect()
    }

    private class FakeConnector(private val connection: FakeConnection) : TcpConnector {
        override suspend fun connect(host: String, port: Int): Connection = connection
    }

    private class FakeConnection : Connection {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        private val sentSignal = Channel<Unit>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

        override suspend fun send(bytes: ByteArray) {
            sent += bytes.decodeToString().trim()
            sentSignal.trySend(Unit)
        }

        override fun close() {
            inbound.close()
        }

        suspend fun awaitSent() {
            sentSignal.receive()
        }

        fun push(line: String) {
            inbound.trySend((line + "\n").encodeToByteArray())
        }

        fun sentFeatures(): List<String> = sent.map { Json.parseToJsonElement(it).jsonObject["feature"]!!.jsonPrimitive.content }
    }
}
