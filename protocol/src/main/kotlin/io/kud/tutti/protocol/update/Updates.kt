package io.kud.tutti.protocol.update

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The in-app updater's rules, with no Android in them (docs/architecture.md, "In-app updates").
 * The app asks GitHub's releases API, picks the newest release with an APK for its own build
 * flavour, downloads it only from GitHub's hosts, and installs it only once its SHA-256 matches
 * the digest GitHub reported.
 */

/** `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-dev.N`, as android.yml derives it from the tag. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val dev: Int?) : Comparable<AppVersion> {
    /** A dev build sorts below its final release: 0.2.0-dev.25 < 0.2.0-dev.26 < 0.2.0. */
    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this,
        other,
        { it.major },
        { it.minor },
        { it.patch },
        { it.dev ?: Int.MAX_VALUE },
    )

    override fun toString(): String = "$major.$minor.$patch" + (dev?.let { "-dev.$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})(-dev(?:\.(\d{1,6}))?)?$""")

        /** A tag (`v0.2.0-dev.26`) or a versionName; a local build's `0.0.0-dev` reads as dev 0. Null otherwise. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, devPart, dev) = match.destructured
            return AppVersion(
                major.toInt(),
                minor.toInt(),
                patch.toInt(),
                if (devPart.isEmpty()) null else dev.ifEmpty { "0" }.toInt(),
            )
        }
    }
}

/** Which APK fits the installed app: a debug build updates from `-debug.apk`, a release build from the other. */
enum class Flavour { DEBUG, RELEASE }

data class ReleaseAsset(val name: String, val url: String, val digest: String?, val size: Long)

data class Release(val tag: String, val prerelease: Boolean, val draft: Boolean, val assets: List<ReleaseAsset>)

/**
 * Which releases the updater offers (Erwann, 2026-11-06: "si tu veux la version stable, tu n'auras
 * pas la version dev"). [STABLE] takes final releases only; [TEST] takes the `-dev` pre-releases as
 * well. Since the signing secrets exist both kinds ship the same release-signed APK, so one install
 * can follow either. Leaving [TEST] never goes back: Android won't downgrade, so a dev build waits
 * for the next final above it.
 */
enum class UpdateChannel {
    STABLE, TEST;

    /** Whether a build of [version] belongs here: [STABLE] refuses any `-dev` version. */
    fun accepts(version: AppVersion): Boolean = this == TEST || version.dev == null

    /**
     * Whether [release] belongs here. [STABLE] refuses it if GitHub flags it a pre-release *or* its
     * tag is a dev version: either alone means "not a final", and stable errs towards waiting.
     */
    fun accepts(release: Release, version: AppVersion): Boolean = this == TEST || (!release.prerelease && accepts(version))

    companion object {
        /** No choice made yet: a test build keeps getting test builds, a final release only finals. */
        fun defaultFor(installed: AppVersion): UpdateChannel = if (installed.dev != null) TEST else STABLE
    }
}

/** What the updater offers: a newer [version], its APK and the SHA-256 GitHub reported for it. */
class Update(val version: AppVersion, val tag: String, val asset: ReleaseAsset, val sha256: ByteArray) {
    override fun toString(): String = "Update($version, ${asset.name})"
}

object ReleaseFeed {
    const val OWNER_REPO = "kud/tutti"
    const val URL = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=30"

    /**
     * The releases page, opened in the browser when a check fails for a reason the app can't fix
     * (2026-11-15), so the person can look and download for themselves. Never fetched by the app.
     */
    const val PAGE = "https://github.com/$OWNER_REPO/releases"

    @Serializable
    private data class WireAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val digest: String? = null,
        val size: Long = 0,
    )

    @Serializable
    private data class WireRelease(
        @SerialName("tag_name") val tag: String,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<WireAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** GitHub's `/releases` list (pre-releases included), or null when the body is not that list. */
    fun parse(body: String): List<Release>? = runCatching {
        json.decodeFromString<List<WireRelease>>(body).map { release ->
            Release(
                release.tag,
                release.prerelease,
                release.draft,
                release.assets.map { ReleaseAsset(it.name, it.url, it.digest, it.size) },
            )
        }
    }.getOrNull()

    /**
     * The APK in [release] for [flavour]: named `*-debug.apk` for a debug build, any other `*.apk`
     * for a release build (never the R8 mapping), served from this repository's download path, with
     * a SHA-256 digest to check it against. Null when the release has none (a final release carries
     * no debug APK, so a debug build skips it). Never an `*-fdroid.apk`: final releases carry one,
     * signed with the same release key, only for F-Droid's reproducible-build check, and installing
     * it would leave this install without its updater. Every release-key install already has this
     * rule (it shipped in v0.2.0, the first release-signed build), and a debug build takes only
     * `*-debug.apk`, so no install in use can take it.
     */
    fun assetFor(release: Release, flavour: Flavour): ReleaseAsset? = release.assets.firstOrNull { asset ->
        val debug = asset.name.endsWith("-debug.apk")
        val fits = when (flavour) {
            Flavour.DEBUG -> debug
            Flavour.RELEASE -> !debug && asset.name.endsWith(".apk") && !asset.name.endsWith("-fdroid.apk")
        }
        fits && isReleaseDownload(asset.url) && Sha256.parse(asset.digest) != null
    }

    /**
     * The newest release above [current] on [channel] that has an APK for [flavour]; drafts never
     * count, and [UpdateChannel.STABLE] skips pre-releases.
     */
    fun pickUpdate(current: AppVersion, releases: List<Release>, flavour: Flavour, channel: UpdateChannel): Update? = releases
        .asSequence()
        .filter { !it.draft }
        .mapNotNull { release ->
            val version = AppVersion.parse(release.tag) ?: return@mapNotNull null
            if (version <= current || !channel.accepts(release, version)) return@mapNotNull null
            val asset = assetFor(release, flavour) ?: return@mapNotNull null
            Update(version, release.tag, asset, Sha256.parse(asset.digest)!!)
        }
        .maxByOrNull { it.version }

    private fun isReleaseDownload(url: String): Boolean {
        val uri = Hosts.parse(url) ?: return false
        return uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawPath.startsWith("/$OWNER_REPO/releases/download/")
    }
}

object Sha256 {
    private val PATTERN = Regex("^sha256:([0-9a-fA-F]{64})$")

    /** GitHub's `digest` field (`sha256:<64 hex>`) as bytes, or null for anything else. */
    fun parse(digest: String?): ByteArray? {
        val hex = PATTERN.matchEntire(digest?.trim() ?: return null)?.groupValues?.get(1) ?: return null
        return ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun matches(expected: ByteArray, actual: ByteArray): Boolean =
        expected.size == 32 && MessageDigest.isEqual(expected, actual)
}

/** The only places the updater talks to: HTTPS, default port, GitHub's API, site and asset hosts. */
object Hosts {
    val ALLOWED = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** Redirects followed by hand, each hop checked; GitHub needs two at most. */
    const val MAX_REDIRECTS = 5

    fun isAllowed(url: String): Boolean = parse(url) != null

    /** Where a redirect from [from] to [location] leads, or null if that is not an allowed host. */
    fun nextHop(from: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val resolved = runCatching { URI(from).resolve(location.trim()).toString() }.getOrNull() ?: return null
        return resolved.takeIf { isAllowed(it) }
    }

    internal fun parse(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val ok = uri.scheme == "https" &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            host in ALLOWED
        return uri.takeIf { ok }
    }
}

object CheckSchedule {
    const val HOUR_MS = 60L * 60 * 1000
    const val DAY_MS = 24 * HOUR_MS

    /**
     * How often the automatic check may run on [channel]: test builds, which come several times a
     * day, at most once an hour (2026-10-28); finals at most once a day. It follows the channel, not
     * the installed build (2026-11-06), so a dev build switched to stable stops asking every hour.
     */
    fun intervalFor(channel: UpdateChannel): Long = if (channel == UpdateChannel.TEST) HOUR_MS else DAY_MS

    /** The automatic check runs at most once per [intervalMs]; a clock moved backwards counts as due. */
    fun isDue(lastCheckMs: Long?, nowMs: Long, intervalMs: Long = DAY_MS): Boolean =
        lastCheckMs == null || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs
}

/**
 * How loudly a newer build makes itself known (docs/design.md, "Updates"; 2026-10-28).
 * [NONE]: nothing newer is known. [QUIET]: a newer build is known but was set aside with "Later",
 * so only the places you go looking say it (the ⋮ menu's row, About). [NOTICE]: also a dot on the
 * remote's ⋮. A newer build than the one set aside notices again.
 */
enum class UpdateCue {
    NONE, QUIET, NOTICE;

    companion object {
        /** A found build off [channel] (a dev build, once switched to stable) cues nothing. */
        fun of(installed: AppVersion, found: AppVersion?, setAside: AppVersion?, channel: UpdateChannel): UpdateCue = when {
            found == null || found <= installed || !channel.accepts(found) -> NONE
            setAside != null && found <= setAside -> QUIET
            else -> NOTICE
        }

        /** The once-per-build message: only a build newer than any found before announces itself. */
        fun announces(found: AppVersion, previouslyFound: AppVersion?): Boolean =
            previouslyFound == null || found > previouslyFound
    }
}

/**
 * A GitHub answer that was not a success, said for what it is. Before 2026-11-15 every non-2xx
 * read as "Couldn't reach GitHub", and the commonest one while online is the unauthenticated API's
 * rate limit: 60 requests an hour per IP address, shared by every device behind it (and by
 * brume's and pastille's updaters on the same phone), so a few checks around a run of installs
 * on a busy network were enough to show a phone that was plainly online as unreachable.
 */
sealed interface ApiRefusal {
    /** GitHub asks us to wait; [untilMs] is when it says to come back (epoch ms), if it said. */
    data class Wait(val untilMs: Long?) : ApiRefusal

    /** Any other answer outside 2xx: GitHub is reachable, it just did not serve the list. */
    data class Error(val code: Int) : ApiRefusal

    companion object {
        /**
         * Reads GitHub's rate-limit signals (docs.github.com, "Rate limits for the REST API"):
         * a 429, or a 403 carrying `retry-after` or `x-ratelimit-remaining: 0`, is a wait. The
         * time comes from `retry-after` (seconds) first, then `x-ratelimit-reset` (epoch seconds),
         * and only if it lies ahead of [nowMs]; otherwise the wait has no stated end.
         */
        fun of(code: Int, remaining: String?, resetEpochSeconds: String?, retryAfterSeconds: String?, nowMs: Long): ApiRefusal {
            val exhausted = remaining?.trim() == "0"
            val retryAfter = retryAfterSeconds?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            val limited = code == 429 || (code == 403 && (exhausted || retryAfter != null))
            if (!limited) return Error(code)
            val until = retryAfter?.let { nowMs + it * 1000 }
                ?: resetEpochSeconds?.trim()?.toLongOrNull()?.takeIf { exhausted }?.let { it * 1000 }
            return Wait(until?.takeIf { it > nowMs })
        }
    }
}
