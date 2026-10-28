package io.kud.tutti.controller

import io.kud.tutti.protocol.cast.CastAuthFailure
import io.kud.tutti.protocol.cast.CastConnection
import io.kud.tutti.protocol.cast.CastConnector
import io.kud.tutti.protocol.cast.CastContext
import io.kud.tutti.protocol.cast.CastEnd
import io.kud.tutti.protocol.cast.CastHold
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import io.kud.tutti.protocol.cast.CastReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.X509Certificate

/** Invented hosts and titles only. */
@OptIn(ExperimentalCoroutinesApi::class)
class CastWatcherTest {
    private val playing = CastNowPlaying("An invented film", "A made-up channel", CastPlayerState.PLAYING, 12.0, 100.0, 1.0, 0)

    private class FakeConnection : CastConnection {
        var closed = false
        override val peerCertificate: X509Certificate get() = error("not read by the fake session")
        override val incoming: Flow<ByteArray> = emptyFlow()
        override suspend fun send(bytes: ByteArray) = Unit
        override fun setReadTimeout(millis: Int) = Unit
        override fun close() {
            closed = true
        }
    }

    private class FakeConnector(var refuse: Boolean = false) : CastConnector {
        val hosts = mutableListOf<String>()
        val opened = mutableListOf<FakeConnection>()
        override suspend fun connect(host: String, port: Int): CastConnection {
            hosts += host
            if (refuse) throw IOException("refused")
            return FakeConnection().also { opened += it }
        }
    }

    private fun TestScope.watcher(
        connector: FakeConnector,
        active: MutableStateFlow<String?>,
        context: Flow<CastContext> = emptyFlow(),
        session: suspend (CastConnection, (CastReport) -> Unit) -> CastEnd,
    ): Pair<CastWatcher, TestScope> {
        val child = TestScope(testScheduler)
        return CastWatcher(connector, child, active, clock = { testScheduler.currentTime }, context = context, runSession = session) to child
    }

    @Test
    fun `runs only while a host is active and closes on leave`() = runTest {
        val connector = FakeConnector()
        val active = MutableStateFlow<String?>(null)
        val (w, child) = watcher(connector, active) { _, emit -> emit(CastReport.Playing(playing)); awaitCancellation() }
        runCurrent()
        assertTrue(connector.hosts.isEmpty())

        active.value = "192.168.0.20"
        runCurrent()
        assertEquals(listOf("192.168.0.20"), connector.hosts)
        assertEquals(playing, w.nowPlaying.value)

        active.value = null
        runCurrent()
        assertTrue(connector.opened.single().closed)
        // Leaving proves nothing about the TV: the item stands for the grace window, then goes.
        assertEquals(playing, w.nowPlaying.value)
        advanceTimeBy(CastHold.GRACE_MS + 1)
        assertNull(w.nowPlaying.value)
        child.cancel()
    }

    @Test
    fun `time to ready is the connect to the first status read, once per session`() = runTest {
        val connector = FakeConnector()
        val active = MutableStateFlow<String?>("192.168.0.20")
        val ready = mutableListOf<Long>()
        val child = TestScope(testScheduler)
        CastWatcher(connector, child, active, clock = { testScheduler.currentTime }, onReady = { ready += it }, runSession = { _, emit ->
            kotlinx.coroutines.delay(700)
            emit(CastReport.Playing(playing))
            kotlinx.coroutines.delay(5_000)
            emit(CastReport.Unknown)
            awaitCancellation()
        })
        advanceTimeBy(10_000)
        assertEquals(listOf(700L), ready)
        child.cancel()
    }

    @Test
    fun `a new host closes the old session and opens one to the new host`() = runTest {
        val connector = FakeConnector()
        val active = MutableStateFlow<String?>("192.168.0.20")
        val (_, child) = watcher(connector, active) { _, _ -> awaitCancellation() }
        runCurrent()
        active.value = "192.168.0.21"
        runCurrent()
        assertEquals(listOf("192.168.0.20", "192.168.0.21"), connector.hosts)
        assertTrue(connector.opened[0].closed)
        child.cancel()
    }

    @Test
    fun `failures back off and double, never a tight loop`() = runTest {
        val connector = FakeConnector(refuse = true)
        val active = MutableStateFlow<String?>("192.168.0.20")
        val (_, child) = watcher(connector, active) { _, _ -> error("not reached") }
        runCurrent()
        assertEquals(1, connector.hosts.size)
        advanceTimeBy(CastBackoff.FIRST_MS - 1)
        assertEquals(1, connector.hosts.size)
        advanceTimeBy(2)
        assertEquals(2, connector.hosts.size)
        advanceTimeBy(2 * CastBackoff.FIRST_MS + 1)
        assertEquals(3, connector.hosts.size)
        child.cancel()
    }

