package io.kud.tutti

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fdroid build carries no updater (docs/fdroid.md): no code in `src/main` or `src/fdroid` may
 * reach the updater, PackageInstaller or GitHub's release API. Only `src/github` may. The manifest
 * half is `checkDistribution<Variant>` in app/build.gradle.kts.
 */
class DistributionGuardsTest {
    private val marks = listOf("platform.update", "PackageInstaller", "REQUEST_INSTALL_PACKAGES", "api.github.com")

    private fun sources(set: String): List<File> =
        File("src/$set").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList()

    @Test
    fun mainAndFdroidNeverReachTheUpdater() {
        val scanned = sources("main") + sources("fdroid")
        // An empty file list would pass quietly: prove the scan saw both source sets.
        assertTrue("scanned ${scanned.size} files", scanned.size > 100)
        assertTrue(scanned.any { it.path.contains("src/fdroid") })
        val hits = scanned.flatMap { file -> marks.filter { it in file.readText() }.map { "${file.path}: $it" } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun theGuardStillSeesTheGithubUpdater() {
        // Probe: the same marks do match the github source set, so the scan above can fail.
        val github = sources("github").joinToString("\n") { it.readText() }
        assertTrue(marks.count { it in github } >= 3)
    }
}
