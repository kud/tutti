package io.kud.tutti.protocol.apps

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppDestinationsTest {
    private val youtube = "com.google.android.youtube.tv"
    private val netflix = "com.netflix.ninja"
    private val subscriptions = AppShortcut.SUBSCRIPTIONS.link

    @Test
    fun `youtube offers its three verified tabs, not the unverified search`() {
        assertEquals(listOf(AppShortcut.SUBSCRIPTIONS, AppShortcut.HISTORY, AppShortcut.LIBRARY), AppDestinations.curated(youtube))
    }

    @Test
    fun `apps without evidence offer nothing curated`() {
        assertTrue(AppDestinations.curated(netflix).isEmpty())
        assertTrue(AppDestinations.curated("org.example").isEmpty())
    }

    @Test
    fun `custom links are https or the app's own scheme`() {
        assertTrue(AppDestinations.accepts(youtube, "https://www.youtube.com/playlist?list=PLexample"))
        assertTrue(AppDestinations.accepts(youtube, "  https://www.youtube.com/@example  "))
        assertTrue(AppDestinations.accepts(youtube, "vnd.youtube.launch://"))
        assertFalse(AppDestinations.accepts(youtube, "vnd.youtube://abcdefghijk"))
        assertTrue(AppDestinations.accepts(netflix, "https://www.netflix.com/title/12345678?source=30"))
        assertTrue(AppDestinations.accepts(netflix, "netflix://"))
        assertTrue(AppDestinations.accepts("org.example", "https://example.com/x"))
    }

    @Test
    fun `everything else is refused before it is stored`() {
        assertFalse(AppDestinations.accepts(youtube, "http://www.youtube.com/feed/subscriptions"))
        assertFalse(AppDestinations.accepts(youtube, "market://launch?id=$youtube"))
        assertFalse(AppDestinations.accepts(youtube, "intent://x#Intent;end"))
        assertFalse(AppDestinations.accepts(youtube, "javascript:alert(1)"))
        assertFalse(AppDestinations.accepts(youtube, "netflix://"))
        assertFalse(AppDestinations.accepts(netflix, "vnd.youtube://abc"))
        assertFalse(AppDestinations.accepts(youtube, "www.youtube.com/feed/subscriptions"))
        assertFalse(AppDestinations.accepts(youtube, "https://www.youtube.com/a b"))
        assertFalse(AppDestinations.accepts(youtube, "https://"))
        assertFalse(AppDestinations.accepts(youtube, "https://x/" + "a".repeat(LinkProbe.MAX_LENGTH)))
    }

    @Test
    fun `the destination link goes first, then the app's ordinary routes`() {
        val plan = LaunchPlan.toDestination(youtube, subscriptions, restOnline = true, restUri = "com.sony.dtv.yt")
        assertEquals(
            listOf(
                LaunchRoute.AppLink(subscriptions),
                LaunchRoute.Rest("com.sony.dtv.yt"),
                LaunchRoute.AppLink("https://www.youtube.com"),
                LaunchRoute.AppLink("vnd.youtube.launch://"),
                LaunchRoute.AppLink("market://launch?id=$youtube"),
            ),
            plan.routes,
        )
        assertEquals(subscriptions, plan.followUp)
    }

    @Test
    fun `home is a plain launch`() {
        val plan = LaunchPlan.toDestination(youtube, null, restOnline = false, restUri = null)
        assertEquals(LaunchPlan.routes(youtube, false, null), plan.routes)
        assertNull(plan.followUp)
    }

    @Test
    fun `a destination equal to one of the app's links is tried once`() {
        val plan = LaunchPlan.toDestination(youtube, "https://www.youtube.com", restOnline = false, restUri = null)
        assertEquals(1, plan.routes.count { it == LaunchRoute.AppLink("https://www.youtube.com") })
    }

    @Test
    fun `a link that opens the app is not sent again`() = runTest {
        val foreground = MutableStateFlow<String?>("com.google.android.tvlauncher")
        val sent = mutableListOf<LaunchRoute>()
        val launcher = AppLauncher(send = { sent += it; true }, foreground = foreground, canObserve = { true })
        launch { delay(500); foreground.value = youtube }
        val result = launcher.launch(youtube, LaunchPlan.toDestination(youtube, subscriptions, false, null))
        assertEquals(LaunchVerdict.CONFIRMED, result.verdict)
        assertEquals(listOf<LaunchRoute>(LaunchRoute.AppLink(subscriptions)), sent)
        assertNull(result.followUpSent)
    }

    @Test
    fun `a link the tv ignores falls back to opening the app, then goes again`() = runTest {
        val foreground = MutableStateFlow<String?>("com.google.android.tvlauncher")
        val sent = mutableListOf<LaunchRoute>()
        val launcher = AppLauncher(
            send = { route ->
                sent += route
                if (route == LaunchRoute.Rest("com.sony.dtv.yt")) launch { delay(300); foreground.value = youtube }
                true
            },
            foreground = foreground,
            canObserve = { true },
        )
        val result = launcher.launch(youtube, LaunchPlan.toDestination(youtube, subscriptions, true, "com.sony.dtv.yt"))
        assertEquals(LaunchVerdict.CONFIRMED, result.verdict)
        assertEquals(
            listOf(LaunchRoute.AppLink(subscriptions), LaunchRoute.Rest("com.sony.dtv.yt"), LaunchRoute.AppLink(subscriptions)),
            sent,
        )
        assertEquals(true, result.followUpSent)
        assertTrue(result.trace().contains("link resent with the app in front: sent"))
    }

    @Test
    fun `nothing is resent when the app never came up`() = runTest {
        val foreground = MutableStateFlow<String?>("com.google.android.tvlauncher")
        val launcher = AppLauncher(send = { true }, foreground = foreground, canObserve = { true })
        val result = launcher.launch(youtube, LaunchPlan.toDestination(youtube, subscriptions, false, null))
        assertEquals(LaunchVerdict.FAILED, result.verdict)
        assertNull(result.followUpSent)
    }

    @Test
    fun `unobserved, the destination link is the answer`() = runTest {
        val sent = mutableListOf<LaunchRoute>()
        val launcher = AppLauncher(send = { sent += it; true }, foreground = MutableStateFlow(null), canObserve = { false })
        val result = launcher.launch(youtube, LaunchPlan.toDestination(youtube, subscriptions, false, null))
        assertEquals(LaunchVerdict.SENT, result.verdict)
        assertEquals(1, sent.size)
        assertNull(result.followUpSent)
    }

    @Test
    fun `a pin's own choice wins, then the app's default, then as usual`() {
        val subs = AppShortcut.SUBSCRIPTIONS.link
        val history = AppShortcut.HISTORY.link
        assertEquals(history, AppDestinations.resolve(pinLink = history, appDefault = subs))
        assertEquals(subs, AppDestinations.resolve(pinLink = null, appDefault = subs))
        assertNull(AppDestinations.resolve(pinLink = AppDestinations.AS_USUAL, appDefault = subs))
        assertNull(AppDestinations.resolve(pinLink = null, appDefault = null))
        assertNull(AppDestinations.resolve(pinLink = null, appDefault = ""))
    }

    @Test
    fun `a curated link names its shortcut, a custom one none`() {
        assertEquals(AppShortcut.LIBRARY, AppDestinations.shortcutFor(AppShortcut.LIBRARY.link))
        assertNull(AppDestinations.shortcutFor("https://www.youtube.com/watch?v=abc"))
        assertNull(AppDestinations.shortcutFor(null))
    }
}
