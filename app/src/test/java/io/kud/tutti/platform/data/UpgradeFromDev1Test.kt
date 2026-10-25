package io.kud.tutti.platform.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kud.tutti.di.preferencesStore
import io.kud.tutti.platform.security.SecretMigration
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.devices.DeviceLink
import io.kud.tutti.protocol.devices.SecretKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The upgrade from v0.1.0-dev.98 / v0.2.0-dev.1 to the device list, on real DataStore files.
 *
 * The two stores hold exactly what those builds wrote: `devices` as their `Devices` JSON (copied
 * literally, not re-encoded by today's classes) and the flat `tv_*` secret keys. The test runs the
 * same edits startup runs ([TuttiStore.migrateDevices], then [SecureStore.migrate], from
 * TargetController's init), twice as a restart would, and reads back through the functions every
 * reader goes through. The blobs stand in for ciphertext: the AndroidKeyStore decrypt, the
 * `bluetooth_hid` SharedPreferences read and the Hilt wiring need a phone and aren't run here.
 * Values are invented.
 */
class UpgradeFromDev1Test {
    @get:Rule
    val folder = TemporaryFolder()

    private val dev1Devices =
        """{"tv":{"host":"10.0.0.20","name":"BRAVIA 8","mac":"02:00:00:00:00:01"},"bar":{"host":"10.0.0.30","name":"Bar 9"}}"""

    @Test
    fun `the tv, the bar, the remote pairing and the psk all resolve after the upgrade`() = runBlocking {
        val tutti = preferencesStore { File(folder.root, "tutti.preferences_pb") }
        val secure = preferencesStore { File(folder.root, "secure.preferences_pb") }
        tutti.edit { it[stringPreferencesKey("devices")] = dev1Devices }
        secure.edit {
            it[stringPreferencesKey("tv_psk")] = "blob-psk=="
            it[stringPreferencesKey("tv_remote_pin")] = "blob-remote=="
            it[stringPreferencesKey("tv_rest_pin")] = "blob-rest=="
        }
        val hidHost = "02:00:00:00:00:02"

        // Before startup's migration has run, readers already see everything.
        assertResolves(tutti.data.first().let { TuttiStore.resolveDeviceList(it, hidHost) }, secure.data.first())

        repeat(2) {
            tutti.edit { TuttiStore.migrateDeviceList(it, hidHost) }
            secure.edit { SecretMigration.apply(it) }
        }

        val tuttiPrefs = tutti.data.first()
        val securePrefs = secure.data.first()
        assertResolves(TuttiStore.resolveDeviceList(tuttiPrefs, hidHost), securePrefs)
        // The migrated list stands on its own once written, even without the old Bluetooth host.
        assertResolves(TuttiStore.resolveDeviceList(tuttiPrefs, null), securePrefs)
        // Version 1 stays in place, so a downgrade finds its TV, bar, PSK and pins.
        assertEquals(dev1Devices, tuttiPrefs[stringPreferencesKey("devices")])
        assertEquals("blob-psk==", securePrefs[stringPreferencesKey("tv_psk")])
        assertEquals("blob-remote==", securePrefs[stringPreferencesKey("tv_remote_pin")])
        assertEquals("blob-rest==", securePrefs[stringPreferencesKey("tv_rest_pin")])
    }

    private fun assertResolves(list: io.kud.tutti.protocol.devices.DeviceList?, securePrefs: androidx.datastore.preferences.core.Preferences) {
        assertNotNull(list)
        val tv = list!!.tv!!
        assertEquals(Device.TV_ID, tv.id)
        assertEquals(DeviceKind.TV, tv.kind)
        assertEquals(DeviceLink.Google("10.0.0.20"), tv.links.filterIsInstance<DeviceLink.Google>().single())
        assertEquals(DeviceLink.SonyIp("10.0.0.20", "02:00:00:00:00:01"), tv.links.filterIsInstance<DeviceLink.SonyIp>().single())
        assertEquals("02:00:00:00:00:02", tv.bluetooth?.address)
        val bar = list.bar!!
        assertEquals(DeviceLink.BarIp("10.0.0.30"), bar.links.single())
        // The rest of the app reads the TV and the bar through this view.
        assertEquals(
            Devices(TvDevice("10.0.0.20", "BRAVIA 8", "02:00:00:00:00:01"), BarDevice("10.0.0.30", "Bar 9")),
            DeviceViews.legacy(list),
        )
        assertEquals("blob-psk==", SecureStore.encodedSecret(securePrefs, SecretKeys.psk(Device.TV_ID)))
        assertEquals("blob-remote==", SecureStore.encodedSecret(securePrefs, SecretKeys.remotePin(Device.TV_ID)))
        assertEquals("blob-rest==", SecureStore.encodedSecret(securePrefs, SecretKeys.restPin(Device.TV_ID)))
    }
}
