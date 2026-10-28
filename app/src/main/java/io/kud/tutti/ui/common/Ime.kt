package io.kud.tutti.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/*
 * The keyboard rule (Iris, 2026-10-28, after Erwann's « Certains screens sont coupés car on doit
 * scroller alors qu'on a la place »):
 *
 * 1. One owner per inset. A Scaffold's padding is consumed before `imePadding()`
 *    (`.padding(padding).consumeWindowInsets(padding).imePadding()`), so the navigation bar,
 *    which the IME's inset already contains, is never counted twice.
 * 2. While the keyboard is up, decoration shows only if everything still fits ([roomWhileTyping]):
 *    the field, its helper or counter and its action come first, the illustration yields.
 * 3. The focused field's whole group ([keepAboveIme]: field, helper, action) is brought into view
 *    as the keyboard settles. Scrolling stays, as the fallback when even that is too tall.
 */

/**
 * While anything inside this element has focus and the keyboard is up, keeps the whole element in
 * view: put it on the group a field belongs to (the field, its counter or helper, its action), not
 * on the field alone, which Compose already scrolls to.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.keepAboveIme(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    // Re-asked on every step of the keyboard's slide; each ask cancels the last, so the final one,
    // once the keyboard has settled, is the one that lands.
    LaunchedEffect(focused, ime) {
        if (focused && ime > 0) requester.bringIntoView()
    }
    bringIntoViewRequester(requester).onFocusChanged { focused = it.hasFocus }
}

/**
 * Whether decoration has room in this box: always without the keyboard (the screen scrolls as it
 * did), and with it only when [fixed] drawn height plus [text] of type still fit. [text] is in sp
 * so the sum follows the font scale.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxWithConstraintsScope.roomWhileTyping(fixed: Dp, text: TextUnit): Boolean {
    if (!WindowInsets.isImeVisible) return true
    val needed = fixed + with(LocalDensity.current) { text.toDp() }
    return maxHeight >= needed
}
