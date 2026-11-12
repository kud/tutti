package io.kud.tutti.protocol.privacy

/**
 * Every feature that reaches, or opens, a service that is neither the person's own device nor free
 * software (Erwann, 2026-11-12: « on peut désactiver par défaut, activer quand on a besoin… avoir un
 * bouton qui permet de réactiver facilement, service libre uniquement »). Each is off until turned on,
 * in both flavours, and "Only free services" turns them all off at once, whatever their own switch
 * says. The answer to F-Droid's NonFreeNet anti-feature (docs/fdroid.md).
 *
 * Not here, on purpose: the TV, the soundbar and Cast on the LAN (the person's own devices, not a
 * service); BRAVIA Connect hand-offs (opening Sony's app for the TV's own setup sends nothing from
 * tutti); "Show on TV" (the person sends a link to their TV); Share (Android's share sheet); the
 * github flavour's updater (GitHub hosts the app itself, and F-Droid builds carry none).
 *
 * A new feature that contacts or opens a third-party service joins this list first; the exhaustive
 * `when` in `:app`'s `AppPrefs.optedIn` then refuses to compile until it has its own switch.
 */
enum class NonFreeFeature(
    /** Who receives what, in plain words for docs and tests; the UI has its own strings. */
    val sends: String,
    /** Contacted by tutti itself, as opposed to a link the phone's browser or another app opens. */
    val tuttiConnects: Boolean,
) {
    /** The picture Cast names, from YouTube's, Google's, TMDB's or Stremio's image hosts. */
    CAST_ARTWORK("the picture's address (a video or film id) to the image host the TV named", tuttiConnects = true),

    /** Synopsis, cast and rating from Stremio's Cinemeta, poster included. */
    TITLE_DETAILS("the IMDb id of what is playing to Stremio's Cinemeta", tuttiConnects = true),

    /** A Trakt search for the title, opened in the browser or the Trakt app. */
    TRAKT_LINK("the title, in a Trakt search link the person taps", tuttiConnects = false),

    /** A JustWatch search for the title, opened in the browser. */
    JUSTWATCH_LINK("the title, in a JustWatch search link the person taps", tuttiConnects = false),

    /** The title's IMDb page, opened in the browser or the IMDb app. */
    IMDB_LINK("the IMDb id of what is playing, in an imdb.com link the person taps", tuttiConnects = false),
}

object FreeServices {
    /** Whether [optedIn] may run: its own switch on and "Only free services" off. The master wins. */
    fun allows(onlyFree: Boolean, optedIn: Boolean): Boolean = optedIn && !onlyFree

    /**
     * Whether to offer a feature where it would help: once, quietly, never while "Only free services"
     * is on, never once it is on or the offer was declined (declined is for good).
     */
    fun offers(onlyFree: Boolean, optedIn: Boolean, declined: Boolean): Boolean = !onlyFree && !optedIn && !declined
}
