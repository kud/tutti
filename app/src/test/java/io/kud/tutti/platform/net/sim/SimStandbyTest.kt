package io.kud.tutti.platform.net.sim

import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.sim.FakeTheatreBar
import io.kud.tutti.protocol.sim.SimTcpConnector
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException

/**
 * A simulator in standby refuses through the connectors without dialling: the port it released
 * is free for a simulator in the other test JVM to take, and AtvSimTest once "connected to a
 * sleeping TV" that way.
 */
class SimStandbyTest {
    private val bar = FakeTheatreBar().apply { start() }

    @After fun tearDown() = bar.close()

    @Test
    fun `a sleeping simulator refuses through the connector without dialling`() = runBlocking {
        bar.sleep()
        var reached = 0
        val real = object : TcpConnector {
            override suspend fun connect(host: String, port: Int): Connection {
                reached++
                throw AssertionError("a sleeping port was dialled")
            }
        }
        try {
            SimTcpConnector(real, mapOf(THEATRE to bar)).connect("127.0.0.1", THEATRE)
            fail("connected to a sleeping simulator")
        } catch (e: ConnectException) {
            assertEquals(0, reached)
        }

        bar.wake()
        assertTrue(bar.awake)
    }

    private companion object {
        const val THEATRE = 33336
    }
}
