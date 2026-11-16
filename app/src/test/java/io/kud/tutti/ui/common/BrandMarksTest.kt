package io.kud.tutti.ui.common

import io.kud.tutti.R
import io.kud.tutti.protocol.atv.AppCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandMarksTest {
    @Test fun `every tv-only package wears a face`() {
        for (pkg in tvOnly.keys) assertNotNull(pkg, brandFace(pkg))
    }

    @Test fun `no tv-only package shadows a catalog app`() {
        for (pkg in tvOnly.keys) assertNull(pkg, AppCatalog.byPackage(pkg))
    }

    @Test fun `the apps on Erwann's grid that showed a letter wear their play icons`() {
        val expected = mapOf(
            "com.channel5.my5" to R.drawable.play_channel5,
            "bbc.iplayer.android" to R.drawable.play_bbciplayer,
            "uk.co.bbc.sounds" to R.drawable.play_bbcsounds,
            "com.sony.dtv.calibrationmonitor" to R.drawable.play_calman,
        )
        for ((pkg, drawable) in expected) {
            val face = brandFace(pkg)!!
            assertEquals(pkg, drawable, face.drawable)
            assertTrue(pkg, face.crop)
        }
    }

    @Test fun `the broadcasters' own packages wear the wrappers' marks`() {
        assertEquals(brandFace("com.sony.dtv.all4launcher")!!.drawable, brandFace("com.channel4.ondemand")!!.drawable)
        assertEquals(brandFace("com.sony.dtv.itvlauncher")!!.drawable, brandFace("air.ITVMobilePlayer")!!.drawable)
    }

    @Test fun `matching is by package, never by a tv-only app's title`() {
        assertNull(brandFace("com.example.unknown", "BBC iPlayer"))
        assertNull(brandFace(null, "5"))
    }
}
