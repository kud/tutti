package io.kud.tutti.platform.data

import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.devices.DeviceLink
import io.kud.tutti.protocol.devices.DeviceList
import io.kud.tutti.protocol.devices.ShownOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Invented hosts and addresses only.
class DeviceViewsTest {
    private val tv = Device(
        "tv", "Salon", DeviceKind.TV,
        listOf(DeviceLink.Google("192.168.1.20"), DeviceLink.SonyIp("192.168.1.20", "aa-bb"), DeviceLink.Bluetooth("00:11:22:33:44:77")),
    )
    private val bar = Device("bar", "Bar", DeviceKind.SOUNDBAR, listOf(DeviceLink.BarIp("192.168.1.21")))
    private val mac = Device("mac-a", "Work", DeviceKind.MAC, listOf(DeviceLink.Bluetooth("00:11:22:33:44:55")), ShownOn("extInput:hdmi?port=2"))
    private val list = DeviceList(listOf(tv, bar, mac))

    @Test
    fun `the flat view reads the tv and bar hosts`() {
        assertEquals(Devices(TvDevice("192.168.1.20", "Salon", "aa-bb"), BarDevice("192.168.1.21", "Bar")), DeviceViews.legacy(list))
        assertEquals(Devices(), DeviceViews.legacy(DeviceList(listOf(mac))))
    }

    @Test
    fun `writing the flat view back changes nothing it doesn't hold`() {
        assertEquals(list, DeviceViews.applyLegacy(list, DeviceViews.legacy(list)))
    }

    @Test
    fun `a new tv host keeps the tv's bluetooth link and every mac`() {
        val next = DeviceViews.applyLegacy(list, Devices(TvDevice("192.168.1.30", "Salon"), BarDevice("192.168.1.21", "Bar")))
        assertEquals(
            listOf(DeviceLink.Google("192.168.1.30"), DeviceLink.SonyIp("192.168.1.30", null), DeviceLink.Bluetooth("00:11:22:33:44:77")),
            next.tv!!.links,
        )
        assertEquals(mac, next["mac-a"])
    }

    @Test
    fun `forgetting the tv in the flat view removes only the tv`() {
        val next = DeviceViews.applyLegacy(list, Devices(bar = BarDevice("192.168.1.21", "Bar")))
        assertNull(next.tv)
        assertEquals(listOf("bar", "mac-a"), next.devices.map { it.id })
    }

    @Test
    fun `onboarding a tv and bar onto an empty list`() {
        val next = DeviceViews.applyLegacy(DeviceList(), Devices(TvDevice("192.168.1.20"), BarDevice("192.168.1.21")))
        assertEquals(listOf("tv", "bar"), next.devices.map { it.id })
        assertEquals(Devices(TvDevice("192.168.1.20"), BarDevice("192.168.1.21")), DeviceViews.legacy(next))
    }
}
