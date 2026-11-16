package io.kud.tutti.protocol.apps

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LinkProbeTest {
    private val youtube = "com.google.android.youtube.tv"
    private val launcher = "com.google.android.tvlauncher"

    @Test
    fun `only a link with a scheme and no whitespace is sent`() {
        assertTrue(LinkProbe.valid("https://www.youtube.com/feed/subscriptions"))
        assertTrue(LinkProbe.valid("vnd.youtube://abc"))
        assertFalse(LinkProbe.valid("www.youtube.com/feed/history"))
        assertFalse(LinkProbe.valid("https://www.youtube.com/ feed"))
        assertFalse(LinkProbe.valid(""))
        assertFalse(LinkProbe.valid("https://x/" + "a".repeat(LinkProbe.MAX_LENGTH)))
        assertTrue(LinkProbe.YOUTUBE_PRESETS.all(LinkProbe::valid))
    }

    @Test
    fun `an invalid link never reaches the tv`() = runTest {
        var sent = 0
        val result = LinkProbe.probe("not a link", { sent++; true }, MutableStateFlow(youtube), { true })
        assertEquals(LinkOutcome.INVALID, result.outcome)
        assertEquals(0, sent)
    }

    @Test
    fun `a refused send says so with what was in front`() = runTest {
        val result = LinkProbe.probe("https://www.youtube.com", { false }, MutableStateFlow(youtube), { true })
        assertEquals(LinkProbeResult(LinkOutcome.NOT_SENT, youtube, null), result)
    }

    @Test
    fun `without a remote link the answer stops at sent`() = runTest {
        val result = LinkProbe.probe("https://www.youtube.com", { true }, MutableStateFlow(youtube), { false })
        assertEquals(LinkOutcome.SENT_UNOBSERVED, result.outcome)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `an app that comes up is reported after the whole watch`() = runTest {
        val foreground = MutableStateFlow<String?>(launcher)
        val result = LinkProbe.probe(
            "https://www.youtube.com/feed/subscriptions",
            { launch { delay(400); foreground.value = youtube }; true },
            foreground,
            { true },
        )
        assertEquals(LinkProbeResult(LinkOutcome.OPENED, launcher, youtube), result)
        assertEquals(LinkProbe.WATCH_MS, currentTime)
    }

    @Test
    fun `the same app in front cannot tell a navigation from an ignored link`() = runTest {
        val result = LinkProbe.probe("https://www.youtube.com/feed/history", { true }, MutableStateFlow(youtube), { true })
        assertEquals(LinkProbeResult(LinkOutcome.SAME_APP, youtube, youtube), result)
    }

    @Test
    fun `no app in front afterwards reads as such`() = runTest {
        val foreground = MutableStateFlow<String?>(youtube)
        val result = LinkProbe.probe("vnd.youtube://x", { launch { delay(100); foreground.value = null }; true }, foreground, { true })
        assertEquals(LinkOutcome.NO_APP, result.outcome)
    }
}
