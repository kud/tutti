package io.kud.tutti.ui.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNoticeGateTest {
    @Test
    fun speaksOnThePlainRemote() {
        assertTrue(updateNoticeAllowed(onRemote = true, editing = false, covers = 0, messageShowing = false))
    }

    @Test
    fun skipsAwayFromTheRemote() {
        assertFalse(updateNoticeAllowed(onRemote = false, editing = false, covers = 0, messageShowing = false))
    }

    @Test
    fun skipsWhileEditingTheDock() {
        assertFalse(updateNoticeAllowed(onRemote = true, editing = true, covers = 0, messageShowing = false))
    }

    @Test
    fun skipsUnderASheetOrMenu() {
        assertFalse(updateNoticeAllowed(onRemote = true, editing = false, covers = 1, messageShowing = false))
        assertFalse(updateNoticeAllowed(onRemote = true, editing = false, covers = 2, messageShowing = false))
    }

    @Test
    fun neverReplacesAnotherMessage() {
        assertFalse(updateNoticeAllowed(onRemote = true, editing = false, covers = 0, messageShowing = true))
    }
}
