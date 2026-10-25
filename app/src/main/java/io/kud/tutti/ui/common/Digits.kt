package io.kud.tutti.ui.common

import androidx.compose.ui.text.TextStyle

/**
 * Tabular figures ("tnum"): every digit as wide as the others, so a volume number that changes
 * under a finger (18, 19, 20) keeps its width instead of shuffling sideways (Iris, 2026-10-25).
 * For every level tutti shows: the rail, the volume sheet, the overlay, the pad's tag, the guard's
 * stepper. A font without the feature simply ignores it.
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

const val TABULAR_FIGURES = "tnum"
