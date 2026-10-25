package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.AndroidTcpConnector
import io.kud.tutti.platform.net.AndroidTlsConnector
import io.kud.tutti.platform.net.OkHttpBraviaTransport
import io.kud.tutti.protocol.atv.AtvPairing
import io.kud.tutti.protocol.atv.AtvRemote
import io.kud.tutti.protocol.diagnostic.DiagnosticReport
import io.kud.tutti.protocol.diagnostic.DiagnosticRunner
import io.kud.tutti.protocol.diagnostic.DiagnosticSteps
import io.kud.tutti.protocol.diagnostic.DiagnosticTargets
import io.kud.tutti.protocol.diagnostic.DiagnosticTimeouts
import io.kud.tutti.protocol.diagnostic.StepResult
import io.kud.tutti.protocol.diagnostic.StepStatus
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.sim.FakeAtvPairingTv
import io.kud.tutti.protocol.sim.FakeAtvRemoteTv
import io.kud.tutti.protocol.sim.FakeBraviaTv
import io.kud.tutti.protocol.sim.FakeTheatreBar
import io.kud.tutti.protocol.sim.SimKeys
import io.kud.tutti.protocol.sim.SimTcpConnector
import io.kud.tutti.protocol.sim.SimTlsConnector
import io.kud.tutti.protocol.theatre.TheatreFeature
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Réglages › Diagnostic end to end: the runner over the app's real OkHttp, TLS and TCP clients
 * against the whole simulated living room, and the report it renders. Read-only, and no host,
 * key or MAC in the text.
 */
class DiagnosticSimTest {
    private val rest = FakeBraviaTv(psk = "sim-psk-7f3a").start()
    private val pairing = FakeAtvPairingTv().apply { start() }
    private val remote = FakeAtvRemoteTv(pingMillis = 200).apply { start() }
    private val bar = FakeTheatreBar(FakeTheatreBar.DEFAULTS + ("system.version" to "1.2.3")).apply { start() }

    @After
    fun stop() {
        rest.close()
        pairing.close()
        remote.close()
        bar.close()
    }

    private fun runner(): DiagnosticRunner {
        val tcp = SimTcpConnector(AndroidTcpConnector(::loopbackOnly, 3_000), mapOf(AtvPairing.PORT to pairing, TheatreFeature.PORT to bar))
        val tls = SimTlsConnector(AndroidTlsConnector(::loopbackOnly, 3_000), mapOf(AtvRemote.PORT to remote))
        return DiagnosticRunner(
            targets = DiagnosticTargets(
                tvHost = HOST,
                barHost = HOST,
                psk = "sim-psk-7f3a",
                remotePin = CertificatePin.of(SimKeys.tv.certificate),
                restPinRecorded = false,
            ),
            bravia = OkHttpBraviaTransport(HOST, null, {}, rest.https.port, rest.http.port, ::loopbackOnly, 2_000),
            tls = tls,
            tcp = tcp,
            identity = { SimKeys.client },
            discover = null,
            cloudNote = "interface only",
            timeouts = DiagnosticTimeouts(stepMillis = 6_000, sessionMillis = 3_000, settleMillis = 400, barAnswerMillis = 800, barFeaturesMillis = 30_000),
        )
    }

    private fun List<StepResult>.of(id: String) = single { it.id == id }

    @Test
    fun `a full run against the simulated room passes every plane, read-only, and the report leaks nothing`() = runBlocking<Unit> {
        val runner = runner()
        runner.run()
        val steps = runner.steps.value
        val ok = listOf(
            DiagnosticSteps.REST_INTERFACE, DiagnosticSteps.REST_AUTH, DiagnosticSteps.REST_METHODS,
            "rest.power", "rest.power_saving", "rest.ircc_codes", "rest.volume", "rest.sound", "rest.picture", "rest.inputs", "rest.playing", "rest.apps",
            DiagnosticSteps.REMOTE_IDENTITY, DiagnosticSteps.REMOTE_PAIRING_PORT, DiagnosticSteps.REMOTE_TLS, DiagnosticSteps.REMOTE_SESSION,
            DiagnosticSteps.BAR_CONNECT, DiagnosticSteps.BAR_FEATURES,
        )
        for (id in ok) assertEquals(steps.of(id).toString(), StepStatus.OK, steps.of(id).status)
        // What the simulated TV does not list is skipped, never called.
        assertEquals(StepStatus.SKIPPED, steps.of("rest.speaker").status)
        assertEquals(StepStatus.SKIPPED, steps.of("rest.supported_functions").status)
        assertFalse("getSpeakerSettings" in rest.calls)
        assertEquals("no_answer", steps.of(DiagnosticSteps.BAR_VOICE_ZOOM).errorClass)
        assertTrue(steps.of(DiagnosticSteps.REMOTE_SESSION).facts.contains("tv model" to "BRAVIA simulator"))
        assertTrue(steps.of(DiagnosticSteps.BAR_FEATURES).facts.contains("system.version" to "answered 1.2.3"))

        // Read-only: no setter, no key, no app link, no bar set.
        assertTrue(rest.calls.toString(), rest.calls.none { it.startsWith("set") || it == "terminateApps" || it == "actRegister" })
        assertTrue(remote.keys.isEmpty() && remote.appLinks.isEmpty())
        assertTrue(bar.lines.toString(), bar.lines.all { "\"type\":\"get\"" in it })
        assertTrue("the diagnostic paired", pairing.received.isEmpty())

        val report = DiagnosticReport.render(listOf("app" to "test"), steps, runner.sensitive)
        for (secret in listOf(HOST, "sim-psk-7f3a", "02:00:00:00:00:01", "02:00:00:00:00:02")) {
            assertFalse("the report shows $secret", report.contains(secret))
        }
        assertTrue(report, report.contains("summary:"))
    }

    @Test
    fun `a TV in standby is said once per plane while the bar still answers`() = runBlocking<Unit> {
        rest.sleep()
        remote.sleep()
        pairing.sleep()
        val runner = runner()
        runner.run()
        val steps = runner.steps.value
        assertEquals(StepStatus.FAILED, steps.of(DiagnosticSteps.REST_INTERFACE).status)
        assertEquals(StepStatus.SKIPPED, steps.of(DiagnosticSteps.REST_AUTH).status)
        assertEquals(StepStatus.SKIPPED, steps.of("rest.power").status)
        assertEquals(StepStatus.FAILED, steps.of(DiagnosticSteps.REMOTE_PAIRING_PORT).status)
        assertEquals(StepStatus.FAILED, steps.of(DiagnosticSteps.REMOTE_TLS).status)
        assertEquals(StepStatus.OK, steps.of(DiagnosticSteps.BAR_FEATURES).status)
    }
}
