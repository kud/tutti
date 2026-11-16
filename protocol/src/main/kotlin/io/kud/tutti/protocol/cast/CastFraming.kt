package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.proto.CastMessage
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream

/** Cast v2 frames: a 4-byte big-endian length, then a protobuf [CastMessage]. */
object CastFraming {
    /** The Cast receiver's own ceiling for one message. */
    const val MAX_FRAME = 64 * 1024

    fun encode(message: CastMessage): ByteArray {
        val body = CastMessage.ADAPTER.encode(message)
        require(body.size <= MAX_FRAME) { "frame too large" }
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        body.copyInto(out, 4)
        return out
    }

    fun text(source: String, destination: String, namespace: String, json: String): CastMessage = CastMessage(
        protocol_version = CastMessage.ProtocolVersion.CASTV2_1_0,
        source_id = source,
        destination_id = destination,
        namespace = namespace,
        payload_type = CastMessage.PayloadType.STRING,
        payload_utf8 = json,
    )

    fun binary(source: String, destination: String, namespace: String, payload: ByteArray): CastMessage = CastMessage(
        protocol_version = CastMessage.ProtocolVersion.CASTV2_1_0,
        source_id = source,
        destination_id = destination,
        namespace = namespace,
        payload_type = CastMessage.PayloadType.BINARY,
        payload_binary = payload.toByteString(),
    )
}

/** Thrown on a frame that can't be a Cast message: the caller closes the connection. */
class CastFrameException(message: String) : Exception(message)

/** Reassembles frames from socket chunks, however they are split or merged. */
class CastFrameDecoder {
    private val buffer = ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<CastMessage> {
        buffer.write(chunk)
        val bytes = buffer.toByteArray()
        val out = mutableListOf<CastMessage>()
        var at = 0
        while (bytes.size - at >= 4) {
            val length = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
                ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
            if (length < 0 || length > CastFraming.MAX_FRAME) throw CastFrameException("frame length $length")
            if (bytes.size - at - 4 < length) break
            val message = try {
                CastMessage.ADAPTER.decode(bytes.copyOfRange(at + 4, at + 4 + length))
            } catch (e: Exception) {
                throw CastFrameException("undecodable frame (${e.javaClass.simpleName})")
            }
            out += message
            at += 4 + length
        }
        buffer.reset()
        buffer.write(bytes, at, bytes.size - at)
        return out
    }
}
