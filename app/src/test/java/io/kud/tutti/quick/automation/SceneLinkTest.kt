package io.kud.tutti.quick.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneLinkTest {
    @Test
    fun `reads the scene id`() {
        assertEquals("cinema", SceneLink.parse("tutti", "scene", "/cinema"))
        assertEquals("starter-night_2", SceneLink.parse("TUTTI", "Scene", "/starter-night_2/"))
    }

    @Test
    fun `round trips what it writes`() {
        val link = SceneLink.of("cinema")
        assertEquals("tutti://scene/cinema", link)
        val uri = java.net.URI(link)
        assertEquals("cinema", SceneLink.parse(uri.scheme, uri.host, uri.path))
    }

    @Test
    fun `refuses anything else`() {
        assertNull(SceneLink.parse("https", "scene", "/cinema"))
        assertNull(SceneLink.parse("tutti", "power", "/off"))
        assertNull(SceneLink.parse("tutti", "scene", "/"))
        assertNull(SceneLink.parse("tutti", "scene", null))
        assertNull(SceneLink.parse("tutti", "scene", "/a/b"))
        assertNull(SceneLink.parse("tutti", "scene", "/../secure"))
        assertNull(SceneLink.parse("tutti", "scene", "/" + "x".repeat(65)))
    }
}
