package io.kud.tutti.protocol.update

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {

    private fun v(text: String) = assertNotNull(AppVersion.parse(text), text)

    @Test
    fun versionsSortDevBelowFinal() {
        assertTrue(v("0.2.0-dev.25") < v("0.2.0-dev.26"))
        assertTrue(v("0.2.0-dev.26") < v("0.2.0"))
        assertTrue(v("0.1.0") < v("0.2.0-dev.1"))
        assertTrue(v("0.2.0-dev.9") < v("0.2.0-dev.10"))
        assertTrue(v("0.9.9") < v("1.0.0-dev.1"))
        assertEquals(0, v("v0.2.0-dev.3").compareTo(v("0.2.0-dev.3")))
    }

    @Test
    fun parseReadsTagsAndVersionNames() {
        assertEquals(AppVersion(0, 2, 0, 26), AppVersion.parse("v0.2.0-dev.26"))
        assertEquals(AppVersion(1, 0, 3, null), AppVersion.parse("1.0.3"))
        // A local build's versionName.
        assertEquals(AppVersion(0, 0, 0, 0), AppVersion.parse("0.0.0-dev"))
        assertEquals("0.2.0-dev.26", v("v0.2.0-dev.26").toString())
        for (bad in listOf("", "latest", "0.2", "0.2.0-beta.1", "0.2.0-dev.", "x0.2.0", "0.2.0 extra")) {
            assertNull(AppVersion.parse(bad), bad)
        }
    }

    private val digest = "sha256:" + "ab".repeat(32)
    private fun debugAsset(tag: String) = ReleaseAsset(
        "tutti-${tag.removePrefix("v")}-debug.apk",
        "https://github.com/kud/tutti/releases/download/$tag/tutti-${tag.removePrefix("v")}-debug.apk",
        digest,
        48_000_000,
    )
    private fun releaseAssets(tag: String) = listOf(
        ReleaseAsset("tutti-${tag.removePrefix("v")}-mapping.txt", "https://github.com/kud/tutti/releases/download/$tag/m.txt", digest, 10),
        ReleaseAsset("tutti-${tag.removePrefix("v")}.apk", "https://github.com/kud/tutti/releases/download/$tag/tutti.apk", digest, 20),
    )

    @Test
    fun aReleaseBuildNeverTakesAnFdroidApk() {
        val fdroid = ReleaseAsset("tutti-0.3.0-fdroid.apk", "https://github.com/kud/tutti/releases/download/v0.3.0/tutti-0.3.0-fdroid.apk", digest, 20)
        assertNull(ReleaseFeed.assetFor(Release("v0.3.0", false, false, listOf(fdroid)), Flavour.RELEASE))
        val both = Release("v0.3.0", false, false, listOf(fdroid) + releaseAssets("v0.3.0"))
        assertEquals("tutti-0.3.0.apk", ReleaseFeed.assetFor(both, Flavour.RELEASE)?.name)
    }

    @Test
    fun pickTakesTheNewestNewerReleaseForTheFlavour() {
        val releases = listOf(
            Release("v0.2.0-dev.26", prerelease = true, draft = false, assets = listOf(debugAsset("v0.2.0-dev.26"))),
            Release("v0.2.0-dev.27", prerelease = true, draft = true, assets = listOf(debugAsset("v0.2.0-dev.27"))),
            Release("v0.2.0-dev.24", prerelease = true, draft = false, assets = listOf(debugAsset("v0.2.0-dev.24"))),
            Release("v0.1.0", prerelease = false, draft = false, assets = releaseAssets("v0.1.0")),
        )
        val update = assertNotNull(ReleaseFeed.pickUpdate(v("0.2.0-dev.25"), releases, Flavour.DEBUG, UpdateChannel.TEST))
        assertEquals(v("0.2.0-dev.26"), update.version)
        assertEquals("tutti-0.2.0-dev.26-debug.apk", update.asset.name)
        assertContentEquals(ByteArray(32) { 0xab.toByte() }, update.sha256)

        assertNull(ReleaseFeed.pickUpdate(v("0.2.0-dev.26"), releases, Flavour.DEBUG, UpdateChannel.TEST), "same version is not an update")
        // A release build never takes a debug APK, and never the mapping.
        assertNull(ReleaseFeed.pickUpdate(v("0.1.0"), releases, Flavour.RELEASE, UpdateChannel.TEST))
        assertEquals("tutti-0.1.0.apk", ReleaseFeed.pickUpdate(v("0.0.9"), releases, Flavour.RELEASE, UpdateChannel.TEST)?.asset?.name)
    }

    @Test
    fun aFinalReleaseWithoutADebugApkIsSkippedByADebugBuild() {
        val releases = listOf(
            Release("v0.2.0", prerelease = false, draft = false, assets = releaseAssets("v0.2.0")),
            Release("v0.2.0-dev.30", prerelease = true, draft = false, assets = listOf(debugAsset("v0.2.0-dev.30"))),
        )
        assertEquals(v("0.2.0-dev.30"), ReleaseFeed.pickUpdate(v("0.2.0-dev.25"), releases, Flavour.DEBUG, UpdateChannel.TEST)?.version)
    }

    @Test
    fun anAssetWithoutDigestOrFromElsewhereIsNeverOffered() {
        val tag = "v0.2.0-dev.26"
        val good = debugAsset(tag)
        for (bad in listOf(
            good.copy(digest = null),
            good.copy(digest = "md5:abc"),
            good.copy(url = "http://github.com/kud/tutti/releases/download/$tag/x-debug.apk"),
            good.copy(url = "https://github.com/someone/tutti/releases/download/$tag/x-debug.apk"),
            good.copy(url = "https://evil.example/kud/tutti/releases/download/$tag/x-debug.apk"),
        )) {
            val release = Release(tag, prerelease = true, draft = false, assets = listOf(bad))
            assertNull(ReleaseFeed.assetFor(release, Flavour.DEBUG), bad.toString())
        }
        assertNotNull(ReleaseFeed.assetFor(Release(tag, true, false, listOf(good)), Flavour.DEBUG))
    }

    @Test
    fun feedParsesGithubsShapeAndRefusesAnythingElse() {
        val body = """
            [{"tag_name":"v0.2.0-dev.25","prerelease":true,"draft":false,"name":"v0.2.0-dev.25","extra":{"x":1},
              "assets":[{"name":"tutti-0.2.0-dev.25-debug.apk","size":48773620,
                "digest":"sha256:1768b87221abc0760e6438eb910f49547fe589510b85ee6b2da16037af16e73c",
                "browser_download_url":"https://github.com/kud/tutti/releases/download/v0.2.0-dev.25/tutti-0.2.0-dev.25-debug.apk"}]}]
        """.trimIndent()
        val releases = assertNotNull(ReleaseFeed.parse(body))
        assertEquals(1, releases.size)
        assertEquals("v0.2.0-dev.25", releases[0].tag)
        assertEquals(48773620, releases[0].assets[0].size)
        assertEquals(v("0.2.0-dev.25"), ReleaseFeed.pickUpdate(v("0.2.0-dev.24"), releases, Flavour.DEBUG, UpdateChannel.TEST)?.version)

        assertEquals(emptyList(), ReleaseFeed.parse("[]"))
        assertNull(ReleaseFeed.parse("""{"message":"API rate limit exceeded"}"""))
        assertNull(ReleaseFeed.parse("<html>"))
    }

    @Test
    fun digestParsesOnlySha256AndComparesExactly() {
        val bytes = assertNotNull(Sha256.parse("sha256:" + "0F".repeat(32)))
        assertTrue(Sha256.matches(bytes, ByteArray(32) { 0x0f }))
        assertFalse(Sha256.matches(bytes, ByteArray(32) { 0x0e }))
        assertFalse(Sha256.matches(ByteArray(0), ByteArray(0)), "an empty digest never matches")
        assertNull(Sha256.parse("sha256:" + "0f".repeat(31)))
        assertNull(Sha256.parse("sha512:" + "0f".repeat(32)))
        assertNull(Sha256.parse(null))
    }

    @Test
    fun onlyGithubHostsOverHttpsAreAllowed() {
        assertTrue(Hosts.isAllowed(ReleaseFeed.URL))
        assertTrue(Hosts.isAllowed("https://objects.githubusercontent.com/a?b=c"))
        assertTrue(Hosts.isAllowed("https://release-assets.githubusercontent.com/a"))
        for (bad in listOf(
            "http://github.com/x",
            "https://github.com:8443/x",
            "https://user@github.com/x",
            "https://github.com.evil.example/x",
            "https://evilgithub.com/x",
            "https://192.168.1.10/x",
            "not a url",
        )) {
            assertFalse(Hosts.isAllowed(bad), bad)
        }
    }

    @Test
    fun redirectsAreResolvedAndCheckedHopByHop() {
        val from = "https://github.com/kud/tutti/releases/download/v1/t.apk"
        assertEquals(
            "https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=x",
            Hosts.nextHop(from, "https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=x"),
        )
        assertEquals("https://github.com/kud/tutti/other", Hosts.nextHop(from, "/kud/tutti/other"))
        assertNull(Hosts.nextHop(from, "https://evil.example/t.apk"))
        assertNull(Hosts.nextHop(from, "http://objects.githubusercontent.com/t.apk"))
        assertNull(Hosts.nextHop(from, null))
    }

    @Test
    fun theAutomaticCheckRunsAtMostOnceADay() {
        val now = 10 * CheckSchedule.DAY_MS
        assertTrue(CheckSchedule.isDue(null, now))
        assertFalse(CheckSchedule.isDue(now - 1, now))
        assertFalse(CheckSchedule.isDue(now - CheckSchedule.DAY_MS + 1, now))
        assertTrue(CheckSchedule.isDue(now - CheckSchedule.DAY_MS, now))
        assertTrue(CheckSchedule.isDue(now + 60_000, now), "a clock moved backwards")
    }

    @Test
    fun theTestChannelChecksHourlyAndStableDaily() {
        assertEquals(CheckSchedule.HOUR_MS, CheckSchedule.intervalFor(UpdateChannel.TEST))
        assertEquals(CheckSchedule.DAY_MS, CheckSchedule.intervalFor(UpdateChannel.STABLE))
        val now = 10 * CheckSchedule.DAY_MS
        val hour = CheckSchedule.HOUR_MS
        assertFalse(CheckSchedule.isDue(now - hour + 1, now, hour))
        assertTrue(CheckSchedule.isDue(now - hour, now, hour))
        assertFalse(CheckSchedule.isDue(now - hour, now), "the default stays a day")
    }

    @Test
    fun cueNoticesANewerBuildUntilSetAside() {
        val installed = v("0.2.0-dev.26")
        assertEquals(UpdateCue.NONE, UpdateCue.of(installed, null, null, UpdateChannel.TEST))
        assertEquals(UpdateCue.NONE, UpdateCue.of(installed, v("0.2.0-dev.26"), null, UpdateChannel.TEST), "already installed")
        assertEquals(UpdateCue.NONE, UpdateCue.of(v("0.2.0-dev.27"), v("0.2.0-dev.27"), v("0.2.0-dev.27"), UpdateChannel.TEST), "installed since")
        assertEquals(UpdateCue.NOTICE, UpdateCue.of(installed, v("0.2.0-dev.27"), null, UpdateChannel.TEST))
        assertEquals(UpdateCue.QUIET, UpdateCue.of(installed, v("0.2.0-dev.27"), v("0.2.0-dev.27"), UpdateChannel.TEST), "Later")
        assertEquals(UpdateCue.NOTICE, UpdateCue.of(installed, v("0.2.0-dev.28"), v("0.2.0-dev.27"), UpdateChannel.TEST), "a newer one notices again")
        assertEquals(UpdateCue.NOTICE, UpdateCue.of(installed, v("0.2.0"), v("0.2.0-dev.27"), UpdateChannel.TEST), "the final release too")
    }

    @Test
    fun eachBuildAnnouncesOnce() {
        assertTrue(UpdateCue.announces(v("0.2.0-dev.27"), null))
        assertFalse(UpdateCue.announces(v("0.2.0-dev.27"), v("0.2.0-dev.27")))
        assertTrue(UpdateCue.announces(v("0.2.0-dev.28"), v("0.2.0-dev.27")))
    }

    private val mixed = listOf(
        Release("v0.2.5-dev.3", prerelease = true, draft = false, assets = releaseAssets("v0.2.5-dev.3")),
        Release("v0.2.4", prerelease = false, draft = false, assets = releaseAssets("v0.2.4")),
        Release("v0.2.4-dev.9", prerelease = true, draft = false, assets = releaseAssets("v0.2.4-dev.9")),
        Release("v0.2.3", prerelease = false, draft = false, assets = releaseAssets("v0.2.3")),
    )

    @Test
    fun stableSkipsPreReleasesAndTestTakesThem() {
        assertEquals(v("0.2.5-dev.3"), ReleaseFeed.pickUpdate(v("0.2.3"), mixed, Flavour.RELEASE, UpdateChannel.TEST)?.version)
        assertEquals(v("0.2.4"), ReleaseFeed.pickUpdate(v("0.2.3"), mixed, Flavour.RELEASE, UpdateChannel.STABLE)?.version)
        // A dev build switched to stable gets the next final above it, never an older one.
        assertEquals(v("0.2.4"), ReleaseFeed.pickUpdate(v("0.2.4-dev.5"), mixed, Flavour.RELEASE, UpdateChannel.STABLE)?.version)
        assertNull(ReleaseFeed.pickUpdate(v("0.2.5-dev.1"), mixed, Flavour.RELEASE, UpdateChannel.STABLE), "no downgrade to 0.2.4")
        assertNull(ReleaseFeed.pickUpdate(v("0.2.4"), mixed, Flavour.RELEASE, UpdateChannel.STABLE), "only a dev build is newer")
    }

    @Test
    fun stableNeedsBothAFinalTagAndNoPreReleaseFlag() {
        val flaggedFinal = listOf(Release("v0.3.0", prerelease = true, draft = false, assets = releaseAssets("v0.3.0")))
        val unflaggedDev = listOf(Release("v0.3.0-dev.1", prerelease = false, draft = false, assets = releaseAssets("v0.3.0-dev.1")))
        for (releases in listOf(flaggedFinal, unflaggedDev)) {
            assertNull(ReleaseFeed.pickUpdate(v("0.2.4"), releases, Flavour.RELEASE, UpdateChannel.STABLE), releases[0].tag)
            assertNotNull(ReleaseFeed.pickUpdate(v("0.2.4"), releases, Flavour.RELEASE, UpdateChannel.TEST), releases[0].tag)
        }
    }

    @Test
    fun theChannelDefaultsToTheKindOfBuildInstalled() {
        assertEquals(UpdateChannel.TEST, UpdateChannel.defaultFor(v("0.2.4-dev.7")))
        assertEquals(UpdateChannel.TEST, UpdateChannel.defaultFor(v("0.0.0-dev")))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultFor(v("0.2.4")))
    }

    @Test
    fun aFoundDevBuildStopsCueingOnStable() {
        val installed = v("0.2.4-dev.7")
        assertEquals(UpdateCue.NOTICE, UpdateCue.of(installed, v("0.2.4-dev.8"), null, UpdateChannel.TEST))
        assertEquals(UpdateCue.NONE, UpdateCue.of(installed, v("0.2.4-dev.8"), null, UpdateChannel.STABLE))
        assertEquals(UpdateCue.NOTICE, UpdateCue.of(installed, v("0.2.4"), null, UpdateChannel.STABLE), "a final still does")
    }
}
