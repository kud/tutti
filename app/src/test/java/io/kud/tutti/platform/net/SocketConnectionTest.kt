package io.kud.tutti.platform.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Real sockets on loopback. The peer accepts and then keeps the socket open without writing,
 * as the TV does after its secret ack and while it waits for a pairing code.
 */
class SocketConnectionTest {
    private fun withPeer(block: (client: Socket, peer: Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val socket = Socket()
            socket.connect(InetSocketAddress(server.inetAddress, server.localPort), 2_000)
            socket.soTimeout = 20_000
            server.accept().use { peer ->
                try {
                    block(socket, peer)
                } finally {
                    socket.close()
                }
            }
        }
    }

    @Test
    fun `cancelling the collector ends a read the peer never answers`() = withPeer { socket, _ ->
        val connection = SocketConnection(socket)
        runBlocking {
            val reader = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { connection.incoming.collect { } }
            Thread.sleep(200) // the read is now blocked
            reader.cancel()
            // Before the fix this waited for the 20 s soTimeout.
            assertNotNull("cancel waited on the blocked read", withTimeoutOrNull(2_000) { reader.join() })
        }
    }

    @Test
    fun `leaving the collect after the first chunk returns at once`() = withPeer { socket, peer ->
        peer.getOutputStream().apply {
            write(byteArrayOf(1, 2, 3))
            flush()
        }
        val connection = SocketConnection(socket)
        val chunk = runBlocking { withTimeoutOrNull(2_000) { connection.incoming.first() } }
        assertArrayEquals(byteArrayOf(1, 2, 3), chunk)
    }
}
