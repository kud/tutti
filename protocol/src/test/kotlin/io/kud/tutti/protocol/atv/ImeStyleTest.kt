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
import kotlin.test.assertTrue

/**
 * The TV's text paths tutti can't see from here, each as a fake: a field that inserts a batch
 * edit at its caret (sofa-remote's reading), one that replaces (tutti's and Hubmote's), either
 * echoing through a batch edit or a fresh show request, and a text box with no input session
 * at all that only key presses reach (a WebView or custom field, Nuvio's likely case).
 */
class ImeStyleTest {
    private val session = RemoteSession()

    private class Tv(val inserts: Boolean, val echoAsShow: Boolean = false, var imeCounter: Int = 7, var fieldCounter: Int = 3) {
        var text = ""
        var caret = 0
        val keys = mutableListOf<Int>()

        fun receive(message: RemoteMessage): List<RemoteMessage> {
            message.remote_key_inject?.let { key ->
                keys += key.key_code.value
                when (key.key_code.value) {
                    V2Key.DEL -> if (caret > 0) {
                        text = text.removeRange(caret - 1, caret)
                        caret--
                        return listOf(echo())
                    }
                    V2Key.MOVE_END -> caret = text.length
                }
                return emptyList()
            }
            val edit = message.remote_ime_batch_edit ?: return emptyList()
            if (edit.ime_counter != imeCounter) return emptyList()
            val value = edit.edit_info.last().text_field_status!!.value_
            if (inserts) {
                text = text.substring(0, caret) + value + text.substring(caret)
                caret += value.length
            } else {
                text = value
                caret = value.length
            }
            fieldCounter++
            return listOf(echo())
        }

        fun echo(): RemoteMessage = if (echoAsShow) {
            RemoteMessage(remote_ime_show_request = RemoteImeShowRequest(RemoteTextFieldStatus(counter_field = fieldCounter, value_ = text, start = caret, end = caret, label = "Search")))
        } else {
            RemoteMessage(
                remote_ime_batch_edit = RemoteImeBatchEdit(
                    ime_counter = imeCounter,
                    field_counter = fieldCounter,
                    edit_info = listOf(RemoteEditInfo(insert = 1, text_field_status = RemoteImeObject(caret, caret, text))),
                ),
            )
        }

        fun focus(): List<RemoteMessage> = listOf(
            RemoteMessage(
                remote_ime_key_inject = RemoteImeKeyInject(
                    app_info = RemoteAppInfo(counter = imeCounter, app_package = "com.example.tv"),
                    text_field_status = RemoteTextFieldStatus(counter_field = fieldCounter, value_ = text),
                ),
            ),
            RemoteMessage(
                remote_ime_show_request = RemoteImeShowRequest(
                    RemoteTextFieldStatus(counter_field = fieldCounter, value_ = text, start = caret, end = caret, label = "Search"),
                ),
            ),
        )
    }

    /** A focused box with no input session: key presses only, shift held around a key. */
    private class KeyBox {
        var text = ""
        private var shift = false

        fun receive(message: RemoteMessage) {
            val key = message.remote_key_inject ?: error("a batch edit reached a box with no input session")
            val code = key.key_code.value
            if (code == TextKeys.SHIFT_LEFT) {
                shift = key.direction == RemoteDirection.START_LONG
                return
            }
            if (code == V2Key.DEL) {
                text = text.dropLast(1)
                return
            }
            val char = (' '..'~').first { TextKeys.stroke(it) == TextKeys.Stroke(code, shift) }
            text += char
        }
    }

    private class Link(val session: RemoteSession, val tv: Tv, var state: RemoteState) {
        private val inbox = mutableListOf<RemoteMessage>()
        var now = 0L

        fun type(text: String, deliver: Boolean = true) {
            val step = session.type(state, text, now)
            state = step.state
            step.messages.forEach { inbox += tv.receive(it) }
            if (deliver) deliver()
        }

        fun deliver() {
            while (inbox.isNotEmpty()) {
                val step = session.reduce(state, inbox.removeAt(0))
                state = step.state
                step.replies.forEach { inbox += tv.receive(it) }
            }
        }
    }

    private fun link(tv: Tv): Link {
        val start = RemoteState(ready = true, currentApp = "com.example.tv")
        return Link(session, tv, tv.focus().fold(start) { s, m -> session.reduce(s, m).state })
    }

    private fun Link.typeEach(text: String, deliver: Boolean = true) =
        (1..text.length).forEach { type(text.take(it), deliver) }

    @Test
    fun `a tv that inserts is found out on the second letter and repaired at once`() {
        val l = link(Tv(inserts = true))
        l.typeEach("ph")
        assertEquals(ImeStyle.INSERT, l.state.imeStyle)
        assertEquals("ph", l.tv.text)
        listOf("pho", "phon", "phone").forEach { l.type(it) }
        assertEquals("phone", l.tv.text)
        assertEquals("phone", l.state.ime?.text)
        assertEquals(emptyList(), l.state.imeEchoes)
    }

