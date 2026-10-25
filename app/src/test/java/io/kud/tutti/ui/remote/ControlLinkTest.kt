package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.protocol.atv.V2Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlLinkTest {

    private fun link(
        owner: Owner,
        tvShown: Link = Link.ONLINE,
        barConnected: Boolean = true,
        barLink: Link = Link.ONLINE,
        tvAdded: Boolean = true,
        barAdded: Boolean = true,
    ) = controlLink(owner, tvAdded, tvShown, barAdded, barConnected, barLink)

    @Test
    fun tvControlsFollowTheTvsShownLink() {
        assertEquals(ControlLink.NORMAL, link(Owner.TV, tvShown = Link.ONLINE))
        assertEquals(ControlLink.CONNECTING, link(Owner.TV, tvShown = Link.CONNECTING))
        assertEquals(ControlLink.CONNECTING, link(Owner.TV, tvShown = Link.ABSENT))
        assertEquals(ControlLink.UNREACHABLE, link(Owner.TV, tvShown = Link.UNREACHABLE))
        assertEquals(ControlLink.UNREACHABLE, link(Owner.TV, tvShown = Link.NEEDS_PAIRING))
        assertEquals(ControlLink.UNREACHABLE, link(Owner.TV, tvShown = Link.UNAUTHORIZED))
    }

    @Test
    fun barControlsFollowTheBar() {
        assertEquals(ControlLink.NORMAL, link(Owner.BAR))
        assertEquals(ControlLink.CONNECTING, link(Owner.BAR, barConnected = false, barLink = Link.CONNECTING))
        assertEquals(ControlLink.CONNECTING, link(Owner.BAR, barConnected = false, barLink = Link.ABSENT))
        assertEquals(ControlLink.UNREACHABLE, link(Owner.BAR, barConnected = false, barLink = Link.UNREACHABLE))
    }

    @Test
    fun connectingAndUnreachableAreBothDown() {
        assertFalse(ControlLink.NORMAL.down)
        assertTrue(ControlLink.CONNECTING.down)
        assertTrue(ControlLink.UNREACHABLE.down)
    }

    @Test
    fun eachDeviceOnlyTakesDownItsOwnControls() {
        // Erwann's screenshot: the TV out of reach, the bar connected. The bar's controls stay normal.
        assertEquals(ControlLink.NORMAL, link(Owner.BAR, tvShown = Link.UNREACHABLE))
        assertEquals(ControlLink.NORMAL, link(Owner.TV, barConnected = false, barLink = Link.UNREACHABLE))
    }

    @Test
    fun anyIsNeverDown() {
        for (tv in Link.entries) {
            for (bar in Link.entries) {
                assertEquals(ControlLink.NORMAL, link(Owner.ANY, tvShown = tv, barConnected = false, barLink = bar))
            }
        }
    }

    @Test
    fun aDeviceNotAddedHasNothingToWaitFor() {
        assertEquals(ControlLink.NORMAL, link(Owner.TV, tvShown = Link.ABSENT, tvAdded = false))
        assertEquals(ControlLink.NORMAL, link(Owner.BAR, barConnected = false, barLink = Link.ABSENT, barAdded = false))
    }

    @Test
    fun dockPinsBelongToTheDeviceTheyTalkTo() {
        assertEquals(Owner.TV, dockOwner(DockAction.LaunchApp("com.example", "Example")))
        assertEquals(Owner.TV, dockOwner(DockAction.Key(V2Key.HOME)))
        assertEquals(Owner.TV, dockOwner(DockAction.Input("extInput:hdmi?port=1", "HDMI 1")))
        assertEquals(Owner.BAR, dockOwner(DockAction.ToggleBar("nightmode")))
        assertEquals(Owner.BAR, dockOwner(DockAction.SetBar("soundfield", "on")))
        // Waking the TV is exactly when it is out of reach; scenes and macros wake and mix devices.
        assertEquals(Owner.ANY, dockOwner(DockAction.Key(V2Key.POWER)))
        assertEquals(Owner.ANY, dockOwner(DockAction.RunScene("cinema")))
        assertEquals(Owner.ANY, dockOwner(DockAction.Macro("evening", emptyList())))
        assertEquals(Owner.ANY, dockOwner(null))
    }
}
