package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.bravia.TvApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLibraryTest {
    @Test
    fun `package comes out of a bravia app uri`() {
        assertEquals("com.netflix.ninja", AppLibrary.packageOf("com.sony.dtv.com.netflix.ninja.com.netflix.ninja.MainActivity"))
        assertEquals(
            "com.google.android.youtube.tv",
            AppLibrary.packageOf("com.sony.dtv.com.google.android.youtube.tv.com.google.android.apps.youtube.tv.activity.ShellActivity"),
        )
        assertEquals("tv.arte.plus7", AppLibrary.packageOf("com.sony.dtv.tv.arte.plus7.tv.arte.plus7.MainActivity"))
        assertEquals("org.example.player", AppLibrary.packageOf("com.sony.dtv.org.example.player.Launcher"))
        assertNull(AppLibrary.packageOf("localapp://webappruntime?url=http%3A%2F%2Fexample.test"))
        assertNull(AppLibrary.packageOf("com.sony.dtv.single"))
    }

    @Test
    fun `fresh list wins but nothing is forgotten`() {
        val seen = listOf(
            KnownApp("org.example.gone", "Gone", "com.sony.dtv.org.example.gone.Main", "http://192.168.1.20/icon/gone.png"),
            KnownApp("com.netflix.ninja", "Netflix", "old-uri", "http://192.168.1.20/icon/old.png"),
        )
        val fresh = listOf(TvApp("Netflix", "com.sony.dtv.com.netflix.ninja.com.netflix.ninja.MainActivity", icon = null))
        val merged = AppLibrary.remember(seen, fresh)
        assertEquals(listOf("Gone", "Netflix"), merged.map { it.name })
        val netflix = merged.first { it.packageName == "com.netflix.ninja" }
        assertEquals("com.sony.dtv.com.netflix.ninja.com.netflix.ninja.MainActivity", netflix.tvUri)
        assertEquals("http://192.168.1.20/icon/old.png", netflix.iconUrl)
    }

    @Test
    fun `catalog fills in what the tv never listed, once`() {
        val all = AppLibrary.all(listOf(KnownApp("com.netflix.ninja", "Netflix", "uri")))
        assertEquals(1, all.count { it.packageName == "com.netflix.ninja" })
        assertEquals("uri", all.first { it.packageName == "com.netflix.ninja" }.tvUri)
        assertTrue(all.any { it.packageName == "com.google.android.youtube.tv" })
        assertEquals(all.sortedBy { it.name.lowercase() }, all)
    }
}
