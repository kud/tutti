package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.AndroidTlsConnector
import io.kud.tutti.protocol.atv.AtvRemote
import io.kud.tutti.protocol.atv.NamedKeys
import io.kud.tutti.protocol.atv.VoiceFailure
import io.kud.tutti.protocol.atv.VoiceSession
import io.kud.tutti.protocol.atv.VoiceState
import io.kud.tutti.protocol.atv.proto.remote.RemoteDirection
import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.sim.FakeAtvRemoteTv
import io.kud.tutti.protocol.sim.SimKeys
import io.kud.tutti.protocol.sim.SimTlsConnector
import io.kud.tutti.protocol.sim.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * What the remote does once connected, end to end over the app's real TLS connector against the
 * simulated TV: long presses, every named key, the app in front, the TV's own volume, the
 * mirrored keyboard (echoes and counters included) and hold-to-talk. Package names are invented.
 */
class RemoteFeaturesSimTest {
    private val tv = FakeAtvRemoteTv(pingMillis = 1_000).apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
        tv.close()
    }

    private suspend fun connected(): AtvRemote {
        val remote = AtvRemote(SimTlsConnector(AndroidTlsConnector(::loopbackOnly, 3_000), mapOf(AtvRemote.PORT to tv)), scope)
        remote.connect(HOST, SimKeys.client, CertificatePin.of(SimKeys.tv.certificate))
        remote.state.await("remote ready") { it.ready && it.powered == true }
        return remote
    }

    @Test
    fun `a long press holds the key down, then lets it go`() = runBlocking<Unit> {
        val remote = connected()
        assertTrue(remote.longPress(RemoteKeyCode.KEYCODE_DPAD_CENTER.value))
        val down = tv.keys.poll(2, TimeUnit.SECONDS)
        val up = tv.keys.poll(2, TimeUnit.SECONDS)
        assertEquals(RemoteKeyCode.KEYCODE_DPAD_CENTER, down?.key_code)
        assertEquals(RemoteDirection.START_LONG, down?.direction)
        assertEquals(RemoteKeyCode.KEYCODE_DPAD_CENTER, up?.key_code)
        assertEquals(RemoteDirection.END_LONG, up?.direction)
    }

    @Test
    fun `every named key in all keys reaches the TV as itself, in order`() = runBlocking<Unit> {
        val remote = connected()
        for (key in NamedKeys.all) assertTrue("${key.name} was not sent", remote.sendKey(key.code))
        val received = NamedKeys.all.map { tv.keys.poll(2, TimeUnit.SECONDS) }
        assertEquals(NamedKeys.all.map { it.code }, received.map { it?.key_code?.value })
        assertTrue(received.all { it?.direction == RemoteDirection.SHORT })
    }

    @Test
    fun `the app in front and the TV's own volume reach the state`() = runBlocking<Unit> {
        val remote = connected()
        tv.foreground("com.example.video")
        remote.state.await("the app in front") { it.currentApp == "com.example.video" }
        tv.reportVolume(30, max = 100, muted = true)
        val volume = remote.state.await("the TV's volume") { it.volume?.level == 30 }.volume!!
        assertEquals(100, volume.max)
        assertTrue(volume.muted)
    }

    @Test
    fun `typing ph with the TV's echoes arriving late lands ph once and never loops`() = runBlocking<Unit> {
        val remote = connected()
        tv.focusField("com.example.video")
        remote.state.await("the field opens") { it.ime != null }
        tv.echoDelayMillis = 150
        remote.type("p")
        remote.type("ph")
        assertTrue("the TV never took ph", eventually { tv.field == "ph" })
        // Both echoes, the stale "p" included, come back after "ph" was typed.
        Thread.sleep(600)
        assertEquals("ph", remote.state.value.ime?.text)
        assertEquals(listOf("p", "ph"), tv.taken.toList())
        assertTrue("refused: ${tv.refused}", tv.refused.isEmpty())
        assertTrue(remote.state.value.imeEchoes.isEmpty())
    }

    @Test
    fun `fast typing and a correction end on the phone's text, with nothing refused`() = runBlocking<Unit> {
        val remote = connected()
        tv.focusField("com.example.video", text = "")
        remote.state.await("the field opens") { it.ime != null }
        tv.echoDelayMillis = 40
        val words = listOf("b", "br", "bra", "brav", "bravi", "bravo", "brav", "bravi", "bravia")
        words.forEach(remote::type)
        assertTrue("the TV ended on ${tv.field}", eventually { tv.field == "bravia" })
        Thread.sleep(400)
        assertEquals("bravia", remote.state.value.ime?.text)
        assertEquals(words, tv.taken.toList())
        assertTrue(tv.refused.isEmpty())
    }

    @Test
    fun `typed before the TV shows its field, the first letter lands with the counters from focus`() = runBlocking<Unit> {
        val remote = connected()
        tv.focusField("com.example.video", show = false)
        remote.state.await("the counters from the keyboard starting") { it.imeCounter == tv.imeCounter }
        remote.type("a")
        assertTrue("the first letter never landed", eventually { tv.field == "a" })
        remote.state.await("the echo opens the field") { it.ime?.text == "a" }
        assertTrue(tv.refused.isEmpty())
    }

    @Test
    fun `text typed on the TV's own keyboard reaches the mirror, and a new app closes it`() = runBlocking<Unit> {
        val remote = connected()
        tv.focusField("com.example.video")
        remote.state.await("the field opens") { it.ime != null }
        tv.typeOnTv("abc")
        remote.state.await("the TV's own text") { it.ime?.text == "abc" }
        tv.foreground("com.example.music")
        remote.state.await("the field closes with the app") { it.ime == null && it.currentApp == "com.example.music" }
    }

    @Test
    fun `hold to talk opens the TV's voice session, streams in chunks and ends it`() = runBlocking<Unit> {
        val remote = connected()
        remote.startVoice()
        val listening = remote.voice.await("the TV opens a session") { it is VoiceState.Listening } as VoiceState.Listening
        assertEquals(RemoteKeyCode.KEYCODE_SEARCH, tv.keys.poll(2, TimeUnit.SECONDS)?.key_code)
        remote.voiceAudio(ByteArray(VoiceSession.CHUNK_BYTES + 4_000))
        remote.finishVoice()
        assertTrue("the session never ended", eventually { tv.voiceEnds.isNotEmpty() })
        assertEquals(listOf(listening.sessionId), tv.voiceBegins.toList())
        assertEquals(VoiceSession.CHUNK_BYTES + VoiceSession.MIN_CHUNK_BYTES, tv.voiceBytes.get())
        assertEquals(listOf(listening.sessionId), tv.voiceEnds.toList())
    }

    @Test
    fun `a TV without the voice feature gets its search opened and a clear failure`() = runBlocking<Unit> {
        tv.features = 622 and VoiceSession.FEATURE_VOICE.inv()
        val remote = connected()
        remote.startVoice()
        val failed = remote.voice.await("unsupported") { it is VoiceState.Failed } as VoiceState.Failed
        assertEquals(VoiceFailure.UNSUPPORTED, failed.reason)
        assertEquals(RemoteKeyCode.KEYCODE_SEARCH, tv.keys.poll(2, TimeUnit.SECONDS)?.key_code)
        assertTrue(tv.voiceBegins.isEmpty())
    }

    @Test
    fun `a TV that never answers the search key fails as no reply and sends no audio`() = runBlocking<Unit> {
        tv.answersVoice = false
        val remote = connected()
        remote.startVoice()
        remote.voiceAudio(ByteArray(1_000))
        val failed = remote.voice.await("no reply", millis = 4_000) { it is VoiceState.Failed } as VoiceState.Failed
        assertEquals(VoiceFailure.NO_REPLY, failed.reason)
        assertEquals(0, tv.voiceBytes.get())
    }
}
