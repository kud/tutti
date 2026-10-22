package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.pairing.PairingMessage
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
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
    serviceName: String = "tutti",
    clientName: String = "tutti",
) {
    private val machine = PairingMachine(serviceName, clientName)

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
        val connection = connector.connect(host, PORT, identity, expectedPin = null)
        var state: PairingState = PairingState.Idle
        try {
            suspend fun apply(transition: PairingTransition) {
                state = transition.state
                transition.send.forEach {
                    note("tx ${kindOf(it)}")
                    connection.send(Framing.frame(it.encode()))
                }
            }
            apply(machine.reduce(state, PairingInput.Start))
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
                        when (val answer = askSecret(connection, maxAttempts, askCode)) {
                            is PairingInput.Secret -> apply(machine.reduce(state, answer))
                            is PairingFailure -> state = PairingState.Failed(answer)
                        }
                    }
                }
                // Stop reading as soon as the outcome is known; the TV may keep the socket open.
                if (state.isTerminal) throw Finished()
            }
            note("stream ended in $state")
        } catch (e: Finished) {
            // Normal end.
        } catch (e: FramingException) {
            state = PairingState.Failed(PairingFailure.Unexpected(e.message ?: "framing"))
        } catch (e: IOException) {
            note("io ${e::class.java.simpleName} in $state")
            if (!state.isTerminal) state = PairingState.Failed(PairingFailure.Closed)
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

    private class Finished : Exception()

    companion object {
        const val PORT = 6467
    }
}
