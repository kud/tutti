package io.kud.tutti.protocol.diagnostic

import io.kud.tutti.protocol.atv.ClientCertificates
import io.kud.tutti.protocol.atv.FrameDecoder
import io.kud.tutti.protocol.atv.Framing
import io.kud.tutti.protocol.atv.proto.remote.RemoteConfigure
import io.kud.tutti.protocol.atv.proto.remote.RemoteDeviceInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.bravia.BraviaTransport
import io.kud.tutti.protocol.bravia.HttpReply
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import io.kud.tutti.protocol.theatre.BarHttpUnavailable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ConnectException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TV = "192.168.1.20"
private const val BAR = "192.168.1.30"
private const val PSK = "Sup3rS3cretPsk"
private const val SERIAL = "4012345"
private const val MAC = "AA:BB:CC:DD:EE:FF"
private const val CID = "ABCDEF0123456789ABCDEF00"

private class FakeRest(private val answer: (String) -> HttpReply) : BraviaTransport {
    val methods = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()

    override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply {
        val method = Json.parseToJsonElement(body).jsonObject["method"]!!.jsonPrimitive.content
        methods += method
        this.headers += headers
        return answer(method)
    }

    override val scheme: String get() = "https"
}

private fun ok(result: String) = HttpReply(200, "{\"result\":$result,\"id\":1}")

/** A Sony-looking TV. getSystemInformation carries the identifying values that must never reach the report. */
private fun livingRoomTv(method: String): HttpReply = when (method) {
    "getInterfaceInformation" -> ok("""[{"productCategory":"tv","productName":"BRAVIA","modelName":"K-65XR80","serverName":"","interfaceVersion":"6.1.0"}]""")
    "getSystemInformation" -> ok(
        """[{"product":"TV","model":"K-65XR80","serial":"$SERIAL","macAddr":"$MAC","name":"Salon de Camille","generation":"6.1.0","cid":"$CID"}]""",
    )
    "getMethodTypes" -> ok("""[["getPowerStatus","[]","[{\"status\":\"string\"}]","1.0"],["getVolumeInformation","[]","[]","1.0"],["getPlayingContentInfo","[]","[]","1.0"]]""")
    "getPowerStatus" -> ok("""[{"status":"active"}]""")
    "getPlayingContentInfo" -> ok(
        """[{"uri":"tv:dvbt?srv=$TV","source":"tv:dvbt","title":"Channel Nine","programTitle":"The Garden Hour","dispNum":"009"}]""",
    )
    "getVolumeInformation" -> HttpReply(200, """{"error":[40005,"display off for $PSK at $TV"],"id":1}""")
    else -> HttpReply(200, """{"error":[12,"No Such Method"],"id":1}""")
}

/** In-memory socket: [answer] maps each written line to the reply lines. */
private class FakeLineConnection(private val answer: (String) -> List<String>) : Connection {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    val written = mutableListOf<String>()
    override val incoming: Flow<ByteArray> = inbox.consumeAsFlow()

    override suspend fun send(bytes: ByteArray) {
        val line = bytes.decodeToString().trim()
        written += line
        answer(line).forEach { inbox.send((it + "\n").encodeToByteArray()) }
    }

    override fun close() {
        inbox.close()
    }
}

private class FakeTcp(private val bar: FakeLineConnection?) : TcpConnector {
    val ports = mutableListOf<Int>()

    override suspend fun connect(host: String, port: Int): Connection {
        ports += port
        if (port == 33336) return bar ?: throw ConnectException("Connection refused to $host")
        return FakeLineConnection { emptyList() }
    }
}

private fun barAnswer(line: String): List<String> {
    val json = Json.parseToJsonElement(line).jsonObject
    val id = json["id"]!!.jsonPrimitive.int
    val feature = json["feature"]!!.jsonPrimitive.content
    val value = when (feature) {
        "main.volumestep" -> "12"
        "system.modeltype" -> "HT-A9000"
        "system.version" -> "1.2.3"
        "system.devicename" -> "Salon de Camille"
        "system.temperature" -> "41"
        "audio.voicezoom3" -> return emptyList()
        "audio.voicezoom3step" -> return emptyList()
        else -> "off"
    }
    return listOf("""{"id":$id,"type":"result","feature":"$feature","value":"$value"}""")
}

/** The bar's HTTP 54545, answering every key it is asked; the AirPlay name must never reach the report. */
private class FakeBarHttp : io.kud.tutti.protocol.theatre.BarHttpTransport {
    val bodies = mutableListOf<String>()

