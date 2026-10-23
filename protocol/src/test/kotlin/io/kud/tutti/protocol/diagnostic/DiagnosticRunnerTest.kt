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
    "getMethodTypes" -> ok("""[["getPowerStatus","[]","[{\"status\":\"string\"}]","1.0"],["getVolumeInformation","[]","[]","1.0"]]""")
    "getPowerStatus" -> ok("""[{"status":"active"}]""")
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
        "audio.voicezoom3" -> return emptyList()
        "audio.voicezoom3step" -> return emptyList()
        else -> "off"
    }
    return listOf("""{"id":$id,"type":"result","feature":"$feature","value":"$value"}""")
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
    fun `a full run probes every plane, read-only`() = runTest {
        val rest = FakeRest(::livingRoomTv)
        val bar = FakeLineConnection(::barAnswer)
        val remote = FakeRemote(server)
        val runner = DiagnosticRunner(
            targets(), rest, remote, FakeTcp(bar), { client }, { DiscoveryOutcome(found = 1, configuredTvSeen = true) }, "interface only",
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
        assertTrue(steps.of(DiagnosticSteps.BAR_FEATURES).facts.contains("system.version" to "1.2.3"))
        assertEquals("no_answer", steps.of(DiagnosticSteps.BAR_VOICE_ZOOM).errorClass)
        // The bar only ever receives gets.
        assertTrue(bar.written.all { "\"type\":\"get\"" in it }, bar.written.toString())
        assertEquals(StepStatus.SKIPPED, steps.of(DiagnosticSteps.CLOUD).status)
    }

    @Test
    fun `the report never carries a secret, an address or an identifier`() = runTest {
        val runner = DiagnosticRunner(
            targets(), FakeRest(::livingRoomTv), FakeRemote(server), FakeTcp(FakeLineConnection(::barAnswer)), { client },
            { DiscoveryOutcome(1, true) }, "interface only",
        )
        runner.run()
        val environment = listOf(
            "app" to "0.1.0-dev.1",
            "android" to "15 (API 35)",
            "wifi" to "fe80::1c2d:3e4f:5a6b:7c8d via $BAR",
            "live links" to "remote ONLINE, rest ONLINE, bar ONLINE",
        )
        val report = DiagnosticReport.render(environment, runner.steps.value, runner.sensitive)

        val leaks = listOf(PSK, TV, BAR, SERIAL, MAC, CID, "Camille", "fe80", pin.sha256Hex, "192.168.")
        for (leak in leaks) assertFalse(leak in report, "report leaks $leak:\n$report")
        // Still useful: models, versions, method list, error classes.
        for (kept in listOf("K-65XR80", "getPowerStatus@1.0", "rpc_40005_standby", "HT-A9000", "BRAVIA 8", "summary:")) {
            assertTrue(kept in report, "report lost $kept:\n$report")
        }
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
