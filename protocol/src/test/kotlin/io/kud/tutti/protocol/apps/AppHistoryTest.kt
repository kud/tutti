package io.kud.tutti.protocol.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppHistoryTest {
    private fun of(vararg packages: String?) = packages.fold(AppHistory()) { history, p -> history.seen(p) }

    @Test
    fun `the app before the current one is the way back`() {
        assertEquals("app.one", of("app.one", "app.two").back())
    }

    @Test
    fun `nothing to go back to with a single app`() {
        assertNull(of("app.one").back())
        assertNull(AppHistory().back())
    }

    @Test
    fun `the launcher is passed through, never remembered`() {
        val history = of("app.one", AppsScreen.GOOGLE_TV_LAUNCHER, "app.two")
        assertEquals("app.two", history.current)
        assertEquals("app.one", history.back())
    }

    @Test
    fun `the same app reported again moves nothing`() {
        assertEquals("app.one", of("app.one", "app.two", "app.two").back())
    }

    @Test
    fun `a null report keeps both`() {
        assertEquals(AppHistory("app.two", "app.one"), of("app.one", "app.two", null))
    }

    @Test
    fun `going back swaps them, so a second long press returns`() {
        val back = of("app.one", "app.two").let { it.seen(it.back()) }
        assertEquals("app.one", back.current)
        assertEquals("app.two", back.back())
    }
}
