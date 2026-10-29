package io.kud.tutti.protocol.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppShortcutsTest {
    @Test
    fun `youtube on the tv gets its tabs, other apps nothing`() {
        assertEquals(AppShortcut.entries.toList(), AppShortcuts.forPackage("com.google.android.youtube.tv"))
        assertTrue(AppShortcuts.forPackage("com.netflix.ninja").isEmpty())
        assertTrue(AppShortcuts.forPackage(null).isEmpty())
    }

    @Test
    fun `every shortcut is a link the probe would send`() {
        for (shortcut in AppShortcut.entries) assertTrue(LinkProbe.valid(shortcut.link), shortcut.name)
    }
}
