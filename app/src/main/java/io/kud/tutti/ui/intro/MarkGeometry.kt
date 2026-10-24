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

    /*
     * The intro's opening (PR #11's splash, Iris, 2026-10-24): the lowercase wordmark under the
     * handset and, in dark, a soft halo behind it. Neither is part of the icon (the system splash
     * takes an icon only), so they sit outside the viewport, in the same units, centred on the mark.
     */

    // The wordmark: 64 units wide (about 1.9 handsets, the v2 lockup's own ratio is 2.4, trimmed
    // because the system splash draws the mark larger than the study did), its baseline 55 units
    // below the handset (the study's 1.56 handsets would leave a phone's screen).
    const val WORDMARK_WIDTH = 64f
    const val WORDMARK_BASELINE = 138f

    // How far the wordmark rises as it arrives.
    const val WORDMARK_RISE = 4f

    // The halo: an ellipse round the mark, a touch below its centre, as in the study (rx 200, ry 235
    // round a 73 x 138 handset).
    const val HALO_Y = 57f
    const val HALO_RX = 94f
    const val HALO_RY = 100f
}

/**
 * The lowercase "tutti" from the v2 lockup (design/branding/v2/lockup-*.svg on PR #11), as outlines,
 * baseline at y 0, so the APK carries no font. The i's dot is not in the ink: it is [DOT_X], [DOT_Y],
 * [DOT_RADIUS], drawn apart in amber.
 */
internal object Wordmark {
    const val PATH = "M77.076,-114.469L53.662,-114.469L53.662,-145.688L21.68,-145.688L21.68,-114.469L1.943,-114.469" +
        "L1.943,-89.424L21.68,-89.424L21.68,-30.352C21.68,-9.539 33.265,0 58.414,0L77.076,0L77.076,-25.044" +
        "L64.831,-25.044C56.159,-25.044 53.662,-27.542 53.662,-35.347L53.662,-89.424L77.076,-89.424Z" +
        "M133.317,2.393C149.794,2.393 161.414,-4.995 169.115,-18.003L169.115,0L200.437,0L200.437,-114.469" +
        "L168.56,-114.469L168.56,-50.852C168.56,-33.057 159.333,-25.148 146.117,-25.148" +
        "C132.797,-25.148 125.096,-33.265 125.096,-48.667L125.096,-114.469L93.114,-114.469L93.114,-42.804" +
        "C93.114,-13.875 108.932,2.393 133.317,2.393Z" +
        "M288.886,-114.469L265.472,-114.469L265.472,-145.688L233.49,-145.688L233.49,-114.469L213.753,-114.469" +
        "L213.753,-89.424L233.49,-89.424L233.49,-30.352C233.49,-9.539 245.076,0 270.224,0L288.886,0L288.886,-25.044" +
        "L276.641,-25.044C267.97,-25.044 265.472,-27.542 265.472,-35.347L265.472,-89.424L288.886,-89.424Z" +
        "M370.727,-114.469L347.313,-114.469L347.313,-145.688L315.331,-145.688L315.331,-114.469L295.594,-114.469" +
        "L295.594,-89.424L315.331,-89.424L315.331,-30.352C315.331,-9.539 326.917,0 352.065,0L370.727,0L370.727,-25.044" +
        "L358.482,-25.044C349.81,-25.044 347.313,-27.542 347.313,-35.347L347.313,-89.424L370.727,-89.424Z" +
        "M386.766,0L418.748,0L418.748,-114.469L386.766,-114.469Z"

    const val DOT_X = 402.7f
    const val DOT_Y = -145.9f
    const val DOT_RADIUS = 17.5f

    // The outline's extent, dot included: left of the first t to right of the dot; top of the dot to the u's overshoot.
    const val LEFT = 1.943f
    const val RIGHT = DOT_X + DOT_RADIUS
    const val TOP = DOT_Y - DOT_RADIUS
    const val BOTTOM = 2.393f

    /** Outline units to the mark's units. */
    const val SCALE = MarkGeometry.WORDMARK_WIDTH / (RIGHT - LEFT)
}
