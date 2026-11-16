package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.protocol.theatre.Pending
import io.kud.tutti.protocol.theatre.TheatreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinToggleTest {
    private val night = DockAction.ToggleBar("audio.nightmode")
    private val live = TheatreState(connected = true)

    @Test
    fun `a pin that is not a bar toggle has no state`() {
        assertNull(pinToggle(DockAction.SetBar("audio.nightmode", "on"), live, barAdded = true))
        assertNull(pinToggle(null, live, barAdded = true))
        assertNull(pinToggle(DockAction.ToggleBar("not.a.feature"), live, barAdded = true))
    }

    @Test
    fun `a reported value shows as on or off`() {
        assertEquals(PinToggle.Known(on = true, pending = false), pinToggle(night, live.copy(confirmed = mapOf("audio.nightmode" to "on")), true))
        assertEquals(PinToggle.Known(on = false, pending = false), pinToggle(night, live.copy(confirmed = mapOf("audio.nightmode" to "off")), true))
    }

    @Test
    fun `a pending value shows at once, marked pending`() {
        val state = live.copy(
            confirmed = mapOf("audio.nightmode" to "off"),
            pending = mapOf("audio.nightmode" to Pending(requestId = 1, value = "on", deadline = 0L)),
        )
        assertEquals(PinToggle.Known(on = true, pending = true), pinToggle(night, state, true))
    }

    @Test
    fun `an unreported value is unknown, never off`() {
        assertEquals(PinToggle.Unknown, pinToggle(night, live, true))
        assertEquals(PinToggle.Unknown, pinToggle(night, live.copy(confirmed = mapOf("audio.nightmode" to "weird")), true))
    }

    @Test
    fun `a dropped bar is unreachable even with a remembered value`() {
        val stale = TheatreState(connected = false, confirmed = mapOf("audio.nightmode" to "on"))
        assertEquals(PinToggle.Unreachable, pinToggle(night, stale, true))
    }

    @Test
    fun `no bar added says so`() {
        assertEquals(PinToggle.NoBar, pinToggle(night, live, barAdded = false))
    }
}
