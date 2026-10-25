package io.kud.tutti.controller

import io.kud.tutti.platform.data.BarDevice
import io.kud.tutti.platform.data.Devices
import io.kud.tutti.platform.data.TvDevice
import io.kud.tutti.quick.ControllerQuickControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLinksTest {
    @Test
    fun `a stopped session leaves no link up and none trying, and keeps verdicts`() {
        assertEquals(Link.UNREACHABLE, Link.ONLINE.stopped())
        assertEquals(Link.UNREACHABLE, Link.CONNECTING.stopped())
        assertEquals(Link.NEEDS_PAIRING, Link.NEEDS_PAIRING.stopped())
        assertEquals(Link.UNAUTHORIZED, Link.UNAUTHORIZED.stopped())
        assertEquals(Link.ABSENT, Link.ABSENT.stopped())
    }

    @Test
    fun `a new epoch asks every live or failed link again`() {
        val old = Links(tv = Link.ONLINE, rest = Link.UNAUTHORIZED, bar = Link.UNREACHABLE, epoch = 3)
        val fresh = old.restarted(4)
        assertEquals(Links(tv = Link.CONNECTING, rest = Link.UNAUTHORIZED, bar = Link.CONNECTING, epoch = 4), fresh)
    }

    private val both = Devices(tv = TvDevice("192.168.1.20"), bar = BarDevice("192.168.1.21"))

    @Test
    fun `Power waits for power to be heard while a link is still trying`() {
        val connecting = TuttiUiState(devices = both, links = Links(tv = Link.CONNECTING, bar = Link.ONLINE))
        assertFalse(ControllerQuickControls.powerSettled(connecting))
        assertTrue(ControllerQuickControls.powerSettled(connecting.copy(powered = false)))
    }

    @Test
    fun `Power goes ahead (and wakes) once no configured link is trying`() {
        val tvAsleep = TuttiUiState(devices = both.copy(bar = null), links = Links(tv = Link.UNREACHABLE, bar = Link.ABSENT))
        assertTrue(ControllerQuickControls.powerSettled(tvAsleep))
    }
}
