package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteAppInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteDirection
import io.kud.tutti.protocol.atv.proto.remote.RemoteEditInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeBatchEdit
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeKeyInject
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeObject
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeShowRequest
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.atv.proto.remote.RemoteTextFieldStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Erwann's report (2026-11-07): a stray "q", letters that don't reach the TV live, Enter that
 * does little. Each test drives [RemoteSession] the way the TV might answer, including not at
 * all, and checks the field ends up holding exactly what the phone shows, nothing more.
 */
class ImeLiveTest {
    private val session = RemoteSession()

    /**
     * A TV text field. [takesEdits] false swallows every batch edit (stale counters, or a field
     * with no input connection); [echoes] false applies edits without ever reporting back. Keys
     * always land; [reportsKeys] reports each as a batch edit, as an input session would.
     */
    private class Field(
        var text: String = "",
        val takesEdits: Boolean = true,
        val echoes: Boolean = true,
        val reportsKeys: Boolean = false,
    ) {
        private var shift = false
        val keyCodes = mutableListOf<Int>()

        fun receive(message: RemoteMessage): List<RemoteMessage> {
            message.remote_key_inject?.let { key ->
                val code = key.key_code.value
                keyCodes += code
                when {
                    code == TextKeys.SHIFT_LEFT -> shift = key.direction == RemoteDirection.START_LONG
                    code == V2Key.DEL -> text = text.dropLast(1)
                    code == V2Key.MOVE_END || code == V2Key.ENTER -> Unit
                    else -> text += (' '..'~').first { TextKeys.stroke(it) == TextKeys.Stroke(code, shift) }
                }
                return if (reportsKeys && code != TextKeys.SHIFT_LEFT) listOf(report()) else emptyList()
            }
            val edit = message.remote_ime_batch_edit ?: return emptyList()
            if (!takesEdits) return emptyList()
            text = edit.edit_info.last().text_field_status!!.value_
            return if (echoes) listOf(report()) else emptyList()
        }

        fun report() = RemoteMessage(
            remote_ime_batch_edit = RemoteImeBatchEdit(
                ime_counter = 7,
                field_counter = 3,
                edit_info = listOf(RemoteEditInfo(insert = 1, text_field_status = RemoteImeObject(text.length, text.length, text))),
            ),
        )

        /** The TV's keyboard starting on the field: a key inject with the field, then a show request. */
        fun focus(show: Boolean = true): List<RemoteMessage> = listOfNotNull(
            RemoteMessage(
                remote_ime_key_inject = RemoteImeKeyInject(
                    app_info = RemoteAppInfo(counter = 7, app_package = "com.example.tv"),
                    text_field_status = RemoteTextFieldStatus(counter_field = 3, value_ = text),
                ),
            ),
            if (!show) null else RemoteMessage(
                remote_ime_show_request = RemoteImeShowRequest(
                    RemoteTextFieldStatus(counter_field = 3, value_ = text, start = text.length, end = text.length, label = "Search"),
                ),
            ),
        )
    }

    private class Link(val session: RemoteSession, val field: Field, var state: RemoteState = RemoteState(ready = true, currentApp = "com.example.tv")) {
        var now = 0L
        val sent = mutableListOf<RemoteMessage>()

        fun hear(messages: List<RemoteMessage>) {
            val inbox = messages.toMutableList()
            while (inbox.isNotEmpty()) state = session.reduce(state, inbox.removeAt(0)).state
        }

        private fun out(messages: List<RemoteMessage>) {
            sent += messages
            hear(messages.flatMap(field::receive))
        }

        fun type(text: String, after: Long = 120) {
            now += after
            val step = session.type(state, text, now)
            state = step.state
            out(step.messages)
        }

        fun settle(after: Long) {
            now += after
            val step = session.settle(state, now)
            state = step.state
            out(step.messages)
        }

        fun typeEach(word: String) = (1..word.length).forEach { type(word.take(it)) }
    }

