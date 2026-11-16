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

    @Test
    fun `recents are newest first, without the app in front, the launcher or settings`() {
        val history = of("a.one", "a.two", AppsScreen.GOOGLE_TV_LAUNCHER, "com.android.tv.settings", "a.three")
        assertEquals(listOf("a.two", "a.one"), history.others())
    }

    @Test
    fun `an app opened again moves to the front instead of showing twice`() {
        assertEquals(listOf("a.three", "a.two"), of("a.one", "a.two", "a.three", "a.one").others())
    }

    @Test
    fun `recents stop at four`() {
        val history = of("a.1", "a.2", "a.3", "a.4", "a.5", "a.6")
        assertEquals(listOf("a.5", "a.4", "a.3", "a.2"), history.others())
    }
}
