package io.kud.tutti.protocol.diagnostic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkTimingsTest {
    @Test
    fun `time to ready runs from the attempt that got there`() {
        val t = LinkTimings()
        t.started(TimedLink.REMOTE, 1_000)
        // A failed attempt is replaced by the next one.
        t.started(TimedLink.REMOTE, 5_000)
        t.ready(TimedLink.REMOTE, 5_840)
        assertEquals(LinkTimings.Stat(840, 840, 840, 1), t.stat(TimedLink.REMOTE))
    }

    @Test
    fun `last, best and worst over every ready`() {
        val t = LinkTimings()
        for ((start, end) in listOf(0L to 900L, 10_000L to 10_600L, 20_000L to 22_300L)) {
            t.started(TimedLink.BAR, start)
            t.ready(TimedLink.BAR, end)
        }
        assertEquals(LinkTimings.Stat(2_300, 600, 2_300, 3), t.stat(TimedLink.BAR))
    }

    @Test
    fun `ready without an attempt, or twice, counts once at most`() {
        val t = LinkTimings()
        t.ready(TimedLink.REST, 100)
        assertNull(t.stat(TimedLink.REST))
        t.started(TimedLink.REST, 0)
        t.ready(TimedLink.REST, 400)
        t.ready(TimedLink.REST, 900)
        assertEquals(1, t.stat(TimedLink.REST)!!.count)
        t.record(TimedLink.CAST, -5)
        assertNull(t.stat(TimedLink.CAST))
    }

    @Test
    fun `the report lists every link, in words`() {
        val t = LinkTimings()
        t.record(TimedLink.CAST, 1_200)
        assertEquals(
            listOf(
                "ready in, tv remote v2" to "not ready this session",
                "ready in, tv ip control" to "not ready this session",
                "ready in, soundbar tcp" to "not ready this session",
                "ready in, cast" to "last 1200 ms, best 1200 ms, worst 1200 ms, 1 time",
            ),
            t.report(),
        )
    }
}
