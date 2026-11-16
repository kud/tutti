package io.kud.tutti.protocol.hid

import io.kud.tutti.protocol.atv.V2Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacLayoutTest {
    private val option = Modifier.LEFT_ALT
    private val shift = Modifier.LEFT_SHIFT

    @Test
    fun appleFrenchIsNotThePcLayout() {
        // Probe: the keys that differ between Apple and PC AZERTY must differ here, or the table is a copy.
        for (c in listOf('@', '#', '§', '!', '-', '{', '[', '|', '\\')) {
            assertNotEquals(KeyLayout.FR_AZERTY.strokes(c), KeyLayout.MAC_FR.strokes(c), "'$c' should differ from PC AZERTY")
        }
    }

    @Test
    fun appleFrenchPositions() {
        val mac = KeyLayout.MAC_FR
        assertEquals(listOf(KeyStroke(0x35)), mac.strokes('@'))
        assertEquals(listOf(KeyStroke(0x35, shift)), mac.strokes('#'))
        assertEquals(listOf(KeyStroke(0x23)), mac.strokes('§'))
        assertEquals(listOf(KeyStroke(0x25)), mac.strokes('!'))
        assertEquals(listOf(KeyStroke(0x2E)), mac.strokes('-'))
        assertEquals(listOf(KeyStroke(0x22, option)), mac.strokes('{'))
        assertEquals(listOf(KeyStroke(0x22, option or shift)), mac.strokes('['))
        assertEquals(listOf(KeyStroke(0x2D, option)), mac.strokes('}'))
        assertEquals(listOf(KeyStroke(0x2D, option or shift)), mac.strokes(']'))
        assertEquals(listOf(KeyStroke(0x0F, option or shift)), mac.strokes('|'))
        assertEquals(listOf(KeyStroke(0x37, option or shift)), mac.strokes('\\'))
        assertEquals(listOf(KeyStroke(0x30, option)), mac.strokes('€'))
        assertEquals(listOf(KeyStroke(0x14)), mac.strokes('a'))
        assertEquals(listOf(KeyStroke(0x1A, shift)), mac.strokes('Z'))
        assertEquals(listOf(KeyStroke(0x33)), mac.strokes('m'))
        // Dead keys: ^ then e, ⌥n then n, ⌥n then space for a bare tilde.
        assertEquals(listOf(KeyStroke(0x2F), KeyStroke(0x08)), mac.strokes('ê'))
        assertEquals(listOf(KeyStroke(0x11, option), KeyStroke(0x11)), mac.strokes('ñ'))
        assertEquals(listOf(KeyStroke(0x11, option), KeyStroke(KeyUsage.SPACE)), mac.strokes('~'))
        assertNull(mac.strokes('É'))
    }

    @Test
    fun usMacReachesAccentsThroughDeadKeys() {
        val mac = KeyLayout.MAC_US
        assertEquals(listOf(KeyStroke(0x08, option), KeyStroke(0x08)), mac.strokes('é'))
        assertEquals(listOf(KeyStroke(0x08, option), KeyStroke(0x08, shift)), mac.strokes('É'))
        assertEquals(listOf(KeyStroke(0x35, option), KeyStroke(0x04)), mac.strokes('à'))
        assertEquals(listOf(KeyStroke(0x0C, option), KeyStroke(0x12)), mac.strokes('ô'))
        assertEquals(listOf(KeyStroke(0x18, option), KeyStroke(0x1C)), mac.strokes('ÿ'))
        assertEquals(listOf(KeyStroke(0x06, option)), mac.strokes('ç'))
        assertEquals(listOf(KeyStroke(0x14, option)), mac.strokes('œ'))
        // The PC US layout can't type these at all; a Mac can.
        assertNull(KeyLayout.US.strokes('é'))
    }

    @Test
    fun everyPrintableAsciiTypesOnTheMac() {
        val ascii = (0x20..0x7E).map { it.toChar() }
        assertTrue(ascii.size == 95, "probe on the input set")
        for (layout in listOf(KeyLayout.MAC_US, KeyLayout.MAC_FR)) {
            // Apple French's ` lives on a dead key by Return whose usage is unverified: left out.
            val missing = layout.type(ascii.joinToString("")).unsupported - setOf('`')
            assertEquals(emptySet(), missing, "$layout can't type $missing")
        }
        assertTrue(KeyLayout.MAC_FR.type("`").unsupported.isNotEmpty(), "probe: an untypable char is reported")
    }

    @Test
    fun macLayoutsUseOptionNeverAltGr() {
        for (layout in listOf(KeyLayout.MAC_US, KeyLayout.MAC_FR)) {
            assertTrue(layout.isMac)
            val strokes = layout.type("{}[]|\\@#€éèçàùœ«»~ñ").strokes
            assertTrue(strokes.isNotEmpty())
            assertTrue(strokes.none { it.modifiers and Modifier.RIGHT_ALT != 0 }, "$layout uses AltGr")
        }
        assertTrue(!KeyLayout.FR_AZERTY.isMac && !KeyLayout.US.isMac)
    }
}

