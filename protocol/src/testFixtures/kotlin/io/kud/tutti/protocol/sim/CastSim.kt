package io.kud.tutti.protocol.sim

import io.kud.tutti.protocol.cast.CastDeviceAuth
import io.kud.tutti.protocol.cast.CastFrameDecoder
import io.kud.tutti.protocol.cast.CastFraming
import io.kud.tutti.protocol.cast.CastNamespaces
import io.kud.tutti.protocol.cast.proto.DeviceAuthMessage
import io.kud.tutti.protocol.net.ClientIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The TV's Cast receiver, TLS on port 8009, read-only as tutti uses it. It presents [identity]
 * (a Cast device's TLS certificate lives at most four days), answers the device-auth challenge
 * with [authReply] (the signed reply for the challenge's nonce, made by the test's own PKI: no
 * real Google key exists here), then serves receiver status naming [app], and the media status
 * [media] on that app's transport. [play] and [closeApp] push changes as the TV does. Titles
 * and ids are invented.
 */
class FakeCastTv(
    identity: ClientIdentity,
    private val authReply: (nonce: ByteArray) -> ByteArray,
) : TlsSimServer("cast", identity, needClientAuth = false) {
    /** The app in front: its id and the transport its media namespace answers on. */
    data class App(val appId: String, val transportId: String, val speaksMedia: Boolean = true)

    @Volatile var app: App? = App("SIMAPP01", "transport-1")

    /** One `MEDIA_STATUS` entry as JSON, or null for an app that reports no media. */
    @Volatile var media: String? =
        """{"mediaSessionId":1,"playerState":"PLAYING","currentTime":3,"playbackRate":1,""" +
            """"media":{"duration":90,"metadata":{"title":"Sample Title","subtitle":"Sample Channel"}}}"""

    /** What the client sent, as `namespace type` (`deviceauth` for the binary challenge). */
    val received = CopyOnWriteArrayList<String>()

    override fun serve(link: Link) {
        val decoder = CastFrameDecoder()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = link.input.read(buffer)
            if (read < 0) return
            for (message in decoder.feed(buffer.copyOf(read))) {
                val json = message.payload_utf8?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
                val type = json?.get("type")?.jsonPrimitive?.content
                received += if (message.namespace == CastDeviceAuth.NAMESPACE) "deviceauth" else "${message.namespace} $type"
                if (!link.received()) continue
                val client = message.source_id
                when (message.namespace) {
                    CastDeviceAuth.NAMESPACE -> {
                        val nonce = message.payload_binary?.let { DeviceAuthMessage.ADAPTER.decode(it).challenge?.sender_nonce?.toByteArray() }
                            ?: continue
                        link.write(CastFraming.encode(CastFraming.binary(RECEIVER, client, CastDeviceAuth.NAMESPACE, authReply(nonce))))
                    }
                    CastNamespaces.HEARTBEAT -> if (type == "PING") link.write(text(RECEIVER, client, CastNamespaces.HEARTBEAT, """{"type":"PONG"}"""))
                    CastNamespaces.RECEIVER -> if (type == "GET_STATUS") link.write(receiverStatus(client))
                    CastNamespaces.MEDIA -> {
                        val current = app
                        if (type == "GET_STATUS" && current != null && message.destination_id == current.transportId) {
                            link.write(mediaStatus(current, client))
                        }
                    }
                }
            }
        }
    }

    /** The app in front starts something else, or changes state: told to every client. */
    fun play(status: String?) {
        media = status
        val current = app ?: return
        links.forEach { link -> runCatching { link.write(mediaStatus(current, "*")) } }
    }

    /** The app in front closes: its transport says CLOSE and the receiver lists no app. */
    fun closeApp() {
        val current = app ?: return
        app = null
        links.forEach { link ->
            runCatching {
                link.write(text(current.transportId, "*", CastNamespaces.CONNECTION, """{"type":"CLOSE"}"""))
                link.write(receiverStatus("*"))
            }
        }
    }

    /** The receiver's own heartbeat, which a client must answer. */
    fun ping() {
        links.forEach { link -> runCatching { link.write(text(RECEIVER, "*", CastNamespaces.HEARTBEAT, """{"type":"PING"}""")) } }
    }

    private fun receiverStatus(client: String): ByteArray {
        val current = app
        val apps = if (current == null) {
            ""
        } else {
            val namespaces = if (current.speaksMedia) """[{"name":"${CastNamespaces.MEDIA}"}]""" else "[]"
            """{"appId":"${current.appId}","displayName":"Sample App","transportId":"${current.transportId}","namespaces":$namespaces}"""
        }
        return text(RECEIVER, client, CastNamespaces.RECEIVER, """{"type":"RECEIVER_STATUS","requestId":0,"status":{"applications":[$apps]}}""")
    }

    private fun mediaStatus(current: App, client: String): ByteArray {
        val entries = media?.let { "[$it]" } ?: "[]"
        return text(current.transportId, client, CastNamespaces.MEDIA, """{"type":"MEDIA_STATUS","requestId":0,"status":$entries}""")
    }

    private fun text(source: String, destination: String, namespace: String, json: String): ByteArray =
        CastFraming.encode(CastFraming.text(source, destination, namespace, json))

    private companion object {
        const val RECEIVER = "receiver-0"
    }
}
