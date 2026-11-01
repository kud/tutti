package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage

data class TvVolume(val level: Int, val max: Int, val muted: Boolean)

/**
 * What survives a dropped link: nothing the TV reported live. Power and volume are the TV's
 * own word, so a reconnect must hear them again: a TV switched off with its own remote while
 * the link was down would otherwise still read as on, and Power would switch it "off".
 */
fun RemoteState.disconnected(): RemoteState =
    copy(ready = false, ime = null, imeEchoes = emptyList(), powered = null, volume = null).fieldGone()

/** Everything tied to one focused field, forgotten when it goes. [ImeStyle] stays: it is the TV's. */
internal fun RemoteState.fieldGone(): RemoteState = copy(
    imeSession = false, imeDeaf = false, imeTrial = null, imeHeld = null, imeSentAt = null,
    tvText = "", tvSaid = "", caretOff = false, keyed = "", keyedAsked = "",
)

/**
 * How the TV applies a batch edit's value. Public sources disagree: androidtvremote2 and Home
 * Assistant send one whole value into an empty field (where both readings agree); sofa-remote
 * says it "inserts at the cursor, it never replaces"; Hubmote says typing replaces the field.
 * tutti learns it from the TV's first echo of an edit into a non-empty field.
 */
enum class ImeStyle { UNKNOWN, REPLACE, INSERT }

/** The edit that tells the styles apart: [sent] went out while the TV held [before]. */
data class ImeTrial(val before: String, val sent: String)

/** The TV's focused text field, mirrored above the phone keyboard. */
data class ImeField(val text: String, val label: String, val selectionStart: Int, val selectionEnd: Int)

/**
 * What the last show request said about its field besides the text: `int5`, unknown, maybe the
 * field's input type (which would let us mask passwords), and its label. Kept for the developer
 * diagnostic only; never the field's value.
 */
data class ImeProbe(val int5: Int, val label: String)

data class RemoteState(
    val ready: Boolean = false,
    val powered: Boolean? = null,
    val volume: TvVolume? = null,
    /** Foreground app package, from `RemoteImeKeyInject.app_info.app_package`. */
    val currentApp: String? = null,
    /** Non-null while the TV reports a focused text field: the keyboard opens. */
    val ime: ImeField? = null,
    val imeCounter: Int = 0,
    val fieldCounter: Int = 0,
    /**
     * One per show request: a field the TV opened. Batch edits move [fieldCounter] too, so this
     * is what tells a new field from the same one being typed in.
     */
    val imeShows: Int = 0,
    /** The last field the TV opened, for the int5 probe; outlives the field. */
    val imeProbe: ImeProbe? = null,
    val error: String? = null,
    /** The TV's feature mask from its configure (`code1`): bit 3 is voice. Null until it arrives. */
    val tvFeatures: Int? = null,
    /**
     * The field's texts we typed, oldest first, that the TV has not echoed back yet. A batch
     * edit carrying one of them is our own echo (maybe a stale one): it never overwrites the
     * mirror, or a late "p" would wipe the "ph" typed since.
     */
    val imeEchoes: List<String> = emptyList(),
    /** The TV opened an input session (show request, its own batch edit, or a key inject with a field). */
    val imeSession: Boolean = false,
    val imeStyle: ImeStyle = ImeStyle.UNKNOWN,
    /** An edit out to learn [imeStyle]; newer text waits in [imeHeld] for its echo. */
    val imeTrial: ImeTrial? = null,
    val imeHeld: String? = null,
    /** When the oldest edit the TV hasn't answered went out (ms, caller's clock). */
    val imeSentAt: Long? = null,
    /** This field never answered an edit: text goes as key presses. */
    val imeDeaf: Boolean = false,
    /** What the TV's field will hold once everything sent lands. */
    val tvText: String = "",
    /** The last text the TV itself reported for the field. */
    val tvSaid: String = "",
    /** The TV's caret was not at the end of its text: an insert first sends MOVE_END. */
    val caretOff: Boolean = false,
    /** Key path: what the key presses put in the focused box, and the phone text they came from. */
    val keyed: String = "",
    val keyedAsked: String = "",
)

