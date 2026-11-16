package io.kud.tutti.protocol.theatre

import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.TcpConnector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarProbeTest {
    @Test
    fun `a bar names its model, and the probe asks nothing else`() = runTest {
        val bar = ScriptedBar { id, feature -> """{"id":$id,"type":"result","feature":"$feature","value":"HT-A9000"}""" }
        assertEquals("HT-A9000", BarProbe.model(Connector(bar), "192.168.1.31"))
        assertEquals(listOf("""{"id":1,"type":"get","feature":"system.modeltype"}"""), bar.sent)
        assertTrue(bar.closed)
    }

    @Test
    fun `a refusal, an ack or silence is no bar`() = runTest {
        assertNull(BarProbe.model(Connector(ScriptedBar { id, _ -> """{"id":$id,"type":"result","value":"ERR"}""" }), "192.168.1.31"))
        assertNull(BarProbe.model(Connector(ScriptedBar { id, _ -> """{"id":$id,"type":"result","value":"ACK"}""" }), "192.168.1.31"))
        val silent = ScriptedBar { _, _ -> null }
        assertNull(BarProbe.model(Connector(silent), "192.168.1.31", timeoutMillis = 500))
        assertTrue(silent.closed)
    }

    @Test
    fun `a closed port or a public address is no bar, and the latter is never dialled`() = runTest {
        val refusing = object : TcpConnector {
            var dialled = 0
            override suspend fun connect(host: String, port: Int): Connection {
                dialled++
                throw ConnectException("refused")
            }
        }
        assertNull(BarProbe.model(refusing, "192.168.1.31"))
        assertNull(BarProbe.model(refusing, "8.8.8.8"))
        assertEquals(1, refusing.dialled)
    }

    private class Connector(private val connection: Connection) : TcpConnector {
        override suspend fun connect(host: String, port: Int): Connection {
            assertEquals(TheatreFeature.PORT, port)
            return connection
        }
    }

    /** Answers each `get` through [answer]; null stays silent. */
    private class ScriptedBar(private val answer: (Int, String) -> String?) : Connection {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var closed = false

        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

        override suspend fun send(bytes: ByteArray) {
            val line = bytes.decodeToString().trim()
            sent += line
            val id = Regex("\"id\":(\\d+)").find(line)!!.groupValues[1].toInt()
            val feature = Regex("\"feature\":\"([^\"]+)\"").find(line)!!.groupValues[1]
            answer(id, feature)?.let { inbound.trySend("$it\n".encodeToByteArray()) }
        }

        override fun close() {
            closed = true
            inbound.close()
        }
    }
}
