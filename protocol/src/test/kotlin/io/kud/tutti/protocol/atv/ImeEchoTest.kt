package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteAppInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteEditInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeBatchEdit
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeKeyInject
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeObject
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeShowRequest
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.atv.proto.remote.RemoteTextFieldStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Typing "ph" on the TV went wild ("pphppphppphpppphpph", Erwann, 2026-10-25): the TV echoed
 * our "p" back while "h" was being typed, the mirror took the stale "p" over the "ph" in the
 * phone field, the phone keyboard committed its composing word again on top, and every new
 * text went out and came back the same way.
 *
 * [FakeTv] holds a field, takes a batch edit's value only with its current input session
 * counter, and echoes its whole field back. [Gboard] is the phone keyboard's one habit that
 * matters here: when the field's text is replaced under its composing word, it commits that
 * word again at the caret.
 */
class ImeEchoTest {
    private val session = RemoteSession()

    private class FakeTv(var imeCounter: Int = 7, var fieldCounter: Int = 3) {
        var text = ""
        val taken = mutableListOf<String>()
        val refused = mutableListOf<String>()

        /** Applies an edit and returns its echo, or nothing when the counters are not its own. */
        fun receive(message: RemoteMessage): RemoteMessage? {
            val edit = message.remote_ime_batch_edit ?: return null
            val value = edit.edit_info.last().text_field_status!!.value_
            if (edit.ime_counter != imeCounter) {
                refused += value
                return null
            }
            text = value
            taken += value
            return echo()
        }

        fun echo() = RemoteMessage(
            remote_ime_batch_edit = RemoteImeBatchEdit(
                ime_counter = imeCounter,
                field_counter = fieldCounter,
                edit_info = listOf(RemoteEditInfo(insert = 1, text_field_status = RemoteImeObject(text.length, text.length, text))),
            ),
        )

        /** The TV focuses a field: its keyboard starts (key inject with counters), then shows it. */
        fun focus(): List<RemoteMessage> = listOf(
            RemoteMessage(
                remote_ime_key_inject = RemoteImeKeyInject(
                    app_info = RemoteAppInfo(counter = imeCounter, app_package = "com.example.tv"),
                    text_field_status = RemoteTextFieldStatus(counter_field = fieldCounter, value_ = text),
                ),
            ),
            RemoteMessage(
                remote_ime_show_request = RemoteImeShowRequest(
                    remote_text_field_status = RemoteTextFieldStatus(counter_field = fieldCounter, value_ = text, label = "Search"),
                ),
            ),
        )
    }

    /** The phone field: its text and the word the keyboard is composing at the end. */
    private class Gboard {
        var text = ""
        var composing = ""

        fun type(char: Char): String {
            text += char
            composing += char
            return text
        }

        fun erase(): String {
            text = text.dropLast(1)
            composing = composing.dropLast(1)
            return text
        }

        /** Replace the text under the composing word: the word is committed again at the caret. */
        fun overwrite(fromOutside: String): String? {
            if (fromOutside == text) return null
            text = if (composing.isEmpty()) fromOutside else fromOutside + composing
            return text
        }
    }

    private fun focused(tv: FakeTv, state: RemoteState = RemoteState(ready = true, currentApp = "com.example.tv")): RemoteState =
        tv.focus().fold(state) { s, m -> session.reduce(s, m).state }

    @Test
    fun `before the fix, a stale echo of p under a composing ph loops the field out of control`() {
        val tv = FakeTv()
        val phone = Gboard()
        // The old client: every echo became the mirror, and the mirror overwrote the phone field.
        val mirrorFollowsEveryEcho = { echo: RemoteMessage -> echo.remote_ime_batch_edit!!.edit_info.last().text_field_status!!.value_ }
        val echoes = ArrayDeque<RemoteMessage>()
        tv.receive(RemoteMessages.text(phone.type('p'), tv.imeCounter, tv.fieldCounter))?.let(echoes::add)
        tv.receive(RemoteMessages.text(phone.type('h'), tv.imeCounter, tv.fieldCounter))?.let(echoes::add)
        var rounds = 0
        while (echoes.isNotEmpty() && rounds < 6) {
            rounds++
            val after = phone.overwrite(mirrorFollowsEveryEcho(echoes.removeFirst())) ?: continue
            tv.receive(RemoteMessages.text(after, tv.imeCounter, tv.fieldCounter))?.let(echoes::add)
        }
        assertEquals("pph", tv.taken[2], "the stale p plus the composing ph, as on the TV")
        assertNotEquals("ph", phone.text)
        assertTrue(tv.text.length > 6, "the loop keeps growing: ${tv.text}")
    }

    @Test
    fun `typing ph with the echo of p arriving after h converges on ph and stops`() {
        val tv = FakeTv()
        val phone = Gboard()
        var state = focused(tv)
        val echoes = ArrayDeque<RemoteMessage>()
        val sent = mutableListOf<String>()

        fun type(text: String) {
            val step = session.type(state, text)
            state = step.state
            step.messages.forEach { m ->
                sent += m.remote_ime_batch_edit!!.edit_info.single().text_field_status!!.value_
                tv.receive(m)?.let(echoes::add)
            }
        }

        fun deliver() {
            val echo = echoes.removeFirst()
            val before = state.ime?.text
            state = session.reduce(state, echo).state
            // What InputView does: follow the mirror only when it moved (and never while composing).
            if (state.ime?.text != before && phone.composing.isEmpty()) phone.overwrite(state.ime!!.text)?.let(::type)
        }

        type(phone.type('p'))
        type(phone.type('h'))
        deliver() // the stale "p"
        assertEquals("ph", state.ime?.text, "a stale echo never takes the mirror back")
        deliver() // "ph"
        assertTrue(echoes.isEmpty())
        assertEquals("ph", phone.text)
        assertEquals("ph", tv.text)
        assertEquals(listOf("p", "ph"), sent)
        assertEquals(emptyList(), state.imeEchoes)
    }

