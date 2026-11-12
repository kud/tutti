package io.kud.tutti.protocol.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanLinkTest {
    private val wifi = NetView(setOf(NetKind.WIFI), validated = true)
    private val wifiUnvalidated = NetView(setOf(NetKind.WIFI), validated = false)
    private val ethernet = NetView(setOf(NetKind.ETHERNET), validated = true)
    private val cellular = NetView(setOf(NetKind.CELLULAR), validated = true)

    @Test
    fun `the first lan network binds without dropping, and wakes the loops`() {
        val link = LanLink()
        val step = link.lanAvailable(1, wifi)
        assertNull(step.drop)
        assertTrue(step.wake)
        assertEquals(1L, link.bound)
    }

    @Test
    fun `a default flip to cellular and back never drops the links`() {
        // The 2026-11-12 pattern: wifi loses validation, the default goes to cellular, then comes back.
        val link = LanLink()
        link.defaultChanged(1, wifi)
        link.lanAvailable(1, wifi)
        assertNull(link.lanCapabilities(1, wifiUnvalidated).drop)
        val away = link.defaultChanged(2, cellular)
        assertNull(away.drop)
        assertFalse(away.wake, "links are bound to the lan: nothing to retry")
        assertNull(link.lanCapabilities(1, wifi).drop)
        assertNull(link.defaultChanged(1, wifi).drop)
        assertEquals(1L, link.bound)
    }

    @Test
    fun `losing the bound lan network drops the links and unbinds`() {
        val link = LanLink()
        link.lanAvailable(1, wifi)
        val step = link.lanLost(1)
        assertNotNull(step.drop)
        assertNull(link.bound)
    }

    @Test
    fun `losing another network is not our business`() {
        val link = LanLink()
        link.lanAvailable(1, wifi)
        assertEquals(LanLink.Step.NONE, link.lanLost(7))
        assertEquals(1L, link.bound)
    }

    @Test
    fun `a replaced lan network drops the links and binds the new one`() {
        val link = LanLink()
        link.lanAvailable(1, wifi)
        val step = link.lanAvailable(2, ethernet)
        assertNotNull(step.drop)
        assertTrue(step.wake)
        assertEquals(2L, link.bound)
    }

    @Test
    fun `the same lan network offered again changes nothing`() {
        val link = LanLink()
        link.lanAvailable(1, wifi)
        assertEquals(LanLink.Step.NONE, link.lanAvailable(1, wifi))
    }

    @Test
    fun `with no lan network a new default is worth a retry, never a drop`() {
        val link = LanLink()
        val step = link.defaultChanged(3, wifi)
        assertNull(step.drop)
        assertTrue(step.wake)
    }

    @Test
    fun `validation flips are logged once each, other capability changes not at all`() {
        val link = LanLink()
        link.lanAvailable(1, wifi)
        assertNull(link.lanCapabilities(1, wifi).log)
        assertNotNull(link.lanCapabilities(1, wifiUnvalidated).log)
        assertNull(link.lanCapabilities(1, wifiUnvalidated).log)
        link.defaultChanged(1, wifi)
        assertNull(link.defaultCapabilities(1, wifi).log)
        assertNotNull(link.defaultCapabilities(1, wifiUnvalidated).log)
    }

    @Test
    fun `log lines name transports and validation, nothing else`() {
        val link = LanLink()
        val lines = listOfNotNull(
            link.lanAvailable(42, wifi).log,
            link.defaultChanged(43, cellular).log,
            link.lanCapabilities(42, wifiUnvalidated).log,
            link.lanAvailable(44, ethernet).log,
            link.lanLost(44).log,
        )
        assertEquals(5, lines.size)
        for (line in lines) {
            assertFalse(Regex("\\d").containsMatchIn(line), "no handle or number in \"$line\"")
            assertTrue(line.startsWith("network: "), line)
        }
        assertEquals("wifi, unvalidated", wifiUnvalidated.label)
    }

    @Test
    fun `two missed polls are tolerated, the third reads unreachable`() {
        var misses = 0
        misses = PollMisses.next(misses, answered = false)
        assertFalse(PollMisses.unreachable(misses))
        misses = PollMisses.next(misses, answered = false)
        assertFalse(PollMisses.unreachable(misses))
        misses = PollMisses.next(misses, answered = false)
        assertTrue(PollMisses.unreachable(misses))
        assertEquals(0, PollMisses.next(misses, answered = true))
    }
}
