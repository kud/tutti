package io.kud.tutti.protocol.theatre

import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.TcpConnector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Framing, decoder variants, paced refresh and the wire log: what the Bar 9 might do differently. */
class TheatreWireTest {
    // region framing

    @Test
    fun `objects with no delimiter at all are split`() {
        val out = JsonStreamSplitter().feed("""{"id":1,"type":"result","value":"ACK"}{"type":"notify","feature":"main.mute","value":"on"}""".encodeToByteArray())
        assertEquals(2, out.size)
        assertIs<TheatreInbound.Notify>(TheatreCodec.decode(out[1]))
    }

    @Test
    fun `newline, CRLF and NUL separators all work`() {
        val text = "{\"a\":1}\n{\"b\":2}\r\n{\"c\":3}\u0000{\"d\":4}"
        assertEquals(listOf("""{"a":1}""", """{"b":2}""", """{"c":3}""", """{"d":4}"""), JsonStreamSplitter().feed(text.encodeToByteArray()))
    }

    @Test
    fun `an object cut across reads, braces in strings and a split multibyte character`() {
        val whole = """{"feature":"x","value":"a}{\"b é"}""".encodeToByteArray()
        val splitter = JsonStreamSplitter()
        val cut = whole.size - 3 // inside the two bytes of é, or just after
        assertTrue(splitter.feed(whole.copyOfRange(0, cut)).isEmpty())
        val out = splitter.feed(whole.copyOfRange(cut, whole.size))
        assertEquals(listOf(whole.decodeToString()), out)
        assertEquals("a}{\"b é", (TheatreCodec.decode(out[0]) as TheatreInbound.Notify).value)
    }

    @Test
    fun `an oversized object is dropped and the next one still arrives`() {
        val splitter = JsonStreamSplitter(maxObjectBytes = 16)
        val out = splitter.feed("""{"value":"${"x".repeat(40)}"}{"ok":1}""".encodeToByteArray())
        assertEquals(listOf("""{"ok":1}"""), out)
    }

    // endregion

    // region decoder variants

    @Test
    fun `a string id is matched like a number`() {
        assertEquals(TheatreInbound.Result(7, "main.power", "on"), TheatreCodec.decode("""{"id":"7","type":"result","feature":"main.power","value":"on"}"""))
    }

    @Test
    fun `numeric and boolean values keep their text`() {
        assertEquals("21", (TheatreCodec.decode("""{"id":2,"type":"result","feature":"main.volumestep","value":21}""") as TheatreInbound.Result).value)
        assertEquals("true", (TheatreCodec.decode("""{"type":"notify","feature":"audio.nightmode","value":true}""") as TheatreInbound.Notify).value)
    }

    @Test
    fun `type in another case is accepted`() {
        assertIs<TheatreInbound.Result>(TheatreCodec.decode("""{"id":1,"type":"Result","value":"ACK"}"""))
        assertIs<TheatreInbound.Notify>(TheatreCodec.decode("""{"type":"NOTIFY","feature":"main.mute","value":"on"}"""))
    }

    @Test
    fun `no type is a result with an id, a notify with a feature and no id`() {
        assertEquals(TheatreInbound.Result(4, null, "off"), TheatreCodec.decode("""{"id":4,"value":"off"}"""))
        assertEquals(TheatreInbound.Notify("main.mute", "on"), TheatreCodec.decode("""{"feature":"main.mute","value":"on"}"""))
        assertIs<TheatreInbound.Malformed>(TheatreCodec.decode("""{"feature":"main.mute"}"""))
    }

    @Test
    fun `an unknown type is still not trusted`() {
        assertIs<TheatreInbound.Malformed>(TheatreCodec.decode("""{"id":1,"type":"hello","value":"on"}"""))
    }

    @Test
    fun `a result without feature fills the feature its id asked for`() {
        val sent = TheatreReducer.reduce(TheatreState(connected = true), TheatreEvent.Sent(TheatreOutbound.GetRequest(9, "audio.nightmode"), 0, 3_000))
        val answered = TheatreReducer.reduce(sent, TheatreEvent.Received(TheatreCodec.decode("""{"id":"9","type":"result","value":"off"}""")))
        assertEquals("off", answered.confirmed["audio.nightmode"])
    }

