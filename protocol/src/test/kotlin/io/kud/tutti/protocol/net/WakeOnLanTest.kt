package io.kud.tutti.protocol.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakeOnLanTest {
    @Test
    fun `macs are normalised from the usual spellings`() {
        listOf("02:00:00:00:00:01", "02-00-00-00-00-01", "020000000001", " 02:00:00:00:00:01 ")
            .forEach { assertEquals("02:00:00:00:00:01", WakeOnLan.normaliseMac(it), it) }
        assertEquals("0a:bc:de:f0:12:34", WakeOnLan.normaliseMac("0A:BC:DE:F0:12:34"))
    }

    @Test
    fun `invalid and unusable macs are refused`() {
        listOf(null, "", "02:00:00:00:00", "02:00:00:00:00:01:02", "02:00:00:00:00:0g", "0200.0000.0001", "2:0:0:0:0:1",
            "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01")
            .forEach { assertNull(WakeOnLan.normaliseMac(it), it.toString()) }
    }

    @Test
    fun `the magic packet is six 0xff then the mac sixteen times`() {
        val packet = WakeOnLan.magicPacket("02:00:00:00:00:01")!!
        assertEquals(102, packet.size)
        assertContentEquals(ByteArray(6) { 0xff.toByte() }, packet.copyOfRange(0, 6))
        val mac = byteArrayOf(0x02, 0, 0, 0, 0, 0x01)
        for (i in 0 until 16) assertContentEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6), "repeat $i")
        assertNull(WakeOnLan.magicPacket("not a mac"))
    }

    @Test
    fun `targets add the subnet broadcasts when the phone shares the tv's subnet`() {
        assertEquals(
            listOf("192.168.1.255", "255.255.255.255", "192.168.1.20"),
            WakeOnLan.targets("192.168.1.20", "192.168.1.42", 24),
        )
        assertEquals(
            listOf("10.0.15.255", "255.255.255.255", "10.0.8.7"),
            WakeOnLan.targets("10.0.8.7", "10.0.12.3", 20),
        )
    }

    @Test
    fun `targets stay unicast off the tv's subnet or without the phone's address`() {
        assertEquals(listOf("192.168.1.20"), WakeOnLan.targets("192.168.1.20", "192.168.2.42", 24))
        assertEquals(listOf("192.168.1.20"), WakeOnLan.targets("192.168.1.20", null, null))
        assertEquals(listOf("192.168.1.20"), WakeOnLan.targets("192.168.1.20", "192.168.1.42", 32))
        assertEquals(listOf("fe80::1"), WakeOnLan.targets("[fe80::1]", "192.168.1.42", 24))
    }

    @Test
    fun `nothing is sent to a tv that is not on the lan`() {
        assertEquals(emptyList(), WakeOnLan.targets("8.8.8.8", "8.8.8.9", 24))
        assertEquals(emptyList(), WakeOnLan.targets("bravia.local", "192.168.1.42", 24))
    }

    @Test
    fun `the mac of the interface carrying the host wins`() {
        val interfaces = listOf("" to "02:00:00:00:00:01", "192.168.1.20" to "02:00:00:00:00:02")
        assertEquals("02:00:00:00:00:02", WakeOnLan.macFor("192.168.1.20", interfaces, "02:00:00:00:00:03"))
        assertEquals("02:00:00:00:00:01", WakeOnLan.macFor("192.168.1.99", interfaces, "02:00:00:00:00:03"))
        assertEquals("02:00:00:00:00:03", WakeOnLan.macFor("192.168.1.20", listOf(null to "junk"), "02-00-00-00-00-03"))
        assertNull(WakeOnLan.macFor("192.168.1.20", emptyList(), null))
    }
}
