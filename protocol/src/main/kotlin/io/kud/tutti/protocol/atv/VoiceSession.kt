package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.atv.proto.remote.RemoteVoiceBegin
import io.kud.tutti.protocol.atv.proto.remote.RemoteVoiceEnd
import io.kud.tutti.protocol.atv.proto.remote.RemoteVoicePayload
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Why a voice session could not run. Shown to the user as a short message, never as a code. */
enum class VoiceFailure {
    /** The remote channel is not ready. */
    NOT_CONNECTED,

    /** The TV's configure did not offer the voice feature bit; its search was opened instead. */
    UNSUPPORTED,

    /** The TV did not answer the search key with a voice begin in time. */
    NO_REPLY,

    /** The link dropped mid-session. */
    DISCONNECTED,
}

/** What just happened, for haptics and messages. Not state. */
enum class VoiceEvent { LISTENING, SENT, CAPPED, CANCELLED }

sealed interface VoiceState {
    /** True while the phone's mic should be open. */
    val capturing: Boolean get() = false

    data object Idle : VoiceState

    /**
     * Search key sent, waiting for the TV's [RemoteVoiceBegin]. Audio captured meanwhile is
     * kept in [pending] so the first words are not lost. [released] = the user let go already;
     * [cancelled] = the user slid off: when the begin arrives it is closed at once.
     */
    data class Opening(
        val sinceMs: Long,
        val pending: ByteString = ByteString.EMPTY,
        val released: Boolean = false,
        val cancelled: Boolean = false,
    ) : VoiceState {
        override val capturing get() = !released && !cancelled
    }

    /** The TV opened [sessionId]; audio streams as it comes. */
    data class Listening(
        val sessionId: Int,
        val sinceMs: Long,
        val pending: ByteString = ByteString.EMPTY,
        val sentBytes: Long = 0,
    ) : VoiceState {
        override val capturing get() = true
    }

    data class Failed(val reason: VoiceFailure, val atMs: Long) : VoiceState
}

data class VoiceStep(
    val state: VoiceState,
    val out: List<RemoteMessage> = emptyList(),
    val event: VoiceEvent? = null,
)

/**
 * The voice flow of Android TV Remote v2, as androidtvremote2 implements it:
 *
 * 1. the client sends KEYCODE_SEARCH (84);
 * 2. the TV answers with `remote_voice_begin { session_id }`;
 * 3. the client echoes `remote_voice_begin { session_id }`;
 * 4. the client streams `remote_voice_payload { session_id, samples }`, 16-bit PCM 8 kHz mono,
 *    at most [CHUNK_BYTES] each, the last padded with silence to [MIN_CHUNK_BYTES];
 * 5. the client sends `remote_voice_end { session_id }`.
 *
 * Pure: time comes in as `nowMs`, [AtvRemote] owns the socket and the timers.
 */
object VoiceSession {
    /** Voice bit of the feature mask exchanged in configure / set-active. */
    const val FEATURE_VOICE = 1 shl 3

    /** 16-bit mono at 8 kHz. */
    const val SAMPLE_RATE = 8_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2

    const val CHUNK_BYTES = 20 * 1024
    const val MIN_CHUNK_BYTES = 8 * 1024

    /** How long the TV gets to answer the search key (androidtvremote2: 2 s). */
    const val OPEN_TIMEOUT_MS = 2_000L

    /** Hard cap on one utterance; the session is sent and closed when it is reached. */
    const val MAX_SESSION_MS = 15_000L

    /** Never hold more than the cap's worth of audio while the TV is slow to open. */
    const val MAX_PENDING_BYTES = (MAX_SESSION_MS / 1000 * BYTES_PER_SECOND).toInt()

    fun supported(tvFeatures: Int?): Boolean? = tvFeatures?.let { it and FEATURE_VOICE != 0 }

    /** The user pressed the mic. */
    fun start(state: VoiceState, nowMs: Long, ready: Boolean, tvFeatures: Int?): VoiceStep {
        if (state is VoiceState.Opening || state is VoiceState.Listening) return VoiceStep(state)
        if (!ready) return VoiceStep(VoiceState.Failed(VoiceFailure.NOT_CONNECTED, nowMs))
        val search = listOfNotNull(RemoteMessages.key(V2Key.SEARCH))
        if (supported(tvFeatures) == false) {
            // Fallback: the TV's own search opens on screen, where the remote's mic or the keyboard can take over.
            return VoiceStep(VoiceState.Failed(VoiceFailure.UNSUPPORTED, nowMs), search)
        }
        return VoiceStep(VoiceState.Opening(nowMs), search)
    }

    /** The TV opened a session. */
    fun begin(state: VoiceState, sessionId: Int, nowMs: Long): VoiceStep = when (state) {
        is VoiceState.Opening -> when {
            state.cancelled -> VoiceStep(VoiceState.Idle, listOf(beginMessage(sessionId), endMessage(sessionId)), VoiceEvent.CANCELLED)
            else -> {
                val listening = VoiceState.Listening(sessionId, nowMs, state.pending)
                val opened = drain(listening, final = state.released)
                VoiceStep(
                    opened.state,
                    listOf(beginMessage(sessionId)) + opened.out,
                    if (state.released) VoiceEvent.SENT else VoiceEvent.LISTENING,
                )
            }
        }
        // A session we did not ask for, or one that arrived after we gave up: close it.
        else -> VoiceStep(state, listOf(endMessage(sessionId)))
    }

