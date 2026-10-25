package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import java.io.IOException

/**
 * The remote channel, TLS on port 6466, pinned to the certificate recorded at pairing.
 * The TV pings about every 5 s; the app should treat ~10 s of silence as a dead link
 * (socket read timeout in its [TlsConnector]) and reconnect.
 *
 * A TLS failure right after connecting usually means the TV forgot the pairing:
 * surface "Associer à nouveau" rather than retrying forever.
 */
class AtvRemote(
    private val connector: TlsConnector,
    private val scope: CoroutineScope,
    deviceInfo: ClientDeviceInfo = ClientDeviceInfo(),
) {
    private val session = RemoteSession(deviceInfo)
    private val mutableState = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = mutableState.asStateFlow()

    private val writeLock = Mutex()

    private val voiceLock = Mutex()
    private val mutableVoice = MutableStateFlow<VoiceState>(VoiceState.Idle)

    /** The TV voice session (hold-to-talk). Audio never appears in it beyond byte counts. */
    val voice: StateFlow<VoiceState> = mutableVoice.asStateFlow()

    private val mutableVoiceEvents = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 8)
    val voiceEvents: SharedFlow<VoiceEvent> = mutableVoiceEvents.asSharedFlow()

    private var voiceTimer: Job? = null

    @Volatile private var connection: TlsConnection? = null
    private var readJob: Job? = null

    suspend fun connect(host: String, identity: ClientIdentity, serverPin: CertificatePin) {
        disconnect()
        val conn = connector.connect(host, PORT, identity, serverPin)
        connection = conn
        readJob = scope.launch {
            val decoder = FrameDecoder()
            try {
                conn.incoming.collect { chunk ->
                    for (frame in decoder.feed(chunk)) {
                        val message = try {
                            RemoteMessage.ADAPTER.decode(frame)
                        } catch (e: IOException) {
                            continue
                        }
                        var replies: List<RemoteMessage> = emptyList()
                        mutableState.update { current ->
                            val step = session.reduce(current, message)
                            replies = step.replies
                            step.state
                        }
                        replies.forEach { write(conn, it) }
                        message.remote_voice_begin?.let { begin ->
                            voiceStep { VoiceSession.begin(it, begin.session_id, now()) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "connection lost") }
            } finally {
                // EOF or a read timeout leaves our end open: close it, or the TV keeps a dead session.
                conn.close()
                if (connection === conn) {
                    connection = null
                    mutableState.update { it.copy(ready = false) }
                    mutableVoice.update { VoiceSession.disconnected(it, now()) }
                }
            }
        }
    }

    fun disconnect() {
        val conn = connection
        connection = null
        readJob?.cancel()
        conn?.close()
        mutableState.update { it.disconnected() }
        mutableVoice.update { VoiceSession.disconnected(it, now()) }
    }

    /**
     * Hold-to-talk pressed: sends the search key; the TV answers with a session. Start the mic
     * at once and feed [voiceAudio]: audio captured before the TV answers is held, not lost.
     */
    suspend fun startVoice() {
        val ready = connection != null && mutableState.value.ready
        voiceStep { VoiceSession.start(it, now(), ready, mutableState.value.tvFeatures) }
        voiceTimer?.cancel()
        voiceTimer = scope.launch {
            while (true) {
                delay(VOICE_TICK_MS)
                val state = voiceStep { VoiceSession.tick(it, now()) }
                if (state !is VoiceState.Opening && state !is VoiceState.Listening) break
            }
        }
    }

    /** 16-bit LE PCM, 8 kHz mono. */
    suspend fun voiceAudio(pcm: ByteArray) {
        voiceStep { VoiceSession.audio(it, pcm.toByteString(), now()) }
    }

    suspend fun finishVoice() {
        voiceStep { VoiceSession.finish(it) }
    }

    suspend fun cancelVoice() {
        voiceStep { VoiceSession.cancel(it) }
    }

    /** Clears a failure once it has been shown. */
    fun clearVoiceFailure() {
        mutableVoice.update { if (it is VoiceState.Failed) VoiceState.Idle else it }
    }

    private suspend fun voiceStep(transition: (VoiceState) -> VoiceStep): VoiceState = voiceLock.withLock {
        val step = transition(mutableVoice.value)
        mutableVoice.value = step.state
        val conn = connection
        if (conn != null) {
            for (message in step.out) {
                try {
                    write(conn, message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableVoice.value = VoiceSession.disconnected(step.state, now())
                    break
                }
            }
        }
        step.event?.let { mutableVoiceEvents.tryEmit(it) }
        mutableVoice.value
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    /** Returns false when not connected or [keyCode] is not a v2 key. */
    suspend fun sendKey(keyCode: Int, direction: KeyDirection = KeyDirection.SHORT): Boolean {
        val message = RemoteMessages.key(keyCode, direction) ?: return false
        return send(message)
    }

    /**
     * A fixed long press, for the TV's own long-press menus: the key goes down, stays down for
     * [KeyHold.MIN_HOLD_MS] so the TV sees it held, then comes up. A held finger uses [holdKey]
     * and [releaseKey] instead, so the key stays down as long as the finger does.
     */
    suspend fun longPress(keyCode: Int): Boolean {
        if (!holdKey(keyCode)) return false
        delay(KeyHold.MIN_HOLD_MS)
        return releaseKey(keyCode)
    }

    /** The key goes down on the TV (START_LONG) and stays down until [releaseKey]. */
    suspend fun holdKey(keyCode: Int): Boolean = sendKey(keyCode, KeyDirection.START_LONG)

    /** The key held by [holdKey] comes up (END_LONG). */
    suspend fun releaseKey(keyCode: Int): Boolean = sendKey(keyCode, KeyDirection.END_LONG)

    private val typing = ArrayDeque<RemoteMessage>()
    private val typingLock = Mutex()

    /**
     * The phone field's whole text, on every change. Not suspending on purpose: the mirror and
     * the queue take each keystroke in the order it was typed, on the caller's thread, and one
     * drainer sends them in that order. A coroutine per keystroke on a pool once let "ph" reach
     * the mirror before "p" did.
     */
    fun type(text: String) {
        synchronized(typing) {
            var messages = emptyList<RemoteMessage>()
            mutableState.update { state -> session.type(state, text).also { messages = it.messages }.state }
            if (messages.isEmpty()) return
            typing.addAll(messages)
        }
        scope.launch {
            typingLock.withLock {
                while (true) {
                    val next = synchronized(typing) { typing.removeFirstOrNull() } ?: break
                    send(next)
                }
            }
        }
    }

    suspend fun launch(link: String): Boolean = send(RemoteMessages.appLink(link))


    private suspend fun send(message: RemoteMessage): Boolean {
        val conn = connection ?: return false
        if (!mutableState.value.ready) return false
        return try {
            write(conn, message)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun write(conn: TlsConnection, message: RemoteMessage) {
        val bytes = Framing.frame(message.encode())
        writeLock.withLock { conn.send(bytes) }
    }

    companion object {
        const val PORT = 6466
        private const val VOICE_TICK_MS = 250L
    }
}
