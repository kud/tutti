package io.kud.salon.protocol.atv

import io.kud.salon.protocol.atv.proto.pairing.PairingMessage

enum class PairingStep { REQUEST, OPTION, CONFIGURATION, SECRET }

sealed interface PairingFailure {
    /** The TV answered with a non-OK status. */
    data class Status(val code: Int) : PairingFailure

    /** The TV rejected the secret, or the code did not match locally. */
    data object BadCode : PairingFailure

    data class Unexpected(val detail: String) : PairingFailure

    /** The connection closed before pairing finished. */
    data object Closed : PairingFailure

    data object Cancelled : PairingFailure
}

sealed interface PairingState {
    data object Idle : PairingState

    data class AwaitingAck(val step: PairingStep) : PairingState

    /** The TV is showing its code; the user must type it. */
    data object AwaitingCode : PairingState

    data object Paired : PairingState

    data class Failed(val failure: PairingFailure) : PairingState

    val isTerminal: Boolean get() = this is Paired || this is Failed
}

sealed interface PairingInput {
    data object Start : PairingInput

    data class Received(val message: PairingMessage) : PairingInput

    /** The secret computed by [PairingHash] from the code the user typed. */
    data class Secret(val secret: ByteArray) : PairingInput {
        override fun equals(other: Any?): Boolean = other is Secret && other.secret.contentEquals(secret)

        override fun hashCode(): Int = secret.contentHashCode()
    }
}

data class PairingTransition(val state: PairingState, val send: List<PairingMessage> = emptyList())

/**
 * Port 6467: PairingRequest → Ack, Option → Option, Configuration → Ack, then the TV shows a
 * code, then Secret → SecretAck. Pure: the driver ([AtvPairing]) owns the socket.
 */
class PairingMachine(private val serviceName: String, private val clientName: String) {
    fun reduce(state: PairingState, input: PairingInput): PairingTransition {
        if (state.isTerminal) return PairingTransition(state)
        return when (input) {
            PairingInput.Start ->
                if (state == PairingState.Idle) {
                    PairingTransition(PairingState.AwaitingAck(PairingStep.REQUEST), listOf(PairingMessages.request(serviceName, clientName)))
                } else {
                    PairingTransition(state)
                }
            is PairingInput.Secret ->
                if (state == PairingState.AwaitingCode) {
                    PairingTransition(PairingState.AwaitingAck(PairingStep.SECRET), listOf(PairingMessages.secret(input.secret)))
                } else {
                    PairingTransition(state)
                }
            is PairingInput.Received -> received(state, input.message)
        }
    }

    private fun received(state: PairingState, message: PairingMessage): PairingTransition {
        when (message.status) {
            PairingMessage.Status.STATUS_OK -> Unit
            PairingMessage.Status.STATUS_BAD_SECRET -> return failed(PairingFailure.BadCode)
            else -> return failed(PairingFailure.Status(message.status.value))
        }
        val step = (state as? PairingState.AwaitingAck)?.step
        return when {
            step == PairingStep.REQUEST && message.pairing_request_ack != null ->
                PairingTransition(PairingState.AwaitingAck(PairingStep.OPTION), listOf(PairingMessages.option()))
            step == PairingStep.OPTION && message.pairing_option != null ->
                PairingTransition(PairingState.AwaitingAck(PairingStep.CONFIGURATION), listOf(PairingMessages.configuration()))
            step == PairingStep.CONFIGURATION && message.pairing_configuration_ack != null ->
                PairingTransition(PairingState.AwaitingCode)
            step == PairingStep.SECRET && message.pairing_secret_ack != null ->
                PairingTransition(PairingState.Paired)
            else -> failed(PairingFailure.Unexpected("message out of order in $state"))
        }
    }

    private fun failed(failure: PairingFailure) = PairingTransition(PairingState.Failed(failure))
}
