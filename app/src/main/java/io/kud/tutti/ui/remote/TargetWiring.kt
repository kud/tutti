package io.kud.tutti.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.controller.DeviceVia
import io.kud.tutti.controller.TargetState
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.hid.RemoteLink

/** How a device's keys travel, in the sheet's three words: the TV's two LAN planes and the bar's are all "the network". */
internal fun DeviceVia.remoteLink(): RemoteLink = when (this) {
    DeviceVia.GOOGLE_TV, DeviceVia.SONY_IP, DeviceVia.NETWORK -> RemoteLink.LAN
    DeviceVia.BLUETOOTH -> RemoteLink.BLUETOOTH
    DeviceVia.NONE -> RemoteLink.NONE
}

/** The engine's present devices as the sheet's rows, the current target ticked. */
internal fun targetRows(state: TargetState, tvInput: String? = null, tvPowered: Boolean? = null, ipControl: Boolean = false): List<TargetRow> = state.present.map { d ->
    TargetRow(
        showOnTv = showOnTvOffered(d.kind == DeviceKind.COMPUTER, d.hdmiInput, tvInput, tvPowered, ipControl),
        id = d.id,
        name = d.name,
        kind = if (d.kind == DeviceKind.COMPUTER) TargetKind.COMPUTER else TargetKind.TV,
        link = d.via.remoteLink(),
        current = d.id == state.target?.id,
    )
}

/** What a remote key does on a computer, for TalkBack beside the unchanged glyph; null for keys with no computer meaning. */
internal fun computerMeaning(code: Int): Int? = when (code) {
    V2Key.BACK -> R.string.computer_meaning_back
    V2Key.DPAD_UP, V2Key.DPAD_DOWN, V2Key.DPAD_LEFT, V2Key.DPAD_RIGHT -> R.string.computer_meaning_arrow
    V2Key.DPAD_CENTER, V2Key.ENTER -> R.string.computer_meaning_ok
    V2Key.MEDIA_PLAY_PAUSE, V2Key.MEDIA_NEXT, V2Key.MEDIA_PREVIOUS, V2Key.MEDIA_REWIND, V2Key.MEDIA_FAST_FORWARD -> R.string.computer_meaning_media
    else -> null
}

@Composable
internal fun computerMeaningWords(): (Int) -> String? {
    val words = listOf(R.string.computer_meaning_back, R.string.computer_meaning_arrow, R.string.computer_meaning_ok, R.string.computer_meaning_media)
        .associateWith { stringResource(it) }
    return { code -> computerMeaning(code)?.let(words::get) }
}
