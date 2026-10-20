package io.kud.salon.protocol.net

import java.io.ByteArrayOutputStream

/**
 * Splits a byte stream into newline-terminated UTF-8 lines, whatever the read boundaries:
 * a line split across reads, several lines in one read, or a multi-byte character cut in two.
 * A line longer than [maxLineBytes] is dropped whole, up to its newline.
 */
class LineSplitter(private val maxLineBytes: Int = 64 * 1024) {
    private val buffer = ByteArrayOutputStream()
    private var discarding = false

    fun feed(chunk: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        for (byte in chunk) {
            if (byte == NEWLINE) {
                if (!discarding) {
                    lines += buffer.toByteArray().decodeToString().removeSuffix("\r")
                }
                buffer.reset()
                discarding = false
            } else if (!discarding) {
                buffer.write(byte.toInt())
                if (buffer.size() > maxLineBytes) {
                    buffer.reset()
                    discarding = true
                }
            }
        }
        return lines
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