/** The phone field's text changed: what to send so the TV ends up holding the same. */
data class TypeStep(
    val state: RemoteState,
    val messages: List<RemoteMessage>,
    /** Characters typed as keys that have no key (accents, emoji): not sent, to be named. */
    val dropped: List<Char> = emptyList(),
)

data class RemoteStep(val state: RemoteState, val replies: List<RemoteMessage> = emptyList())

/**
 * Port 6466 session reducer: answers the TV's configure / set-active / ping handshake and
 * folds everything else into [RemoteState]. Pure; [AtvRemote] owns the socket.
 */
class RemoteSession(private val deviceInfo: ClientDeviceInfo = ClientDeviceInfo()) {
    fun reduce(state: RemoteState, message: RemoteMessage): RemoteStep {
        message.remote_configure?.let {
            return RemoteStep(state.copy(ready = true, error = null, tvFeatures = it.code1), listOf(RemoteMessages.configure(deviceInfo)))
        }
        message.remote_set_active?.let {
            return RemoteStep(state, listOf(RemoteMessages.setActive()))
        }
        message.remote_ping_request?.let { ping ->
            return RemoteStep(state, listOf(RemoteMessages.pingResponse(ping.val1)))
        }
        message.remote_ime_key_inject?.let { inject ->
            val app = inject.app_info?.app_package?.takeIf { it.isNotEmpty() }
            // A new foreground app means any text field we mirrored is gone (heuristic, unverified).
            val moved = app != null && app != state.currentApp
            val ime = if (moved) null else state.ime
            // The TV starting its keyboard on a field also says which input session and field
            // it is: the counters a batch edit must carry to be taken. Without them our first
            // edits went with stale ones until the TV's own keyboard typed a letter and a batch
            // edit taught us (Erwann, 2026-10-25). Reading app_info.counter as the input
            // session counter is inferred from the message shape, unverified on the XR80.
            val imeCounter = inject.app_info?.counter?.takeIf { it != 0 } ?: state.imeCounter
            val fieldCounter = inject.text_field_status?.counter_field?.takeIf { it != 0 } ?: state.fieldCounter
            val base = if (moved) state.fieldGone() else state
            return RemoteStep(
                base.copy(
                    currentApp = app ?: state.currentApp,
                    ime = ime,
                    imeEchoes = if (moved) emptyList() else state.imeEchoes,
                    imeCounter = imeCounter,
                    fieldCounter = fieldCounter,
                    // A field in the inject is the TV's keyboard starting: edits have somewhere to go.
                    imeSession = base.imeSession || inject.text_field_status != null,
                    tvText = if (!base.imeSession && inject.text_field_status != null) inject.text_field_status.value_ else base.tvText,
                    tvSaid = if (!base.imeSession && inject.text_field_status != null) inject.text_field_status.value_ else base.tvSaid,
                ),
            )
        }
        message.remote_ime_show_request?.let { show ->
            val field = show.remote_text_field_status
            if (field == null) return RemoteStep(state)
            // sofa-remote reports the TV re-sending a show request with the new text after each
            // edit. Our own text coming back that way is an echo of the same field, never a new
            // one: counting it as one reset the phone field and buzzed on every keystroke.
            if (state.ime != null && isOurs(state, field.value_)) {
                return heard(state.copy(fieldCounter = field.counter_field), field.value_, field.start, field.end)
            }
            return RemoteStep(
                state.fieldGone().copy(
                    ime = ImeField(field.value_, field.label, field.start, field.end),
                    fieldCounter = field.counter_field,
                    imeProbe = ImeProbe(field.int5, field.label),
                    imeShows = state.imeShows + 1,
                    imeEchoes = emptyList(),
                    imeSession = true,
                    tvText = field.value_,
                    tvSaid = field.value_,
                    caretOff = field.end in 0 until field.value_.length,
                ),
            )
        }
        message.remote_ime_batch_edit?.let { edit ->
            val latest = edit.edit_info.lastOrNull()?.text_field_status
            val counted = state.copy(imeCounter = edit.ime_counter, fieldCounter = edit.field_counter)
            if (latest == null) return RemoteStep(counted)
            return heard(counted, latest.value_, latest.start, latest.end)
        }
        message.remote_start?.let { return RemoteStep(state.copy(powered = it.started)) }
        message.remote_set_volume_level?.let {
            return RemoteStep(state.copy(volume = TvVolume(it.volume_level, it.volume_max, it.volume_muted)))
        }
        message.remote_error?.let { return RemoteStep(state.copy(error = "remote error")) }
        return RemoteStep(state)
    }

