package io.kud.tutti.controller

import io.kud.tutti.protocol.atv.V2Key

/**
 * The TV's remote link as the remote screen shows it: [link] after the calm rule, and
 * [reconnecting] while a link that was up is being won back. Technical details read the raw
 * [Links.tv]; everything on the remote reads this.
 */
data class TvShown(val link: Link, val reconnecting: Boolean = false)

/** What happened to a TV key pressed while the remote link was not up. */
enum class KeyOutcome {
    /** A one-shot key, held for up to [TvLinkRule.HOLD_MS] and sent the moment the link is back. */
    HELD,

    /** Dropped, while the link is on its way (a blip, a reconnect, the first connect). */
    DROPPED_WAITING,

    /** Dropped: the TV has been out of reach past [TvLinkRule.GIVE_UP_MS], or there is no TV. */
    DROPPED_DOWN,

    /** Dropped: the TV forgot this phone. */
    DROPPED_PAIRING,

    /** A held key whose link did not come back within [TvLinkRule.HOLD_MS]. */
    EXPIRED,
}

/**
 * Calm when it doesn't matter, honest when it does (Iris, 2026-10-24, from Erwann's "si les
 * touches ne marchent pas à ce moment-là, peut-être que c'est mieux").
 *
 * A link that drops after being up goes through the remote loop as UNREACHABLE, a 1 s backoff,
 * CONNECTING, then the TLS handshake and the remote's configure exchange: about 1.5 to 2.5 s on a
 * home LAN. [BLIP_MS] (3 s) covers that first retry with margin, so a clean blip changes nothing
 * on screen. Past it the first retry has failed, and the remote says "Reconnecting to the TV…",
 * quietly. Past [GIVE_UP_MS] (20 s: the 1, 2, 4 and 8 s backoffs and their attempts) it is a real
 * outage and the stronger "out of reach" comes back. A link that was never up this session (first
 * connect, no pairing) shows as it is.
 */
object TvLinkRule {
    const val BLIP_MS = 3_000L
    const val GIVE_UP_MS = 20_000L

    /**
     * How long a one-shot key waits for the link. A blip's reconnect lands within about 2 s of the
     * drop, so a key pressed in it usually lands in under that. Any later and the person has
     * already looked down or pressed again: a Home arriving five seconds on is a surprise, not help.
     */
    const val HOLD_MS = 2_000L

    /**
     * One-shot keys: where they go doesn't depend on where the focus is, and one landing late
     * does what was asked. Held (newest wins, so two Backs in a blip send one). Everything else
     * drops: D-pad and OK (a burst of arrows landing late, or OK on whatever got focus meanwhile),
     * digits and channel steps (sequences), colour keys and skips (context), and power, which has
     * its own wake path and must never toggle late.
     */
    val HOLDABLE: Set<Int> = setOf(
        V2Key.HOME,
        V2Key.BACK,
        V2Key.MEDIA_PLAY_PAUSE,
        V2Key.MEDIA_PLAY,
        V2Key.MEDIA_PAUSE,
        V2Key.MEDIA_STOP,
        V2Key.GUIDE,
        V2Key.SETTINGS,
        V2Key.ALL_APPS,
        V2Key.TV,
    )

    /**
     * Implicit wake (Erwann, 2026-10-25, delegated to Maya): Home and Play/Pause pressed while the
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
     * [downForMs]: time since a link that was up this session went down; null when it never was
     * (or is up). ABSENT and NEEDS_PAIRING always show as they are.
     */
    fun shown(raw: Link, downForMs: Long?): TvShown = when {
        raw == Link.ONLINE -> TvShown(Link.ONLINE)
        raw == Link.ABSENT || raw == Link.NEEDS_PAIRING || raw == Link.UNAUTHORIZED -> TvShown(raw)
        downForMs == null -> TvShown(raw)
        downForMs < BLIP_MS -> TvShown(Link.ONLINE)
        downForMs < GIVE_UP_MS -> TvShown(Link.CONNECTING, reconnecting = true)
        else -> TvShown(Link.UNREACHABLE)
    }

    /**
     * What a TV link reads while an attempt runs, given the attempts that already failed in a row
     * as out of reach since it was last up (or since the session started). The first attempt
     * reads CONNECTING; a retry keeps UNREACHABLE, so a TV off the network reads "out of reach"
     * with its Wake action instead of "connecting…" forever: the retries are woken at once by
     * the remote coming to the front and by Power's wake kick, so CONNECTING was nearly all one
     * ever saw (dev.16, 2026-10-26). A link that was up keeps the calm rule ([shown]).
     */
    fun whileTrying(failedInARow: Int): Link = if (failedInARow > 0) Link.UNREACHABLE else Link.CONNECTING

    /** When [shown] next changes on its own, in ms from now; null once nothing more will. */
    fun nextChangeInMs(downForMs: Long?): Long? = when {
        downForMs == null -> null
        downForMs < BLIP_MS -> BLIP_MS - downForMs
        downForMs < GIVE_UP_MS -> GIVE_UP_MS - downForMs
        else -> null
    }

    /**
     * An app launch tapped with no route able to go now (remote link not ready, no IP control
     * route). While the remote still shows the link up, a blip, it is held and sent the moment the
     * link is back ([io.kud.tutti.protocol.apps.LaunchQueue.HOLD_MS]); once it shows connecting or
     * out of reach every TV control is down (Erwann, 2026-10-25), and a launch is refused like the
     * rest, with the same words as a key.
     */
    fun launchWhileDown(raw: Link, shown: TvShown): KeyOutcome = when {
        raw == Link.NEEDS_PAIRING -> KeyOutcome.DROPPED_PAIRING
        shown.link == Link.ONLINE -> KeyOutcome.HELD
        raw == Link.ABSENT || shown.link == Link.UNREACHABLE -> KeyOutcome.DROPPED_DOWN
        else -> KeyOutcome.DROPPED_WAITING
    }

    /** A key pressed while the remote link was not ready: hold it or drop it, and say which. */
    fun whileDown(keyCode: Int, long: Boolean, raw: Link, shown: TvShown): KeyOutcome = when {
        raw == Link.NEEDS_PAIRING -> KeyOutcome.DROPPED_PAIRING
        raw == Link.ABSENT || shown.link == Link.UNREACHABLE -> KeyOutcome.DROPPED_DOWN
        !long && keyCode in HOLDABLE -> KeyOutcome.HELD
        else -> KeyOutcome.DROPPED_WAITING
    }
}
