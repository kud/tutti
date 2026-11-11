package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.AppCatalog

/**
 * Where in an app a launch lands (Erwann, 2026-11-11: « j'ai pas envie d'arriver sur la home page
 * de YouTube… je voudrais créer un bouton YouTube subscription »). A destination is an app link
 * sent as the first route of an ordinary launch ([LaunchPlan.toDestination]); a null link is the
 * app's own home, i.e. a plain launch.
 *
 * Evidence, 2026-11-11:
 * - YouTube's three tabs ([AppShortcut.SUBSCRIPTIONS], [AppShortcut.HISTORY],
 *   [AppShortcut.LIBRARY]) open on Erwann's BRAVIA when sent with YouTube in front (the playback
 *   sheet's chips, [AppShortcuts]). From another app the same link is unverified, hence the
 *   resend once the app is up ([DestinationPlan.followUp]).
 * - Search is left out of the curated list: still unverified on the BRAVIA.
 * - Netflix: Home Assistant's Android TV Remote page documents only
 *   `https://www.netflix.com/title/<id>?source=30` (a title) and `netflix://`; nothing documents
 *   My List or any browse page, so none is offered. A title link goes in as a custom link.
 * - Sony's `appControl.setActiveApp` takes the TV's app uri only, no page; it stays a fallback.
 */
object AppDestinations {
    /** The curated destinations for an app: only links seen working on a BRAVIA. */
    fun curated(packageName: String): List<AppShortcut> =
        AppShortcuts.forPackage(packageName).filter { it != AppShortcut.SEARCH }

    /** Schemes never sent as a destination: they reach the store, the file system or a browser engine. */
    private val refused = setOf("market", "intent", "file", "content", "javascript", "data", "http")

    /**
     * Whether [link] may be saved as a destination for [packageName]: a well-formed link
     * ([LinkProbe.valid]) that is `https://`, or uses a scheme the catalogue lists for that app
     * (`netflix://`, `vnd.youtube://`, `twitch://`…). Anything else is refused before it is stored.
     */
    fun accepts(packageName: String, link: String): Boolean {
        val trimmed = link.trim()
        if (!LinkProbe.valid(trimmed)) return false
        val scheme = trimmed.substringBefore(':').lowercase()
        if (scheme in refused) return false
        if (scheme == "https") return trimmed.length > "https://".length && trimmed.startsWith("https://", ignoreCase = true)
        return scheme in ownSchemes(packageName)
    }

    /** The non-web schemes the catalogue lists for [packageName]. */
    fun ownSchemes(packageName: String): Set<String> =
        AppCatalog.byPackage(packageName)?.links.orEmpty()
            .map { it.substringBefore(':').lowercase() }
            .filter { it != "https" && it != "http" }
            .toSet()
}

/** The routes for a launch to a destination, and the link to send again once the app is up. */
data class DestinationPlan(val routes: List<LaunchRoute>, val followUp: String?)

/** Launching to a destination. Pure. */
fun LaunchPlan.toDestination(packageName: String, link: String?, restOnline: Boolean, restUri: String?): DestinationPlan {
    val plain = routes(packageName, restOnline, restUri)
    if (link == null) return DestinationPlan(plain, followUp = null)
    // The destination link first: on Google TV it is a plain VIEW intent that YouTube answers from
    // anywhere. Should it not bring the app up, the ordinary routes open it, and the link goes again
    // with the app in front, the one way it is verified on the BRAVIA.
    val first = LaunchRoute.AppLink(link)
    return DestinationPlan(listOf(first) + plain.filter { it != first }, followUp = link)
}
