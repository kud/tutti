package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.pairing.PairingMessage
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.interfaces.RSAPublicKey

sealed interface PairingResult {
    /** Store [serverPin] with the client identity: it pins the TV on every later connection. */
    data class Paired(val serverPin: CertificatePin) : PairingResult

    data class Failed(val failure: PairingFailure) : PairingResult
}

/**
 * Drives [PairingMachine] over TLS on port 6467. First contact is trust-on-first-use:
 * the TV's self-signed certificate is accepted and returned as a pin once the user has
 * proven, by typing the code, that they are looking at this TV.
 */
class AtvPairing(
    private val connector: TlsConnector,
    private val serviceName: String = "tutti",
) {

    /** What went over the wire, in order (message kinds and statuses only, never the secret), for a failure report. */
    val trace: List<String> get() = synchronized(events) { events.toList() }
    private val events = mutableListOf<String>()

    private fun note(event: String) = synchronized(events) { events += event }

    /**
     * @param askCode called when the TV shows its code; `attempt` starts at 1. Return null to cancel.
     *   A mistyped code is caught locally (hash check) and asked again, up to [maxAttempts].
     */
    suspend fun pair(
        host: String,
        identity: ClientIdentity,
        maxAttempts: Int = 3,
        askCode: suspend (attempt: Int) -> String?,
    ): PairingResult {
        // The client name is the certificate's CN, never a separate value: the TV keys its pairings
        // on that name, so the two must not drift apart (see [ClientNames]).
        val machine = PairingMachine(serviceName, ClientNames.of(identity.certificate))
        val connection = connector.connect(host, PORT, identity, expectedPin = null)
        // The reader runs ahead of us and keeps reading while the person types the code, which
        // the TV waits for in silence: the remote's 20 s read timeout failed a slow typist. The
        // socket now waits as long as a person may; TV silence is timed below, only while we
        // wait on the TV, by closing the socket (which is what ends a blocked read).
        connection.setReadTimeout(PERSON_READ_TIMEOUT_MS)
        var state: PairingState = PairingState.Idle
        var silentTv = false
        try {
            coroutineScope {
                var tvTimer: Job? = null
                fun waitForTv() {
                    tvTimer?.cancel()
                    tvTimer = launch {
                        delay(TV_ANSWER_TIMEOUT_MS)
                        silentTv = true
                        note("tv silent ${TV_ANSWER_TIMEOUT_MS} ms")
                        connection.close()
                    }
                }
                try {
                    suspend fun apply(transition: PairingTransition) {
                        state = transition.state
                        transition.send.forEach {
                            note("tx ${kindOf(it)}")
                            connection.send(Framing.frame(it.encode()))
                        }
                    }
                    apply(machine.reduce(state, PairingInput.Start))
                    waitForTv()
                    val decoder = FrameDecoder()
                    connection.incoming.collect { chunk ->
                        for (frame in decoder.feed(chunk)) {
                            if (state.isTerminal) break
                            val message = try {
                                PairingMessage.ADAPTER.decode(frame)
                            } catch (e: IOException) {
                                state = PairingState.Failed(PairingFailure.Unexpected("undecodable pairing message"))
                                break
                            }
                            note("rx ${kindOf(message)} ${message.status}")
                            apply(machine.reduce(state, PairingInput.Received(message)))
                            if (state == PairingState.AwaitingCode) {
                                tvTimer?.cancel()
                                when (val answer = askSecret(connection, maxAttempts, askCode)) {
                                    is PairingInput.Secret -> apply(machine.reduce(state, answer))
                                    is PairingFailure -> state = PairingState.Failed(answer)
                                }
                            }
                            if (!state.isTerminal) waitForTv()
                        }
                        // Stop reading as soon as the outcome is known. The TV keeps the socket open after
                        // its secret ack, and leaving collect waits for the reader, which is blocked in a read
                        // nothing will answer: close first, so that read ends and collect can return.
                        if (state.isTerminal) {
                            note("done: ${outcomeOf(state)}")
                            connection.close()
                            throw Finished()
                        }
                    }
                    note("stream ended in $state")
                } finally {
                    tvTimer?.cancel()
                }
            }
        } catch (e: Finished) {
            // Normal end.
        } catch (e: FramingException) {
            state = PairingState.Failed(PairingFailure.Unexpected(e.message ?: "framing"))
        } catch (e: IOException) {
            // Once the outcome is known we close the socket under the reader ourselves, and on a
            // real socket that read can fail before collect has left: not an error, and the trace
            // must not end on one after "done: paired".
            if (!state.isTerminal) {
                note("io ${e::class.java.simpleName} in $state")
                state = PairingState.Failed(if (silentTv) PairingFailure.Unexpected("no answer from the TV") else PairingFailure.Closed)
            }
        } finally {
            connection.close()
        }
        return when (val outcome = state) {
            PairingState.Paired -> PairingResult.Paired(CertificatePin.of(connection.peerCertificate))
            is PairingState.Failed -> PairingResult.Failed(outcome.failure)
            else -> PairingResult.Failed(PairingFailure.Closed)
        }
    }

    /** Either the secret to send, or why there is none. */
    private suspend fun askSecret(
        connection: TlsConnection,
        maxAttempts: Int,
        askCode: suspend (attempt: Int) -> String?,
    ): Any {
        val client = connection.localCertificate.publicKey as? RSAPublicKey
            ?: return PairingFailure.Unexpected("client key is not RSA")
        val server = connection.peerCertificate.publicKey as? RSAPublicKey
            ?: return PairingFailure.Unexpected("TV key is not RSA")
        for (attempt in 1..maxAttempts) {
            val code = askCode(attempt)?.trim() ?: return PairingFailure.Cancelled
            if (!PairingHash.isWellFormed(code)) continue
            PairingHash.compute(client, server, code)?.let { return PairingInput.Secret(it) }
        }
        return PairingFailure.BadCode
    }

    private fun kindOf(message: PairingMessage): String = when {
        message.pairing_request != null -> "request"
        message.pairing_request_ack != null -> "request_ack"
        message.pairing_option != null -> "option"
        message.pairing_configuration != null -> "configuration"
        message.pairing_configuration_ack != null -> "configuration_ack"
        message.pairing_secret != null -> "secret"
        message.pairing_secret_ack != null -> "secret_ack"
        else -> "empty"
    }

    private fun outcomeOf(state: PairingState): String = if (state == PairingState.Paired) "paired" else "failed"

    private class Finished : Exception()

    companion object {
        const val PORT = 6467

        /** How long the TV may stay silent while we wait on it (not on the person typing). */
        const val TV_ANSWER_TIMEOUT_MS = 10_000L

        /** Read timeout on the pairing socket: time enough to read six characters off the TV. */
        const val PERSON_READ_TIMEOUT_MS = 300_000
    }
}
