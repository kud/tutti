package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class TileOutcomeTest {
    @Test
    fun `a value the bar confirms settles as confirmed`() {
        val outcome = TileOutcome()
        assertEquals(TileSettle.NONE, outcome.observe(pending = "on", shown = "on", connected = true))
        assertEquals(TileSettle.CONFIRMED, outcome.observe(pending = null, shown = "on", connected = true))
    }

    @Test
    fun `a rollback settles as refused`() {
        val outcome = TileOutcome()
        outcome.observe(pending = "on", shown = "on", connected = true)
        assertEquals(TileSettle.REFUSED, outcome.observe(pending = null, shown = "off", connected = true))
    }

    @Test
    fun `a disconnect is not the tile's failure`() {
        val outcome = TileOutcome()
        outcome.observe(pending = "on", shown = "on", connected = true)
        assertEquals(TileSettle.NONE, outcome.observe(pending = null, shown = "off", connected = false))
    }

    @Test
    fun `a newer request replaces the old one without settling`() {
        val outcome = TileOutcome()
        outcome.observe(pending = "on", shown = "on", connected = true)
        assertEquals(TileSettle.NONE, outcome.observe(pending = "off", shown = "off", connected = true))
        assertEquals(TileSettle.CONFIRMED, outcome.observe(pending = null, shown = "off", connected = true))
    }

    @Test
    fun `settles once, then rests`() {
        val outcome = TileOutcome()
        outcome.observe(pending = "on", shown = "on", connected = true)
        outcome.observe(pending = null, shown = "off", connected = true)
        assertEquals(TileSettle.NONE, outcome.observe(pending = null, shown = "off", connected = true))
    }

    @Test
    fun `nothing asked, nothing settles`() {
        assertEquals(TileSettle.NONE, TileOutcome().observe(pending = null, shown = "off", connected = true))
    }

    @Test
    fun `a refused value that the bar reports after all settles as late`() {
        val outcome = TileOutcome()
        outcome.observe(pending = "on", shown = "on", connected = true)
        assertEquals(TileSettle.REFUSED, outcome.observe(pending = null, shown = "off", connected = true))
        assertEquals(TileSettle.LATE, outcome.observe(pending = null, shown = "on", connected = true))
        assertEquals(TileSettle.NONE, outcome.observe(pending = null, shown = "on", connected = true))
    }

    @Test
    fun `a new request forgets the missed value`() {
        val outcome = TileOutcome()
        outcome.observe(pending = "on", shown = "on", connected = true)
        outcome.observe(pending = null, shown = "off", connected = true)
        outcome.observe(pending = "off", shown = "off", connected = true)
        assertEquals(TileSettle.REFUSED, outcome.observe(pending = null, shown = "on", connected = true))
    }
}
