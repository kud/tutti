package io.kud.tutti.platform.net

import io.kud.tutti.protocol.net.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosingIfCancelledTest {
    private class Fake : Connection {
        var closed = false
        override val incoming: Flow<ByteArray> = emptyFlow()
        override suspend fun send(bytes: ByteArray) = Unit
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `a connection opened for a cancelled caller is closed`() = runBlocking {
        val conn = Fake()
        val gate = CompletableDeferred<Unit>()
        val call = async {
            closingIfCancelled<Fake> { opened ->
                opened(conn)
                gate.await() // cancelled here, after the socket opened
                conn
            }
        }
        yield()
        call.cancel()
        runCatching { call.await() }
        assertTrue(conn.closed)
    }

    @Test
    fun `a connection handed to a live caller stays open`() = runBlocking {
        val conn = Fake()
        val got = closingIfCancelled<Fake> { opened -> conn.also(opened) }
        assertFalse(got.closed)
    }
}
