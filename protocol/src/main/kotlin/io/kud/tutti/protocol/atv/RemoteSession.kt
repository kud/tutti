package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage

data class TvVolume(val level: Int, val max: Int, val muted: Boolean)

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
            val ime = if (app != null && app != state.currentApp) null else state.ime
            return RemoteStep(state.copy(currentApp = app ?: state.currentApp, ime = ime))
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
                ),
            )
        }
        message.remote_ime_batch_edit?.let { edit ->
            val latest = edit.edit_info.lastOrNull()?.text_field_status
            val ime = if (latest != null && state.ime != null) {
                state.ime.copy(text = latest.value_, selectionStart = latest.start, selectionEnd = latest.end)
            } else {
                state.ime
            }
            return RemoteStep(state.copy(imeCounter = edit.ime_counter, fieldCounter = edit.field_counter, ime = ime))
        }
        message.remote_start?.let { return RemoteStep(state.copy(powered = it.started)) }
        message.remote_set_volume_level?.let {
            return RemoteStep(state.copy(volume = TvVolume(it.volume_level, it.volume_max, it.volume_muted)))
        }
        message.remote_error?.let { return RemoteStep(state.copy(error = "remote error")) }
        return RemoteStep(state)
    }

    /** Optimistic local echo of what we just sent, so the mirror field never lags. */
    fun typed(state: RemoteState, text: String): RemoteState =
        state.copy(ime = state.ime?.copy(text = text, selectionStart = text.length, selectionEnd = text.length))
}
