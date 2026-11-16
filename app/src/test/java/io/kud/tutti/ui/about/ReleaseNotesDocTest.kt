package io.kud.tutti.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The notes have one source, the app's strings; `docs/releases/<version>.md` repeats the English
 * for the GitHub release and the blog. Every line the page shows must be in the file, word for word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ReleaseNotesDocTest {
    @Test
    fun everyReleaseHasItsFileWithTheSameWords() {
        val context = RuntimeEnvironment.getApplication()
        val missing = mutableListOf<String>()
        var checked = 0
        for (release in ReleaseNotes.all) {
            val file = File("../docs/releases/${release.version}.md")
            assertTrue("${file.path} missing", file.isFile)
            val doc = file.readText()
            val lines = listOf(release.headline) + (release.new + release.better + release.fixed).flatMap { listOf(it.title, it.detail) }
            for (id in lines) {
                val text = context.getString(id)
                checked++
                if (text !in doc) missing += "${release.version}: $text"
            }
        }
        // Probe: the walk saw the notes, so an empty list cannot pass.
        assertTrue(checked > 10)
        assertEquals(emptyList<String>(), missing)
    }
}