    override suspend fun post(body: String): String {
        bodies += body
        return """{"type":"http_get_result","packet":[[""" +
            """{"feature":"network.connectiontype","value":"wireless"},{"feature":"network.internet","value":"connected"},""" +
            """{"feature":"wlan.strength","value":"3"},{"feature":"airplay.accessoryname","value":"Salon de Camille"}]]}"""
    }
}

/** A TV remote port that opens the v2 handshake and records every message the app sends. */
private class FakeRemote(private val server: ClientIdentity) : TlsConnector {
    val sent = mutableListOf<RemoteMessage>()

    override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?): TlsConnection {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val decoder = FrameDecoder()
        val configure = RemoteMessage(
            remote_configure = RemoteConfigure(code1 = 622, device_info = RemoteDeviceInfo(model = "BRAVIA 8", vendor = "Sony", app_version = "5.4.1")),
        )
        inbox.send(Framing.frame(configure.encode()))
        return object : TlsConnection {
            override val localCertificate: X509Certificate = identity.certificate
            override val peerCertificate: X509Certificate = server.certificate
            override val incoming: Flow<ByteArray> = inbox.consumeAsFlow()

            override suspend fun send(bytes: ByteArray) {
                decoder.feed(bytes).forEach { sent += RemoteMessage.ADAPTER.decode(it) }
            }

            override fun close() {
                inbox.close()
            }
        }
    }
}

private object RefusingTls : TlsConnector {
    override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?): TlsConnection =
        throw SSLHandshakeException("handshake with $host failed")
}

class DiagnosticRunnerTest {
    private val client = ClientCertificates.generate("client")
    private val server = ClientCertificates.generate("tv")
    private val pin = CertificatePin.of(server.certificate)

    private fun targets(tv: String? = TV, bar: String? = BAR, psk: String? = PSK, pin: CertificatePin? = this.pin) =
        DiagnosticTargets(tvHost = tv, barHost = bar, psk = psk, remotePin = pin, restPinRecorded = true)

    private fun List<StepResult>.of(id: String) = first { it.id == id }

    @Test
    fun `the extra reads go only where the TV lists them, and settings schema under its own service`() = runTest {
        val rest = FakeRest { method ->
            when (method) {
                "getMethodTypes" -> ok("""[["getCurrentTime","[]","[\"string\"]","1.0"],["getSettingsSchema","[]","[]","1.0"]]""")
                "getCurrentTime" -> ok("""["2026-10-26T21:40:00+0100"]""")
                "getSettingsSchema" -> ok("""[{"target":"x","type":"string"}]""")
                else -> livingRoomTv(method)
            }
        }
        val runner = DiagnosticRunner(targets(bar = null), rest, RefusingTls, FakeTcp(null), { client }, null, "unavailable")
        runner.run()
        val steps = runner.steps.value
        assertEquals(StepStatus.OK, steps.of("rest.time").status)
        assertEquals(StepStatus.OK, steps.of("rest.settings_schema").status)
        assertTrue(steps.of("rest.settings_schema").facts.any { it.first == "call" && it.second.startsWith("system.getSettingsSchema") })
        // Listed nowhere (every service answered getMethodTypes with the same list here, so videoScreen lists no scene getter).
        assertEquals(StepStatus.SKIPPED, steps.of("rest.scene").status)
        assertEquals("not_listed", steps.of("rest.scene").errorClass)
        assertFalse("getSceneSetting" in rest.methods)
        // Nothing but getters went out.
        assertTrue(rest.methods.all { it.startsWith("get") }, rest.methods.toString())
    }

    @Test
    fun `nothing configured skips every step without touching the network`() = runTest {
        val tcp = FakeTcp(null)
        val runner = DiagnosticRunner(targets(tv = null, bar = null, psk = null, pin = null), null, RefusingTls, tcp, { null }, null, "unavailable")
        runner.run()
        assertTrue(runner.steps.value.all { it.status == StepStatus.SKIPPED }, runner.steps.value.toString())
        assertTrue(tcp.ports.isEmpty())
    }

