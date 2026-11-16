package io.kud.tutti.controller

import io.kud.tutti.protocol.atv.V2Key

/**
 * The TV's remote link as the remote screen shows it: [link] as it is ([TvLinkRule.shown]),
 * [reconnecting] while a link that was up is being won back in use, and [resuming] while one that
 * was up when the app left is being won back on its return. Technical details read the raw
 * [Links.tv]; everything on the remote reads this.
 */
data class TvShown(val link: Link, val reconnecting: Boolean = false, val resuming: Boolean = false)

/**
 * Why a TV key or app pressed while the remote link was not up was dropped. Nothing is ever held
 * to go out later (Erwann, 2026-11-07: « soit c'est connecté et ça marche, soit c'est pas
 * connecté et ça fait rien »).
 */
enum class KeyOutcome {
    /** Dropped, while the link is on its way (a blip, a reconnect, the first connect). */
    DROPPED_WAITING,

    /** Dropped: the TV has been out of reach past [TvLinkRule.GIVE_UP_MS], or there is no TV. */
    DROPPED_DOWN,

    /** Dropped: the TV forgot this phone. */
    DROPPED_PAIRING,

    /** A key waiting on a wake ([TuttiController.wakeThenKey]) whose TV never said it was on. */
    EXPIRED,
}

/**
 * The remote shows the TV's link as it is (Erwann, 2026-11-07: « Soit c'est connecté, soit c'est
 * pas connecté, et puis basta. »; it replaces the 2026-10-24 calm rule, which showed a drop as
 * still connected for 3 s while presses failed).
 *
 * The moment a link that was up drops, the remote wears the down dress and reads "Reconnecting to
 * the TV…": a clean blip on a home LAN (a 1 s backoff, then the TLS handshake and configure, about
 * 1.5 to 2.5 s) is a short fade down and back (`linkFade`). Past [GIVE_UP_MS] (20 s: the 1, 2, 4
 * and 8 s backoffs and their attempts) it is a real outage and "out of reach" comes back. A link
 * never up in this process (the first connect) shows as it is.
 *
 * A return to the app whose TV link was up when it left, but must be connected again (the
 * sessions stopped after the linger, or the one kept went stale while the phone slept), is a
 * resume ([TvShown.resuming]): CONNECTING, "Connecting…", from the first frame back, so the
 * remote comes back already in the down dress and has one transition, up, when the link is ready
 * (no fade from normal to down; Iris, 2026-11-07). The now-showing card keeps its last content
 * meanwhile, no skeletons: the content is rarely wrong after a short absence, and only the link
 * is in doubt. Keys stay honest: a press is dropped and says "connecting" ([whileDown]). A return
 * to a session still alive changes nothing.
 */
object TvLinkRule {
    const val GIVE_UP_MS = 20_000L

    /**
     * Implicit wake (Erwann, 2026-10-25): Home and Play/Pause pressed while the
     * TV is known asleep wake it, then go once it is on, the way any physical remote wakes the
     * screen. Only these two, because they mean "I want the TV": Home is where the TV starts, and
     * Play/Pause on a sleeping TV can only mean "carry on watching". Arrows, OK, Back, digits and
     * the rest mean something only on a screen already showing, so they keep their standby refusal.
     */
    val WAKES: Set<Int> = setOf(V2Key.HOME, V2Key.MEDIA_PLAY_PAUSE)

    /**
     * Whether [keyCode] wakes the TV before it is sent. [tvPowered] false only: a TV merely out of
     * reach (null) is a fault, not standby, and waking it blind would hide that. With the bar
     * playing its own source ([barAlone]), Play/Pause is about that music, so it never wakes the
     * TV there (waking it can pull the bar back to the TV's input over HDMI-CEC); Home still does.
     */
    fun wakesTv(keyCode: Int, long: Boolean, tvPowered: Boolean?, barAlone: Boolean): Boolean = when {
        long || tvPowered != false || keyCode !in WAKES -> false
        barAlone -> keyCode == V2Key.HOME
        else -> true
    }

