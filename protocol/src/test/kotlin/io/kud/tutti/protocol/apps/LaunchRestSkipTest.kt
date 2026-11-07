package io.kud.tutti.protocol.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sideloaded app's launch (Erwann, 2026-11-07): the trace says why setActiveApp wasn't tried. */
class LaunchRestSkipTest {
    @Test
    fun `the reason the rest route was skipped`() {
        assertEquals(RestSkip.KEY_REFUSED, LaunchPlan.restSkip(restOnline = true, keyRefused = true, restUri = "u"))
        assertEquals(RestSkip.OFFLINE, LaunchPlan.restSkip(restOnline = false, keyRefused = false, restUri = null))
        assertEquals(RestSkip.NOT_LISTED, LaunchPlan.restSkip(restOnline = true, keyRefused = false, restUri = null))
        assertNull(LaunchPlan.restSkip(restOnline = true, keyRefused = false, restUri = "u"))
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
