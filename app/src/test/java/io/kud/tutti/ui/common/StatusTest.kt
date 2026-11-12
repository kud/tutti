package io.kud.tutti.ui.common

import io.kud.tutti.ui.remote.ConnWords
import io.kud.tutti.ui.settings.UnlockStatus
import io.kud.tutti.ui.settings.status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** tutti's one status language (design.md › Status). */
class StatusTest {
    @Test fun `every status has a glyph of its own`() {
        val glyphs = Status.entries.map { statusLook(it).glyph }
        assertEquals(Status.entries.size, glyphs.toSet().size)
    }

    @Test fun `only needs-you is emphasised`() {
        assertEquals(listOf(Status.NEEDS_YOU), Status.entries.filter { statusLook(it).emphasised })
    }

    @Test fun `a link's words map to the shared statuses`() {
        assertEquals(Status.OK, ConnWords.CONNECTED.mark)
        assertEquals(Status.WORKING, ConnWords.RECONNECTING.mark)
        assertEquals(Status.STANDBY, ConnWords.STANDBY.mark)
        assertEquals(Status.NOT_YET, ConnWords.NOT_ADDED.mark)
        assertEquals(Status.NEEDS_YOU, ConnWords.NEEDS_PAIRING.mark)
        // No link word is ever "unavailable": a link is either reachable, asleep, to set up, or his to fix.
        assertTrue(ConnWords.entries.none { it.mark == Status.UNAVAILABLE })
    }

    @Test fun `a setup step's status maps to the shared statuses`() {
        assertEquals(Status.OK, UnlockStatus.DONE.status)
        assertEquals(Status.NOT_YET, UnlockStatus.TODO.status)
        assertEquals(Status.NEEDS_YOU, UnlockStatus.ATTENTION.status)
        assertEquals(Status.WORKING, UnlockStatus.CHECKING.status)
        for (s in listOf(UnlockStatus.BLOCKED, UnlockStatus.UNKNOWN)) assertEquals(Status.UNAVAILABLE, s.status)
    }

    @Test fun `every setup status and link word lands on a status`() {
        // Exhaustive by construction; this guards a new entry from slipping past the mapping.
        UnlockStatus.entries.forEach { it.status }
        ConnWords.entries.forEach { it.mark }
    }
}
