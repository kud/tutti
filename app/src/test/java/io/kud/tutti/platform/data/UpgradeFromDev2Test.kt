package io.kud.tutti.platform.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.di.preferencesStore
import io.kud.tutti.protocol.devices.ComputerOs
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.devices.DeviceLink
import io.kud.tutti.protocol.devices.ShownOn
import io.kud.tutti.protocol.hid.KeyLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The upgrade from v0.2.0-dev.1 / dev.2, where a computer could only be a Mac (kind "MAC"), to a
 * computer with a system, on a real DataStore file. `devices_v2` holds exactly what those builds
 * wrote (copied literally, not re-encoded by today's classes); the test reads it through
 * [TuttiStore.resolveDeviceList], writes it back the way any device edit does, and reads it again.
 * Values are invented.
 */
class UpgradeFromDev2Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val dev2List =
        """{"devices":[{"id":"tv","name":"BRAVIA 8","kind":"TV","links":[{"type":"google","host":"10.0.0.20"},""" +
            """{"type":"sony-ip","host":"10.0.0.20","mac":"02:00:00:00:00:01"}]},""" +
            """{"id":"bar","name":"Bar 9","kind":"SOUNDBAR","links":[{"type":"bar-ip","host":"10.0.0.30"}]},""" +
            """{"id":"mac-1a2b3c4d","name":"Work","kind":"MAC","links":[{"type":"bluetooth","address":"02:00:00:00:00:03","layout":"MAC_FR"}],""" +
            """"shownOn":{"hdmiInput":"extInput:hdmi?port=2","airplay":true}}],"version":2}"""

    @Test
    fun `a mac stored by dev 2 reads and writes back as a macos computer, link, layout and input intact`() = runBlocking {
        val tutti = preferencesStore { File(folder.root, "tutti.preferences_pb") }
        tutti.edit { it[stringPreferencesKey("devices_v2")] = dev2List }

        val read = TuttiStore.resolveDeviceList(tutti.data.first(), null)
        assertNotNull(read)
        assertMacos(read!!)

        // Any device edit writes the list back through today's classes.
        tutti.edit { it[stringPreferencesKey("devices_v2")] = StoreJson.encodeToString(io.kud.tutti.protocol.devices.DeviceList.serializer(), read) }
        val written = tutti.data.first()[stringPreferencesKey("devices_v2")]!!
        assertTrue(written, "\"kind\":\"COMPUTER\"" in written && "\"os\":\"MACOS\"" in written)
        assertFalse(written, "\"kind\":\"MAC\"" in written)
        assertMacos(TuttiStore.resolveDeviceList(tutti.data.first(), null)!!)
    }

    private fun assertMacos(list: io.kud.tutti.protocol.devices.DeviceList) {
        assertEquals(listOf("tv", "bar", "mac-1a2b3c4d"), list.devices.map { it.id })
        val mac = list.computers.single()
        assertEquals(DeviceKind.COMPUTER, mac.kind)
        assertEquals(ComputerOs.MACOS, mac.os)
        assertEquals("Work", mac.name)
        assertEquals(DeviceLink.Bluetooth("02:00:00:00:00:03", "MAC_FR"), mac.bluetooth)
        assertEquals(KeyLayout.MAC_FR, mac.layout())
        assertEquals(ShownOn("extInput:hdmi?port=2", airplay = true), mac.shownOn)
        assertEquals("10.0.0.20", list.tv!!.link<DeviceLink.Google>()!!.host)
        assertEquals(DeviceLink.BarIp("10.0.0.30"), list.bar!!.links.single())
    }
}