    @Test
    fun `with no input session every keystroke goes out at once, as that one key`() {
        val link = Link(session, Field())
        listOf("n", "nu", "nuv").forEach { text ->
            val before = link.sent.size
            link.type(text)
            val keys = link.sent.drop(before).map { it.remote_key_inject!!.key_code.value }
            assertEquals(listOf(TextKeys.stroke(text.last())!!.keyCode), keys)
        }
        assertEquals("nuv", link.field.text)
    }

    @Test
    fun `no path sends a q nobody typed`() {
        val q = TextKeys.stroke('q')!!.keyCode
        val fields = listOf(
            Field(),
            Field(takesEdits = false),
            Field(echoes = false),
            Field(text = "ab", takesEdits = false),
            Field(takesEdits = false, reportsKeys = true),
        )
        for (field in fields) {
            val typed = "${field.text}hello"
            val link = Link(session, field)
            link.hear(field.focus())
            (field.text.length + 1..typed.length).forEach { link.type(typed.take(it)) }
            link.settle(RemoteSession.ACK_MS)
            assertFalse(q in field.keyCodes, "a stray q reached the field")
            assertEquals(typed, field.text)
        }
    }

    @Test
    fun `a field that swallows edits gets them as keys when the answer is late, without another keystroke`() {
        val field = Field(takesEdits = false)
        val link = Link(session, field)
        link.hear(field.focus())
        link.typeEach("nuvio")
        // Typed fast, nothing answered yet: still edits in flight, the field empty.
        assertEquals("", field.text)
        link.settle(RemoteSession.ACK_MS)
        assertEquals("nuvio", field.text)
        assertTrue(link.state.imeDeaf)
        // From here each keystroke is live, one key each.
        link.type("nuvio ")
        assertEquals("nuvio ", field.text)
    }

    @Test
    fun `a style trial the TV never answers lets the held text go as keys, the field cleared first`() {
        val field = Field(text = "ab", takesEdits = false)
        val link = Link(session, field)
        link.hear(field.focus())
        link.type("abc")
        link.type("abcd")
        assertEquals("abcd", link.state.imeHeld)
        link.settle(RemoteSession.ACK_MS)
        assertEquals("abcd", field.text)
    }

    @Test
    fun `edits that landed without an echo are never typed twice`() {
        val field = Field(echoes = false)
        val link = Link(session, field)
        link.hear(field.focus())
        link.typeEach("star")
        // "st" was the style trial and landed; "star" waits behind it for an echo that never comes.
        assertEquals("st", field.text)
        link.settle(RemoteSession.ACK_MS)
        assertEquals("star", field.text)
        link.type("stars")
        assertEquals("stars", field.text)
    }

    @Test
    fun `keys the field reports as they land never pull the phone field back`() {
        val field = Field(takesEdits = false, reportsKeys = true)
        val link = Link(session, field)
        link.hear(field.focus())
        link.typeEach("film")
        link.settle(RemoteSession.ACK_MS)
        assertEquals("film", field.text)
        assertEquals("film", link.state.ime?.text)
        assertTrue(link.state.imeDeaf)
    }

    @Test
    fun `settle sends nothing while the TV answers, nor before the wait is up`() {
        val field = Field()
        val link = Link(session, field)
        link.hear(field.focus())
        link.typeEach("doc")
        val before = link.sent.size
        link.settle(RemoteSession.ACK_MS)
        assertEquals(before, link.sent.size)

        val quiet = Link(session, Field(takesEdits = false))
        quiet.hear(quiet.field.focus())
        quiet.type("d")
        val sent = quiet.sent.size
        quiet.settle(RemoteSession.ACK_MS / 2)
        assertEquals(sent, quiet.sent.size)
    }

    @Test
    fun `a batch edit puts the caret on the last character, as androidtvremote2 does`() {
        assertEquals(2, RemoteMessages.text("abc", 1, 1).remote_ime_batch_edit!!.edit_info.single().text_field_status!!.start)
        assertEquals(0, RemoteMessages.text("", 1, 1).remote_ime_batch_edit!!.edit_info.single().text_field_status!!.end)
    }
}
