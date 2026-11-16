package io.kud.tutti.protocol.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForegroundNameTest {
    private val sideloaded = KnownApp(packageName = "com.example.player", name = "Example Player")

    @Test
    fun `an app the tv listed is named, though the catalogue never heard of it`() {
        assertEquals("Example Player", ForegroundName.of("com.example.player", listOf(sideloaded)))
    }

    @Test
    fun `the tv's own name wins over the catalogue's`() {
        assertEquals("Netflix TV", ForegroundName.of("com.netflix.ninja", listOf(KnownApp("com.netflix.ninja", "Netflix TV"))))
        assertEquals("Netflix", ForegroundName.of("com.netflix.ninja", emptyList()))
        assertEquals("Netflix", ForegroundName.of("com.netflix.ninja", listOf(KnownApp("com.netflix.ninja", " "))))
    }

    @Test
    fun `the launcher and settings stay the home screen, even when listed`() {
        val listed = AppHistory.PASS_THROUGH.map { KnownApp(it, "Home") }
        AppHistory.PASS_THROUGH.forEach { assertNull(ForegroundName.of(it, listed)) }
    }

    @Test
    fun `an unknown package or none is never a raw id`() {
        assertNull(ForegroundName.of("com.example.unknown", listOf(sideloaded)))
        assertNull(ForegroundName.of(null, listOf(sideloaded)))
        assertNull(ForegroundName.of("", listOf(sideloaded)))
    }
}
