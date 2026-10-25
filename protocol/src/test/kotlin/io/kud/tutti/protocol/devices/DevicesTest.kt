package io.kud.tutti.protocol.devices

import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.hid.HidAction
import io.kud.tutti.protocol.hid.KeyLayout
import io.kud.tutti.protocol.hid.KeyStroke
import io.kud.tutti.protocol.hid.KeyUsage
import io.kud.tutti.protocol.hid.RemoteLink
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Invented hosts and addresses only.
class DevicesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val tv = Device(Device.TV_ID, "Salon", DeviceKind.TV, listOf(DeviceLink.Google("192.168.1.20"), DeviceLink.SonyIp("192.168.1.20")))
    private val bar = Device(Device.BAR_ID, "Bar", DeviceKind.SOUNDBAR, listOf(DeviceLink.BarIp("192.168.1.21")))
    private val work = Device("mac-a", "Work", DeviceKind.COMPUTER, listOf(DeviceLink.Bluetooth("00:11:22:33:44:55")), ShownOn("extInput:hdmi?port=2"), ComputerOs.MACOS)
    private val home = Device("mac-b", "Home", DeviceKind.COMPUTER, listOf(DeviceLink.Bluetooth("00:11:22:33:44:66", "MAC_US")), ShownOn("extInput:hdmi?port=3"), ComputerOs.MACOS)
    private val room = DeviceList(listOf(tv, bar, work, home))

    @Test
    fun `version 1 devices migrate to a list with the same hosts and the remembered bluetooth tv`() {
        val legacy = """{"tv":{"host":"192.168.1.20","name":"Salon","mac":"aa-bb"},"bar":{"host":"192.168.1.21"}}"""
        val list = DeviceMigration.migrate(null, legacy, "00:11:22:33:44:77")!!
        assertEquals(
            Device("tv", "Salon", DeviceKind.TV, listOf(DeviceLink.Google("192.168.1.20"), DeviceLink.SonyIp("192.168.1.20", "aa-bb"), DeviceLink.Bluetooth("00:11:22:33:44:77"))),
            list.tv,
        )
        assertEquals(Device("bar", "Bar 9", DeviceKind.SOUNDBAR, listOf(DeviceLink.BarIp("192.168.1.21"))), list.bar)
        assertEquals(2, list.version)
    }

    @Test
    fun `a tv bluetooth link that is a computer is dropped, the tv's own is kept`() {
        val onMac = room.update(Device.TV_ID) { it.withLink(DeviceLink.Bluetooth("00:11:22:33:44:66")) }
        assertEquals(null, DeviceMigration.withoutStrayTvBluetooth(onMac, emptySet()).tv?.bluetooth)
        val onBonded = room.update(Device.TV_ID) { it.withLink(DeviceLink.Bluetooth("aa:bb:cc:dd:ee:01")) }
        assertEquals(null, DeviceMigration.withoutStrayTvBluetooth(onBonded, setOf("AA:BB:CC:DD:EE:01")).tv?.bluetooth)
        val own = room.update(Device.TV_ID) { it.withLink(DeviceLink.Bluetooth("00:11:22:33:44:77", "US")) }
        assertEquals(own, DeviceMigration.withoutStrayTvBluetooth(own, setOf("AA:BB:CC:DD:EE:01")))
        assertEquals(room, DeviceMigration.withoutStrayTvBluetooth(room, setOf("00:11:22:33:44:77")))
    }

    @Test
    fun `migration is idempotent and never overwrites a list already written`() {
        val legacy = """{"tv":{"host":"192.168.1.20"}}"""
        val once = DeviceMigration.migrate(null, legacy, null)
        assertEquals(once, DeviceMigration.migrate(once, legacy, null))
        assertEquals(room, DeviceMigration.migrate(room, legacy, "00:11:22:33:44:77"))
    }

    @Test
    fun `nothing or garbage migrates to nothing`() {
        assertNull(DeviceMigration.migrate(null, null, null))
        assertNull(DeviceMigration.migrate(null, "{not json", null))
        assertNull(DeviceMigration.migrate(null, "{}", "00:11:22:33:44:77"))
        assertEquals(listOf("bar"), DeviceMigration.migrate(null, """{"bar":{"host":"192.168.1.21"}}""", null)!!.devices.map { it.id })
    }

    /** Exactly what v0.2.0-dev.2 wrote for a Mac (copied literally, not encoded by today's classes). */
    private val dev2Store = """{"devices":[{"id":"tv","name":"Salon","kind":"TV","links":[{"type":"google","host":"192.168.1.20"}]},""" +
        """{"id":"mac-a","name":"Work","kind":"MAC","links":[{"type":"bluetooth","address":"00:11:22:33:44:55","layout":"MAC_US"}],""" +
        """"shownOn":{"hdmiInput":"extInput:hdmi?port=2","airplay":true}}],"version":2}"""

    @Test
    fun `a mac stored by dev 2 comes back as a macos computer with its link, layout and input`() {
        val list = json.decodeFromString(DeviceList.serializer(), dev2Store)
        val mac = list["mac-a"]!!
        assertEquals(DeviceKind.COMPUTER, mac.kind)
        assertEquals(ComputerOs.MACOS, mac.os)
        assertEquals(ComputerOs.MACOS, mac.computerOs)
        assertEquals("00:11:22:33:44:55", mac.bluetooth?.address)
        assertEquals(KeyLayout.MAC_US, mac.layout())
        assertEquals(ShownOn("extInput:hdmi?port=2", airplay = true), mac.shownOn)
        assertTrue(mac.airplay)
        assertEquals(listOf("mac-a"), list.computers.map { it.id })
        // Written back, it says what it is now, and reads the same again.
        val written = json.encodeToString(DeviceList.serializer(), list)
        assertTrue("\"kind\":\"COMPUTER\"" in written && "\"os\":\"MACOS\"" in written, written)
        assertTrue("\"MAC\"" !in written, written)
        assertEquals(list, json.decodeFromString(DeviceList.serializer(), written))
    }

    @Test
    fun `windows and linux type on the pc tables, french azerty by default, and never over airplay`() {
        for (os in listOf(ComputerOs.WINDOWS, ComputerOs.LINUX)) {
            val pc = Device("pc", "Desk", DeviceKind.COMPUTER, listOf(DeviceLink.Bluetooth("00:11:22:33:44:88")), ShownOn("extInput:hdmi?port=4", airplay = false), os)
            assertEquals(listOf(KeyLayout.FR_AZERTY, KeyLayout.US), pc.layouts())
            assertEquals(KeyLayout.FR_AZERTY, pc.layout())
            assertEquals(KeyLayout.FR_AZERTY, pc.withLink(DeviceLink.Bluetooth("00:11:22:33:44:88", "MAC_FR")).layout(), "a mac layout never types to a pc")
            assertEquals(KeyLayout.US, pc.withLink(DeviceLink.Bluetooth("00:11:22:33:44:88", "US")).layout())
            assertTrue(!pc.airplay)
            assertTrue(!pc.copy(shownOn = ShownOn()).airplay, "airplay is a mac's only, whatever was stored")
            // AltGr is allowed on a PC: @ is AltGr+0 on the French AZERTY.
            assertTrue(pc.layout()!!.strokes('@')!!.single().modifiers != 0)
            // Same meanings as on a Mac: Back is Esc, OK is Enter, digits mean nothing.
            assertEquals(HidAction.Key(KeyStroke(KeyUsage.ESCAPE)), Targeting.route(V2Key.BACK, pc, true, true).action)
            assertEquals(HidAction.Key(KeyStroke(KeyUsage.ENTER)), Targeting.route(V2Key.DPAD_CENTER, pc, true, true).action)
            assertEquals(KeyAvailability.NOT_ON_COMPUTER, Targeting.route(V2Key.NUM_1, pc, true, true).availability)
            assertEquals(json.decodeFromString(DeviceList.serializer(), json.encodeToString(DeviceList.serializer(), DeviceList(listOf(pc)))).devices.single(), pc)
        }
    }

    @Test
    fun `macos keeps the apple tables, apple french by default, and the tv only its own`() {
        assertEquals(listOf(KeyLayout.MAC_FR, KeyLayout.MAC_US), work.layouts())
        assertEquals(KeyLayout.MAC_FR, work.layout())
        assertEquals(KeyLayout.MAC_FR, work.withLink(DeviceLink.Bluetooth("00:11:22:33:44:55", "FR_AZERTY")).layout())
        assertEquals(KeyLayout.US, tv.layout())
        assertEquals(KeyLayout.FR_AZERTY, tv.withLink(DeviceLink.Bluetooth("00:11:22:33:44:99", "FR_AZERTY")).layout())
        assertEquals(KeyLayout.US, tv.withLink(DeviceLink.Bluetooth("00:11:22:33:44:99", "MAC_FR")).layout())
        assertNull(bar.layout())
    }

    @Test
    fun `a list round-trips through json`() {
        assertEquals(room, json.decodeFromString(DeviceList.serializer(), json.encodeToString(DeviceList.serializer(), room)))
    }

    @Test
    fun `a link or kind from a newer build is skipped, never the whole list`() {
        val text = """{"devices":[
            {"id":"tv","name":"Salon","kind":"TV","links":[{"type":"google","host":"192.168.1.20"},{"type":"matter","node":7},{"type":"sony-ip","host":"192.168.1.20"}]},
            {"id":"x","name":"Fridge","kind":"FRIDGE"},
            {"id":"mac-a","name":"Work","kind":"MAC","links":[{"type":"bluetooth","address":"00:11:22:33:44:55","layout":"MAC_DVORAK"}]}
        ],"version":3}"""
        val list = json.decodeFromString(DeviceList.serializer(), text)
        assertEquals(listOf("tv", "mac-a"), list.devices.map { it.id })
        assertEquals(listOf(DeviceLink.Google("192.168.1.20"), DeviceLink.SonyIp("192.168.1.20")), list.tv!!.links)
        assertEquals("00:11:22:33:44:55", list["mac-a"]!!.bluetooth!!.address)
        assertEquals(KeyLayout.MAC_FR, list["mac-a"]!!.layout(), "an unknown layout falls back to the kind's default")
        assertEquals(KeyLayout.MAC_US, home.layout())
    }

    @Test
    fun `secrets are copied to per-device keys only where the new key is empty`() {
        assertEquals(
            mapOf("tv_psk" to "psk/tv", "tv_remote_pin" to "pin/tv/remote", "tv_rest_pin" to "pin/tv/rest"),
            SecretKeys.copies(setOf("tv_psk", "tv_remote_pin", "tv_rest_pin")),
        )
        assertEquals(emptyMap(), SecretKeys.copies(setOf("tv_psk", "psk/tv")), "a newer PSK is never overwritten by the old one")
        assertEquals(mapOf("tv_rest_pin" to "pin/tv/rest"), SecretKeys.copies(setOf("tv_rest_pin", "psk/tv")))
        assertEquals(emptyMap(), SecretKeys.copies(emptySet()))
        assertTrue(SecretKeys.all("tv").containsAll(listOf("tv_psk", "psk/tv", "pin/tv/rest", "tv_rest_pin")))
        assertEquals(listOf("psk/mac-a", "pin/mac-a/remote", "pin/mac-a/rest", "ipcookie/mac-a"), SecretKeys.all("mac-a"))
    }

    @Test
    fun `one tv and one bar, any number of macs`() {
        val other = Device("tv2", "Other", DeviceKind.TV)
        val list = room.put(other)
        assertEquals(listOf("tv2", "bar", "mac-a", "mac-b"), list.devices.map { it.id })
        val third = room.put(Device("mac-c", "Third", DeviceKind.COMPUTER))
        assertEquals(3, third.computers.size)
        assertEquals(listOf("tv", "bar", "mac-b"), room.remove("mac-a").devices.map { it.id })
    }

    @Test
    fun `each kind accepts only its links`() {
        assertTrue(DeviceKinds.accepts(DeviceKind.TV, DeviceLink.Bluetooth("x")))
        assertTrue(DeviceKinds.accepts(DeviceKind.COMPUTER, DeviceLink.Bluetooth("x")))
        assertTrue(!DeviceKinds.accepts(DeviceKind.COMPUTER, DeviceLink.Google("x")))
        assertTrue(!DeviceKinds.accepts(DeviceKind.SOUNDBAR, DeviceLink.Bluetooth("x")))
        assertTrue(!DeviceKinds.accepts(DeviceKind.SOUNDBAR, DeviceLink.SonyIp("x")))
    }

    @Test
    fun `the target follows the input to the mac marked for it`() {
        assertEquals("mac-a", Targeting.auto("extInput:hdmi?port=2", room)?.id)
        assertEquals("mac-b", Targeting.auto("extInput:hdmi?port=3", room)?.id)
        assertEquals("tv", Targeting.auto("extInput:hdmi?port=1", room)?.id)
        assertEquals("tv", Targeting.auto(null, room)?.id)
        assertEquals("mac-a", Targeting.auto(null, DeviceList(listOf(work)))?.id)
        assertNull(Targeting.auto(null, DeviceList()))
        assertEquals("mac-a", Targeting.computerOnInput("extInput:hdmi?port=2", room)?.id)
        assertNull(Targeting.computerOnInput(null, DeviceList(listOf(work.copy(shownOn = ShownOn(null))))))
    }

    @Test
    fun `a manual choice holds until the input changes`() {
        val choice = TargetChoice("mac-b", "extInput:hdmi?port=1")
        assertEquals("mac-b", Targeting.current(choice, "extInput:hdmi?port=1", room)?.id)
        assertEquals("mac-a", Targeting.current(choice, "extInput:hdmi?port=2", room)?.id)
        assertEquals("tv", Targeting.current(choice, "extInput:hdmi?port=4", room)?.id)
        assertEquals("tv", Targeting.current(choice, "extInput:hdmi?port=1", room.remove("mac-b"))?.id, "a forgotten mac drops the choice")
        assertEquals("tv", Targeting.current(TargetChoice("bar", null), null, room)?.id, "the bar is never a target")
        assertEquals(listOf("tv", "mac-a", "mac-b"), Targeting.targets(room).map { it.id })
    }

    @Test
    fun `the hid role goes to the targeted mac, the tv only with its lan down, else a mac stays ready`() {
        assertEquals("mac-b", Targeting.hidHost(room, home, tvLanUp = true, lastComputer = "mac-a")?.id)
        assertEquals("mac-a", Targeting.hidHost(room, tv, tvLanUp = true, lastComputer = "mac-a")?.id)
        assertEquals("mac-b", Targeting.hidHost(room, tv, tvLanUp = true, lastComputer = "mac-b")?.id)
        assertEquals("mac-a", Targeting.hidHost(room, tv, tvLanUp = true, lastComputer = null)?.id)
        val tvBt = tv.withLink(DeviceLink.Bluetooth("00:11:22:33:44:77"))
        assertEquals("tv", Targeting.hidHost(room.put(tvBt), tvBt, tvLanUp = false, lastComputer = "mac-a")?.id)
        assertEquals("mac-a", Targeting.hidHost(room, tv, tvLanUp = false, lastComputer = null)?.id, "a tv without bluetooth can't hold it")
        assertNull(Targeting.hidHost(DeviceList(listOf(tv, bar)), tv, tvLanUp = true, lastComputer = null))
        assertEquals("mac-a", Targeting.hidHost(room, work.withoutLink<DeviceLink.Bluetooth>(), tvLanUp = true, lastComputer = null)?.id)
    }

    @Test
    fun `keys for the mac go over bluetooth, tv keys keep going to the tv over the lan`() {
        val back = Targeting.route(V2Key.BACK, work, tvLanUp = true, hidConnected = true)
        assertEquals(KeyRoute(KeyAvailability.AVAILABLE, RemoteLink.BLUETOOTH, false, HidAction.Key(KeyStroke(KeyUsage.ESCAPE))), back)
        for (key in listOf(V2Key.HOME, V2Key.ALL_APPS, V2Key.SETTINGS, V2Key.POWER, V2Key.TV_INPUT, V2Key.VOLUME_UP)) {
            assertEquals(KeyRoute(KeyAvailability.AVAILABLE, RemoteLink.LAN, toTv = true), Targeting.route(key, work, true, true))
            assertEquals(KeyAvailability.LINK_DOWN, Targeting.route(key, work, false, true).availability)
        }
        for (key in listOf(V2Key.MEDIA_REWIND, V2Key.CAPTIONS, V2Key.MEDIA_AUDIO_TRACK, V2Key.NUM_5, V2Key.CHANNEL_UP, V2Key.PROG_RED, V2Key.ASSIST)) {
            assertEquals(KeyAvailability.NOT_ON_COMPUTER, Targeting.route(key, work, true, true).availability, "key $key")
        }
        assertEquals(KeyAvailability.LINK_DOWN, Targeting.route(V2Key.DPAD_UP, work, true, hidConnected = false).availability)
    }

    @Test
    fun `the tv prefers its lan and says which keys bluetooth can't carry`() {
        assertEquals(KeyRoute(KeyAvailability.AVAILABLE, RemoteLink.LAN, true), Targeting.route(V2Key.POWER, tv, true, false))
        assertEquals(RemoteLink.BLUETOOTH, Targeting.route(V2Key.DPAD_UP, tv, false, true).link)
        assertEquals(KeyAvailability.NOT_OVER_BLUETOOTH, Targeting.route(V2Key.POWER, tv, false, true).availability)
        assertEquals(KeyAvailability.LINK_DOWN, Targeting.route(V2Key.DPAD_UP, tv, false, false).availability)
        assertEquals(KeyAvailability.AVAILABLE, Targeting.route(V2Key.HOME, null, true, false).availability, "no device reads as the tv")
    }

    @Test
    fun `text edits erase back to the common prefix and type the rest in the layout`() {
        val bs = KeyStroke(KeyUsage.BACKSPACE)
        assertEquals(KeyLayout.MAC_FR.type("c").strokes, Targeting.textEdit("ab", "abc", KeyLayout.MAC_FR).strokes)
        assertEquals(listOf(bs), Targeting.textEdit("abc", "ab", KeyLayout.MAC_FR).strokes)
        assertEquals(listOf(bs, bs) + KeyLayout.MAC_US.type("xy").strokes, Targeting.textEdit("abc", "axy", KeyLayout.MAC_US).strokes)
        assertEquals(emptyList(), Targeting.textEdit("same", "same", KeyLayout.MAC_US).strokes)
        assertEquals(setOf('☃'), Targeting.textEdit("", "a☃", KeyLayout.MAC_US).unsupported)
    }

    @Test
    fun `the hdmi input that just came on is the one preselected for a new mac`() {
        fun input(port: Int, on: Boolean) = io.kud.tutti.protocol.bravia.ExternalInput("extInput:hdmi?port=$port", "HDMI $port", "", on)
        val before = listOf(input(1, true), input(2, false), input(3, false))
        assertEquals("extInput:hdmi?port=2", Targeting.newlyConnected(before, listOf(input(1, true), input(2, true), input(3, false))))
        assertNull(Targeting.newlyConnected(before, before))
        assertNull(Targeting.newlyConnected(before, listOf(input(1, true), input(2, true), input(3, true))), "two at once: ask, never guess")
    }

    @Test
    fun `the foreground log keeps the last ten distinct packages`() {
        var log = ForegroundLog()
        log = log.seen("com.a", 1).seen("com.a", 2).seen(null, 3).seen("com.b", 4).seen("com.a", 5)
        assertEquals(listOf("com.b" to 4L, "com.a" to 5L), log.entries)
        repeat(15) { log = log.seen("p$it", 10L + it) }
        assertEquals(10, log.entries.size)
        assertEquals("p14", log.entries.last().first)
    }
}