    /**
     * [downForMs]: time since a link that was up went down (or since the return, when [resuming]);
     * null when it never was (or is up). Never ONLINE unless [raw] is: no grace that shows
     * connected while presses fail. ABSENT and NEEDS_PAIRING always show as they are.
     */
    fun shown(raw: Link, downForMs: Long?, resuming: Boolean = false): TvShown = when {
        raw == Link.ONLINE -> TvShown(Link.ONLINE)
        raw == Link.ABSENT || raw == Link.NEEDS_PAIRING || raw == Link.UNAUTHORIZED -> TvShown(raw)
        downForMs == null -> TvShown(raw)
        downForMs < GIVE_UP_MS -> TvShown(Link.CONNECTING, reconnecting = !resuming, resuming = resuming)
        else -> TvShown(Link.UNREACHABLE)
    }

    /**
     * What a TV link reads while an attempt runs, given the attempts that already failed in a row
     * as out of reach since it was last up (or since the session started). The first attempt
     * reads CONNECTING; a retry keeps UNREACHABLE, so a TV off the network reads "out of reach"
     * with its Wake action instead of "connecting…" forever: the retries are woken at once by
     * the remote coming to the front and by Power's wake kick, so CONNECTING was nearly all one
     * ever saw (dev.16, 2026-10-26). A link that was up reads "reconnecting" until give-up ([shown]).
     */
    fun whileTrying(failedInARow: Int): Link = if (failedInARow > 0) Link.UNREACHABLE else Link.CONNECTING

    /**
     * Whether the app coming to the front is a resume ([TvShown.resuming]): the TV link was up
     * when the app left, and must be connected again, because the sessions stopped
     * ([sessionsLive] false) or the one kept went stale ([droppedStale]).
     */
    fun resumes(upWhenLeft: Boolean, sessionsLive: Boolean, droppedStale: Boolean): Boolean =
        upWhenLeft && (!sessionsLive || droppedStale)

    /** When [shown] next changes on its own, in ms from now; null once nothing more will. */
    fun nextChangeInMs(downForMs: Long?): Long? = when {
        downForMs == null -> null
        downForMs < GIVE_UP_MS -> GIVE_UP_MS - downForMs
        else -> null
    }

    /**
     * Whether an app tapped by hand wakes the TV before it opens (Erwann, 2026-11-01: « allumer
     * la TV quand on appuie sur Netflix »). Unlike a key ([wakesTv]), an app
     * means "this, on the TV", the way the TV's own Netflix button powers it straight into
     * Netflix. So it wakes a TV known in standby, and also one shown out of reach:
     * that is where a TV in deep standby sits, and a wake that fails says why (Remote start)
     * where the tap was only refused. Never with no TV or a lost pairing, and never while a link
     * that was up is on its way back: a reconnect is not standby, and the tap is refused.
     */
    fun launchWakes(hasTv: Boolean, tvPowered: Boolean?, raw: Link, shown: TvShown): Boolean = when {
        !hasTv || raw == Link.ABSENT || raw == Link.NEEDS_PAIRING || raw == Link.UNAUTHORIZED -> false
        tvPowered == false -> true
        else -> shown.link == Link.UNREACHABLE
    }

    /**
     * A key or an app launch pressed while no route could carry it: dropped, never held for the
     * link to come back (Erwann, 2026-11-07), and this says why, in the same words for both.
     */
    fun whileDown(raw: Link, shown: TvShown): KeyOutcome = when {
        raw == Link.NEEDS_PAIRING -> KeyOutcome.DROPPED_PAIRING
        raw == Link.ABSENT || shown.link == Link.UNREACHABLE -> KeyOutcome.DROPPED_DOWN
        else -> KeyOutcome.DROPPED_WAITING
    }
}
