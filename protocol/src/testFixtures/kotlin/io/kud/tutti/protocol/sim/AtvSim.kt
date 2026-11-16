package io.kud.tutti.protocol.sim

import io.kud.tutti.protocol.atv.FrameDecoder
import io.kud.tutti.protocol.atv.Framing
import io.kud.tutti.protocol.atv.PairingHash
import io.kud.tutti.protocol.atv.proto.pairing.PairingConfigurationAck
import io.kud.tutti.protocol.atv.proto.pairing.PairingEncoding
import io.kud.tutti.protocol.atv.proto.pairing.PairingMessage
import io.kud.tutti.protocol.atv.proto.pairing.PairingOption
import io.kud.tutti.protocol.atv.proto.pairing.PairingRequestAck
import io.kud.tutti.protocol.atv.proto.pairing.PairingSecretAck
import io.kud.tutti.protocol.atv.proto.pairing.RoleType
import io.kud.tutti.protocol.atv.proto.remote.RemoteAppInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteConfigure
import io.kud.tutti.protocol.atv.proto.remote.RemoteDeviceInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteEditInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeBatchEdit
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeKeyInject
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeObject
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeShowRequest
import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode
import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyInject
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.atv.proto.remote.RemotePingRequest
import io.kud.tutti.protocol.atv.proto.remote.RemoteSetActive
import io.kud.tutti.protocol.atv.proto.remote.RemoteSetVolumeLevel
import io.kud.tutti.protocol.atv.proto.remote.RemoteStart
import io.kud.tutti.protocol.atv.proto.remote.RemoteTextFieldStatus
import io.kud.tutti.protocol.atv.proto.remote.RemoteVoiceBegin
import io.kud.tutti.protocol.net.ClientIdentity
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** One entry of a pairing trace: what the client sent (`tx`) or the TV answered (`rx`). */
data class TraceStep(val sent: Boolean, val kind: String, val status: String? = null) {
    override fun toString(): String = if (sent) "tx $kind" else "rx $kind ${status ?: "STATUS_OK"}"
}

/**
 * The developer-mode pairing trace ([io.kud.tutti.protocol.atv.AtvPairing.trace], shown as
 * "steps: tx request · rx request_ack STATUS_OK · …") as a script for [FakeAtvPairingTv].
 * Entries that are not a message (`done: paired`, `io …`, `tv silent …`) are not steps.
 */
object PairingTrace {
    val HAPPY: List<TraceStep> = parse(
        "tx request · rx request_ack STATUS_OK · tx option · rx option STATUS_OK · " +
            "tx configuration · rx configuration_ack STATUS_OK · tx secret · rx secret_ack STATUS_OK",
    )

    fun parse(line: String): List<TraceStep> = line.trim().removePrefix("steps:")
        .split('·')
        .map { it.trim().split(Regex("\\s+")) }
        .mapNotNull { words ->
            when (words.firstOrNull()) {
                "tx" -> TraceStep(sent = true, kind = words[1])
                "rx" -> TraceStep(sent = false, kind = words[1], status = words.getOrNull(2))
                else -> null
            }
        }

    fun render(steps: List<TraceStep>): String = steps.joinToString(" · ")
}

/**
 * The TV's pairing port (6467): TLS with a self-signed certificate, client certificate required.
 * It answers the client by [script] (a [PairingTrace]), shows a fresh code after the
 * configuration ack ([codes]), checks the secret against that code with the real hash, and
 * keeps the socket open after its secret ack, as the real TV does.
 *
 * An `rx` step's status is what the TV answers, except `secret_ack STATUS_OK`, which is the
 * TV's honest verdict: a wrong secret gets `STATUS_BAD_SECRET`.
 */
