package io.kud.tutti.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The down dress (design.md › Visual › Down): solid and quieter, never the editor's dashed slot. */
class DownDressTest {
    private val tonal = Color(0xFF1E4D5C)
    private val ink = Color(0xFFCFE6F1)

    @Test fun `the fill stays the control's own tone`() {
        val fill = downFill(tonal)
        assertEquals(tonal.copy(alpha = 1f), fill.copy(alpha = 1f))
    }

    @Test fun `the fill stays a solid shape, not an empty place`() {
        val fill = downFill(tonal)
        assertTrue("a down control keeps a visible fill", fill.alpha >= 0.4f)
        assertTrue("and is quieter than at rest", fill.alpha < tonal.alpha)
    }

    @Test fun `the ink fades by lightness, never by hue`() {
        val faded = downInk(ink)
        assertEquals(ink.copy(alpha = 1f), faded.copy(alpha = 1f))
        assertEquals(DOWN_INK, faded.alpha, 0.001f)
        assertTrue(faded.alpha < downFill(tonal).alpha)
    }

    @Test fun `the ink stays quieter than the fill it sits on`() {
        // Ink fades harder than the fill, so the glyph recedes into the key rather than the key
        // vanishing round a bright glyph: the key reads waiting, not missing.
        assertTrue(DOWN_INK < DOWN_FILL)
    }

    @Test fun `no control draws the down dress with a dash`() {
        // Dashes belong to the layout editor (an empty place, a drop target) and to "not known".
        // A dashed down key read as a slot to fill (Erwann, 2026-11-07).
        val root = sequenceOf(File("src/main/java/io/kud/tutti/ui"), File("app/src/main/java/io/kud/tutti/ui")).first { it.isDirectory }
        val files = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("found the UI sources", files.size > 20)
        val offenders = files.filter { "drawDownRing" in it.readText() || "DOWN_DASH" in it.readText() }
        assertEquals(emptyList<File>(), offenders)
    }
}
