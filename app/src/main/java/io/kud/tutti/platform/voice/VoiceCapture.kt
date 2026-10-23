package io.kud.tutti.platform.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import io.kud.tutti.protocol.atv.VoicePcm
import io.kud.tutti.protocol.atv.VoiceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * The phone's mic for TV voice: 16-bit PCM mono at 8 kHz, as Android TV Remote v2 expects
 * (16 kHz halved where the mic refuses 8 kHz). The recorder exists only while the flow is
 * collected: cancelling the collector stops and releases it at once. Audio is never logged
 * or stored.
 */
class VoiceCapture(private val context: Context) {
    class MicUnavailable : Exception("mic unavailable")

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Chunks of ~100 ms. Throws [MicUnavailable] (or [SecurityException]) if the mic cannot open. */
    fun stream(): Flow<ByteArray> = flow {
        if (!granted()) throw SecurityException("RECORD_AUDIO not granted")
        val (record, halve) = open() ?: throw MicUnavailable()
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicUnavailable()
            val rate = if (halve) VoiceSession.SAMPLE_RATE * 2 else VoiceSession.SAMPLE_RATE
            val buffer = ByteArray(rate * 2 / 10)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw MicUnavailable()
                if (read == 0) continue
                val chunk = buffer.copyOf(read)
                emit(if (halve) VoicePcm.halve(chunk) else chunk)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    /** Returns the recorder and whether its output must be halved to 8 kHz. */
    private fun open(): Pair<AudioRecord, Boolean>? {
        for ((rate, halve) in listOf(VoiceSession.SAMPLE_RATE to false, VoiceSession.SAMPLE_RATE * 2 to true)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, rate * 2 / 5),
                )
            } catch (e: IllegalArgumentException) {
                continue
            } catch (e: SecurityException) {
                return null
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to halve
            record.release()
        }
        return null
    }
}
