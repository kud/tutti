package io.kud.tutti.ui.common

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * One name per thing (docs/design.md, "Words: one name per thing"): no string in a locale uses a
 * word the table bans there. Each banned word names the house word that replaces it, so a failure
 * says what to write instead.
 */
class WordTableTest {

    @Test
    fun noLocaleUsesABannedWord() {
        val found = mutableListOf<String>()
        for ((locale, banned) in BANNED) {
            val strings = strings(locale)
            for ((name, text) in strings) {
                for (ban in banned) {
                    if (ban.pattern.containsMatchIn(text)) found += "$locale/$name: \"${ban.pattern.find(text)?.value}\" → ${ban.instead}: $text"
                }
            }
        }
        assertTrue("Banned words (docs/design.md, Words):\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** A guard that cannot fail is not a guard: every banned pattern must catch its own example. */
    @Test
    fun everyBanCatchesItsExample() {
        assertTrue(strings("values").size > 100)
        assertTrue(strings("values-fr").size > 100)
        for ((locale, banned) in BANNED) {
            for (ban in banned) {
                assertTrue("$locale: ${ban.pattern} misses \"${ban.example}\"", ban.pattern.containsMatchIn(ban.example))
            }
        }
        // Words that merely contain a banned one stay allowed.
        val fr = BANNED.getValue("values-fr")
        val allowed = listOf(
            "le téléphone",
            "Télétexte",
            "la télécommande",
            "Alimentation du caisson",
            "Paramètres › Réseau et Internet › Réseau domestique › Contrôle IP › Authentification",
        )
        assertEquals(emptyList<String>(), allowed.filter { text -> fr.any { it.pattern.containsMatchIn(text) } })
        val en = BANNED.getValue("values")
        assertTrue(en.none { it.pattern.containsMatchIn("On the TV: $IP_PATH › Authentication") })
    }

    private class Ban(regex: String, val instead: String, val example: String) {
        val pattern = Regex(regex)
    }

    private fun strings(dir: String): Map<String, String> {
        val files = File("src/main/res/$dir").listFiles { f -> f.name.endsWith(".xml") } ?: error("no $dir")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return files.flatMap { file ->
            val doc = builder.parse(file)
            val single = doc.getElementsByTagName("string").let { nodes ->
                (0 until nodes.length).map { i -> (nodes.item(i) as Element).let { it.getAttribute("name") to it.textContent } }
            }
            val plural = doc.getElementsByTagName("item").let { nodes ->
                (0 until nodes.length).mapNotNull { i ->
                    val item = nodes.item(i) as Element
                    val parent = item.parentNode as? Element ?: return@mapNotNull null
                    if (parent.tagName != "plurals") return@mapNotNull null
                    "${parent.getAttribute("name")}#${item.getAttribute("quantity")}" to item.textContent
                }
            }
            single + plural
        }.toMap()
    }

    private companion object {
        val IP_PATH = "Settings › Network & Internet › Home network › IP control"

        val BANNED = mapOf(
            "values" to listOf(
                Ban("(?i)\\btelevision\\b", "TV", "Turn the television on"),
                Ban("\\bSlot\\b", "pin", "Slot 2 · empty"),
                Ban("(?i)\\bput in the dock\\b", "Pin to the dock", "Put in the dock"),
                Ban("Local network setup|(?<!Home network )[→›] IP control", IP_PATH, "Local network setup → IP control"),
            ),
            "values-fr" to listOf(
                Ban("(?i)téléviseur", "la TV", "Oublier ce téléviseur"),
                Ban("(?i)(?<!\\p{L})télé(?!\\p{L})", "la TV", "règlent la télé."),
                Ban("(?i)(?<!\\p{L})applis?(?!\\p{L})", "apps", "Aucune appli en cours"),
                Ban("\\bAlimentation\\b(?! du caisson)", "Marche / arrêt", "Alimentation"),
                Ban("(?i)placer dans le dock|\\bEmplacement \\d", "Épingler au dock, épingle", "Emplacement 2"),
                Ban("Configuration du réseau local|(?<!Réseau domestique )[→›] Contrôle IP", "Paramètres › Réseau et Internet › Réseau domestique › Contrôle IP", "Configuration du réseau local → Contrôle IP"),
            ),
        )
    }
}