    private fun isOurs(state: RemoteState, text: String): Boolean {
        if (text in state.imeEchoes) return true
        val trial = state.imeTrial ?: return false
        return text == trial.sent || text == trial.before + trial.sent
    }

    /** The TV reported its field's text [text]: an echo of ours, the answer to a trial, or its own change. */
    private fun heard(state: RemoteState, text: String, start: Int, end: Int): RemoteStep {
        val answered = state.copy(imeSession = true, imeSentAt = null, imeDeaf = false, tvSaid = text)
        val trial = state.imeTrial
        if (trial != null && text != trial.sent && text == trial.before + trial.sent) {
            // The TV inserted our whole text after its own: it inserts. Repair from what it holds
            // to what the phone shows now, with deletes and an insert.
            val learnt = answered.copy(imeStyle = ImeStyle.INSERT, imeTrial = null, imeHeld = null, imeEchoes = emptyList(), tvText = text, caretOff = false)
            val target = state.imeHeld ?: trial.sent
            val sent = send(learnt, target)
            return RemoteStep(sent.state, sent.messages)
        }
        if (trial != null && text == trial.sent) {
            val learnt = answered.copy(imeStyle = ImeStyle.REPLACE, imeTrial = null, imeHeld = null)
            val echoed = echo(learnt, text)
            val held = state.imeHeld ?: return RemoteStep(echoed)
            val sent = send(echoed, held)
            return RemoteStep(sent.state, sent.messages)
        }
        if (text in state.imeEchoes) return RemoteStep(echo(answered, text))
        if (trial != null) {
            // Something else while we wait: the TV's own change. The trial is moot; the held text goes as a replace next time.
            val ime = (state.ime ?: ImeField("", "", 0, 0)).copy(text = text, selectionStart = start, selectionEnd = end)
            return RemoteStep(answered.copy(ime = ime, imeEchoes = emptyList(), imeTrial = null, imeHeld = null, tvText = text, caretOff = end in 0 until text.length))
        }
        // The TV changed the field itself (its own keyboard, autocomplete, another remote):
        // follow it, and a batch edit is a field even when no show request came first.
        val ime = (state.ime ?: ImeField("", "", 0, 0)).copy(text = text, selectionStart = start, selectionEnd = end)
        return RemoteStep(answered.copy(ime = ime, imeEchoes = emptyList(), tvText = text, caretOff = end in 0 until text.length))
    }

    /**
     * Our own text coming back: the mirror already shows it, or something newer. Typed before
     * the TV said it had a field, it also tells us the field is there.
     */
    private fun echo(state: RemoteState, text: String): RemoteState {
        val ours = state.imeEchoes.indexOf(text)
        val newest = state.imeHeld ?: state.imeEchoes.lastOrNull() ?: text
        val ime = state.ime ?: ImeField(newest, "", newest.length, newest.length)
        return state.copy(ime = ime, imeEchoes = if (ours >= 0) state.imeEchoes.drop(ours + 1) else state.imeEchoes)
    }

