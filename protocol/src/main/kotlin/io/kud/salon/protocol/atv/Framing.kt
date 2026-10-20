package io.kud.salon.protocol.atv

import java.io.ByteArrayOutputStream

class FramingException(message: String) : Exception(message)

/** Android TV Remote v2 frames: a protobuf varint length, then that many bytes of message. */
object Framing {
    fun frame(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 5)
        var remaining = payload.size
        while (remaining >= 0x80) {
            out.write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        out.write(remaining)
        out.write(payload)
        return out.toByteArray()
    }
}

/**
 * Reassembles frames whatever the TCP read boundaries: a frame (or its length prefix)
 * split across reads, or several frames in one read.
 */
class FrameDecoder(private val maxFrameBytes: Int = 1 shl 20) {
    private var buffer = ByteArray(0)

    /** @throws FramingException on a malformed length prefix or an oversize frame; drop the connection. */
    fun feed(chunk: ByteArray): List<ByteArray> {
        buffer += chunk
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (true) {
            var length = 0L
            var shift = 0
            var position = offset
            var complete = false
            while (position < buffer.size) {
                val byte = buffer[position].toInt() and 0xff
                position++
                length = length or ((byte and 0x7f).toLong() shl shift)
                if ((byte and 0x80) == 0) {
                    complete = true
                    break
                }
                shift += 7
                if (shift >= 35) throw FramingException("length prefix longer than 5 bytes")
            }
            if (!complete) break
            if (length > maxFrameBytes) throw FramingException("frame of $length bytes exceeds $maxFrameBytes")
            val size = length.toInt()
            if (buffer.size - position < size) break
            frames += buffer.copyOfRange(position, position + size)
            offset = position + size
        }
        buffer = buffer.copyOfRange(offset, buffer.size)
        return frames
    }
}
