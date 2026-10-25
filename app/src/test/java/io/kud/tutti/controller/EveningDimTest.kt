package io.kud.tutti.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class EveningDimTest {
    @Test
    fun `dims in the evening with the tv on and the switch on`() {
        assertEquals(EveningDim.LEVEL, dimTarget(enabled = true, tvOn = true, hour = 21, userChangedBrightness = false))
        assertEquals(EveningDim.LEVEL, dimTarget(enabled = true, tvOn = true, hour = 0, userChangedBrightness = false))
        assertEquals(EveningDim.LEVEL, dimTarget(enabled = true, tvOn = true, hour = 4, userChangedBrightness = false))
    }

    @Test
    fun `leaves the phone's brightness alone otherwise`() {
        assertNull(dimTarget(enabled = false, tvOn = true, hour = 21, userChangedBrightness = false))
        assertNull(dimTarget(enabled = true, tvOn = false, hour = 21, userChangedBrightness = false))
        assertNull(dimTarget(enabled = true, tvOn = true, hour = 21, userChangedBrightness = true))
        assertNull(dimTarget(enabled = true, tvOn = true, hour = 14, userChangedBrightness = false))
    }

    @Test
    fun `the evening runs from 20 00 to 05 00`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 20, 21, 22, 23), (0..23).filter(EveningDim::isEvening))
    }

    @Test
    fun `after midnight is still the evening that started the day before`() {
        val day = LocalDate.of(2026, 10, 25)
        assertEquals(day, EveningDim.eveningOf(LocalDateTime.of(2026, 10, 25, 21, 0)))
        assertEquals(day, EveningDim.eveningOf(LocalDateTime.of(2026, 10, 26, 1, 30)))
        assertEquals(day.plusDays(1), EveningDim.eveningOf(LocalDateTime.of(2026, 10, 26, 20, 0)))
    }
}
