package io.kud.tutti.protocol.atv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppCatalogTest {
    @Test
    fun `ids and packages are unique`() {
        assertEquals(AppCatalog.apps.size, AppCatalog.apps.map { it.id }.toSet().size)
        assertEquals(AppCatalog.apps.size, AppCatalog.apps.map { it.packageName }.toSet().size)
    }

    @Test
    fun `finds by id, name and package`() {
        assertEquals("com.wbd.stream", AppCatalog.find("max")?.packageName)
        assertEquals("com.wbd.stream", AppCatalog.find("HBO Max")?.packageName)
        assertNotNull(AppCatalog.byPackage("com.canal.android.canal"))
        assertEquals("market://launch?id=fr.francetv.pluzz", AppCatalog.find("francetv")?.launchLink)
    }
}
