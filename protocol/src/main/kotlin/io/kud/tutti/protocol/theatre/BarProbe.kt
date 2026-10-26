package io.kud.tutti.protocol.theatre

import io.kud.tutti.protocol.net.LanAddress
import io.kud.tutti.protocol.net.TcpConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Is a Sony soundbar at this address? One read-only `get system.modeltype` on port 33336, on a
 * connection of its own, closed at once: nothing is set, and nothing else is asked. For
 * [io.kud.tutti.protocol.net.Rediscovery], which only ever turns the answer into an offer.
 */
object BarProbe {
    private const val ID = 1

    /** The model [host] names on port 33336, or null: not private, refused, silent, or no answer within [timeoutMillis]. */
    suspend fun model(tcp: TcpConnector, host: String, timeoutMillis: Long = 2_000): String? {
        if (!LanAddress.isPrivate(host)) return null
        val connection = try {
            withTimeoutOrNull(timeoutMillis) { tcp.connect(host, TheatreFeature.PORT) } ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMillis) {
                val splitter = JsonStreamSplitter()
                connection.send(TheatreCodec.encode(TheatreOutbound.GetRequest(ID, TheatreFeature.MODEL.key)).encodeToByteArray())
                connection.incoming
                    .map { chunk -> splitter.feed(chunk).firstNotNullOfOrNull(::answer) }
                    .firstOrNull { it != null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            connection.close()
        }
    }

    private fun answer(text: String): String? {
        val result = TheatreCodec.decode(text) as? TheatreInbound.Result ?: return null
        if (result.feature != TheatreFeature.MODEL.key && result.id != ID) return null
        if (result.isAck || result.isRefusal) return null
        return result.value?.takeIf { it.isNotBlank() }
    }
}
