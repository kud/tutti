package io.kud.tutti.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListRefreshTest {
    private val asked = 1_000_000L

    @Test
    fun `never asked while the TV is off`() {
        assertFalse(AppListRefresh.due(active = false, wasActive = false, hasApps = false, askedAt = null, now = asked))
        assertFalse(AppListRefresh.due(active = false, wasActive = true, hasApps = true, askedAt = 0, now = asked + AppListRefresh.EVERY_MS * 10))
    }

    @Test
    fun `asked on every poll until a list is known`() {
        assertTrue(AppListRefresh.due(active = true, wasActive = true, hasApps = false, askedAt = asked, now = asked + 1))
    }

    @Test
    fun `asked again when the TV wakes or REST reconnects`() {
        assertTrue(AppListRefresh.due(active = true, wasActive = false, hasApps = true, askedAt = asked, now = asked + 1))
    }

    @Test
    fun `asked again only once the interval has passed while the TV stays on`() {
        assertFalse(AppListRefresh.due(active = true, wasActive = true, hasApps = true, askedAt = asked, now = asked + AppListRefresh.EVERY_MS - 1))
        assertTrue(AppListRefresh.due(active = true, wasActive = true, hasApps = true, askedAt = asked, now = asked + AppListRefresh.EVERY_MS))
    }

    @Test
    fun `a list known from before with no ask this session is asked`() {
        assertTrue(AppListRefresh.due(active = true, wasActive = true, hasApps = true, askedAt = null, now = asked))
    }
}
