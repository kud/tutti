package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.AppCatalog
import io.kud.tutti.protocol.bravia.HtmlText
import io.kud.tutti.protocol.bravia.TvApp
import kotlinx.serialization.Serializable

/**
 * One app tutti knows: from [AppCatalog], or seen once on the TV's own list. Persisted, so the
 * Apps sheet still shows everything (with cached icons) when IP control is off or the TV asleep.
 *
 * @param tvUri the TV's `setActiveApp` uri, when the TV ever listed it.
 * @param iconUrl the TV's icon URL, never blank; fetched from the TV only ([AppIcons]) and cached by [packageName].
 */
@Serializable
data class KnownApp(
    val packageName: String,
    val name: String,
    val tvUri: String? = null,
    val iconUrl: String? = null,
)

object AppLibrary {
    private const val SONY_PREFIX = "com.sony.dtv."

    /**
     * The package inside a BRAVIA app uri, `com.sony.dtv.<package>.<activity class>`. The
     * activity class usually restarts with the package's first segment
     * (`com.netflix.ninja.com.netflix.ninja.MainActivity`); otherwise the last segment is the
     * activity. Null for uris that are not apps (`localapp://webappruntime?...`).
     */
    fun packageOf(uri: String, known: Collection<String> = AppCatalog.apps.map { it.packageName }): String? {
        if (!uri.startsWith(SONY_PREFIX)) return null
        val rest = uri.removePrefix(SONY_PREFIX)
        // A package we know, followed by a class: no guessing (the longest, should two nest).
        known.filter { rest.startsWith("$it.") }.maxByOrNull { it.length }?.let { return it }
        val parts = rest.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val restart = (2 until parts.size).firstOrNull { parts[it] == parts[0] }
        // An activity in another namespace (`com.nuvio.app` + `app.nuvio.MainActivity`): it
        // starts on a domain root, with at least a segment and the class after it. The last
        // such root, since the package itself may hold one (`com.nuvio.app`).
        val foreign = (2..parts.size - 3).lastOrNull { parts[it] in ROOTS }
        val end = restart ?: foreign ?: (parts.size - 1)
        return parts.subList(0, end).joinToString(".")
    }

    /** Whether the TV uri [uri] opens [packageName], whatever its activity is called. */
    fun lists(uri: String, packageName: String): Boolean =
        uri.startsWith("$SONY_PREFIX$packageName.") || packageOf(uri) == packageName

    /** Top-level domains that start Java namespaces, for activities outside their package. */
    private val ROOTS = setOf(
        "com", "org", "net", "io", "app", "tv", "dev", "me", "co", "de", "fr", "uk", "nl", "es", "it",
        "se", "no", "dk", "fi", "ch", "at", "be", "pl", "pt", "jp", "kr", "cn", "ru", "br", "ca", "au", "us", "eu",
    )

    /** A TV list entry as a [KnownApp]; null when its uri names no package. */
    fun fromTv(app: TvApp): KnownApp? {
        val pkg = packageOf(app.uri) ?: return null
        val name = app.title.ifBlank { AppCatalog.byPackage(pkg)?.name ?: pkg }
        return KnownApp(pkg, name, app.uri, app.icon?.trim()?.ifEmpty { null })
    }

    /**
     * Folds a fresh TV list into what was seen before: the TV's name, uri and icon win; an app
     * the TV no longer lists stays (it may be asleep, or the list partial). Sorted by name.
     */
    fun remember(seen: List<KnownApp>, fresh: List<TvApp>): List<KnownApp> {
        val byPackage = LinkedHashMap<String, KnownApp>()
        seen.forEach { byPackage[it.packageName] = it }
        fresh.mapNotNull(::fromTv).forEach { app ->
            val old = byPackage[app.packageName]
            byPackage[app.packageName] = app.copy(iconUrl = app.iconUrl ?: old?.iconUrl?.takeIf { it.isNotBlank() }, tvUri = app.tvUri ?: old?.tvUri)
        }
        return byPackage.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Everything to show: every app ever seen, plus the catalog entries the TV never listed. A name
     * stored before titles were decoded ("Live TV &amp; Catch Up") is decoded here, until the TV
     * lists it again.
     */
    fun all(seen: List<KnownApp>): List<KnownApp> {
        val known = seen.associateBy { it.packageName }
        val catalog = AppCatalog.apps.filter { it.packageName !in known }.map { KnownApp(it.packageName, it.name) }
        return (seen.map { it.copy(name = HtmlText.decode(it.name)) } + catalog).sortedBy { it.name.lowercase() }
    }
}
