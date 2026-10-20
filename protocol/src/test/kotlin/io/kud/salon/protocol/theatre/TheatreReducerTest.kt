package io.kud.salon.protocol.theatre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TheatreReducerTest {
    private val volume = TheatreFeature.VOLUME.key

    private fun TheatreState.on(vararg events: TheatreEvent): TheatreState =
        events.fold(this) { state, event -> TheatreReducer.reduce(state, event) }

    private fun set(id: Int, value: String, now: Long = 0) = TheatreEvent.Sent(
        TheatreOutbound.SetRequest(id, volume, value, numeric = true),
        now = now,
        timeoutMillis = 1_000,
    )

    private fun result(id: Int, value: String, feature: String? = null) =
        TheatreEvent.Received(TheatreInbound.Result(id, feature, value))

    private fun notify(value: String) = TheatreEvent.Received(TheatreInbound.Notify(volume, value))

    private val start = TheatreState(connected = true, confirmed = mapOf(volume to "20"))

    @Test
    fun `optimistic set shows at once and is confirmed by the ack`() {
        val sent = start.on(set(1, "30"))
        assertEquals("30", sent.value(TheatreFeature.VOLUME))
        assertTrue(sent.isPending(TheatreFeature.VOLUME))

        val acked = sent.on(result(1, "ACK"))
        assertEquals("30", acked.value(TheatreFeature.VOLUME))
        assertEquals("30", acked.confirmed[volume])
        assertFalse(acked.isPending(TheatreFeature.VOLUME))
        assertTrue(acked.outstanding.isEmpty())
    }

    @Test
    fun `a notify arriving before the ack reconciles, and the late ack is harmless`() {
        val notified = start.on(set(1, "30"), notify("30"))
        assertFalse(notified.isPending(TheatreFeature.VOLUME))
        assertEquals("30", notified.confirmed[volume])

        val acked = notified.on(result(1, "ACK"))
        assertEquals("30", acked.value(TheatreFeature.VOLUME))
        assertTrue(acked.outstanding.isEmpty())
    }

    @Test
    fun `a notify with another value keeps the newer intent pending`() {
        val state = start.on(set(1, "30"), notify("25"))
        assertEquals("25", state.confirmed[volume])
        assertEquals("30", state.value(TheatreFeature.VOLUME))
    }

    @Test
    fun `timeout rolls back to the confirmed value`() {
        val state = start.on(set(1, "30", now = 0), TheatreEvent.Tick(999))
        assertEquals("30", state.value(TheatreFeature.VOLUME))

        val expired = state.on(TheatreEvent.Tick(1_000))
        assertEquals("20", expired.value(TheatreFeature.VOLUME))
        assertTrue(expired.outstanding.isEmpty())
        assertNotNull(expired.lastError)
    }

    @Test
    fun `a refused set rolls back`() {
        val state = start.on(set(1, "30"), result(1, "NACK"))
        assertEquals("20", state.value(TheatreFeature.VOLUME))
        assertNotNull(state.lastError)
    }

    @Test
    fun `a superseded set does not clobber the newer pending value`() {
        val state = start.on(set(1, "30"), set(2, "35"), result(1, "ACK"))
        assertEquals("30", state.confirmed[volume])
        assertEquals("35", state.value(TheatreFeature.VOLUME))

        val refusedOld = start.on(set(1, "30"), set(2, "35"), result(1, "NACK"))
        assertEquals("35", refusedOld.value(TheatreFeature.VOLUME))
    }

    @Test
    fun `get results fill confirmed state`() {
        val state = TheatreState(connected = true).on(
            TheatreEvent.Sent(TheatreOutbound.GetRequest(7, "audio.nightmode"), 0, 1_000),
            result(7, "on", feature = "audio.nightmode"),
        )
        assertEquals(true, state.isOn(TheatreFeature.NIGHT))
    }

    @Test
    fun `voice enhancer uses its own toggle vocabulary`() {
        val state = TheatreState(confirmed = mapOf(TheatreFeature.VOICE.key to "upon"))
        assertEquals(true, state.isOn(TheatreFeature.VOICE))
        assertEquals("upoff", TheatreFeature.VOICE.toggleValue(false))
    }

    @Test
    fun `disconnect drops every pending value`() {
        val state = start.on(set(1, "30"), TheatreEvent.Connected(false))
        assertFalse(state.connected)
        assertEquals("20", state.value(TheatreFeature.VOLUME))
    }

    @Test
    fun `send failure rolls back that request only`() {
        val state = start.on(set(1, "30"), TheatreEvent.SendFailed(1, "broken pipe"))
        assertEquals("20", state.value(TheatreFeature.VOLUME))
        assertNull(state.outstanding[1])
    }

    @Test
    fun `malformed input leaves state untouched`() {
        val state = start.on(set(1, "30"))
        assertEquals(state, state.on(TheatreEvent.Received(TheatreInbound.Malformed("x", "not JSON"))))
    }
}
