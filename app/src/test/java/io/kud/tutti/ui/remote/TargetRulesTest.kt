package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.protocol.hid.RemoteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetRulesTest {
    private val tv = TargetRow("tv", "BRAVIA", TargetKind.TV, RemoteLink.LAN, current = true)
    private val mac = TargetRow("mac", "Laptop", TargetKind.MAC, RemoteLink.BLUETOOTH, current = false)

    @Test
    fun `no chip with the tv alone, a chip once a mac is present`() {
        assertFalse(targetChipShown(emptyList()))
        assertFalse(targetChipShown(listOf(tv)))
        assertTrue(targetChipShown(listOf(tv, mac)))
    }

    @Test
    fun `the current target is the ticked row, else the tv`() {
        assertEquals("tv", currentTarget(listOf(tv.copy(current = false), mac))?.id)
        assertEquals(mac.copy(current = true), currentTarget(listOf(tv.copy(current = false), mac.copy(current = true))))
    }

    @Test
    fun `talkback hears real changes only, never the first target`() {
        assertFalse(targetAnnounced(lastAnnounced = null, settled = "tv"))
        assertFalse(targetAnnounced(lastAnnounced = "tv", settled = "tv"))
        assertTrue(targetAnnounced(lastAnnounced = "tv", settled = "mac"))
    }

    @Test
    fun `gyro is a third mode on bluetooth targets only`() {
        assertEquals(listOf(PadMode.TOUCHPAD, PadMode.DPAD), padModes(bluetooth = false))
        assertEquals(PadMode.GYRO, nextPadMode(PadMode.DPAD, bluetooth = true))
        assertEquals(PadMode.TOUCHPAD, nextPadMode(PadMode.GYRO, bluetooth = true))
        assertEquals(PadMode.TOUCHPAD, nextPadMode(PadMode.DPAD, bluetooth = false))
        assertEquals(PadMode.TOUCHPAD, nextPadMode(PadMode.GYRO, bluetooth = false))
    }

    @Test
    fun `gyro says why it can't run, the sensor first`() {
        assertEquals(GyroBlock.NO_SENSOR, gyroBlock(hasGyroscope = false, gyroPossible = false))
        assertEquals(GyroBlock.LINK_DOWN, gyroBlock(hasGyroscope = true, gyroPossible = false))
        assertEquals(GyroBlock.NONE, gyroBlock(hasGyroscope = true, gyroPossible = true))
    }

    @Test
    fun `a dropped link takes the pad back to the mode before gyro`() {
        assertEquals(PadMode.GYRO, padModeShown(PadMode.GYRO, bluetooth = true, gyroPossible = true))
        assertEquals(PadMode.DPAD, padModeShown(PadMode.GYRO, bluetooth = true, gyroPossible = false, previous = PadMode.DPAD))
        assertEquals(PadMode.TOUCHPAD, padModeShown(PadMode.GYRO, bluetooth = false, gyroPossible = true, previous = PadMode.GYRO))
        assertEquals(PadMode.DPAD, padModeShown(PadMode.DPAD, bluetooth = false, gyroPossible = false))
    }

    @Test
    fun `the link indicator shows over bluetooth only`() {
        assertTrue(linkIndicatorShown(RemoteLink.BLUETOOTH))
        assertFalse(linkIndicatorShown(RemoteLink.LAN))
        assertFalse(linkIndicatorShown(RemoteLink.NONE))
    }
}
