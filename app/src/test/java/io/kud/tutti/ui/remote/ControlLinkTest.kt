package io.kud.tutti.ui.remote

import io.kud.tutti.controller.Link
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.MacroStep
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneKey
import io.kud.tutti.protocol.scenes.SceneValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Waking the TV is exactly when it is out of reach.
        assertEquals(Owner.ANY, dockOwner(DockAction.Key(V2Key.POWER)))
        assertEquals(Owner.ANY, dockOwner(null))
        // Apps and Keys open sheets of the TV's apps, inputs and keys: nothing in them acts without it.
        assertEquals(Owner.TV_EITHER, dockOwner(DockAction.OpenApps))
        assertEquals(Owner.TV, dockOwner(DockAction.OpenKeys))
    }

    private val cinema = Scene(
        id = "cinema",
        name = "Cinema",
        color = 0,
        glyph = "movie",
        settings = mapOf(
            SceneKey.TV_INPUT to SceneValue.Text("extInput:hdmi?port=1"),
            SceneKey.SOUND_FIELD to SceneValue.Flag(true),
        ),
    )
    private val barOnly = Scene("night", "Night", 0, "bedtime", mapOf(SceneKey.NIGHT to SceneValue.Flag(true), SceneKey.VOLUME_CAP to SceneValue.Level(20)))
    private val appOnly = Scene("yt", "YouTube", 0, "play", launchApp = "com.example.tv")
    private val targetOnly = Scene("mac", "Mac", 0, "laptop", mapOf(SceneKey.TARGET to SceneValue.Text("mac")))
    private val scenes = listOf(cinema, barOnly, appOnly, targetOnly).associateBy { it.id }

    @Test
    fun aSceneNeedsEveryDeviceItSets() {
        assertEquals(setOf(Owner.TV_EITHER, Owner.BAR), sceneOwners(cinema))
        // The cap is tutti's own ceiling; the bar's night mode needs the bar.
        assertEquals(setOf(Owner.BAR), sceneOwners(barOnly))
        // A final app is launched on the TV.
        assertEquals(setOf(Owner.TV_EITHER), sceneOwners(appOnly))
        // Where the keys go is tutti's own: a scene of that alone needs no device.
        assertEquals(emptySet<Owner>(), sceneOwners(targetOnly))
        // Every key is placed somewhere (exhaustive in code; this pins the TV/bar split).
        assertEquals(
            setOf(SceneKey.TV_INPUT, SceneKey.TV_APP, SceneKey.PICTURE_MODE, SceneKey.TV_AUDIO_OUTPUT, SceneKey.PICTURE_OFF),
            SceneKey.entries.filter { sceneKeyOwner(it) == Owner.TV_EITHER }.toSet(),
        )
    }

    @Test
    fun scenePinsAndMacrosNeedEveryDeviceTheyTouch() {
        assertEquals(setOf(Owner.TV_EITHER, Owner.BAR), dockOwners(DockAction.RunScene("cinema"), scenes::get))
        assertEquals(setOf(Owner.BAR), dockOwners(DockAction.RunScene("night"), scenes::get))
        // A pinned scene since deleted does nothing on any device: nothing to wait for.
        assertEquals(emptySet<Owner>(), dockOwners(DockAction.RunScene("gone"), scenes::get))
        val evening = DockAction.Macro(
            "evening",
            listOf(
                MacroStep.Run(DockAction.Key(V2Key.POWER)),
                MacroStep.Wait(2_000),
                MacroStep.Run(DockAction.ToggleBar("nightmode")),
            ),
        )
        // Power wakes, so only the bar half can hold the macro back.
        assertEquals(setOf(Owner.BAR), dockOwners(evening, scenes::get))
        val launch = DockAction.Macro("tv", listOf(MacroStep.Run(DockAction.LaunchApp("com.example", "Example"))))
        assertEquals(setOf(Owner.TV), dockOwners(launch, scenes::get))
        // A macro inside a macro never runs, so it needs nothing.
        assertEquals(emptySet<Owner>(), dockOwners(DockAction.Macro("outer", listOf(MacroStep.Run(launch))), scenes::get))
        assertEquals(emptySet<Owner>(), dockOwners(DockAction.Key(V2Key.POWER), scenes::get))
        assertEquals(setOf(Owner.TV_EITHER), dockOwners(DockAction.OpenApps, scenes::get))
        assertEquals(setOf(Owner.TV), dockOwners(DockAction.OpenKeys, scenes::get))
    }

    /** Every combination of the two links, against what each kind of control needs. */
    @Test
    fun aControlIsDownWhenAnyDeviceItNeedsIs() {
        val states = listOf(ControlLink.NORMAL, ControlLink.CONNECTING, ControlLink.UNREACHABLE)
        for (tv in states) {
            for (bar in states) {
                val links = mapOf(Owner.TV to tv, Owner.BAR to bar)
                fun down(owners: Set<Owner>) = downOwner(owners) { links.getValue(it) }
                // Everything online: nothing changes, nothing is down.
                if (tv == ControlLink.NORMAL && bar == ControlLink.NORMAL) {
                    assertNull(down(setOf(Owner.TV, Owner.BAR)))
                }
                assertEquals(if (tv.down) Owner.TV to tv else null, down(setOf(Owner.TV)))
                assertEquals(if (bar.down) Owner.BAR to bar else null, down(setOf(Owner.BAR)))
                // A scene of both: the TV is blamed first, then the bar.
                val both = down(setOf(Owner.TV, Owner.BAR))
                when {
                    tv.down -> assertEquals(Owner.TV to tv, both)
                    bar.down -> assertEquals(Owner.BAR to bar, both)
                    else -> assertNull(both)
                }
                // Nothing needed: never down (Power, a scene of the target alone).
                assertNull(down(emptySet()))
            }
        }
    }

    /** The TV over either plane: the remote link down is not enough while IP control answers. */
    @Test
    fun eitherPlaneKeepsInputsAppsAndScenesAlive() {
        for (shown in listOf(Link.ONLINE, Link.CONNECTING, Link.UNREACHABLE)) {
            for (ip in listOf(true, false)) {
                val either = controlLink(Owner.TV_EITHER, tvAdded = true, tvShown = shown, barAdded = true, barConnected = true, barLink = Link.ONLINE, ipControlOnline = ip)
                val remote = controlLink(Owner.TV, tvAdded = true, tvShown = shown, barAdded = true, barConnected = true, barLink = Link.ONLINE, ipControlOnline = ip)
                // IP control never lifts a remote-link key (D-pad, Keys, the Input key's long press).
                assertEquals(remote, controlLink(Owner.TV, true, shown, true, true, Link.ONLINE))
                assertEquals(if (ip) ControlLink.NORMAL else remote, either)
            }
        }
        // No TV added: nothing to wait for, either way.
        assertEquals(ControlLink.NORMAL, controlLink(Owner.TV_EITHER, false, Link.ABSENT, true, true, Link.ONLINE))
    }
}
