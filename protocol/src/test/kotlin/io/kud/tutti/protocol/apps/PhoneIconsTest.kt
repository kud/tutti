package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.AppCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneIconsTest {
    private val netflix = AppCatalog.find("netflix")!!

    @Test
    fun `a catalog app tries its tv package then its phone package`() {
        assertEquals(listOf(netflix.packageName) + netflix.phonePackages, PhoneIcons.candidates(netflix.packageName))
        assertTrue(netflix.phonePackages.isNotEmpty())
    }

    @Test
    fun `an app outside the catalog matches only its exact package`() {
        assertEquals(listOf("org.example.tvapp"), PhoneIcons.candidates("org.example.tvapp"))
        assertEquals(listOf("org.example.tvapp"), PhoneIcons.candidates("  org.example.tvapp "))
        assertNull(PhoneIcons.match("org.example.tvapp") { it == "org.example.tvapp.mobile" })
        assertNull(PhoneIcons.match("org.example.tvapp") { it == "org.example" })
    }

    @Test
    fun `the first installed candidate wins`() {
        val phone = netflix.phonePackages.first()
        assertEquals(phone, PhoneIcons.match(netflix.packageName) { it == phone })
        assertEquals(netflix.packageName, PhoneIcons.match(netflix.packageName) { true })
        assertNull(PhoneIcons.match(netflix.packageName) { false })
        assertEquals("org.example.tvapp", PhoneIcons.match("org.example.tvapp") { it == "org.example.tvapp" })
    }

    @Test
    fun `nothing is asked for a malformed package`() {
        for (bad in listOf("", " ", "netflix", "Netflix", "org..example", ".org.example", "org.example.", "org.1example", "org/example", "org.example tv")) {
            assertEquals(bad, emptyList<String>(), PhoneIcons.candidates(bad))
            assertNull(PhoneIcons.match(bad) { true })
        }
        assertTrue(PhoneIcons.isPackageName("org.example_tv.app2"))
        assertFalse(PhoneIcons.isPackageName("org"))
    }

    @Test
    fun `catalog phone packages are well formed, distinct and never another entry's tv package`() {
        val tvPackages = AppCatalog.apps.map { it.packageName }.toSet()
        val phone = AppCatalog.apps.flatMap { it.phonePackages }
        assertEquals(phone.size, phone.toSet().size)
        for (entry in AppCatalog.apps) {
            for (p in entry.phonePackages) {
                assertTrue(p, PhoneIcons.isPackageName(p))
                assertFalse(p, p == entry.packageName)
                assertFalse(p, p in tvPackages)
            }
        }
    }

    @Test
    fun `the catalog set covers every candidate`() {
        val all = PhoneIcons.catalogPackages
        for (entry in AppCatalog.apps) assertTrue(all.containsAll(PhoneIcons.candidates(entry.packageName)))
    }
}
