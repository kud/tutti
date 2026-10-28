package io.kud.tutti.ui.common

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * A message is one short sentence (docs/design.md, "Messages and popups"): every string that can
 * reach [Messages.show], directly or through [Problems.report], stays within [CAP] words in every
 * locale. Longer help belongs behind the message's action (the Unlock checklist, a dialog).
 */
class MessageCopyTest {

    @Test
    fun everyMessageFitsTheCapInEveryLocale() {
        val locales = listOf("values", "values-fr").associateWith { strings(it) }
        val over = mutableListOf<String>()
        for ((locale, strings) in locales) {
            for (name in MESSAGES) {
                val text = strings[name] ?: error("$name missing from $locale")
                val count = words(text)
                if (count > CAP) over += "$locale/$name: $count words: $text"
            }
        }
        assertTrue("Messages over $CAP words:\n" + over.joinToString("\n"), over.isEmpty())
    }

    /** A guard that cannot fail is not a guard: the counter must see a long paragraph as long. */
    @Test
    fun theCounterCatchesAParagraph() {
        assertTrue(MESSAGES.isNotEmpty())
        assertTrue(strings("values").size > 100)
        val wall = "The TV did not wake. On the TV, turn on Settings → Network & Internet → Remote start. " +
            "To wake it from a full switch-off too, add the TV’s PSK in tutti’s Settings."
        assertTrue(words(wall) > CAP)
        // French spacing puts the colon on its own: punctuation is not a word.
        assertEquals(3, words("Micro refusé : autorisez-le ."))
    }

    private fun words(text: String): Int =
        text.replace("\\n", " ").split(Regex("\\s+")).count { token -> token.any { it.isLetterOrDigit() } }

    private fun strings(dir: String): Map<String, String> {
        val files = File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
            ?: error("no $dir")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return files.flatMap { file ->
            val nodes = builder.parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { i -> (nodes.item(i) as Element).let { it.getAttribute("name") to it.textContent } }
        }.toMap()
    }

    private companion object {
        /**
         * 14 words in any locale, aiming at 12: three lines of the card's text column on a 360 dp
         * phone (about 30 characters a line in bodyMedium beside the glyph and the close button),
         * read in a glance within the 4 s a plain message stays. French runs about 15% longer than
         * English, so a 12-word English line still fits once translated.
         */
        const val CAP = 14

        /** Every string shown as a message. A new message string joins this list. */
        val MESSAGES = listOf(
            // The updater's daily check (TuttiApp).
            "update_available",
            // Problems.report and the problem reporter (TuttiApp, VoiceButton).
            "scene_failed_steps",
            "error_action_failed",
            "voice_mic_unavailable",
            "power_wake_failed_tv",
            "power_wake_failed_tv_no_mac",
            "power_wake_failed_tv_not_found",
            "power_wake_failed_bar",
            "voice_not_connected",
            "voice_unsupported",
            "voice_no_reply",
            "voice_disconnected",
            // Plain messages.
            "key_held",
            "key_dropped_waiting",
            "key_dropped_down",
            "key_dropped_pairing",
            "key_expired",
            "key_tv_standby",
            "apps_screen_failed",
            "apps_screen_unconfirmed",
            "voice_hold_hint",
            "voice_permission_denied",
            "voice_capped",
            "keyboard_dictation_unavailable",
            "editor_capture_empty",
            "problem_copied",
            "scene_applied",
            "scene_undone",
            "scene_undone_generic",
            "scene_undo_failed",
            "pin_done",
            "pin_done_page",
            // Actions.
            "scene_undo",
            "pin_undo",
            "unlock_show_how",
            "problem_copy",
        )
    }
}