    @Test
    fun `typing ph with the echo of p arriving before h converges on ph`() {
        val tv = FakeTv()
        val phone = Gboard()
        var state = focused(tv)
        var step = session.type(state, phone.type('p'))
        state = session.reduce(step.state, tv.receive(step.messages.single())!!).state
        assertEquals("p", state.ime?.text)
        step = session.type(state, phone.type('h'))
        state = session.reduce(step.state, tv.receive(step.messages.single())!!).state
        assertEquals("ph", state.ime?.text)
        assertEquals("ph", tv.text)
        assertEquals(listOf("p", "ph"), tv.taken)
    }

    @Test
    fun `the first character typed before any batch edit carries the counters the tv gave on focus`() {
        val tv = FakeTv(imeCounter = 41, fieldCounter = 9)
        // A stale session from an earlier field: before the fix, these were what went out.
        val state = focused(tv, RemoteState(ready = true, currentApp = "com.example.tv", imeCounter = 2, fieldCounter = 1))
        assertEquals(41, state.imeCounter)
        assertEquals(9, state.fieldCounter)
        val step = session.type(state, "p")
        val edit = step.messages.single().remote_ime_batch_edit!!
        assertEquals(41, edit.ime_counter)
        assertEquals(9, edit.field_counter)
        tv.receive(step.messages.single())
        assertEquals("p", tv.text)
        assertEquals(emptyList(), tv.refused)
    }

    @Test
    fun `fast typing with every echo late keeps the mirror on the newest text`() {
        val tv = FakeTv()
        var state = focused(tv)
        val echoes = mutableListOf<RemoteMessage>()
        for (text in listOf("b", "br", "bra", "brav", "bravi", "bravia")) {
            val step = session.type(state, text)
            state = step.state
            step.messages.forEach { tv.receive(it)?.let(echoes::add) }
        }
        echoes.forEach {
            state = session.reduce(state, it).state
            assertEquals("bravia", state.ime?.text)
        }
        assertEquals("bravia", tv.text)
        assertEquals(emptyList(), state.imeEchoes)
    }

    @Test
    fun `delete and a keyboard correction go out as the whole text and their echoes stay quiet`() {
        val tv = FakeTv()
        val phone = Gboard()
        var state = focused(tv)
        val echoes = mutableListOf<RemoteMessage>()
        fun type(text: String) {
            val step = session.type(state, text)
            state = step.state
            step.messages.forEach { tv.receive(it)?.let(echoes::add) }
        }
        "teh".forEach { type(phone.type(it)) }
        type(phone.erase())
        // Gboard's correction replaces the composing word in one change.
        phone.text = "the "
        phone.composing = ""
        type(phone.text)
        echoes.forEach { state = session.reduce(state, it).state }
        assertEquals("the ", state.ime?.text)
        assertEquals("the ", tv.text)
        assertEquals(listOf("t", "te", "teh", "te", "the "), tv.taken)
    }

    @Test
    fun `a composing-only change and an unchanged text send nothing`() {
        val tv = FakeTv()
        val state = focused(tv)
        val typed = session.type(state, "ph").state
        assertEquals(emptyList(), session.type(typed, "ph").messages)
    }

    @Test
    fun `a change made on the tv itself still reaches the mirror`() {
        val tv = FakeTv()
        var state = focused(tv)
        val step = session.type(state, "arte")
        state = session.reduce(step.state, tv.receive(step.messages.single())!!).state
        tv.text = "arte concert" // the TV's own suggestion, picked on the TV
        state = session.reduce(state, tv.echo()).state
        assertEquals("arte concert", state.ime?.text)
        assertEquals(emptyList(), state.imeEchoes)
    }

    @Test
    fun `typed before the tv reported any field, the first characters land and the echo opens the field`() {
        val tv = FakeTv(imeCounter = 12, fieldCounter = 4)
        // Only the key inject that starts the TV's keyboard: no show request, no batch edit yet.
        var state = session.reduce(RemoteState(ready = true, currentApp = "com.example.tv"), tv.focus().first()).state
        assertEquals(null, state.ime)
        var step = session.type(state, "p")
        state = step.state
        val early = tv.receive(step.messages.single())!!
        step = session.type(state, "ph")
        state = step.state
        val late = tv.receive(step.messages.single())!!
        state = session.reduce(state, early).state
        assertEquals("ph", state.ime?.text, "the early echo opens the field with what was typed since")
        state = session.reduce(state, late).state
        assertEquals("ph", state.ime?.text)
        assertEquals("ph", tv.text)
        assertEquals(emptyList(), tv.refused)
        assertEquals(emptyList(), state.imeEchoes)
    }

    @Test
    fun `a letter typed on the tv's own keyboard opens the field even without a show request`() {
        val tv = FakeTv()
        tv.text = "a"
        val state = session.reduce(RemoteState(ready = true), tv.echo()).state
        assertEquals("a", state.ime?.text)
        assertEquals(tv.imeCounter, state.imeCounter)
    }
}