    @Test
    fun `an unreachable TV fails once and skips the rest of REST`() = runTest {
        val rest = object : BraviaTransport {
            var calls = 0
            override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply {
                calls++
                throw ConnectException("failed to connect to /$TV (port 443)")
            }
        }
        val runner = DiagnosticRunner(targets(bar = null), rest, RefusingTls, FakeTcp(null), { client }, null, "unavailable")
        runner.run()
        val steps = runner.steps.value
        assertEquals(StepStatus.FAILED, steps.of(DiagnosticSteps.REST_INTERFACE).status)
        assertEquals("connection_refused", steps.of(DiagnosticSteps.REST_INTERFACE).errorClass)
        assertEquals(1, rest.calls)
        assertTrue(steps.filter { it.plane == Plane.REST && it.id != DiagnosticSteps.REST_INTERFACE }.all { it.status == StepStatus.SKIPPED })
        assertEquals("tls_handshake", steps.of(DiagnosticSteps.REMOTE_TLS).errorClass)
        assertEquals(StepStatus.SKIPPED, steps.of(DiagnosticSteps.REMOTE_SESSION).status)
    }

    @Test
    fun `optional bar HTTP rejection is unavailable, not a broken soundbar`() = runTest {
        val unavailable = object : io.kud.tutti.protocol.theatre.BarHttpTransport {
            override suspend fun post(body: String): String = throw BarHttpUnavailable(500)
        }
        val runner = DiagnosticRunner(
            targets(tv = null, bar = BAR, psk = null, pin = null),
            null,
            RefusingTls,
            FakeTcp(null),
            { null },
            null,
            "unavailable",
            barHttp = unavailable,
        )

        runner.run()

        val step = runner.steps.value.of(DiagnosticSteps.BAR_HTTP)
        assertEquals(StepStatus.SKIPPED, step.status)
        assertEquals("unsupported", step.errorClass)
        assertTrue(step.message.orEmpty().contains("HTTP 500"))
    }

    @Test
    fun `a listener on 54545 that is not the management plane is unavailable`() = runTest {
        val other = object : io.kud.tutti.protocol.theatre.BarHttpTransport {
            override suspend fun post(body: String): String = """{"type":"something_else"}"""
        }
        val runner = DiagnosticRunner(
            targets(tv = null, bar = BAR, psk = null, pin = null),
            null,
            RefusingTls,
            FakeTcp(null),
            { null },
            null,
            "unavailable",
            barHttp = other,
        )

        runner.run()

        val step = runner.steps.value.of(DiagnosticSteps.BAR_HTTP)
        assertEquals(StepStatus.SKIPPED, step.status)
        assertEquals("unsupported", step.errorClass)
    }

    @Test
    fun `a full run probes every plane, read-only`() = runTest {
        val rest = FakeRest(::livingRoomTv)
        val bar = FakeLineConnection(::barAnswer)
        val remote = FakeRemote(server)
        val http = FakeBarHttp()
        val runner = DiagnosticRunner(
            targets(), rest, remote, FakeTcp(bar), { client }, { DiscoveryOutcome(found = 1, configuredTvSeen = true) }, "interface only",
            barHttp = http,
        )
        runner.run()
        val steps = runner.steps.value

        assertEquals(StepStatus.OK, steps.of(DiagnosticSteps.DISCOVERY).status)
        assertEquals(StepStatus.OK, steps.of(DiagnosticSteps.REST_INTERFACE).status)
        assertEquals(StepStatus.OK, steps.of(DiagnosticSteps.REST_AUTH).status)
        assertEquals(StepStatus.OK, steps.of(DiagnosticSteps.REST_METHODS).status)
        assertEquals(StepStatus.OK, steps.of("rest.power").status)
        assertEquals("rpc_40005_standby", steps.of("rest.volume").errorClass)
        // Methods the TV did not list are never called.
        assertEquals(StepStatus.SKIPPED, steps.of("rest.apps").status)
        assertFalse("getApplicationList" in rest.methods)
        assertTrue(rest.methods.none { it.startsWith("set") })
        // getInterfaceInformation goes without the PSK; the authenticated calls carry it.
        assertFalse(rest.headers.first().containsKey("X-Auth-PSK"))

        assertEquals(StepStatus.OK, steps.of(DiagnosticSteps.REMOTE_SESSION).status, steps.of(DiagnosticSteps.REMOTE_SESSION).toString())
        assertTrue(steps.of(DiagnosticSteps.REMOTE_SESSION).facts.contains("tv model" to "BRAVIA 8"))
        // The remote probe only answers the handshake: never a key, an app link or text.
        assertTrue(remote.sent.isNotEmpty())
        assertTrue(remote.sent.all { it.remote_configure != null || it.remote_set_active != null || it.remote_ping_response != null })

        assertEquals(StepStatus.OK, steps.of(DiagnosticSteps.BAR_FEATURES).status)
        assertTrue(steps.of(DiagnosticSteps.BAR_FEATURES).facts.contains("system.version" to "answered 1.2.3"))
        assertEquals("no_answer", steps.of(DiagnosticSteps.BAR_VOICE_ZOOM).errorClass)
        // The bar only ever receives gets.
        assertTrue(bar.written.all { "\"type\":\"get\"" in it }, bar.written.toString())
        assertEquals(StepStatus.SKIPPED, steps.of(DiagnosticSteps.CLOUD).status)

        // The detail readouts are in the pass; the bar's own name only as answered.
        assertTrue(steps.of(DiagnosticSteps.BAR_FEATURES).facts.contains("system.temperature" to "answered 41"))
        assertTrue(steps.of(DiagnosticSteps.BAR_FEATURES).facts.contains("system.devicename" to "answered"))
        // HTTP 54545: one read, only the four readout keys.
        val httpStep = steps.of(DiagnosticSteps.BAR_HTTP)
        assertEquals(StepStatus.OK, httpStep.status, httpStep.toString())
        assertTrue(httpStep.facts.contains("wlan.strength" to "answered 3"))
        assertTrue(httpStep.facts.contains("airplay.accessoryname" to "answered"))
        assertEquals(1, http.bodies.size)
        assertTrue("http_get" in http.bodies.single())
        // The TV's supported functions are asked only when listed: this TV does not list them.
        assertEquals(StepStatus.SKIPPED, steps.of("rest.supported_functions").status)
    }

