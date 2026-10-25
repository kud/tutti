package io.kud.tutti.platform.data

import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.devices.DeviceLink
import io.kud.tutti.protocol.devices.DeviceList

/**
 * The flat `Devices(tv, bar)` the rest of the app still reads, as a view of the [DeviceList]
 * (stage 1 keeps the TV and bar engine untouched). Writes through [applyLegacy] change only the
 * TV's and bar's LAN links and names: the TV's Bluetooth link and every Mac survive.
 */
internal object DeviceViews {
    fun legacy(list: DeviceList): Devices {
        val tv = list.tv?.let { d ->
            val host = d.link<DeviceLink.Google>()?.host ?: d.link<DeviceLink.SonyIp>()?.host
            host?.let { TvDevice(it, d.name, d.link<DeviceLink.SonyIp>()?.mac) }
        }
        val bar = list.bar?.let { d -> d.link<DeviceLink.BarIp>()?.let { BarDevice(it.host, d.name) } }
        return Devices(tv, bar)
    }

    fun applyLegacy(list: DeviceList, devices: Devices): DeviceList {
        var out = list
        val oldTv = list.tv
        out = when (val tv = devices.tv) {
            null -> oldTv?.let { out.remove(it.id) } ?: out
            else -> out.put(
                (oldTv ?: Device(Device.TV_ID, tv.name, DeviceKind.TV)).copy(name = tv.name)
                    .withLink(DeviceLink.Google(tv.host))
                    .withLink(DeviceLink.SonyIp(tv.host, tv.mac))
                    .let { d -> d.copy(links = d.links.sortedBy { LINK_ORDER.indexOf(it.javaClass) }) },
            )
        }
        val oldBar = list.bar
        out = when (val bar = devices.bar) {
            null -> oldBar?.let { out.remove(it.id) } ?: out
            else -> out.put((oldBar ?: Device(Device.BAR_ID, bar.name, DeviceKind.SOUNDBAR)).copy(name = bar.name).withLink(DeviceLink.BarIp(bar.host)))
        }
        return out
    }

    private val LINK_ORDER = listOf(DeviceLink.Google::class.java, DeviceLink.SonyIp::class.java, DeviceLink.Bluetooth::class.java)
}