class FakeAtvPairingTv(
    identity: ClientIdentity = SimKeys.tv,
    @Volatile var script: List<TraceStep> = PairingTrace.HAPPY,
) : TlsSimServer("atv-pairing", identity, needClientAuth = true) {
    /** Every code shown on screen, in order. */
    val codes = LinkedBlockingQueue<String>()

    /** What the TV received, as trace steps. */
    val received = CopyOnWriteArrayList<TraceStep>()

    /** Messages that did not match the script. */
    val surprises = CopyOnWriteArrayList<String>()

    /** The client certificate of the last successful pairing: the remote port accepts it. */
    @Volatile var pairedClient: X509Certificate? = null

    private val random = SecureRandom()

    override fun serve(link: Link) {
        val client = link.peerCertificate ?: return
        val decoder = FrameDecoder()
        val buffer = ByteArray(4096)
        var step = 0
        var code: String? = null
        while (true) {
            val read = link.input.read(buffer)
            if (read < 0) return
            for (frame in decoder.feed(buffer.copyOf(read))) {
                val message = PairingMessage.ADAPTER.decode(frame)
                val kind = kindOf(message)
                received += TraceStep(sent = true, kind = kind)
                val expected = script.getOrNull(step)
                if (expected == null || !expected.sent || expected.kind != kind) {
                    surprises += "expected ${expected ?: "nothing"}, got tx $kind"
                    continue
                }
                step++
                if (!link.received()) continue
                while (step < script.size && !script[step].sent) {
                    val answer = script[step++]
                    var status = PairingMessage.Status.valueOf(answer.status ?: "STATUS_OK")
                    if (answer.kind == "secret_ack" && status == PairingMessage.Status.STATUS_OK) {
                        val secret = message.pairing_secret?.secret?.toByteArray()
                        val expectedSecret = code?.let { secretFor(client, it) }
                        if (secret == null || expectedSecret == null || !secret.contentEquals(expectedSecret)) {
                            status = PairingMessage.Status.STATUS_BAD_SECRET
                        } else {
                            pairedClient = client
                        }
                    }
                    link.write(Framing.frame(answerFor(answer.kind, status).encode()))
                    if (answer.kind == "configuration_ack") code = newCode(client).also { codes += it }
                }
                if (step >= script.size && faults.closeAfterLast) {
                    link.drop()
                    return
                }
            }
        }
    }

    private fun secretFor(client: X509Certificate, code: String): ByteArray? =
        PairingHash.compute(client.publicKey as RSAPublicKey, identity.certificate.publicKey as RSAPublicKey, code)

    /** A code whose first byte checks out against the hash, as the TV's does. */
    private fun newCode(client: X509Certificate): String {
        val tail = "%04X".format(random.nextInt(0x10000))
        for (first in 0..0xff) {
            val code = "%02X".format(first) + tail
            if (secretFor(client, code) != null) return code
        }
        error("no code for $tail")
    }

    private fun answerFor(kind: String, status: PairingMessage.Status): PairingMessage {
        val hex6 = PairingEncoding(type = PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6)
        return when (kind) {
            "request_ack" -> PairingMessage(protocol_version = 2, status = status, pairing_request_ack = PairingRequestAck(server_name = "BRAVIA simulator"))
            "option" -> PairingMessage(
                protocol_version = 2,
                status = status,
                pairing_option = PairingOption(input_encodings = listOf(hex6), preferred_role = RoleType.ROLE_TYPE_INPUT),
            )
            "configuration_ack" -> PairingMessage(protocol_version = 2, status = status, pairing_configuration_ack = PairingConfigurationAck())
            "secret_ack" -> PairingMessage(protocol_version = 2, status = status, pairing_secret_ack = PairingSecretAck())
            else -> PairingMessage(protocol_version = 2, status = status)
        }
    }

    private fun kindOf(message: PairingMessage): String = when {
        message.pairing_request != null -> "request"
        message.pairing_option != null -> "option"
        message.pairing_configuration != null -> "configuration"
        message.pairing_secret != null -> "secret"
        else -> "other"
    }
}

/**
 * The TV's remote port (6466): pinned TLS, client certificate required and checked against
 * [pairedClient] (a TV that forgot the pairing drops the link). It opens with its configure,
 * expects the client's configure, sends set-active, then the power state and volume, and pings
 * every [pingMillis]. Keys, app links and pongs are recorded.
 *
 * Beyond the handshake it plays the TV's side of the rest of the session: the app in front
 * ([foreground]), a focused text field ([focusField]) that takes a batch edit only with its own
 * input-session counter and echoes its whole text back, after [echoDelayMillis] (a late echo is
 * what looped "ph" into "pphppph…"), its own volume reports ([reportVolume]), and voice: the
 * search key opens a session when [features] carries bit 3 and [answersVoice] is on.
 */
