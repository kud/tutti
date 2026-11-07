package io.kud.tutti.ui.about

import io.kud.tutti.protocol.update.AppVersion
import io.kud.tutti.ui.about.WhatsNewRule.Hint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class WhatsNewRuleTest {
    private val v030 = AppVersion(0, 3, 0, null)
    private val v023 = AppVersion(0, 2, 3, null)

    @Test
    fun anUpgradeFromBeforeWhatsNewExistedIsTold() {
        // 0.2.x stored no key at all: a set-up install is an upgrade, so it hears about it.
        assertEquals(Hint.SHOW, WhatsNewRule.hint(v030, seen = null, established = true))
    }

    @Test
    fun aFreshInstallIsNeverTold() {
        assertEquals(Hint.RECORD, WhatsNewRule.hint(v030, seen = null, established = false))
    }

    @Test
    fun onceSeenItNeverShowsAgain() {
        assertEquals(Hint.NONE, WhatsNewRule.hint(v030, seen = v030, established = true))
        assertEquals(Hint.NONE, WhatsNewRule.hint(v030, seen = AppVersion(0, 4, 0, null), established = true))
    }

    @Test
    fun aNewerReleaseIsToldAgain() {
        assertEquals(Hint.SHOW, WhatsNewRule.hint(v030, seen = v023, established = true))
        // Even an install that never set up a device: it saw the earlier notes, so it lived through an update.
        assertEquals(Hint.SHOW, WhatsNewRule.hint(v030, seen = v023, established = false))
    }

    @Test
    fun noNotesNothingToSay() {
        assertEquals(Hint.NONE, WhatsNewRule.hint(null, seen = null, established = true))
    }

    @Test
    fun aTestBuildCarriesItsReleasesNotes() {
        assertEquals(v030, ReleaseNotes.forInstalled(AppVersion(0, 3, 0, 4))?.version)
        assertEquals(v030, ReleaseNotes.forInstalled(AppVersion(0, 3, 1, 2))?.version)
        assertEquals(v030, ReleaseNotes.forInstalled(v030)?.version)
        // An older build has no notes of its own here, and an unknown version none at all.
        assertNull(ReleaseNotes.forInstalled(v023))
        assertNull(ReleaseNotes.forInstalled(null))
    }

    @Test
    fun theNotesAreNewestFirst() {
        val versions = ReleaseNotes.all.map { it.version }
        assertEquals(versions.sortedDescending(), versions)
        assertTrue(versions.all { it.dev == null })
    }

    @Test
    fun theFdroidBuildLeavesOutTheUpdatersChanges() {
        val full = ReleaseNotes.latest.forBuild(carriesUpdater = true)
        val fdroid = ReleaseNotes.latest.forBuild(carriesUpdater = false)
        val all = { r: Release -> r.new + r.better + r.fixed }
        // Probe: there is something to leave out, so the check below can fail.
        assertTrue(all(full).any { it.updaterOnly })
        assertFalse(all(fdroid).any { it.updaterOnly })
        assertEquals(all(full).count { !it.updaterOnly }, all(fdroid).size)
    }
}

/** Which notes were read survives the process and only moves forward. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatsNewMemoryTest {
    @Test
    fun rememberedAcrossInstancesAndNeverMovedBack() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(WhatsNewMemory(context).seen)
        WhatsNewMemory(context).markSeen(AppVersion(0, 3, 0, null))
        assertEquals(AppVersion(0, 3, 0, null), WhatsNewMemory(context).seen)
        WhatsNewMemory(context).markSeen(AppVersion(0, 2, 3, null))
        assertEquals(AppVersion(0, 3, 0, null), WhatsNewMemory(context).seen)
        assertEquals(Hint.NONE, WhatsNewRule.hint(AppVersion(0, 3, 0, null), WhatsNewMemory(context).seen, established = true))
    }
}
