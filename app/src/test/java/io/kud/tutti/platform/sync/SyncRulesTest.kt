package io.kud.tutti.platform.sync

import io.kud.tutti.protocol.sync.SyncFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The folder's file and the stale-read guard: the parts of sync that don't need a provider. */
class SyncRulesTest {
    @Test
    fun anEmptyFolderHasNoFile() {
        assertNull(SyncRules.pick(emptyList()))
    }

    @Test
    fun onlyTheExactNameAmongFilesCounts() {
        val children = listOf(
            SyncChild("a", "tutti-sync (1).json", 30),
            SyncChild("b", "Tutti-Sync.json", 30),
            SyncChild("c", SyncFile.NAME, 99, isDirectory = true),
            SyncChild("d", null, 99),
        )
        assertNull(SyncRules.pick(children))
        assertEquals("e", SyncRules.pick(children + SyncChild("e", SyncFile.NAME, 1)))
    }

    @Test
    fun severalMatchesUseTheNewestAndUnknownTimesCountAsOldest() {
        val children = listOf(
            SyncChild("old", SyncFile.NAME, 10),
            SyncChild("unknown", SyncFile.NAME, null),
            SyncChild("zero", SyncFile.NAME, 0),
            SyncChild("new", SyncFile.NAME, 20),
        )
        assertEquals("new", SyncRules.pick(children))
        val tie = listOf(SyncChild("first", SyncFile.NAME, null), SyncChild("second", SyncFile.NAME, null))
        assertEquals("first", SyncRules.pick(tie))
    }

    @Test
    fun readingWhatTheLastWriteReplacedIsStale() {
        assertTrue(SyncRules.isStaleRead("old", replacedHash = "old", writtenHash = "new"))
        assertFalse(SyncRules.isStaleRead("new", replacedHash = "old", writtenHash = "new"))
        assertFalse(SyncRules.isStaleRead("other", replacedHash = "old", writtenHash = "new"))
        assertFalse(SyncRules.isStaleRead("old", replacedHash = null, writtenHash = "new"))
        // A write that put back the same bytes isn't stale when read.
        assertFalse(SyncRules.isStaleRead("same", replacedHash = "same", writtenHash = "same"))
    }
}