    @Test
    fun `on a tv that inserts, delete and a correction become deletes and one insert`() {
        val l = link(Tv(inserts = true))
        l.typeEach("teh")
        l.tv.keys.clear()
        l.type("te")
        assertEquals(listOf(V2Key.DEL), l.tv.keys)
        l.type("the ")
        assertEquals("the ", l.tv.text)
        assertEquals("the ", l.state.ime?.text)
    }

    @Test
    fun `on a tv that inserts, fast typing with every echo late still converges`() {
        val l = link(Tv(inserts = true))
        l.typeEach("bravia", deliver = false)
        l.deliver()
        assertEquals("bravia", l.tv.text)
        assertEquals("bravia", l.state.ime?.text)
        l.typeEach("bravia 8", deliver = false)
        l.deliver()
        assertEquals("bravia 8", l.tv.text)
    }

    @Test
    fun `a tv that replaces is confirmed and keeps whole-text edits`() {
        val l = link(Tv(inserts = false))
        l.typeEach("arte")
        l.type("art")
        assertEquals(ImeStyle.REPLACE, l.state.imeStyle)
        assertEquals("art", l.tv.text)
        assertTrue(l.tv.keys.isEmpty(), "a replacing field never needs a key")
    }

    @Test
    fun `an echo that comes back as a show request is the same field, not a new one`() {
        for (inserts in listOf(true, false)) {
            val l = link(Tv(inserts = inserts, echoAsShow = true))
            val shows = l.state.imeShows
            l.typeEach("nuvio")
            assertEquals(shows, l.state.imeShows, "inserts=$inserts")
            assertEquals("nuvio", l.tv.text, "inserts=$inserts")
            assertEquals("nuvio", l.state.ime?.text, "inserts=$inserts")
        }
    }

    @Test
    fun `a field opened with text and the caret mid-way gets move-end before an insert`() {
        val tv = Tv(inserts = true)
        tv.text = "dune"
        tv.caret = 2
        val l = link(tv)
        l.state = l.state.copy(imeStyle = ImeStyle.INSERT)
        l.type("dune 2")
        assertEquals(V2Key.MOVE_END, tv.keys.first())
        assertEquals("dune 2", tv.text)
    }

    @Test
    fun `with no input session on the tv, text goes as key presses with shift for capitals`() {
        val box = KeyBox()
        var state = RemoteState(ready = true, currentApp = "com.example.webapp")
        fun type(text: String): TypeStep = session.type(state, text).also { step ->
            state = step.state
            step.messages.forEach(box::receive)
        }
        "Hi! 2".indices.forEach { type("Hi! 2".take(it + 1)) }
        assertEquals("Hi! 2", box.text)
        type("Hi!")
        assertEquals("Hi!", box.text)
        val accent = type("Hi!é")
        assertEquals(listOf('é'), accent.dropped)
        assertEquals("Hi!", box.text)
        type("Hi!éa")
        assertEquals("Hi!a", box.text, "the dropped accent is not deleted nor retyped")
        type("")
        assertEquals("", box.text)
    }

    @Test
    fun `every printable ascii character has a key`() {
        assertTrue((' '..'~').all(TextKeys::typable))
        assertEquals(TextKeys.Stroke(29 + 25, shift = true), TextKeys.stroke('Z'))
        assertEquals(null, TextKeys.stroke('é'))
        assertEquals(null, TextKeys.stroke('\uD83D'))
    }

    @Test
    fun `a field that never answers an edit falls back to keys from what it last said`() {
        val tv = Tv(inserts = false, imeCounter = 7)
        val start = RemoteState(ready = true, currentApp = "com.example.tv")
        // The TV started its keyboard (so tutti tries edits), but the box swallows them.
        var state = session.reduce(start, tv.focus().first()).state
        val deaf = Tv(inserts = false, imeCounter = 99)
        var step = session.type(state, "n", now = 1_000)
        state = step.state
        step.messages.forEach { assertTrue(deaf.receive(it).isEmpty()) }
        step = session.type(state, "nu", now = 1_000 + RemoteSession.ACK_MS)
        state = step.state
        assertTrue(state.imeDeaf)
        val box = KeyBox()
        step.messages.forEach(box::receive)
        assertEquals("nu", box.text)
    }

    @Test
    fun `a new app forgets the key path`() {
        var state = RemoteState(ready = true, currentApp = "a")
        state = session.type(state, "abc").state
        state = session.reduce(state, RemoteMessage(remote_ime_key_inject = RemoteImeKeyInject(app_info = RemoteAppInfo(app_package = "b")))).state
        assertEquals("", state.keyed)
        val box = KeyBox()
        session.type(state, "x").messages.forEach(box::receive)
        assertEquals("x", box.text)
    }
}
