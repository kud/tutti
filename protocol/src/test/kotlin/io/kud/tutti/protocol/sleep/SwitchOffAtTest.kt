package io.kud.tutti.protocol.sleep

import io.kud.tutti.protocol.sleep.SwitchOffAt.Plan
import io.kud.tutti.protocol.sleep.SwitchOffAt.Refusal
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchOffAtTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun at(text: String, zone: ZoneId = paris): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun next(now: String, time: String, zone: ZoneId = paris): ZonedDateTime =
        SwitchOffAt.nextOccurrence(at(now, zone), LocalTime.parse(time), zone).atZone(zone)

    @Test
    fun `a time still ahead is today`() {
        assertEquals(LocalDateTime.parse("2026-11-11T21:50"), next("2026-11-11T21:30", "21:50").toLocalDateTime())
    }

    @Test
    fun `a time already past, or this very minute, is tomorrow`() {
        assertEquals(LocalDateTime.parse("2026-11-12T21:50"), next("2026-11-11T22:00", "21:50").toLocalDateTime())
        assertEquals(LocalDateTime.parse("2026-11-12T21:50"), next("2026-11-11T21:50:20", "21:50").toLocalDateTime())
    }

    @Test
    fun `around midnight the next day is the right one`() {
        assertEquals(LocalDateTime.parse("2026-11-12T00:10"), next("2026-11-11T23:55", "00:10").toLocalDateTime())
        assertEquals(LocalDateTime.parse("2026-11-12T23:50"), next("2026-11-12T00:05", "23:50").toLocalDateTime())
        assertEquals(LocalDateTime.parse("2027-01-01T00:30"), next("2026-12-31T23:40", "00:30").toLocalDateTime())
    }

    @Test
    fun `tomorrow is said when the end falls on a later day`() {
        val now = at("2026-11-11T23:55")
        assertTrue(SwitchOffAt.isTomorrow(now, at("2026-11-12T00:10"), paris))
        assertFalse(SwitchOffAt.isTomorrow(now, at("2026-11-11T23:59"), paris))
        val plan = SwitchOffAt.plan(now, LocalTime.of(0, 10), paris)
        assertEquals(Plan.Start(at("2026-11-12T00:10").toEpochMilli(), tomorrow = true), plan)
    }

    @Test
    fun `a time skipped when the clocks go forward lands just after the gap`() {
        // Paris, 2027-03-28: 02:00 becomes 03:00.
        val end = next("2027-03-28T01:00", "02:30")
        assertEquals(LocalDateTime.parse("2027-03-28T03:30"), end.toLocalDateTime())
        // 01:00 winter to 03:30 summer: 2 h 30 on the clock, 1 h 30 of real time.
        assertEquals(90 * 60_000L, end.toInstant().toEpochMilli() - at("2027-03-28T01:00").toEpochMilli())
    }

    @Test
    fun `the real lead counts the hour lost when the clocks go forward`() {
        val now = at("2027-03-27T23:00")
        val end = SwitchOffAt.nextOccurrence(now, LocalTime.of(7, 0), paris)
        assertEquals(7 * 60 * 60_000L, end.toEpochMilli() - now.toEpochMilli())
    }

    @Test
    fun `a time read twice when the clocks go back takes the reading still ahead`() {
        // Paris, 2026-10-25: 03:00 summer time becomes 02:00 winter time.
        val before = at("2026-10-25T01:30")
        val first = SwitchOffAt.nextOccurrence(before, LocalTime.of(2, 30), paris)
        assertEquals(60 * 60_000L, first.toEpochMilli() - before.toEpochMilli())

        // 02:45 summer time (the first reading): 02:30 summer is past, 02:30 winter is 45 min away.
        val firstReading = ZonedDateTime.ofLocal(LocalDateTime.parse("2026-10-25T02:45"), paris, null).toInstant()
        val second = SwitchOffAt.nextOccurrence(firstReading, LocalTime.of(2, 30), paris)
        assertEquals(45 * 60_000L, second.toEpochMilli() - firstReading.toEpochMilli())
    }

    @Test
    fun `a minute behind now on the night the clocks go back is 24 h 59 min away and still allowed`() {
        val now = at("2026-10-24T09:50")
        val plan = SwitchOffAt.plan(now, LocalTime.of(9, 49), paris)
        val start = plan as Plan.Start
        assertEquals((24 * 60 + 59) * 60_000L, start.endsAt - now.toEpochMilli())
        assertTrue(start.tomorrow)
        assertTrue(SwitchOffAt.accepts(now.toEpochMilli(), start.endsAt))
    }

    @Test
    fun `a time closer than the fade is refused`() {
        val now = at("2026-11-11T21:48:30")
        assertEquals(Plan.Refused(Refusal.TOO_SOON), SwitchOffAt.plan(now, LocalTime.of(21, 50), paris))
        assertEquals(Plan.Start(at("2026-11-11T21:51").toEpochMilli(), tomorrow = false), SwitchOffAt.plan(now, LocalTime.of(21, 51), paris))
        val exactlyTheFade = at("2026-11-11T21:48")
        assertEquals(Plan.Start(at("2026-11-11T21:50").toEpochMilli(), tomorrow = false), SwitchOffAt.plan(exactlyTheFade, LocalTime.of(21, 50), paris))
    }

    @Test
    fun `refusals name the reason`() {
        val now = 1_000_000_000L
        assertEquals(Refusal.PAST, SwitchOffAt.refusal(now, now))
        assertEquals(Refusal.PAST, SwitchOffAt.refusal(now, now - 1))
        assertEquals(Refusal.TOO_SOON, SwitchOffAt.refusal(now, now + SleepFade.FADE_MS - 1))
        assertNull(SwitchOffAt.refusal(now, now + SleepFade.FADE_MS))
        assertNull(SwitchOffAt.refusal(now, now + SwitchOffAt.MAX_LEAD_MS))
        assertEquals(Refusal.TOO_FAR, SwitchOffAt.refusal(now, now + SwitchOffAt.MAX_LEAD_MS + 1))
    }

    @Test
    fun `the service accepts a sheet-checked end a moment later, nothing past or beyond a day`() {
        val now = 1_000_000_000L
        assertTrue(SwitchOffAt.accepts(now, now + SleepFade.FADE_MS - 1_000L))
        assertFalse(SwitchOffAt.accepts(now, now + SleepFade.FADE_MS - SwitchOffAt.START_GRACE_MS - 1))
        assertFalse(SwitchOffAt.accepts(now, now - 1))
        assertFalse(SwitchOffAt.accepts(now, 0L))
        assertFalse(SwitchOffAt.accepts(now, now + SwitchOffAt.MAX_LEAD_MS + 1))
        assertTrue(SwitchOffAt.accepts(now, now + 9 * 60 * 60_000L))
    }

    @Test
    fun `hours and minutes left round up like minutes left`() {
        val now = 1_000_000_000L
        assertEquals(0 to 12, SwitchOffAt.hoursAndMinutesLeft(now, now + 11 * 60_000L + 1))
        assertEquals(1 to 0, SwitchOffAt.hoursAndMinutesLeft(now, now + 60 * 60_000L))
        assertEquals(9 to 50, SwitchOffAt.hoursAndMinutesLeft(now, now + (9 * 60 + 50) * 60_000L))
    }

    @Test
    fun `ten minutes is offered and still leaves its fade whole`() {
        assertEquals(10, SleepFade.DURATIONS_MIN.first())
        val now = 1_000_000_000L
        val end = SleepFade.endsAt(now, 10)
        assertEquals(SleepFade.Phase.WAITING, SleepFade.phase(now, end))
        assertEquals(now + 8 * 60_000L, SleepFade.fadeStartsAt(end))
        assertTrue(SleepFade.DURATIONS_MIN.all { it * 60_000L >= SwitchOffAt.MIN_LEAD_MS })
    }
}
