package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteConfigure
import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceSessionTest {
    private val voiceTv = RemoteMessages.FEATURES

    private fun pcm(bytes: Int): ByteString = ByteArray(bytes) { (it % 7 + 1).toByte() }.toByteString()

    private fun opened(id: Int = 42): VoiceState {
        val opening = VoiceSession.start(VoiceState.Idle, 0, ready = true, tvFeatures = voiceTv).state
        return VoiceSession.begin(opening, id, 100).state
    }

    @Test
    fun `start sends the search key and waits for the TV`() {
        val step = VoiceSession.start(VoiceState.Idle, 0, ready = true, tvFeatures = voiceTv)
        assertIs<VoiceState.Opening>(step.state)
        assertEquals(RemoteKeyCode.KEYCODE_SEARCH, step.out.single().remote_key_inject?.key_code)
        assertTrue(step.state.capturing)
    }

    @Test
    fun `start without a link fails and sends nothing`() {
        val step = VoiceSession.start(VoiceState.Idle, 0, ready = false, tvFeatures = voiceTv)
        assertEquals(VoiceFailure.NOT_CONNECTED, (step.state as VoiceState.Failed).reason)
        assertTrue(step.out.isEmpty())
    }

    @Test
    fun `a TV without the voice bit gets its search opened and a clear failure`() {
        val step = VoiceSession.start(VoiceState.Idle, 0, ready = true, tvFeatures = 2 or 4 or 32)
        assertEquals(VoiceFailure.UNSUPPORTED, (step.state as VoiceState.Failed).reason)
        assertEquals(RemoteKeyCode.KEYCODE_SEARCH, step.out.single().remote_key_inject?.key_code)
        assertTrue(!step.state.capturing)
    }

    @Test
    fun `unknown features still try`() {
        assertIs<VoiceState.Opening>(VoiceSession.start(VoiceState.Idle, 0, ready = true, tvFeatures = null).state)
    }

    @Test
    fun `a second press while open changes nothing`() {
        val open = opened()
        val step = VoiceSession.start(open, 10, ready = true, tvFeatures = voiceTv)
        assertEquals(open, step.state)
        assertTrue(step.out.isEmpty())
    }

    @Test
    fun `the TV's begin is echoed with its session id and early audio follows`() {
        val opening = VoiceSession.start(VoiceState.Idle, 0, true, voiceTv).state
        val buffered = VoiceSession.audio(opening, pcm(VoiceSession.CHUNK_BYTES + 100), 50).state
        val step = VoiceSession.begin(buffered, 7, 300)
        assertEquals(7, step.out[0].remote_voice_begin?.session_id)
        val payload = assertNotNull(step.out[1].remote_voice_payload)
        assertEquals(7, payload.session_id)
        assertEquals(VoiceSession.CHUNK_BYTES, payload.samples.size)
        assertEquals(2, step.out.size)
        assertEquals(VoiceEvent.LISTENING, step.event)
        assertEquals(100, (step.state as VoiceState.Listening).pending.size)
    }

    @Test
    fun `audio streams in 20 KiB chunks`() {
        var state = opened()
        val out = mutableListOf<RemoteMessage>()
        repeat(9) {
            val step = VoiceSession.audio(state, pcm(VoiceSession.BYTES_PER_SECOND / 2), 200L + it * 500)
            state = step.state
            out += step.out
        }
        // 9 × 8000 = 72000 bytes = three full chunks, 10560 left.
        assertEquals(3, out.size)
        assertTrue(out.all { it.remote_voice_payload?.samples?.size == VoiceSession.CHUNK_BYTES })
        assertEquals(72_000 - 3 * VoiceSession.CHUNK_BYTES, (state as VoiceState.Listening).pending.size)
    }

    @Test
    fun `finishing flushes the remainder padded with silence then ends`() {
        val listening = VoiceSession.audio(opened(), pcm(1_000), 200).state
        val step = VoiceSession.finish(listening)
        val samples = assertNotNull(step.out[0].remote_voice_payload).samples
        assertEquals(VoiceSession.MIN_CHUNK_BYTES, samples.size)
        assertContentEquals(pcm(1_000).toByteArray(), samples.substring(0, 1_000).toByteArray())
        assertTrue(samples.substring(1_000).toByteArray().all { it == 0.toByte() })
        assertEquals(42, step.out[1].remote_voice_end?.session_id)
        assertEquals(VoiceState.Idle, step.state)
        assertEquals(VoiceEvent.SENT, step.event)
    }

    @Test
    fun `finishing with nothing left only ends`() {
        val step = VoiceSession.finish(opened())
        assertNotNull(step.out.single().remote_voice_end)
    }

    @Test
    fun `cancelling mid-session ends without sending what is left`() {
        val listening = VoiceSession.audio(opened(), pcm(1_000), 200).state
        val step = VoiceSession.cancel(listening)
        assertNotNull(step.out.single().remote_voice_end)
        assertEquals(VoiceEvent.CANCELLED, step.event)
        assertTrue(!step.state.capturing)
    }

    @Test
    fun `released before the TV answered sends everything once it does`() {
        var state = VoiceSession.start(VoiceState.Idle, 0, true, voiceTv).state
        state = VoiceSession.audio(state, pcm(3_000), 100).state
        state = VoiceSession.finish(state).state
        assertTrue(!state.capturing)
        // Late audio after release is dropped.
        state = VoiceSession.audio(state, pcm(500), 150).state
        val step = VoiceSession.begin(state, 9, 400)
        assertEquals(listOf(true, false, false), step.out.map { it.remote_voice_begin != null })
        assertEquals(VoiceSession.MIN_CHUNK_BYTES, step.out[1].remote_voice_payload?.samples?.size)
        assertEquals(9, step.out[2].remote_voice_end?.session_id)
        assertEquals(VoiceState.Idle, step.state)
    }

    @Test
    fun `cancelled before the TV answered closes its session without audio`() {
        var state = VoiceSession.start(VoiceState.Idle, 0, true, voiceTv).state
        state = VoiceSession.audio(state, pcm(3_000), 100).state
        state = VoiceSession.cancel(state).state
        val step = VoiceSession.begin(state, 5, 400)
        assertEquals(2, step.out.size)
        assertNotNull(step.out[0].remote_voice_begin)
        assertEquals(5, step.out[1].remote_voice_end?.session_id)
        assertTrue(step.out.none { it.remote_voice_payload != null })
    }

    @Test
    fun `no answer from the TV in time is a failure`() {
        val opening = VoiceSession.start(VoiceState.Idle, 0, true, voiceTv).state
        assertIs<VoiceState.Opening>(VoiceSession.tick(opening, VoiceSession.OPEN_TIMEOUT_MS - 1).state)
        val failed = VoiceSession.tick(opening, VoiceSession.OPEN_TIMEOUT_MS).state
        assertEquals(VoiceFailure.NO_REPLY, (failed as VoiceState.Failed).reason)
    }

    @Test
    fun `a begin nobody asked for is closed`() {
        val step = VoiceSession.begin(VoiceState.Idle, 3, 0)
        assertEquals(3, step.out.single().remote_voice_end?.session_id)
        assertEquals(VoiceState.Idle, step.state)
    }

    @Test
    fun `the cap sends and closes the session`() {
        val listening = VoiceSession.audio(opened(), pcm(2_000), 200).state
        val step = VoiceSession.tick(listening, 100 + VoiceSession.MAX_SESSION_MS)
        assertEquals(VoiceEvent.CAPPED, step.event)
        assertNotNull(step.out.last().remote_voice_end)
        assertEquals(VoiceState.Idle, step.state)
    }

    @Test
    fun `audio past the cap closes the session too`() {
        val step = VoiceSession.audio(opened(), pcm(2_000), 100 + VoiceSession.MAX_SESSION_MS)
        assertEquals(VoiceEvent.CAPPED, step.event)
        assertNotNull(step.out.last().remote_voice_end)
    }

    @Test
    fun `audio held while opening is bounded`() {
        var state = VoiceSession.start(VoiceState.Idle, 0, true, voiceTv).state
        repeat(40) { state = VoiceSession.audio(state, pcm(VoiceSession.BYTES_PER_SECOND), 10).state }
        assertEquals(VoiceSession.MAX_PENDING_BYTES, (state as VoiceState.Opening).pending.size)
    }

    @Test
    fun `a dropped link fails an open session and stops the mic`() {
        val failed = VoiceSession.disconnected(opened(), 500)
        assertEquals(VoiceFailure.DISCONNECTED, (failed as VoiceState.Failed).reason)
        assertTrue(!failed.capturing)
        assertEquals(VoiceState.Idle, VoiceSession.disconnected(VoiceState.Idle, 500))
    }

    @Test
    fun `the default feature mask advertises voice and the TV's mask is kept`() {
        assertEquals(true, VoiceSession.supported(RemoteMessages.FEATURES))
        val step = RemoteSession().reduce(RemoteState(), RemoteMessage(remote_configure = RemoteConfigure(code1 = 0x26)))
        assertEquals(0x26, step.state.tvFeatures)
        assertEquals(false, VoiceSession.supported(step.state.tvFeatures))
        assertNull(VoiceSession.supported(null))
    }

    @Test
    fun `voice messages round-trip through the wire format`() {
        val message = VoiceSession.payloadMessage(11, pcm(64))
        assertEquals(message, RemoteMessage.ADAPTER.decode(message.encode()))
    }

    @Test
    fun `halving 16 kHz averages each pair of samples`() {
        fun le(vararg samples: Int) = samples.flatMap { listOf((it and 0xFF).toByte(), (it shr 8).toByte()) }.toByteArray()
        assertContentEquals(le(150, -150, 32767), VoicePcm.halve(le(100, 200, -100, -200, 32767, 32767, 5)))
    }
}