class HidRouteTest {
    @Test
    fun theTvPrefersTheLanAndTheMacHasOnlyBluetooth() {
        assertEquals(RemoteLink.LAN, HidRoute.link(HidTarget.TV, lanUp = true, hidConnected = true))
        assertEquals(RemoteLink.BLUETOOTH, HidRoute.link(HidTarget.TV, lanUp = false, hidConnected = true))
        assertEquals(RemoteLink.NONE, HidRoute.link(HidTarget.TV, lanUp = false, hidConnected = false))
        assertEquals(RemoteLink.BLUETOOTH, HidRoute.link(HidTarget.COMPUTER, lanUp = true, hidConnected = true))
        assertEquals(RemoteLink.NONE, HidRoute.link(HidTarget.COMPUTER, lanUp = true, hidConnected = false))
        // The Mac holds Bluetooth whenever it is set up, even while the TV is the target.
        assertEquals(HidTarget.COMPUTER, HidRoute.hidHost(HidTarget.TV, computerConfigured = true, lanUp = true))
        assertEquals(HidTarget.COMPUTER, HidRoute.hidHost(HidTarget.COMPUTER, computerConfigured = true, lanUp = false))
        assertEquals(HidTarget.TV, HidRoute.hidHost(HidTarget.TV, computerConfigured = true, lanUp = false))
        assertEquals(HidTarget.TV, HidRoute.hidHost(HidTarget.TV, computerConfigured = false, lanUp = false))
        assertNull(HidRoute.hidHost(HidTarget.TV, computerConfigured = false, lanUp = true))
        assertTrue(HidRoute.gyroPossible(RemoteLink.BLUETOOTH, hasGyroscope = true))
        assertTrue(!HidRoute.gyroPossible(RemoteLink.LAN, hasGyroscope = true))
        assertTrue(!HidRoute.gyroPossible(RemoteLink.BLUETOOTH, hasGyroscope = false))
    }

    @Test
    fun theTargetFollowsTheMacsInput() {
        val mac = "extInput:hdmi?port=3"
        assertEquals(HidTarget.COMPUTER, HidRoute.targetFor(mac, mac))
        assertEquals(HidTarget.TV, HidRoute.targetFor("extInput:hdmi?port=2", mac))
        assertEquals(HidTarget.TV, HidRoute.targetFor(null, mac))
        assertEquals(HidTarget.TV, HidRoute.targetFor(null, null))
    }

    @Test
    fun remoteKeysMapToHid() {
        for (target in HidTarget.entries) {
            assertEquals(HidAction.Key(KeyStroke(KeyUsage.UP)), HidRoute.action(V2Key.DPAD_UP, target))
            assertEquals(HidAction.Key(KeyStroke(KeyUsage.ENTER)), HidRoute.action(V2Key.DPAD_CENTER, target))
            assertEquals(HidAction.Consumer(ConsumerUsage.PLAY_PAUSE), HidRoute.action(V2Key.MEDIA_PLAY_PAUSE, target))
            assertEquals(HidAction.Key(KeyStroke(0x27)), HidRoute.action(V2Key.NUM_0, target))
            assertEquals(HidAction.Key(KeyStroke(0x1E)), HidRoute.action(V2Key.NUM_1, target))
            assertNull(HidRoute.action(V2Key.POWER, target))
            assertNull(HidRoute.action(V2Key.TV_INPUT, target))
        }
        assertEquals(HidAction.Consumer(ConsumerUsage.BACK), HidRoute.action(V2Key.BACK, HidTarget.TV))
        assertEquals(HidAction.Key(KeyStroke(KeyUsage.ESCAPE)), HidRoute.action(V2Key.BACK, HidTarget.COMPUTER))
        assertEquals(HidAction.Consumer(ConsumerUsage.VOLUME_UP), HidRoute.action(V2Key.VOLUME_UP, HidTarget.TV))
        assertNull(HidRoute.action(V2Key.VOLUME_UP, HidTarget.COMPUTER))
        assertNull(HidRoute.action(V2Key.HOME, HidTarget.COMPUTER))
    }

}
