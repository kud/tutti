package io.kud.tutti.protocol.theatre

import java.io.ByteArrayOutputStream

/**
 * Splits the bar's byte stream into top-level JSON objects, whatever separates them: a newline,
 * `\r\n`, a NUL, or nothing at all. The reference client for this port (sony-cisip2, used by
 * bravia-quad-homeassistant) decodes "concatenated JSON objects with no delimiter", so a
 * newline is never assumed. A line splitter waiting for `\n` hears nothing from such a bar,
 * not even an ACK.
 *
 * Scans bytes: braces, quotes and backslashes are ASCII, and every byte of a multi-byte UTF-8
 * character is 0x80 or above, so a character cut across reads never confuses the depth count.
 * Bytes between objects are skipped. An object longer than [maxObjectBytes] is dropped whole.
 */
class JsonStreamSplitter(private val maxObjectBytes: Int = 64 * 1024) {
    private val buffer = ByteArrayOutputStream()
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var discarding = false

    fun feed(chunk: ByteArray): List<String> {
        val objects = mutableListOf<String>()
        for (byte in chunk) {
            if (depth == 0) {
                if (byte == OPEN) {
                    depth = 1
                    inString = false
                    escaped = false
                    discarding = false
                    buffer.reset()
                    buffer.write(byte.toInt())
                }
                // Anything else between objects (whitespace, NUL, a stray byte) is a separator.
                continue
            }
            if (!discarding) {
                buffer.write(byte.toInt())
                if (buffer.size() > maxObjectBytes) {
                    buffer.reset()
                    discarding = true
                }
            }
            when {
                escaped -> escaped = false
                inString && byte == BACKSLASH -> escaped = true
                byte == QUOTE -> inString = !inString
                inString -> Unit
                byte == OPEN -> depth++
                byte == CLOSE -> {
                    depth--
                    if (depth == 0) {
                        if (!discarding) objects += buffer.toByteArray().decodeToString()
                        buffer.reset()
                        discarding = false
                    }
                }
            }
        }
        return objects
    }

    private companion object {
        const val OPEN: Byte = '{'.code.toByte()
        const val CLOSE: Byte = '}'.code.toByte()
        const val QUOTE: Byte = '"'.code.toByte()
        const val BACKSLASH: Byte = '\\'.code.toByte()
    }
}
