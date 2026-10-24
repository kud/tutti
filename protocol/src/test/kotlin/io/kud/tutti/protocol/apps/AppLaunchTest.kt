package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.AppCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppLaunchTest {
    private val netflix = "com.netflix.ninja"

    @Test
    fun `netflix tries its documented deep link before the play store link`() {
        assertEquals(listOf("netflix://", "market://launch?id=$netflix"), AppCatalog.byPackage(netflix)?.launchLinks)
    }

    @Test
    fun `rest comes first only when it answers and knows the app`() {
        val all = LaunchPlan.routes(netflix, restOnline = true, restUri = "com.sony.dtv.x")
        assertEquals(LaunchRoute.Rest("com.sony.dtv.x"), all.first())
        assertEquals(3, all.size)
        assertEquals(2, LaunchPlan.routes(netflix, restOnline = false, restUri = "com.sony.dtv.x").size)
        assertEquals(2, LaunchPlan.routes(netflix, restOnline = true, restUri = null).size)
    }

    @Test
    fun `an app outside the catalogue still gets the play store link`() {
        assertEquals(listOf(LaunchRoute.AppLink("market://launch?id=org.example")), LaunchPlan.routes("org.example", false, null))
    }

    @Test
    fun `confirmed as soon as the tv reports the app`() = runTest {
        val foreground = MutableStateFlow<String?>("com.google.android.tvlauncher")
        val sent = mutableListOf<LaunchRoute>()
        val launcher = AppLauncher(send = { sent += it; true }, foreground = foreground, canObserve = { true })
        launch {
            delay(800)
            foreground.value = netflix
        }
        val result = launcher.launch(netflix, LaunchPlan.routes(netflix, false, null))
        assertEquals(LaunchVerdict.CONFIRMED, result.verdict)
        assertEquals(1, sent.size)
        assertEquals(800, currentTime)
        assertEquals(netflix, result.attempts.single().foreground)
    }

    @Test
    fun `a link the tv ignores falls through to the next after three seconds`() = runTest {
        val foreground = MutableStateFlow<String?>("com.google.android.tvlauncher")
        val sent = mutableListOf<LaunchRoute>()
        val launcher = AppLauncher(
            send = { route ->
                sent += route
                if (route == LaunchRoute.AppLink("market://launch?id=$netflix")) launch { delay(500); foreground.value = netflix }
                true
            },
            foreground = foreground,
            canObserve = { true },
        )
        val result = launcher.launch(netflix, LaunchPlan.routes(netflix, true, "rest-uri"))
        assertEquals(LaunchVerdict.CONFIRMED, result.verdict)
        assertEquals(3, sent.size)
        assertEquals(2 * AppLauncher.CONFIRM_MS + 500, currentTime)
        assertEquals("com.google.android.tvlauncher", result.attempts.first().foreground)
    }

    @Test
    fun `nothing comes up and every route is reported`() = runTest {
        val launcher = AppLauncher(send = { it !is LaunchRoute.Rest }, foreground = MutableStateFlow("com.android.vending"), canObserve = { true })
        val result = launcher.launch(netflix, LaunchPlan.routes(netflix, true, "rest-uri"))
        assertEquals(LaunchVerdict.FAILED, result.verdict)
        assertEquals(listOf(false, true, true), result.attempts.map { it.sent })
        assertEquals(2 * AppLauncher.CONFIRM_MS, currentTime)
        val trace = result.trace()
        assertTrue("netflix://" in trace && "market://launch?id=$netflix" in trace && "not sent" in trace)
        assertTrue("foreground com.android.vending" in trace)
    }

    @Test
    fun `blind, the first accepted route is the answer`() = runTest {
        val sent = mutableListOf<LaunchRoute>()
        val launcher = AppLauncher(send = { sent += it; true }, foreground = MutableStateFlow(null), canObserve = { false })
        val result = launcher.launch(netflix, LaunchPlan.routes(netflix, true, "rest-uri"))
        assertEquals(LaunchVerdict.SENT, result.verdict)
        assertEquals(listOf<LaunchRoute>(LaunchRoute.Rest("rest-uri")), sent)
        assertEquals(0, currentTime)
    }

    @Test
    fun `no link at all fails at once`() = runTest {
        val launcher = AppLauncher(send = { false }, foreground = MutableStateFlow(null), canObserve = { true })
        val result = launcher.launch(netflix, LaunchPlan.routes(netflix, false, null))
        assertEquals(LaunchVerdict.FAILED, result.verdict)
        assertEquals(0, currentTime)
    }
}
