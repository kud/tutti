package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.AndroidTcpConnector
import io.kud.tutti.protocol.sim.FakeTheatreBar
import io.kud.tutti.protocol.sim.SimTcpConnector
import io.kud.tutti.protocol.sim.eventually
import io.kud.tutti.protocol.theatre.TheatreClient
import io.kud.tutti.protocol.theatre.TheatreFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The real soundbar client over the app's real TCP connector, against the simulated bar on loopback. */
class TheatreSimTest {
    private val bar = FakeTheatreBar().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
        bar.close()
    }

    private fun client(readTimeoutMs: Int = 2_000) = TheatreClient(
        SimTcpConnector(AndroidTcpConnector(::loopbackOnly, readTimeoutMs), mapOf(TheatreFeature.PORT to bar)),
        scope,
    )

    private suspend fun TheatreClient.connected() = also {
        connect(HOST)
        state.await("bar state read") { it.connected && it.confirmed[TheatreFeature.VOLUME.key] == "20" }
    }

    @Test
    fun `connects, reads everything, sets and hears notifications`() = runBlocking {
        val client = client().connected()
        client.setLevel(TheatreFeature.VOLUME, 30)
        client.state.await("volume confirmed by the bar's notify") { it.confirmed[TheatreFeature.VOLUME.key] == "30" }
        assertEquals("30", bar.values[TheatreFeature.VOLUME.key])
        assertTrue(bar.lines.any { it.contains("\"value\":30") })

        bar.change(TheatreFeature.MUTE.key, "on")
        client.state.await("a change made on the bar") { it.confirmed[TheatreFeature.MUTE.key] == "on" }
        client.disconnect()
    }

    @Test
    fun `ERR and NAK are refusals`() = runBlocking {
        val client = client().connected()
        assertTrue(client.probe(TheatreFeature.VOLUME))
        assertFalse(client.probe(TheatreFeature.SPATIAL_360))
        bar.refused += TheatreFeature.NIGHT.key
        client.setToggle(TheatreFeature.NIGHT, true)
        client.state.await("the refusal reported") { it.lastError != null }
        assertEquals("off", bar.values[TheatreFeature.NIGHT.key])
    }

    @Test
    fun `a dropped bar is seen at once, refuses while asleep and reconnects on wake`() = runBlocking {
        val client = client().connected()
        bar.dropConnections()
        client.state.await("disconnect seen") { !it.connected }
        bar.sleep()
        try {
            client.connect(HOST)
            fail("connected to a sleeping bar")
        } catch (e: IOException) {
            // Refused.
        }
        bar.wake()
        bar.values[TheatreFeature.VOLUME.key] = "20"
        client.connected()
        assertTrue(bar.accepted.get() >= 2)
    }

    @Test
    fun `a half-closed bar is a disconnect and we close our end`() = runBlocking {
        val client = client().connected()
        bar.halfCloseConnections()
        client.state.await("disconnect on EOF") { !it.connected }
        assertTrue("our end stayed open", eventually { bar.liveConnections == 0 })
    }

    @Test
    fun `a silent bar is a dead link once the read times out`() = runBlocking {
        val client = client(readTimeoutMs = 500).connected()
        bar.faults.silent = true
        client.state.await("dead link") { !it.connected }
        assertTrue(eventually { bar.liveConnections == 0 })
    }

    @Test
    fun `a slow bar still lands its answers`() = runBlocking {
        bar.faults.replyDelayMillis = 30
        client().connected()
        Unit
    }
}
