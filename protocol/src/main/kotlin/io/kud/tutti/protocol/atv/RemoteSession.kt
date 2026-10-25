package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage

data class TvVolume(val level: Int, val max: Int, val muted: Boolean)

/**
 * What survives a dropped link: nothing the TV reported live. Power and volume are the TV's
 * own word, so a reconnect must hear them again: a TV switched off with its own remote while
 * the link was down would otherwise still read as on, and Power would switch it "off".
 */
fun RemoteState.disconnected(): RemoteState = copy(ready = false, ime = null, powered = null, volume = null)

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
)

/** The phone field's text changed: what to send so the TV ends up holding the same. */
data class TypeStep(val state: RemoteState, val messages: List<RemoteMessage>)

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
            val ime = if (app != null && app != state.currentApp) null else state.ime
            // The TV starting its keyboard on a field also says which input session and field
            // it is: the counters a batch edit must carry to be taken. Without them our first
            // edits went with stale ones until the TV's own keyboard typed a letter and a batch
            // edit taught us (Erwann, 2026-10-25). Reading app_info.counter as the input
            // session counter is inferred from the message shape, unverified on the XR80.
            val imeCounter = inject.app_info?.counter?.takeIf { it != 0 } ?: state.imeCounter
            val fieldCounter = inject.text_field_status?.counter_field?.takeIf { it != 0 } ?: state.fieldCounter
            return RemoteStep(
                state.copy(currentApp = app ?: state.currentApp, ime = ime, imeCounter = imeCounter, fieldCounter = fieldCounter),
            )
        }
        message.remote_ime_show_request?.let { show ->
            val field = show.remote_text_field_status
            if (field == null) return RemoteStep(state)
            return RemoteStep(
                state.copy(
                    ime = ImeField(field.value_, field.label, field.start, field.end),
                    fieldCounter = field.counter_field,
                    imeProbe = ImeProbe(field.int5, field.label),
                    imeShows = state.imeShows + 1,
                    imeEchoes = emptyList(),
                ),
            )
        }
        message.remote_ime_batch_edit?.let { edit ->
            val latest = edit.edit_info.lastOrNull()?.text_field_status
            val counted = state.copy(imeCounter = edit.ime_counter, fieldCounter = edit.field_counter)
            if (latest == null || state.ime == null) return RemoteStep(counted)
            val ours = state.imeEchoes.indexOf(latest.value_)
            if (ours >= 0) {
                // Our own text coming back: the mirror already shows it, or something newer.
                return RemoteStep(counted.copy(imeEchoes = state.imeEchoes.drop(ours + 1)))
            }
            // The TV changed the field itself (its autocomplete, another remote): follow it.
            val ime = state.ime.copy(text = latest.value_, selectionStart = latest.start, selectionEnd = latest.end)
            return RemoteStep(counted.copy(ime = ime, imeEchoes = emptyList()))
        }
        message.remote_start?.let { return RemoteStep(state.copy(powered = it.started)) }
        message.remote_set_volume_level?.let {
            return RemoteStep(state.copy(volume = TvVolume(it.volume_level, it.volume_max, it.volume_muted)))
        }
        message.remote_error?.let { return RemoteStep(state.copy(error = "remote error")) }
        return RemoteStep(state)
    }

    /**
     * The phone field now reads [text]: the whole value goes to the TV with its latest counters,
     * and the mirror takes it at once (optimistic), remembering it so its echo is recognised.
     * Unchanged text sends nothing, so a composing-only change (Gboard underlining a word) is quiet.
     */
    fun type(state: RemoteState, text: String): TypeStep {
        if (state.ime != null && state.ime.text == text) return TypeStep(state, emptyList())
        val message = RemoteMessages.text(text, state.imeCounter, state.fieldCounter)
        val next = if (state.ime == null) {
            state
        } else {
            state.copy(
                ime = state.ime.copy(text = text, selectionStart = text.length, selectionEnd = text.length),
                imeEchoes = (state.imeEchoes + text).takeLast(ECHOES),
            )
        }
        return TypeStep(next, listOf(message))
    }

    companion object {
        /** Our own texts awaiting their echo; past this many, the oldest are given up on. */
        const val ECHOES = 32
    }
}