    /**
     * The phone field now reads [text]. With an input session on the TV it goes as a batch
     * edit, shaped by [ImeStyle]; with none (a WebView or custom text box, Nuvio's likely
     * case), or once a field has ignored an edit for [ACK_MS], it goes as key presses. The
     * mirror takes it at once (optimistic) and remembers it so its echo is recognised.
     * Unchanged text sends nothing, so a composing-only change (Gboard underlining a word) is quiet.
     * [now] is a monotonic clock in milliseconds.
     */
    fun type(state: RemoteState, text: String, now: Long = 0L): TypeStep {
        var s = state
        val sentAt = s.imeSentAt
        if (s.imeSession && !s.imeDeaf && sentAt != null && now - sentAt >= ACK_MS) {
            // The field never answered: our edits went nowhere. Keys from what it last confirmed.
            val confirmed = s.tvSaid
            s = s.copy(imeDeaf = true, imeEchoes = emptyList(), imeTrial = null, imeHeld = null, imeSentAt = null, keyed = confirmed, keyedAsked = confirmed)
        }
        if (!s.imeSession || s.imeDeaf) return typeKeys(s, text)
        val asked = s.imeHeld ?: s.ime?.text ?: s.imeEchoes.lastOrNull()
        if (asked == text) return TypeStep(s, emptyList())
        val mirrored = s.copy(ime = s.ime?.copy(text = text, selectionStart = text.length, selectionEnd = text.length))
        // One round trip while the style is being learnt: only the newest text waits.
        if (s.imeTrial != null) return TypeStep(mirrored.copy(imeHeld = text), emptyList())
        val sent = send(mirrored, text)
        return TypeStep(sent.state.copy(imeSentAt = s.imeSentAt ?: now), sent.messages)
    }

    /** Edits that take the TV's field from [RemoteState.tvText] to [text], in the TV's style. */
    private fun send(state: RemoteState, text: String): TypeStep {
        val before = state.tvText
        if (before == text) return TypeStep(state, emptyList())
        if (state.imeStyle == ImeStyle.INSERT) {
            val keep = TextKeys.commonPrefix(before, text)
            val messages = mutableListOf<RemoteMessage>()
            if (state.caretOff) messages += RemoteMessages.key(V2Key.MOVE_END)!!
            // Each delete may come back as its own report: expect every step on the way.
            val steps = mutableListOf<String>()
            for (i in 1..(before.length - keep)) {
                messages += RemoteMessages.key(V2Key.DEL)!!
                steps += before.dropLast(i)
            }
            val tail = text.substring(keep)
            if (tail.isNotEmpty()) messages += RemoteMessages.text(tail, state.imeCounter, state.fieldCounter)
            steps += text
            return TypeStep(state.copy(tvText = text, caretOff = false, imeEchoes = (state.imeEchoes + steps).takeLast(ECHOES)), messages)
        }
        val message = RemoteMessages.text(text, state.imeCounter, state.fieldCounter)
        // A replace into a non-empty field is where the styles differ: its echo teaches us.
        val trial = if (state.imeStyle == ImeStyle.UNKNOWN && before.isNotEmpty()) ImeTrial(before, text) else null
        return TypeStep(
            state.copy(tvText = text, imeTrial = trial, imeEchoes = (state.imeEchoes + text).takeLast(ECHOES)),
            listOf(message),
        )
    }

    /** No input session: deletes and characters as key presses, from what the keys typed so far. */
    private fun typeKeys(state: RemoteState, text: String): TypeStep {
        if (text == state.keyedAsked) return TypeStep(state, emptyList())
        val fresh = text.substring(TextKeys.commonPrefix(state.keyedAsked, text))
        val dropped = fresh.filterNot(TextKeys::typable).toList()
        val target = text.filter(TextKeys::typable)
        val keep = TextKeys.commonPrefix(state.keyed, target)
        val messages = mutableListOf<RemoteMessage>()
        repeat(state.keyed.length - keep) { messages += RemoteMessages.key(V2Key.DEL)!! }
        target.substring(keep).forEach { messages += TextKeys.messages(TextKeys.stroke(it)!!) }
        val ime = state.ime?.copy(text = text, selectionStart = text.length, selectionEnd = text.length)
        return TypeStep(state.copy(ime = ime, keyed = target, keyedAsked = text), messages, dropped)
    }

    companion object {
        /** Our own texts awaiting their echo; past this many, the oldest are given up on. */
        const val ECHOES = 32

        /** An edit the TV hasn't answered in this long went nowhere: the field takes keys instead. */
        const val ACK_MS = 1_500L
    }
}