class FakeAtvRemoteTv(
    identity: ClientIdentity = SimKeys.tv,
    @Volatile var pairedClient: X509Certificate? = null,
    @Volatile var pingMillis: Long = 5_000,
) : TlsSimServer("atv-remote", identity, needClientAuth = true) {
    @Volatile var powered: Boolean = true
    val keys = LinkedBlockingQueue<RemoteKeyInject>()
    val appLinks = LinkedBlockingQueue<String>()
    val pongs = AtomicInteger()
    val configured = AtomicInteger()

    /** The TV's features in its configure: bit 3 is voice. */
    @Volatile var features: Int = 622

    /** The focused field's input session and field counters, as the TV numbers them. */
    @Volatile var imeCounter: Int = 7
    @Volatile var fieldCounter: Int = 3

    /** The focused field's text; null while no field has focus. */
    @Volatile var field: String? = null

    /** Texts the field took from batch edits, in order, and those refused for a stale counter. */
    val taken = CopyOnWriteArrayList<String>()
    val refused = CopyOnWriteArrayList<String>()

    /** How long the TV takes to echo a batch edit back: 0 answers at once. */
    @Volatile var echoDelayMillis: Long = 0

    /** Whether the search key opens a voice session (the TV also needs [features] bit 3). */
    @Volatile var answersVoice: Boolean = true
    private val voiceIds = AtomicInteger(40)

    /** Voice traffic from the client: echoed begins, payload bytes, ends, by session id. */
    val voiceBegins = CopyOnWriteArrayList<Int>()
    val voiceBytes = AtomicInteger()
    val voiceEnds = CopyOnWriteArrayList<Int>()

    override fun serve(link: Link) {
        val client = link.peerCertificate ?: return
        val paired = pairedClient
        if (paired != null && paired != client) return
        link.write(frame(RemoteMessage(remote_configure = RemoteConfigure(code1 = features, device_info = RemoteDeviceInfo(model = "BRAVIA simulator", vendor = "Sim")))))
        val decoder = FrameDecoder()
        val buffer = ByteArray(4096)
        while (true) {
            val read = link.input.read(buffer)
            if (read < 0) return
            for (frame in decoder.feed(buffer.copyOf(read))) {
                val message = RemoteMessage.ADAPTER.decode(frame)
                when {
                    message.remote_ping_response != null -> pongs.incrementAndGet()
                    message.remote_key_inject != null -> keys += message.remote_key_inject
                    message.remote_app_link_launch_request != null -> appLinks += message.remote_app_link_launch_request.app_link
                    message.remote_voice_begin != null -> voiceBegins += message.remote_voice_begin.session_id
                    message.remote_voice_payload != null -> voiceBytes.addAndGet(message.remote_voice_payload.samples.size)
                    message.remote_voice_end != null -> voiceEnds += message.remote_voice_end.session_id
                }
                if (!link.received()) continue
                message.remote_ime_batch_edit?.let { edit(link, it) }
                val key = message.remote_key_inject
                if (key?.key_code == RemoteKeyCode.KEYCODE_SEARCH && answersVoice && features and VOICE_BIT != 0) {
                    link.write(frame(RemoteMessage(remote_voice_begin = RemoteVoiceBegin(session_id = voiceIds.incrementAndGet(), package_name = "com.example.assistant"))))
                }
                when {
                    message.remote_configure != null -> {
                        configured.incrementAndGet()
                        link.write(frame(RemoteMessage(remote_set_active = RemoteSetActive())))
                    }
                    message.remote_set_active != null -> {
                        link.write(frame(RemoteMessage(remote_start = RemoteStart(started = powered))))
                        link.write(frame(RemoteMessage(remote_set_volume_level = RemoteSetVolumeLevel(volume_max = 100, volume_level = 12))))
                        startPinging(link)
                    }
                }
            }
        }
    }

    /** A batch edit: taken only with the field's own input-session counter, then echoed whole. */
    private fun edit(link: Link, edit: RemoteImeBatchEdit) {
        val value = edit.edit_info.lastOrNull()?.text_field_status?.value_ ?: return
        if (field == null || edit.ime_counter != imeCounter) {
            refused += value
            return
        }
        field = value
        taken += value
        val echo = echo(value)
        val delay = echoDelayMillis
        if (delay <= 0) {
            link.write(echo)
        } else {
            // One thread, so late echoes still leave in the order the edits came, as on one socket.
            echoes.schedule({ runCatching { link.write(echo) } }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private val echoes = Executors.newSingleThreadScheduledExecutor { Thread(it, "atv-remote-echo").apply { isDaemon = true } }

    private fun echo(text: String): ByteArray = frame(
        RemoteMessage(
            remote_ime_batch_edit = RemoteImeBatchEdit(
                ime_counter = imeCounter,
                field_counter = fieldCounter,
                edit_info = listOf(RemoteEditInfo(insert = 1, text_field_status = RemoteImeObject(text.length, text.length, text))),
            ),
        ),
    )

    private fun tell(message: RemoteMessage) {
        links.forEach { link -> runCatching { link.write(frame(message)) } }
    }

    /** The TV brings [app] to the front: its keyboard service says so, and any field loses focus. */
    fun foreground(app: String) {
        field = null
        tell(RemoteMessage(remote_ime_key_inject = RemoteImeKeyInject(app_info = RemoteAppInfo(app_package = app))))
    }

    /**
     * A text field gets focus in [app]: the TV's keyboard starts on it (a key inject carrying the
     * counters) and shows it (a show request with its text and [label]). Without [show], only
     * the keyboard starts, as when the phone types before the TV reports a field.
     */
    fun focusField(app: String, label: String = "Search", text: String = "", show: Boolean = true) {
        imeCounter++
        fieldCounter++
        field = text
        tell(
            RemoteMessage(
                remote_ime_key_inject = RemoteImeKeyInject(
                    app_info = RemoteAppInfo(counter = imeCounter, app_package = app),
                    text_field_status = RemoteTextFieldStatus(counter_field = fieldCounter, value_ = text),
                ),
            ),
        )
        if (!show) return
        tell(
            RemoteMessage(
                remote_ime_show_request = RemoteImeShowRequest(
                    remote_text_field_status = RemoteTextFieldStatus(counter_field = fieldCounter, value_ = text, start = text.length, end = text.length, label = label),
                ),
            ),
        )
    }

    /** Text typed on the TV's own keyboard: the field changes and every client hears it. */
    fun typeOnTv(text: String) {
        field = text
        tell(RemoteMessage(remote_ime_batch_edit = RemoteImeBatchEdit(ime_counter = imeCounter, field_counter = fieldCounter, edit_info = listOf(RemoteEditInfo(insert = 1, text_field_status = RemoteImeObject(text.length, text.length, text))))))
    }

    /** The TV's own volume changed (its remote, CEC): told to every client. */
    fun reportVolume(level: Int, max: Int = 100, muted: Boolean = false) {
        tell(RemoteMessage(remote_set_volume_level = RemoteSetVolumeLevel(volume_max = max, volume_level = level, volume_muted = muted)))
    }

    /** The power button on the TV itself, or its own standby timer: told to every client. */
    fun power(on: Boolean) {
        powered = on
        links.forEach { link -> runCatching { link.write(frame(RemoteMessage(remote_start = RemoteStart(started = on)))) } }
    }

    private fun startPinging(link: Link) {
        thread(isDaemon = true, name = "atv-remote-ping") {
            var n = 0
            while (!link.raw.isClosed) {
                Thread.sleep(pingMillis)
                if (faults.silent) continue
                if (runCatching { link.write(frame(RemoteMessage(remote_ping_request = RemotePingRequest(val1 = ++n)))) }.isFailure) return@thread
            }
        }
    }

    private fun frame(message: RemoteMessage): ByteArray = Framing.frame(message.encode())

    private companion object {
        const val VOICE_BIT = 8
    }
}