    @Test
    fun `the report never carries a secret, an address or an identifier`() = runTest {
        val runner = DiagnosticRunner(
            targets(), FakeRest(::livingRoomTv), FakeRemote(server), FakeTcp(FakeLineConnection(::barAnswer)), { client },
            { DiscoveryOutcome(1, true) }, "interface only",
            barHttp = FakeBarHttp(),
        )
        runner.run()
        val environment = listOf(
            "app" to "0.1.0-dev.1",
            "android" to "15 (API 35)",
            "wifi" to "fe80::1c2d:3e4f:5a6b:7c8d via $BAR",
            "live links" to "remote ONLINE, rest ONLINE, bar ONLINE",
        )
        val report = DiagnosticReport.render(environment, runner.steps.value, runner.sensitive)

        val leaks = listOf(PSK, TV, BAR, SERIAL, MAC, CID, "Camille", "Salon", "fe80", pin.sha256Hex, "192.168.")
        for (leak in leaks) assertFalse(leak in report, "report leaks $leak:\n$report")
        // Still useful: models, versions, method list, error classes.
        for (kept in listOf("K-65XR80", "getPowerStatus@1.0", "rpc_40005_standby", "HT-A9000", "BRAVIA 8", "summary:")) {
            assertTrue(kept in report, "report lost $kept:\n$report")
        }
    }

    @Test
    fun `developer mode keeps the playing content reply, scrubbed`() = runTest {
        suspend fun playing(developer: Boolean): StepResult {
            val runner = DiagnosticRunner(
                targets(), FakeRest(::livingRoomTv), FakeRemote(server), FakeTcp(FakeLineConnection(::barAnswer)), { client },
                null, "interface only", developer = developer,
            )
            runner.run()
            return runner.steps.value.of("rest.playing")
        }
        val raw = playing(developer = true).facts.firstOrNull { it.first == "raw" }?.second
        assertTrue(raw != null && "The Garden Hour" in raw && "009" in raw, playing(developer = true).toString())
        assertFalse(TV in raw, raw)
        assertFalse(PSK in raw, raw)
        assertTrue(playing(developer = false).facts.none { it.first == "raw" })
    }

    @Test
    fun `json shapes hide identifying values and collect them`() {
        val sensitive = mutableSetOf<String>()
        val json = Json.parseToJsonElement("""[{"model":"K-65XR80","serial":"$SERIAL","macAddr":"$MAC","inputs":[{"uri":"extInput:hdmi?port=1"},{"uri":"x"}]}]""")
        val shape = JsonShape.of(json, sensitive)
        assertEquals("""[{"model":"K-65XR80","serial":<redacted>,"macAddr":<redacted>,"inputs":[2x {"uri":str(20)}]}]""", shape)
        assertEquals(setOf(SERIAL, MAC), sensitive)
    }
}