    @Test
    fun `an untrusted device is left alone, even across leaving and coming back`() = runTest {
        val connector = FakeConnector()
        val active = MutableStateFlow<String?>("192.168.0.20")
        val (w, child) = watcher(connector, active) { _, _ -> CastEnd.Untrusted(CastAuthFailure.UNTRUSTED_CHAIN) }
        runCurrent()
        assertEquals(1, connector.hosts.size)
        assertTrue(connector.opened.single().closed)
        assertNull(w.nowPlaying.value)

        active.value = null
        runCurrent()
        active.value = "192.168.0.20"
        advanceTimeBy(CastBackoff.UNTRUSTED_MS - 1_000)
        assertEquals(1, connector.hosts.size)
        advanceTimeBy(2_000)
        assertEquals(2, connector.hosts.size)
        child.cancel()
    }

    @Test
    fun `a healthy session that ends retries after the first delay`() = runTest {
        val connector = FakeConnector()
        val active = MutableStateFlow<String?>("192.168.0.20")
        val ends = CompletableDeferred<Unit>()
        val (w, child) = watcher(connector, active) { _, emit ->
            emit(CastReport.Playing(playing))
            kotlinx.coroutines.delay(CastBackoff.HEALTHY_MS)
            if (!ends.isCompleted) ends.complete(Unit)
            CastEnd.Closed("eof")
        }
        runCurrent()
        assertEquals(playing, w.nowPlaying.value)
        advanceTimeBy(CastBackoff.HEALTHY_MS + 1)
        // The session dropped: the item rides the reconnect, and the new session confirms it.
        assertEquals(playing, w.nowPlaying.value)
        advanceTimeBy(CastBackoff.FIRST_MS)
        assertEquals(2, connector.hosts.size)
        assertEquals(playing, w.nowPlaying.value)
        child.cancel()
    }

    @Test
    fun `a session that drops and never comes back clears after the grace window`() = runTest {
        val connector = FakeConnector()
        val active = MutableStateFlow<String?>("192.168.0.20")
        var first = true
        val (w, child) = watcher(connector, active) { _, emit ->
            if (first) {
                first = false
                emit(CastReport.Playing(playing))
                CastEnd.Closed("eof")
            } else {
                emit(CastReport.Unknown)
                awaitCancellation()
            }
        }
        runCurrent()
        assertEquals(playing, w.nowPlaying.value)
        advanceTimeBy(CastHold.GRACE_MS - 1)
        assertEquals(playing, w.nowPlaying.value)
        advanceTimeBy(2)
        assertNull(w.nowPlaying.value)
        child.cancel()
    }

    @Test
    fun `standby, another app or a new host clears the item at once`() = runTest {
        val connector = FakeConnector()
        val active = MutableStateFlow<String?>("192.168.0.20")
        val context = MutableStateFlow(CastContext(foreground = "com.example.player"))
        val (w, child) = watcher(connector, active, context) { _, emit ->
            emit(if (connector.hosts.last() == "192.168.0.20") CastReport.Playing(playing) else CastReport.Unknown)
            awaitCancellation()
        }
        runCurrent()
        assertEquals(playing, w.nowPlaying.value)
        context.value = CastContext(foreground = "com.example.launcher")
        runCurrent()
        assertNull(w.nowPlaying.value)

        active.value = null
        runCurrent()
        active.value = "192.168.0.20"
        runCurrent()
        context.value = CastContext(standby = true)
        runCurrent()
        assertNull(w.nowPlaying.value)

        context.value = CastContext()
        active.value = null
        runCurrent()
        active.value = "192.168.0.20"
        runCurrent()
        assertEquals(playing, w.nowPlaying.value)
        active.value = "192.168.0.21"
        runCurrent()
        assertNull(w.nowPlaying.value)
        child.cancel()
    }

    @Test
    fun `the target needs the remote in front, a host and the tv on`() {
        assertEquals("192.168.0.20", CastWatcher.target(true, "192.168.0.20", true))
        assertNull(CastWatcher.target(false, "192.168.0.20", true))
        assertNull(CastWatcher.target(true, null, true))
        assertNull(CastWatcher.target(true, "192.168.0.20", false))
        assertNull(CastWatcher.target(true, " ", true))
    }

    @Test
    fun `backoff caps and untrusted waits longest`() {
        assertEquals(CastBackoff.FIRST_MS, CastBackoff.delayFor(CastEnd.Malformed, 1))
        assertEquals(CastBackoff.FIRST_MS * 4, CastBackoff.delayFor(CastEnd.Closed("eof"), 3))
        assertEquals(CastBackoff.MAX_MS, CastBackoff.delayFor(CastEnd.Closed("eof"), 40))
        assertEquals(CastBackoff.UNTRUSTED_MS, CastBackoff.delayFor(CastEnd.Untrusted(CastAuthFailure.BAD_SIGNATURE), 1))
    }
}