    @Test
    fun `a get that times out is silent until the bar speaks of it`() {
        var state = TheatreReducer.reduce(TheatreState(connected = true), TheatreEvent.Sent(TheatreOutbound.GetRequest(3, "audio.aav"), 0, 3_000))
        state = TheatreReducer.reduce(state, TheatreEvent.Tick(3_000))
        assertEquals(setOf("audio.aav"), state.silent)
        assertTrue(TheatreFeature.AUTO_VOLUME in state.missing())
        state = TheatreReducer.reduce(state, TheatreEvent.Received(TheatreInbound.Notify("audio.aav", "on")))
        assertTrue(state.silent.isEmpty())
        assertFalse(TheatreFeature.AUTO_VOLUME in state.missing())
    }

    // endregion

    // region client

    @Test
    fun `answers with no newline reach the state`() = runTest {
        val connection = FakeConnection()
        val client = TheatreClient(FakeConnector(connection), backgroundScope, clock = { testScheduler.currentTime })
        connection.answer = { id, feature -> """{"feature":"$feature","id":$id,"type":"result","value":"on"}""" }
        connection.delimiter = ""
        client.connect("192.0.2.10")
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("on", client.state.value.value(TheatreFeature.NIGHT))
        assertEquals(TheatreFeature.everyday.size, connection.sent.size)
        client.disconnect()
    }

    @Test
    fun `refresh never has more than three gets waiting and re-asks the silent ones once`() = runTest {
        val connection = FakeConnection()
        val silent = setOf(TheatreFeature.IMAX.key, TheatreFeature.DRC.key)
        connection.answer = { id, feature -> if (feature in silent) null else """{"id":$id,"type":"result","feature":"$feature","value":"on"}""" }
        connection.answerAfterMillis = 200
        val client = TheatreClient(FakeConnector(connection), backgroundScope, clock = { testScheduler.currentTime })
        client.connect("192.0.2.10")
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(connection.maxWaiting <= 3, "at most three in flight, saw ${connection.maxWaiting}")
        val asked = connection.sentFeatures()
        assertEquals(TheatreFeature.everyday.size + silent.size, asked.size)
        silent.forEach { key -> assertEquals(2, asked.count { it == key }) }
        assertTrue((asked.toSet() intersect TheatreFeature.NEVER_ASKED).isEmpty())
        assertEquals(silent, client.state.value.silent)
        assertEquals(TheatreFeature.everyday.size - silent.size, client.state.value.confirmed.size)
        client.disconnect()
    }

    @Test
    fun `refresh missing asks only what was neither answered nor refused`() = runTest {
        val connection = FakeConnection()
        val client = TheatreClient(FakeConnector(connection), backgroundScope, clock = { testScheduler.currentTime })
        client.connect("192.0.2.10")
        advanceTimeBy(600_000)
        runCurrent()
        connection.sent.clear()
        connection.answer = { id, feature ->
            if (feature == TheatreFeature.IMAX.key) """{"id":$id,"type":"result","feature":"$feature","value":"ERR"}""" else """{"id":$id,"type":"result","feature":"$feature","value":"off"}"""
        }
        val job = launch { client.refreshMissing() }
        advanceTimeBy(600_000)
        job.join()
        connection.sent.clear()
        launch { client.refreshMissing() }.join()
        assertTrue(connection.sent.isEmpty(), "everything answered or refused: nothing left to ask")
        assertTrue(TheatreFeature.IMAX.key in client.state.value.refused)
        client.disconnect()
    }

    @Test
    fun `the wire log keeps raw reads and requests, scrubbed`() = runTest {
        val connection = FakeConnection()
        val client = TheatreClient(FakeConnector(connection), backgroundScope, clock = { testScheduler.currentTime })
        client.connect("192.0.2.10")
        runCurrent()
        connection.pushRaw("""{"type":"notify","feature":"network.ipaddress","value":"192.168.1.30"}{"type":"notify","feature":"system.serialnumber","value":"S01234567"}""" + "\r\n")
        runCurrent()
        val lines = client.wire.lines()
        assertTrue(lines.any { it.contains(" > ") && it.contains("\"type\":\"get\"") && it.endsWith("\\n") })
        val read = lines.last { it.contains(" < ") }
        assertTrue(read.endsWith("\\r\\n"), read)
        assertFalse(read.contains("192.168"), read)
        assertFalse(read.contains("S01234567"), read)
        client.disconnect()
    }

    @Test
    fun `the wire log keeps only the last entries`() {
        val log = TheatreWireLog(capacity = 3, clock = { 0 })
        repeat(5) { log.sent("line $it") }
        assertEquals(listOf("+0ms > line 2", "+0ms > line 3", "+0ms > line 4"), log.lines())
    }

