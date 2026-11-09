package io.kud.tutti.protocol.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An uncatalogued app's launch (Erwann, 2026-11-07 and 2026-11-09): the trace says why setActiveApp wasn't tried. */
class LaunchRestSkipTest {
    @Test
    fun `the reason the rest route was skipped`() {
        assertEquals(RestSkip.NOT_SET_UP, LaunchPlan.restSkip(IpControl.NOT_SET_UP, restUri = null))
        assertEquals(RestSkip.NO_LAN_PERMISSION, LaunchPlan.restSkip(IpControl.NO_LAN_PERMISSION, restUri = null))
        assertEquals(RestSkip.CONNECTING, LaunchPlan.restSkip(IpControl.CONNECTING, restUri = null))
        assertEquals(RestSkip.OFFLINE, LaunchPlan.restSkip(IpControl.NOT_ANSWERING, restUri = null))
        assertEquals(RestSkip.KEY_REFUSED, LaunchPlan.restSkip(IpControl.KEY_REFUSED, restUri = "u"))
        assertEquals(RestSkip.CERTIFICATE_CHANGED, LaunchPlan.restSkip(IpControl.CERTIFICATE_CHANGED, restUri = "u"))
        assertEquals(RestSkip.NOT_LISTED, LaunchPlan.restSkip(IpControl.ONLINE, restUri = null))
        assertNull(LaunchPlan.restSkip(IpControl.ONLINE, restUri = "u"))
    }

    @Test
    fun `not set up and set up but down never read the same`() {
        val words = RestSkip.entries.map { it.words }
        assertEquals(words.size, words.toSet().size)
        assertTrue(RestSkip.OFFLINE.words.startsWith("IP control set up"))
        assertTrue(RestSkip.CONNECTING.words.startsWith("IP control set up"))
        assertEquals("IP control not set up", RestSkip.NOT_SET_UP.words)
    }

    @Test
    fun `a launch holds only for an IP control that is set up and may come up`() {
        val waits = IpControl.entries.filter(LaunchPlan::waitsForRest).toSet()
        assertEquals(setOf(IpControl.CONNECTING, IpControl.NOT_ANSWERING), waits)
        assertTrue(LaunchPlan.REST_WAIT_MS in 1_000L..AppLauncher.CONFIRM_MS)
    }

    @Test
    fun `the trace says the launch waited, and how it ended`() {
        val rest = LaunchRoute.Rest("com.sony.dtv.org.example.org.example.Main")
        val up = LaunchResult("org.example", LaunchVerdict.CONFIRMED, listOf(LaunchAttempt(rest, true, "org.example")), restWait = RestWait(1_200, cameUp = true))
        assertEquals("launch org.example: confirmed\nrest waited 1200 ms for IP control: up\n1. rest setActiveApp com.sony.dtv.org.example.org.example.Main - sent, foreground org.example", up.trace())
        val link = LaunchRoute.AppLink("market://launch?id=org.example")
        val down = LaunchResult("org.example", LaunchVerdict.FAILED, listOf(LaunchAttempt(link, true, "com.other")), RestSkip.OFFLINE, RestWait(2_500, cameUp = false))
        assertEquals("launch org.example: failed\nrest waited 2500 ms for IP control: still down\nrest setActiveApp skipped: IP control set up, not answering\n1. remote app link market://launch?id=org.example - sent, foreground com.other", down.trace())
    }

    @Test
    fun `nuvio opens by its own scheme before the store link, and IP control before both`() {
        assertEquals(
            listOf(LaunchRoute.AppLink("nuvio://"), LaunchRoute.AppLink("market://launch?id=com.nuvio.app")),
            LaunchPlan.routes("com.nuvio.app", restOnline = false, restUri = null),
        )
        val uri = "com.sony.dtv.com.nuvio.app.app.nuvio.MainActivity"
        assertEquals(LaunchRoute.Rest(uri), LaunchPlan.routes("com.nuvio.app", restOnline = true, restUri = uri).first())
        assertEquals(LaunchRoute.AppLink("market://launch?id=com.nuvio.app"), LaunchPlan.routes("com.nuvio.app", restOnline = true, restUri = uri).last())
    }

    @Test
    fun `the trace names it before the routes`() {
        val link = LaunchRoute.AppLink("market://launch?id=org.example")
        val result = LaunchResult("org.example", LaunchVerdict.FAILED, listOf(LaunchAttempt(link, true, "com.other")), RestSkip.NOT_LISTED)
        assertEquals("launch org.example: failed\nrest setActiveApp skipped: app never listed by the TV\n1. remote app link market://launch?id=org.example - sent, foreground com.other", result.trace())
        assertTrue("skipped" !in result.copy(restSkipped = null).trace())
    }

    @Test
    fun `an activity outside its package still gives the package`() {
        val uri = "com.sony.dtv.com.nuvio.app.app.nuvio.MainActivity"
        assertEquals("com.nuvio.app", AppLibrary.packageOf(uri, known = emptyList()))
        assertTrue(AppLibrary.lists(uri, "com.nuvio.app"))
        assertEquals("com.netflix.ninja", AppLibrary.packageOf("com.sony.dtv.com.netflix.ninja.com.netflix.ninja.MainActivity", known = emptyList()))
        assertEquals("org.example.tv", AppLibrary.packageOf("com.sony.dtv.org.example.tv.org.example.Main", known = listOf("org.example.tv")))
    }
}
