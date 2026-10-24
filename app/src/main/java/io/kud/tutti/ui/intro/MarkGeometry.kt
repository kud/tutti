package io.kud.tutti.ui.intro

/**
 * The launcher mark's geometry, in its 108-unit adaptive-icon viewport: one ivory handset, a small
 * top button, the OK and an amber pill. The vectors (ic_launcher_foreground, ic_launcher_monochrome,
 * splash_icon) spell out the same numbers by hand, and the launch intro draws from these, so the
 * system splash and the first frame of the intro are the same picture (Iris, 2026-10-24, from the
 * v2 brand study: the taller handset, the top button and the heavier pill; not its dial ring,
 * gradients or glow).
 */
internal object MarkGeometry {
    const val VIEWPORT = 108f
    const val CENTRE = 54f

    // The handset: 34 x 58, so it reads as a remote rather than a phone or a battery.
    const val BODY_LEFT = 37f
    const val BODY_TOP = 25f
    const val BODY_RIGHT = 71f
    const val BODY_BOTTOM = 83f
    const val BODY_CORNER = 13f

    // The small top button: tells up from down at launcher size.
    const val TOP_Y = 32f
    const val TOP_RADIUS = 2.5f

    // The OK: the one thing the intro moves.
    const val OK_Y = 50f
    const val OK_RADIUS = 10f

    // The amber pill: 18 x 6, heavy enough to survive 48 dp.
    const val PILL_LEFT = 45f
    const val PILL_RIGHT = 63f
    const val PILL_TOP = 69f
    const val PILL_BOTTOM = 75f

    /** Android's adaptive-icon safe zone: a 66-unit circle, centred; nothing of the mark may leave it. */
    const val SAFE_RADIUS = 33f
}
