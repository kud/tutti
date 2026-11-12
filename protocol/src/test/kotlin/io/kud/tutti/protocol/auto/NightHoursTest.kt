package io.kud.tutti.protocol.auto

import io.kud.tutti.protocol.auto.NightHours.Bar
import io.kud.tutti.protocol.auto.NightHours.Feature
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightHoursTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val start = NightHours.DEFAULT_START
    private val end = NightHours.DEFAULT_END

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(paris).toInstant()

    private fun bar(night: Boolean?, voice: Boolean?, connected: Boolean = true) =
        Bar(connected, mapOf(Feature.NIGHT to night, Feature.VOICE to voice))

    private fun step(session: NightHours.Session?, now: Instant, bar: Bar, enabled: Boolean = true) =
        NightHours.step(session, enabled, now, paris, start, end, bar)

    @Test
    fun `the window crosses midnight`() {
        assertTrue(NightHours.inside(22 * 60, start, end))
        assertTrue(NightHours.inside(23 * 60 + 59, start, end))
        assertTrue(NightHours.inside(0, start, end))
        assertTrue(NightHours.inside(6 * 60 + 59, start, end))
        assertFalse(NightHours.inside(7 * 60, start, end))
        assertFalse(NightHours.inside(21 * 60 + 59, start, end))
        // A daytime window, and an empty one.
        assertTrue(NightHours.inside(13 * 60, 12 * 60, 14 * 60))
        assertFalse(NightHours.inside(15 * 60, 12 * 60, 14 * 60))
        assertFalse(NightHours.inside(12 * 60, 12 * 60, 12 * 60))
    }

    @Test
    fun `the end is fixed at the next morning, across both clock changes`() {
        assertEquals(at("2026-11-13T07:00"), NightHours.endOf(at("2026-11-12T22:00"), paris, end))
        assertEquals(at("2026-11-13T07:00"), NightHours.endOf(at("2026-11-13T01:00"), paris, end))
        // Spring forward (29 March 2026): the night is an hour shorter, the end still 07:00.
        assertEquals(at("2026-03-29T07:00"), NightHours.endOf(at("2026-03-28T22:30"), paris, end))
        // Fall back (25 October 2026): an hour longer, and an end at 02:30 read twice takes the first.
        assertEquals(at("2026-10-25T07:00"), NightHours.endOf(at("2026-10-24T22:30"), paris, end))
        val doubled = NightHours.endOf(at("2026-10-24T23:00"), paris, 2 * 60 + 30)
        assertEquals(LocalDateTime.parse("2026-10-25T02:30"), doubled.atZone(paris).toLocalDateTime())
        assertEquals(doubled, LocalDateTime.parse("2026-10-25T02:30").atZone(paris).withEarlierOffsetAtOverlap().toInstant())
        // An end that doesn't exist (02:30 on the spring night) lands after the gap.
        assertEquals(LocalDateTime.parse("2026-03-29T03:30"), NightHours.endOf(at("2026-03-28T23:00"), paris, 2 * 60 + 30).atZone(paris).toLocalDateTime())
    }

    @Test
    fun `opens once the bar has answered, turns on only what was off, and puts it back`() {
        assertNull(step(null, at("2026-11-12T22:05"), bar(false, null)).session)
        val open = step(null, at("2026-11-12T22:05"), bar(night = false, voice = true))
        assertEquals(mapOf(Feature.NIGHT to true), open.set)
        val session = assertNotNull(open.session)
        // Holding: nothing sent while the bar confirms.
        val held = step(session, at("2026-11-12T23:00"), bar(true, true))
        assertEquals(emptyMap(), held.set)
        assertNotNull(held.session)
        val closed = step(held.session, at("2026-11-13T07:01"), bar(true, true))
        assertEquals(mapOf(Feature.NIGHT to false), closed.set)
        assertNull(closed.session)
    }

    @Test
    fun `a manual change in the window is left alone, now and at the end`() {
        val open = step(null, at("2026-11-12T22:05"), bar(false, false))
        assertEquals(mapOf(Feature.NIGHT to true, Feature.VOICE to true), open.set)
        val landed = step(open.session, at("2026-11-12T22:06"), bar(true, true)).session
        // He turns night mode off himself.
        val manual = step(landed, at("2026-11-12T23:00"), bar(false, true))
        assertEquals(emptyMap(), manual.set)
        assertEquals(setOf(Feature.NIGHT), manual.session?.handsOff)
        val closed = step(manual.session, at("2026-11-13T07:00"), bar(false, true))
        assertEquals(mapOf(Feature.VOICE to false), closed.set)
    }

    @Test
    fun `a value not yet landed is not taken for a manual change`() {
        val open = step(null, at("2026-11-12T22:05"), bar(false, true))
        // The bar still reports the old value a moment later.
        val early = step(open.session, at("2026-11-12T22:05"), bar(false, true))
        assertEquals(emptySet(), early.session?.handsOff)
    }

    @Test
    fun `an end slept through is restored at the next connect`() {
        val open = step(null, at("2026-11-12T22:05"), bar(false, false)).session
        val away = step(open, at("2026-11-13T09:00"), bar(null, null, connected = false))
        assertEquals(emptyMap(), away.set)
        assertNotNull(away.session)
        val back = step(away.session, at("2026-11-13T18:00"), bar(true, true))
        assertEquals(mapOf(Feature.NIGHT to false, Feature.VOICE to false), back.set)
        assertNull(back.session)
    }

    @Test
    fun `a whole day slept through rolls into the next window and keeps what to put back`() {
        val open = step(null, at("2026-11-12T22:05"), bar(false, true)).session
        val next = step(open, at("2026-11-13T22:30"), bar(true, true))
        assertEquals(emptyMap(), next.set)
        assertEquals(at("2026-11-14T07:00").toEpochMilli(), next.session?.endsAt)
        val closed = step(next.session, at("2026-11-14T07:00"), bar(true, true))
        assertEquals(mapOf(Feature.NIGHT to false), closed.set)
    }

    @Test
    fun `turning the option off mid-window restores at once`() {
        val open = step(null, at("2026-11-12T22:05"), bar(false, true)).session
        val off = step(open, at("2026-11-12T23:00"), bar(true, true), enabled = false)
        assertEquals(mapOf(Feature.NIGHT to false), off.set)
        assertNull(off.session)
        // And nothing opens while it is off.
        assertNull(step(null, at("2026-11-12T23:01"), bar(false, true), enabled = false).session)
    }

    @Test
    fun `the night guard sits at two thirds of the day's`() {
        assertEquals(20, NightHours.guardLine(30))
        assertEquals(NightHours.MIN_LINE, NightHours.guardLine(6))
    }
}
