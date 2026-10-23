package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.AndroidTlsConnector
import io.kud.tutti.platform.net.PinMismatchException
import io.kud.tutti.protocol.atv.AtvPairing
import io.kud.tutti.protocol.atv.AtvRemote
import io.kud.tutti.protocol.atv.PairingFailure
import io.kud.tutti.protocol.atv.PairingResult
import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.sim.FakeAtvPairingTv
import io.kud.tutti.protocol.sim.FakeAtvRemoteTv
import io.kud.tutti.protocol.sim.PairingTrace
import io.kud.tutti.protocol.sim.SimKeys
import io.kud.tutti.protocol.sim.SimTlsConnector
import io.kud.tutti.protocol.sim.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The real pairing and remote clients over the app's real TLS connector, against the simulated
 * TV on loopback: handshakes, client certificates, pins, framing and socket lifecycles are all
 * real; only the TV is not.
 */
class AtvSimTest {
    private val pairingTv = FakeAtvPairingTv().apply { start() }
    private val remoteTv = FakeAtvRemoteTv(pingMillis = 150).apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
        pairingTv.close()
        remoteTv.close()
    }

    private fun connector(readTimeoutMs: Int = 2_000) = SimTlsConnector(
        AndroidTlsConnector(::loopbackOnly, readTimeoutMs),
        mapOf(AtvPairing.PORT to pairingTv, AtvRemote.PORT to remoteTv),
    )

    private fun shownCode(): String = pairingTv.codes.poll(3, TimeUnit.SECONDS) ?: error("the TV never showed a code")

    private suspend fun pair(pairing: AtvPairing = AtvPairing(connector())): PairingResult =
        withTimeout(5_000) { pairing.pair(HOST, SimKeys.client) { shownCode() } }

    private suspend fun pairedRemote(readTimeoutMs: Int = 2_000): Pair<AtvRemote, CertificatePin> {
        val pin = (pair() as PairingResult.Paired).serverPin
        remoteTv.pairedClient = pairingTv.pairedClient
        val remote = AtvRemote(connector(readTimeoutMs), scope)
        remote.connect(HOST, SimKeys.client, pin)
        remote.state.await("remote ready") { it.ready && it.powered == true }
        return remote to pin
    }

    @Test
    fun `pairs and returns at once although the TV keeps the socket open`() = runBlocking {
        val started = System.nanoTime()
        val result = pair()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(PairingResult.Paired(CertificatePin.of(SimKeys.tv.certificate)), result)
        assertEquals(SimKeys.client.certificate, pairingTv.pairedClient)
        assertTrue(pairingTv.surprises.toString(), pairingTv.surprises.isEmpty())
        // dev.36: leaving collect waited out the read the TV never answers.
        assertTrue("pairing took $tookMs ms", tookMs < 2_000)
        assertTrue("our end of the pairing socket stayed open", eventually { pairingTv.liveConnections == 0 })
    }

    @Test
    fun `a mistyped code is caught locally and asked again`() = runBlocking {
        val attempts = mutableListOf<Int>()
        val result = withTimeout(5_000) {
            AtvPairing(connector()).pair(HOST, SimKeys.client) { attempt ->
                attempts += attempt
                val code = pairingTv.codes.peekOrWait()
                // The first byte is the hash check: change it and the code can never match.
                if (attempt == 1) "%02X".format((code.take(2).toInt(16) + 1) and 0xff) + code.drop(2) else code
            }
        }
        assertTrue("$result", result is PairingResult.Paired)
        assertEquals(listOf(1, 2), attempts)
        assertEquals(1, pairingTv.received.count { it.kind == "secret" })
    }

    private fun java.util.concurrent.BlockingQueue<String>.peekOrWait(): String {
        val deadline = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < deadline) peek()?.let { return it }.also { Thread.sleep(10) }
        error("the TV never showed a code")
    }

    @Test
    fun `a TV that closes mid-pairing fails as closed, at once`() = runBlocking {
        pairingTv.faults.closeAfter = 2
        assertEquals(PairingResult.Failed(PairingFailure.Closed), pair())
    }

    @Test
    fun `a TV that half-closes mid-pairing fails as closed and we close our end`() = runBlocking {
        pairingTv.faults.halfCloseAfter = 3
        assertEquals(PairingResult.Failed(PairingFailure.Closed), pair())
        assertTrue(eventually { pairingTv.liveConnections == 0 })
    }

    @Test
    fun `replays the pairing trace from Erwann's phone`() = runBlocking {
        val trace = "tx request · rx request_ack STATUS_OK · tx option · rx option STATUS_OK · " +
            "tx configuration · rx configuration_ack STATUS_OK · tx secret · rx secret_ack STATUS_OK"
        pairingTv.script = PairingTrace.parse("steps: $trace")
        // Closing our end races the reader on a real socket: replay enough times to see it.
        repeat(15) { run ->
            val pairing = AtvPairing(connector())
            assertTrue(pair(pairing) is PairingResult.Paired)
            assertEquals(trace, PairingTrace.render(PairingTrace.parse(pairing.trace.joinToString(" · "))))
            assertEquals("run $run: ${pairing.trace}", "done: paired", pairing.trace.last())
        }
        assertTrue(pairingTv.surprises.toString(), pairingTv.surprises.isEmpty())
    }

    @Test
    fun `replays a TV refusing the secret`() = runBlocking {
        pairingTv.script = PairingTrace.parse(
            "tx request · rx request_ack STATUS_OK · tx option · rx option STATUS_OK · " +
                "tx configuration · rx configuration_ack STATUS_OK · tx secret · rx secret_ack STATUS_BAD_SECRET",
        )
        assertEquals(PairingResult.Failed(PairingFailure.BadCode), pair())
    }

    @Test
    fun `replays a TV refusing the configuration`() = runBlocking {
        pairingTv.script = PairingTrace.parse(
            "tx request · rx request_ack STATUS_OK · tx option · rx option STATUS_OK · " +
                "tx configuration · rx configuration_ack STATUS_BAD_CONFIGURATION",
        )
        assertEquals(PairingResult.Failed(PairingFailure.Status(401)), pair())
    }

    @Test
    fun `pair, remote, key, app link, TV sleeps, reconnect after wake`() = runBlocking {
        val (remote, pin) = pairedRemote()
        assertTrue(remote.sendKey(RemoteKeyCode.KEYCODE_HOME.value))
        assertEquals(RemoteKeyCode.KEYCODE_HOME, remoteTv.keys.poll(2, TimeUnit.SECONDS)?.key_code)
        assertTrue(remote.launch("https://example.com/sim"))
        assertEquals("https://example.com/sim", remoteTv.appLinks.poll(2, TimeUnit.SECONDS))
        assertTrue("pings went unanswered", eventually { remoteTv.pongs.get() >= 2 })
        assertEquals(12, remote.state.value.volume?.level)

        remoteTv.power(false)
        remote.state.await("power off reported") { it.powered == false }

        remoteTv.sleep()
        remote.state.await("link down when the TV sleeps") { !it.ready }
        try {
            remote.connect(HOST, SimKeys.client, pin)
            fail("connected to a sleeping TV")
        } catch (e: IOException) {
            // Refused: the port is closed in standby.
        }

        remoteTv.powered = true
        remoteTv.wake()
        remote.connect(HOST, SimKeys.client, pin)
        remote.state.await("ready after wake") { it.ready && it.powered == true }
        assertTrue(remote.sendKey(RemoteKeyCode.KEYCODE_DPAD_CENTER.value))
        assertEquals(RemoteKeyCode.KEYCODE_DPAD_CENTER, remoteTv.keys.poll(2, TimeUnit.SECONDS)?.key_code)
        remote.disconnect()
    }

    @Test
    fun `a TV gone silent is a dead link once the read times out`() = runBlocking {
        val (remote, _) = pairedRemote(readTimeoutMs = 600)
        remoteTv.faults.silent = true
        val state = remote.state.await("dead link") { !it.ready }
        assertNotNull(state.error)
        assertTrue("our end stayed open", eventually { remoteTv.liveConnections == 0 })
    }

    @Test
    fun `a TV that half-closes the remote socket is a dropped link and we close our end`() = runBlocking {
        val (remote, _) = pairedRemote()
        remoteTv.halfCloseConnections()
        remote.state.await("link down on EOF") { !it.ready }
        assertTrue("our end stayed open", eventually { remoteTv.liveConnections == 0 })
    }

    @Test
    fun `a TV with a new certificate fails the pinned handshake`() = runBlocking {
        val (remote, pin) = pairedRemote()
        remote.disconnect()
        remoteTv.changeCertificate(SimKeys.tvAfterReset)
        try {
            remote.connect(HOST, SimKeys.client, pin)
            fail("a changed certificate passed the pin")
        } catch (e: Exception) {
            assertTrue("$e", e.hasCause<PinMismatchException>())
        }
        assertFalse(remote.state.value.ready)
    }

    @Test
    fun `a TV that forgot the pairing drops the link before it is ready`() = runBlocking {
        val pin = (pair() as PairingResult.Paired).serverPin
        remoteTv.pairedClient = SimKeys.otherClient.certificate
        val remote = AtvRemote(connector(), scope)
        // TLS 1.3 completes the client's handshake before the TV judges the certificate, so
        // connect may succeed; the TV then drops the link, and the controller's ready timeout
        // reports it.
        runCatching { remote.connect(HOST, SimKeys.client, pin) }
        assertTrue(eventually { remoteTv.accepted.get() >= 1 && remoteTv.liveConnections == 0 })
        assertFalse(remote.state.value.ready)
        assertEquals(0, remoteTv.configured.get())
    }
}
