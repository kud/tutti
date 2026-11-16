package io.kud.tutti.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * :protocol is plain JVM, so Android lint never sees it, yet every line of it
 * runs on the phone. `URLEncoder.encode(String, Charset)` and its decoder twin
 * are Java 10 overloads that Android only ships from API 33; on an older phone
 * (minSdk is 29) they throw NoSuchMethodError. That crashed the share sheet's
 * IMDb and Trakt links on a real phone (2026-11-15). Pass the charset's name.
 */
class AndroidApiGuardTest {
    private val charsetOverload =
        Regex("""URL(En|De)coder\.(encode|decode)\([^()]*(\([^()]*\))?[^()]*,\s*(Charsets|StandardCharsets)\.UTF_8\s*\)""")

    @Test
    fun `the detector catches the charset overload`() {
        assertTrue(charsetOverload.containsMatchIn("""URLEncoder.encode(title.trim(), Charsets.UTF_8)"""))
        assertTrue(charsetOverload.containsMatchIn("""URLDecoder.decode(s, StandardCharsets.UTF_8)"""))
        assertTrue(!charsetOverload.containsMatchIn("""URLEncoder.encode(title.trim(), Charsets.UTF_8.name())"""))
    }

    @Test
    fun `protocol code never calls the API 33 charset overloads`() {
        val sources = File("src/main").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 10, "found only ${sources.size} sources under ${File("src/main").absolutePath}")
        val hits = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (charsetOverload.containsMatchIn(line)) "${file.path}:${i + 1}" else null
            }
        }
        assertEquals(emptyList(), hits)
    }
}
