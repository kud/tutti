package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.atv.V2Key

/**
 * The keys that open a menu on the TV (Erwann, 2026-10-25: « Si je clique sur Subtitles, il faut
 * que je recache la bottom sheet pour pouvoir contrôler les subtitles »). That menu is then
 * driven with the pad, so a sheet that sends one of these closes and hands the remote back, pad
 * in view. Every other sheet key (transport, digits, channels, the colour keys, Live TV, Jump) is
 * pressed again and again, so the sheet stays open for the next press. design.md, Sheets.
 */
val TV_MENU_KEYS: Set<Int> = setOf(
    V2Key.CAPTIONS,
    V2Key.MEDIA_AUDIO_TRACK,
    V2Key.MENU,
    V2Key.GUIDE,
    V2Key.INFO,
    V2Key.TV_INPUT,
    V2Key.SETTINGS,
)

/** Whether pressing [code] in a sheet closes it: only a key that opens a TV-side menu does. */
fun keyClosesSheet(code: Int): Boolean = code in TV_MENU_KEYS