    /** Captured PCM, 16-bit LE 8 kHz mono. */
    fun audio(state: VoiceState, pcm: ByteString, nowMs: Long): VoiceStep = when (state) {
        is VoiceState.Opening -> if (state.capturing) {
            val room = MAX_PENDING_BYTES - state.pending.size
            val kept = if (pcm.size <= room) pcm else pcm.substring(0, room.coerceAtLeast(0))
            VoiceStep(state.copy(pending = state.pending + kept))
        } else {
            VoiceStep(state)
        }
        is VoiceState.Listening -> {
            val step = drain(state.copy(pending = state.pending + pcm), final = false)
            if (nowMs - state.sinceMs >= MAX_SESSION_MS) capped(step) else step
        }
        else -> VoiceStep(state)
    }

    /** The user let go over the button: send what is left and close. */
    fun finish(state: VoiceState): VoiceStep = when (state) {
        is VoiceState.Opening -> VoiceStep(state.copy(released = true))
        is VoiceState.Listening -> drain(state, final = true).copy(event = VoiceEvent.SENT)
        else -> VoiceStep(state)
    }

    /** The user slid off, the app went to the background, or the mic failed. */
    fun cancel(state: VoiceState): VoiceStep = when (state) {
        is VoiceState.Opening -> VoiceStep(state.copy(cancelled = true, pending = ByteString.EMPTY), event = VoiceEvent.CANCELLED)
        is VoiceState.Listening -> VoiceStep(VoiceState.Idle, listOf(endMessage(state.sessionId)), VoiceEvent.CANCELLED)
        else -> VoiceStep(state)
    }

    /** Timers: the TV's reply deadline and the session cap. */
    fun tick(state: VoiceState, nowMs: Long): VoiceStep = when (state) {
        is VoiceState.Opening -> if (nowMs - state.sinceMs >= OPEN_TIMEOUT_MS) {
            if (state.cancelled) VoiceStep(VoiceState.Idle) else VoiceStep(VoiceState.Failed(VoiceFailure.NO_REPLY, nowMs))
        } else {
            VoiceStep(state)
        }
        is VoiceState.Listening -> if (nowMs - state.sinceMs >= MAX_SESSION_MS) capped(VoiceStep(state)) else VoiceStep(state)
        else -> VoiceStep(state)
    }

    fun disconnected(state: VoiceState, nowMs: Long): VoiceState = when (state) {
        is VoiceState.Opening, is VoiceState.Listening -> VoiceState.Failed(VoiceFailure.DISCONNECTED, nowMs)
        else -> state
    }

    private fun capped(step: VoiceStep): VoiceStep {
        val listening = step.state as? VoiceState.Listening ?: return step
        val closed = drain(listening, final = true)
        return VoiceStep(closed.state, step.out + closed.out, VoiceEvent.CAPPED)
    }

    /** Sends every full chunk; when [final], also the padded remainder and the end. */
    private fun drain(state: VoiceState.Listening, final: Boolean): VoiceStep {
        val out = mutableListOf<RemoteMessage>()
        var pending = state.pending
        var sent = state.sentBytes
        while (pending.size >= CHUNK_BYTES) {
            out += payloadMessage(state.sessionId, pending.substring(0, CHUNK_BYTES))
            sent += CHUNK_BYTES
            pending = pending.substring(CHUNK_BYTES)
        }
        if (!final) return VoiceStep(state.copy(pending = pending, sentBytes = sent), out)
        if (pending.size > 0) {
            out += payloadMessage(state.sessionId, padded(pending))
        }
        out += endMessage(state.sessionId)
        return VoiceStep(VoiceState.Idle, out)
    }

    private fun padded(pcm: ByteString): ByteString =
        if (pcm.size >= MIN_CHUNK_BYTES) pcm else (pcm.toByteArray() + ByteArray(MIN_CHUNK_BYTES - pcm.size)).toByteString()

    private operator fun ByteString.plus(other: ByteString): ByteString = when {
        size == 0 -> other
        other.size == 0 -> this
        else -> (toByteArray() + other.toByteArray()).toByteString()
    }

    fun beginMessage(sessionId: Int) = RemoteMessage(remote_voice_begin = RemoteVoiceBegin(session_id = sessionId))

    fun payloadMessage(sessionId: Int, samples: ByteString) =
        RemoteMessage(remote_voice_payload = RemoteVoicePayload(session_id = sessionId, samples = samples))

    fun endMessage(sessionId: Int) = RemoteMessage(remote_voice_end = RemoteVoiceEnd(session_id = sessionId))
}

/** PCM helpers for phones whose mic will not open at 8 kHz. */
object VoicePcm {
    /**
     * 16 kHz → 8 kHz, 16-bit LE mono: each output sample is the mean of an input pair, a crude
     * low-pass that keeps the worst aliasing out of plain decimation. A trailing odd byte or
     * sample is dropped.
     */
    fun halve(pcm16k: ByteArray): ByteArray {
        val pairs = pcm16k.size / 4
        val out = ByteArray(pairs * 2)
        for (i in 0 until pairs) {
            val a = sample(pcm16k, i * 4)
            val b = sample(pcm16k, i * 4 + 2)
            val mean = (a + b) / 2
            out[i * 2] = (mean and 0xFF).toByte()
            out[i * 2 + 1] = (mean shr 8).toByte()
        }
        return out
    }

    private fun sample(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)
}
