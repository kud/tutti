package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.AppCatalog

/**
 * Which app on the phone can lend its icon to a TV app, when the TV gave none. Matching is by
 * package only: the TV package itself (many apps ship one package to phones and TVs), then the
 * catalog's verified phone packages ([io.kud.tutti.protocol.atv.AppEntry.phonePackages]). Never by
 * name: a label match would put a wrong coloured logo on a tile, which is worse than the honest
 * letter. Everything stays on the phone; nothing is listed, only these names are asked about.
 */
object PhoneIcons {
    /** Package names to ask the phone about, best first, without duplicates; empty if malformed. */
    fun candidates(tvPackage: String): List<String> {
        val pkg = tvPackage.trim()
        if (!isPackageName(pkg)) return emptyList()
        val phone = AppCatalog.byPackage(pkg)?.phonePackages.orEmpty()
        return (listOf(pkg) + phone).distinct()
    }

    /** The first candidate [installed] on the phone, or null. */
    fun match(tvPackage: String, installed: (String) -> Boolean): String? =
        candidates(tvPackage).firstOrNull(installed)

    /** Every package a catalog app could match, for the manifest check. */
    val catalogPackages: Set<String>
        get() = AppCatalog.apps.flatMap { candidates(it.packageName) }.toSet()

    /** Java-style dotted name, at least two segments, each starting with a letter. */
    fun isPackageName(name: String): Boolean =
        name.length <= 255 && name.split('.').let { parts ->
            parts.size >= 2 && parts.all { p -> p.isNotEmpty() && p[0].isLetter() && p.all { it.isLetterOrDigit() || it == '_' } }
        }
}