    @Test
    fun `detail readouts are asked only when technical details opens, never on connect or in the re-ask`() = runTest {
        val connection = FakeConnection()
        connection.answer = { id, feature ->
            when (feature) {
                TheatreFeature.TV_AV_SYNC.key -> """{"id":$id,"type":"result","feature":"$feature","value":"ERR"}"""
                TheatreFeature.NETWORK_DHCP.key -> null
                else -> """{"id":$id,"type":"result","feature":"$feature","value":"on"}"""
            }
        }
        val client = TheatreClient(FakeConnector(connection), backgroundScope, clock = { testScheduler.currentTime })
        client.connect("192.0.2.10")
        advanceTimeBy(600_000)
        runCurrent()
        val details = TheatreFeature.details.map { it.key }.toSet()
        assertTrue((connection.sentFeatures().toSet() intersect details).isEmpty(), "connect asked a detail")

        connection.sent.clear()
        client.refreshMissing()
        assertTrue((connection.sentFeatures().toSet() intersect details).isEmpty(), "the re-ask asked a detail")

        connection.sent.clear()
        client.refreshDetails()
        assertEquals(details, connection.sentFeatures().toSet())
        assertTrue(connection.sent.all { "\"type\":\"get\"" in it })
        assertEquals("on", client.state.value.value(TheatreFeature.TEMPERATURE))
        assertTrue(TheatreFeature.TV_AV_SYNC.key in client.state.value.refused)

        // A refused readout is not asked again; a silent one is.
        connection.sent.clear()
        client.refreshDetails()
        val again = connection.sentFeatures()
        assertFalse(TheatreFeature.TV_AV_SYNC.key in again)
        assertTrue(TheatreFeature.NETWORK_DHCP.key in again)
        client.disconnect()
    }

    @Test
    fun `detail readouts are read-only and never set`() = runTest {
        for (feature in TheatreFeature.details) {
            assertEquals(FeatureKind.ReadOnly, feature.kind, feature.name)
            assertFalse(feature.notifies, feature.name)
            assertFalse(feature.key in TheatreFeature.NEVER_ASKED, feature.name)
        }
        val connection = FakeConnection()
        val client = TheatreClient(FakeConnector(connection), backgroundScope, clock = { testScheduler.currentTime })
        client.connect("192.0.2.10")
        advanceTimeBy(600_000)
        runCurrent()
        connection.sent.clear()
        for (feature in TheatreFeature.details) client.set(feature, "off")
        runCurrent()
        assertTrue(connection.sent.isEmpty(), connection.sent.toString())
        client.disconnect()
    }

    @Test
    fun `the wire log blanks the bar's own name`() {
        val line = TheatreWireLog.scrub("""{"id":7,"type":"result","feature":"system.devicename","value":"Salon de Camille"}""")
        assertFalse("Camille" in line, line)
        assertTrue("system.devicename" in line, line)
    }

    // endregion

    private class FakeConnector(private val connection: FakeConnection) : TcpConnector {
        override suspend fun connect(host: String, port: Int): Connection = connection
    }

    /** A bar that answers each get through [answer] (null: silence), after [answerAfterMillis]. */
    private inner class FakeConnection : Connection {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var answer: (Int, String) -> String? = { _, _ -> null }
        var delimiter = "\n"
        var answerAfterMillis = 0L
        var waiting = 0
        var maxWaiting = 0

        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

        override suspend fun send(bytes: ByteArray) {
            val line = bytes.decodeToString().trim()
            sent += line
            val obj = Json.parseToJsonElement(line).jsonObject
            if (obj["type"]!!.jsonPrimitive.content != "get") return
            val reply = answer(obj["id"]!!.jsonPrimitive.content.toInt(), obj["feature"]!!.jsonPrimitive.content) ?: run {
                waiting++
                maxWaiting = maxOf(maxWaiting, waiting)
                kotlinx.coroutines.delay(1_500)
                waiting--
                return
            }
            waiting++
            maxWaiting = maxOf(maxWaiting, waiting)
            if (answerAfterMillis > 0) kotlinx.coroutines.delay(answerAfterMillis)
            waiting--
            inbound.trySend((reply + delimiter).encodeToByteArray())
        }

        override fun close() {
            inbound.close()
        }

        fun pushRaw(text: String) {
            inbound.trySend(text.encodeToByteArray())
        }

        fun sentFeatures(): List<String> = sent.map { Json.parseToJsonElement(it).jsonObject["feature"]!!.jsonPrimitive.content }
    }
}
