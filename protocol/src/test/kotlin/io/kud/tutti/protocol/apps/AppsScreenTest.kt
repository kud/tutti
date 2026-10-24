package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.V2Key
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppsScreenTest {
    private val launcher = AppsScreen.GOOGLE_TV_LAUNCHER
    private val someApp = "org.example.player"

    @Test
    fun `the links are intent uris in the form Intent parseUri reads`() {
        assertEquals(
            "intent:#Intent;launchFlags=0x10000000;component=org.example/.ui.Main;end",
            AppsScreen.componentLink("org.example/.ui.Main"),
        )
        assertEquals(
            "intent:#Intent;action=android.intent.action.ALL_APPS;launchFlags=0x10000000;package=org.example;end",
            AppsScreen.actionLink(AppsScreen.ACTION_ALL_APPS, "org.example"),
        )
        assertFailsWith<IllegalArgumentException> { AppsScreen.componentLink("org.example") }
        assertFailsWith<IllegalArgumentException> { AppsScreen.componentLink("org.example/") }
    }

    @Test
    fun `routes go from the activity to the action to the keycode`() {
        val routes = AppsScreen.routes()
        assertEquals(3, routes.size)
        assertTrue((routes[0] as AppsScreenRoute.Link).link.contains("component=$launcher/"))
        assertTrue((routes[1] as AppsScreenRoute.Link).link.contains("action=android.intent.action.ALL_APPS"))
        assertTrue((routes[1] as AppsScreenRoute.Link).link.contains("package=$launcher;"))
        assertEquals(AppsScreenRoute.Key(V2Key.ALL_APPS), routes[2])
        assertEquals(284, V2Key.ALL_APPS)
    }

    @Test
    fun `opened as soon as the launcher comes to the front`() = runTest {
        val foreground = MutableStateFlow<String?>(someApp)
        val sent = mutableListOf<AppsScreenRoute>()
        val opener = AppsScreenOpener(send = { sent += it; true }, foreground = foreground, current = { foreground.value })
        launch {
            delay(600)
            foreground.value = launcher
        }
        val result = opener.open()
        assertEquals(AppsScreenVerdict.OPENED, result.verdict)
        assertEquals(1, sent.size)
        assertEquals(600, currentTime)
        assertEquals(launcher, result.attempts.single().foreground)
    }

    @Test
    fun `a route the tv ignores falls through to the next after two seconds`() = runTest {
        val foreground = MutableStateFlow<String?>(someApp)
        val routes = AppsScreen.routes()
        val opener = AppsScreenOpener(
            send = { route ->
                if (route is AppsScreenRoute.Key) launch { delay(300); foreground.value = launcher }
                true
            },
            foreground = foreground,
            current = { foreground.value },
        )
        val result = opener.open(routes)
        assertEquals(AppsScreenVerdict.OPENED, result.verdict)
        assertEquals(routes, result.attempts.map { it.route })
        assertEquals(2 * AppsScreenOpener.CONFIRM_MS + 300, currentTime)
        assertEquals(someApp, result.attempts.first().foreground)
    }

    @Test
    fun `nothing coming up is a failure, never a success`() = runTest {
        val foreground = MutableStateFlow<String?>(someApp)
        val result = AppsScreenOpener(send = { true }, foreground = foreground, current = { foreground.value }).open()
        assertEquals(AppsScreenVerdict.FAILED, result.verdict)
        assertEquals(3, result.attempts.size)
        assertEquals(3 * AppsScreenOpener.CONFIRM_MS, currentTime)
    }

    @Test
    fun `another app opening is not the launcher`() = runTest {
        val foreground = MutableStateFlow<String?>(someApp)
        val opener = AppsScreenOpener(
            send = { foreground.value = "com.android.vending"; true },
            foreground = foreground,
            current = { foreground.value },
        )
        assertEquals(AppsScreenVerdict.FAILED, opener.open().verdict)
    }

    @Test
    fun `with the launcher already in front every route goes and nothing is claimed`() = runTest {
        val foreground = MutableStateFlow<String?>(launcher)
        val sent = mutableListOf<AppsScreenRoute>()
        val result = AppsScreenOpener(send = { sent += it; true }, foreground = foreground, current = { foreground.value }).open()
        assertEquals(AppsScreenVerdict.SENT, result.verdict)
        assertEquals(AppsScreen.routes(), sent)
        assertEquals(0, currentTime)
    }

    @Test
    fun `refused routes are skipped and all refused is not sent`() = runTest {
        val foreground = MutableStateFlow<String?>(someApp)
        val none = AppsScreenOpener(send = { false }, foreground = foreground, current = { foreground.value }).open()
        assertEquals(AppsScreenVerdict.NOT_SENT, none.verdict)
        assertTrue(none.attempts.none { it.sent })
        assertEquals(0, currentTime)

        val onlyKey = AppsScreenOpener(
            send = { route -> route is AppsScreenRoute.Key && run { foreground.value = launcher; true } },
            foreground = foreground,
            current = { foreground.value },
        ).open()
        assertEquals(AppsScreenVerdict.OPENED, onlyKey.verdict)
        assertEquals(listOf(false, false, true), onlyKey.attempts.map { it.sent })
    }

    @Test
    fun `the trace names each route and what was seen`() {
        val result = AppsScreenResult(
            AppsScreenVerdict.FAILED,
            listOf(
                AppsScreenAttempt(AppsScreenRoute.Link("intent:#Intent;end"), sent = true, foreground = someApp),
                AppsScreenAttempt(AppsScreenRoute.Key(284), sent = false, foreground = null),
            ),
        )
        assertEquals(
            "apps screen: failed\n1. remote app link intent:#Intent;end - sent, foreground $someApp\n2. key 284 - not sent",
            result.trace(),
        )
    }
}
