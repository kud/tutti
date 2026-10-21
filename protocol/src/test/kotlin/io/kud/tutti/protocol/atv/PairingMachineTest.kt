package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.pairing.PairingConfigurationAck
import io.kud.tutti.protocol.atv.proto.pairing.PairingMessage
import io.kud.tutti.protocol.atv.proto.pairing.PairingOption
import io.kud.tutti.protocol.atv.proto.pairing.PairingRequestAck
import io.kud.tutti.protocol.atv.proto.pairing.PairingSecretAck
import io.kud.tutti.protocol.atv.proto.pairing.RoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PairingMachineTest {
    private val machine = PairingMachine("tutti", "Pixel")
    private val ok = PairingMessage.Status.STATUS_OK

    private fun received(message: PairingMessage) = PairingInput.Received(message)

    @Test
    fun `walks request, option, configuration, code, secret`() {
        var t = machine.reduce(PairingState.Idle, PairingInput.Start)
        assertEquals(PairingState.AwaitingAck(PairingStep.REQUEST), t.state)
        assertEquals("tutti", t.send.single().pairing_request?.service_name)
        assertEquals(2, t.send.single().protocol_version)

        t = machine.reduce(t.state, received(PairingMessage(status = ok, pairing_request_ack = PairingRequestAck(server_name = "BRAVIA"))))
        assertNotNull(t.send.single().pairing_option)
        assertEquals(RoleType.ROLE_TYPE_INPUT, t.send.single().pairing_option?.preferred_role)

        t = machine.reduce(t.state, received(PairingMessage(status = ok, pairing_option = PairingOption())))
        assertEquals(6, t.send.single().pairing_configuration?.encoding?.symbol_length)

        t = machine.reduce(t.state, received(PairingMessage(status = ok, pairing_configuration_ack = PairingConfigurationAck())))
        assertEquals(PairingState.AwaitingCode, t.state)
        assertEquals(0, t.send.size)

        t = machine.reduce(t.state, PairingInput.Secret(byteArrayOf(1, 2, 3)))
        assertEquals(3, t.send.single().pairing_secret?.secret?.size)

        t = machine.reduce(t.state, received(PairingMessage(status = ok, pairing_secret_ack = PairingSecretAck())))
        assertEquals(PairingState.Paired, t.state)
    }

    @Test
    fun `bad secret status means a wrong code`() {
        val state = PairingState.AwaitingAck(PairingStep.SECRET)
        val t = machine.reduce(state, received(PairingMessage(status = PairingMessage.Status.STATUS_BAD_SECRET)))
        assertEquals(PairingState.Failed(PairingFailure.BadCode), t.state)
    }

    @Test
    fun `error status and out-of-order messages fail`() {
        val error = machine.reduce(PairingState.AwaitingAck(PairingStep.REQUEST), received(PairingMessage(status = PairingMessage.Status.STATUS_ERROR)))
        assertEquals(PairingState.Failed(PairingFailure.Status(400)), error.state)

        val outOfOrder = machine.reduce(PairingState.AwaitingAck(PairingStep.REQUEST), received(PairingMessage(status = ok, pairing_secret_ack = PairingSecretAck())))
        assertIs<PairingState.Failed>(outOfOrder.state)
    }

    @Test
    fun `terminal states absorb everything`() {
        val t = machine.reduce(PairingState.Paired, PairingInput.Start)
        assertEquals(PairingState.Paired, t.state)
        assertEquals(0, t.send.size)
    }
}
