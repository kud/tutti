package io.kud.tutti.protocol.net

/** What a network carries, as far as the link rules care. Never a name, an address or an id. */
enum class NetKind(val label: String) { WIFI("wifi"), ETHERNET("ethernet"), CELLULAR("cellular"), VPN("vpn"), OTHER("other") }

/** A network as [LanLink] sees it: its transports and whether Android validated it. */
data class NetView(val kinds: Set<NetKind>, val validated: Boolean) {
    /** "wifi, validated": what the event log may say about a network. */
    val label: String
        get() = (kinds.ifEmpty { setOf(NetKind.OTHER) }.sortedBy { it.ordinal }.joinToString("+") { it.label }) +
            if (validated) ", validated" else ", unvalidated"
}

/**
 * Which network the LAN sockets ride, and when a change to it ends the links.
 *
 * Every socket to the TV and the bar is bound to the Wi-Fi or Ethernet network that carries the
 * living room (the "LAN network"), never left to follow Android's default network. The default
 * flips to cellular whenever Wi-Fi briefly loses validation ("switch to mobile data"), and an
 * unbound socket to a private address then leaves over cellular and times out until Wi-Fi is
 * default again: 10 to 50 s with every plane down, on 2026-11-12 (dev.10, Android 17), repeating
 * every 20 to 70 s while the app was in front. A default flip says nothing about the living room,
 * so it never drops a link; only the LAN network going away or being replaced does.
 *
 * Handles are opaque (the platform's network handle): compared, never logged. Not thread-safe:
 * the caller feeds it from one thread, as Android's network callbacks are.
 */
class LanLink {
    /** What the caller does after an event: drop the links (and why), end reconnect backoffs, log a line. */
    data class Step(val drop: String? = null, val wake: Boolean = false, val log: String? = null) {
        companion object {
            val NONE = Step()
        }
    }

    /** The LAN network sockets are bound to, or null: sockets are unbound and follow the default. */
    var bound: Long? = null
        private set
    private var boundView: NetView? = null
    private var defaultHandle: Long? = null
    private var defaultView: NetView? = null

    /** The LAN network Android offers now: the first one binds; another one replaces it. */
    fun lanAvailable(handle: Long, view: NetView): Step {
        val before = bound
        val beforeView = boundView
        bound = handle
        boundView = view
        return when (before) {
            handle -> Step.NONE
            // Sockets opened unbound meanwhile followed the default, which never reached the LAN
            // for long: nothing worth keeping to drop, but the reconnect loops should try now.
            null -> Step(wake = true, log = "network: lan ${view.label} available, sockets bound to it")
            else -> Step(
                drop = "the lan network changed",
                wake = true,
                log = "network: lan ${beforeView?.label ?: "?"} replaced by ${view.label}, sockets bound to the new one, links dropped",
            )
        }
    }

    /** A LAN network is gone; only the bound one matters. */
    fun lanLost(handle: Long): Step {
        if (bound != handle) return Step.NONE
        val was = boundView
        bound = null
        boundView = null
        return Step(
            drop = "the lan network was lost",
            log = "network: lan ${was?.label ?: "?"} lost, links dropped, sockets unbound until a lan network returns",
        )
    }

    /** Capabilities of the bound LAN network changed: logged only when validation flips. */
    fun lanCapabilities(handle: Long, view: NetView): Step {
        if (bound != handle) return Step.NONE
        val was = boundView
        boundView = view
        if (was == null || was.validated == view.validated) return Step.NONE
        return Step(log = "network: lan now ${view.label}, links kept")
    }

    /**
     * Android's default network changed (or is gone, [handle] null). Never a drop: links ride the
     * LAN network. With no LAN network bound, unbound sockets follow the default, so a new one is
     * worth a reconnect attempt.
     */
    fun defaultChanged(handle: Long?, view: NetView?): Step {
        if (handle == defaultHandle) return Step.NONE
        defaultHandle = handle
        defaultView = view
        val now = view?.label ?: "none"
        val kept = boundView?.let { "links kept on lan ${it.label}" } ?: "no lan network, sockets unbound"
        return Step(wake = bound == null && handle != null, log = "network: default now $now; $kept")
    }

    /** Capabilities of the default network changed: logged only when its validation flips. */
    fun defaultCapabilities(handle: Long, view: NetView): Step {
        if (handle != defaultHandle) return Step.NONE
        val was = defaultView
        defaultView = view
        if (was == null || was.validated == view.validated) return Step.NONE
        return Step(log = "network: default now ${view.label}")
    }
}

/**
 * How many polls in a row may go unanswered before the TV's IP control reads UNREACHABLE.
 * One missed poll is a blip (a Wi-Fi hiccup, a busy TV): a red link and a full rediscovery for
 * it cost more than the 10 s the next poll takes to tell.
 */
object PollMisses {
    const val TOLERATED = 2

    /** The count after a poll: reset by an answer, one more for a miss. */
    fun next(misses: Int, answered: Boolean): Int = if (answered) 0 else misses + 1

    /** True once [misses] in a row are more than the link tolerates. */
    fun unreachable(misses: Int): Boolean = misses > TOLERATED
}
