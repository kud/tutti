package io.kud.tutti.protocol.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invented titles and channels only. */
class WatchShareTest {
    private val nb = ' '

    @Test
    fun `cast media with channel, app and time, in both languages`() {
        val w = Watching.Media("Lemon Tart Week", "Kitchen Corner", "YouTube", positionSec = 1421, durationSec = 3730)
        assertEquals("Je regarde «${nb}Lemon Tart Week$nb» (Kitchen Corner) sur YouTube, j'en suis à 23:41 sur 1:02:10.", WatchShare.text(w, ShareLanguage.FR))
        assertEquals("I'm watching “Lemon Tart Week” (Kitchen Corner) on YouTube, 23:41 into 1:02:10.", WatchShare.text(w, ShareLanguage.EN))
    }

    @Test
    fun `live content and an untrusted position leave the time out`() {
        assertEquals("I'm watching “Night Rail”.", WatchShare.text(Watching.Media("Night Rail", positionSec = 90), ShareLanguage.EN))
        assertEquals("I'm watching “Night Rail”.", WatchShare.text(Watching.Media("Night Rail", positionSec = 900, durationSec = 60), ShareLanguage.EN))
    }

    @Test
    fun `a secondary equal to the title is not repeated`() {
        assertEquals("Je regarde «${nb}Fog$nb».", WatchShare.text(Watching.Media("Fog", "Fog"), ShareLanguage.FR))
    }

    @Test
    fun `youtube link carries the current second`() {
        val w = Watching.Media("Lemon Tart Week", app = "YouTube", positionSec = 1421, durationSec = 3730, videoId = "AbCdEf_12-3")
        val text = WatchShare.text(w, ShareLanguage.EN)!!
        assertTrue(text.endsWith("\nhttps://youtu.be/AbCdEf_12-3?t=1421"), text)
        assertEquals("https://youtu.be/AbCdEf_12-3", WatchShare.youTubeLink("AbCdEf_12-3", 0))
        assertNull(WatchShare.youTubeLink("not an id", 3))
    }

    @Test
    fun `video id only from a youtube thumbnail url`() {
        assertEquals("AbCdEf_12-3", WatchShare.youTubeVideoId("https://i.ytimg.com/vi/AbCdEf_12-3/hqdefault.jpg"))
        assertEquals("AbCdEf_12-3", WatchShare.youTubeVideoId("https://i.ytimg.com/vi_webp/AbCdEf_12-3/maxresdefault.webp"))
        for (bad in listOf(
            null,
            "http://i.ytimg.com/vi/AbCdEf_12-3/hqdefault.jpg",
            "https://i.ytimg.com.example.org/vi/AbCdEf_12-3/hqdefault.jpg",
            "https://lh3.googleusercontent.com/vi/AbCdEf_12-3/x.jpg",
            "https://i.ytimg.com/vi/short/hqdefault.jpg",
            "https://i.ytimg.com/an/AbCdEf_12-3/hqdefault.jpg",
            "https://i.ytimg.com/vi/AbCdEf_12-3",
        )) assertNull(WatchShare.youTubeVideoId(bad), bad)
    }

    @Test
    fun `tuner channel with and without a programme`() {
        val c = Watching.Channel("7", "Canal Exemple", "Les Jardins du Nord")
        assertEquals("Je regarde «${nb}Les Jardins du Nord$nb» sur 7 Canal Exemple.", WatchShare.text(c, ShareLanguage.FR))
        assertEquals("I'm watching “Les Jardins du Nord” on 7 Canal Exemple.", WatchShare.text(c, ShareLanguage.EN))
        assertEquals("I'm watching 7 Canal Exemple.", WatchShare.text(Watching.Channel("7", "Canal Exemple", null), ShareLanguage.EN))
        assertNull(WatchShare.text(Watching.Channel(null, " ", null), ShareLanguage.EN))
    }

    @Test
    fun `an app alone names the app`() {
        assertEquals("Je regarde Example Flix.", WatchShare.text(Watching.App("Example Flix"), ShareLanguage.FR))
        assertNull(WatchShare.text(Watching.App(" "), ShareLanguage.EN))
    }

    @Test
    fun `clock`() {
        assertEquals("0:07", WatchShare.clock(7))
        assertEquals("23:41", WatchShare.clock(1421))
        assertEquals("1:02:10", WatchShare.clock(3730))
    }

    @Test
    fun `language falls back to english`() {
        assertEquals(ShareLanguage.FR, ShareLanguage.of("fr"))
        assertEquals(ShareLanguage.EN, ShareLanguage.of("de"))
        assertEquals(ShareLanguage.EN, ShareLanguage.of(null))
    }

    @Test
    fun `viewing data never reaches toString`() {
        val s = Watching.Media("Secret Title", "Secret Channel", "YouTube", 1, 2, "AbCdEf_12-3").toString() +
            Watching.Channel("7", "Secret Channel", "Secret Show").toString()
        assertFalse("Secret" in s, s)
        assertFalse("AbCdEf" in s, s)
    }

    @Test
    fun `title links are encoded`() {
        assertEquals("https://app.trakt.tv/search?m=media&q=Black+Bird", TitleLinks.trakt("Black Bird"))
        assertEquals("https://app.trakt.tv/search?m=media&q=L%27%C3%A9t%C3%A9+%26+co", TitleLinks.trakt(" L'été & co "))
        assertEquals("https://www.justwatch.com/fr/recherche?q=Night%20Rail", TitleLinks.justWatch("Night Rail", "FR", "fr"))
        assertEquals("https://www.justwatch.com/uk/search?q=Night%20Rail", TitleLinks.justWatch("Night Rail", "GB", "en"))
        assertEquals("https://www.justwatch.com/de/Suche?q=Night%20Rail", TitleLinks.justWatch("Night Rail", "AT", "de"))
        assertEquals("https://www.justwatch.com/us/search?q=Night%20Rail", TitleLinks.justWatch("Night Rail", null, null))
    }

    @Test
    fun `links show with their switch on and something to open`() {
        val media = Watching.Media("Night Rail")
        assertEquals(setOf(TitleLink.TRAKT, TitleLink.JUSTWATCH), TitleLinks.offered(media, trakt = true, justWatch = true))
        assertEquals(setOf(TitleLink.JUSTWATCH), TitleLinks.offered(media, trakt = false, justWatch = true))
        assertEquals(emptySet(), TitleLinks.offered(media, trakt = false, justWatch = false))
        // An app that names nothing: Trakt still opens, at its own start; JustWatch has nothing to search.
        assertEquals(setOf(TitleLink.TRAKT), TitleLinks.offered(Watching.App("Example Flix"), trakt = true, justWatch = true))
        assertEquals(emptySet(), TitleLinks.offered(Watching.App("Example Flix"), trakt = false, justWatch = true))
        assertEquals(emptySet(), TitleLinks.offered(Watching.App("  "), trakt = true, justWatch = true))
        assertEquals(emptySet(), TitleLinks.offered(Watching.Channel("7", "Canal Exemple", null), trakt = true, justWatch = true))
        assertEquals(setOf(TitleLink.TRAKT), TitleLinks.offered(Watching.Channel("7", null, "Les Jardins du Nord"), trakt = true, justWatch = false))
        assertEquals(emptySet(), TitleLinks.offered(null, trakt = true, justWatch = true))
    }

    @Test
    fun `trakt target is the search with a title, trakt itself with an app alone`() {
        val episode = Watching.Media("Night Rail", secondary = "S1:E5 – The Long Platform", app = "Example Player")
        assertEquals(TraktTarget.Search("Night Rail", Episode(1, 5)), TitleLinks.traktTarget(episode))
        assertEquals(TraktTarget.Search("Night Rail"), TitleLinks.traktTarget(Watching.Media("Night Rail", secondary = "Some Artist")))
        assertEquals(TraktTarget.Search("Les Jardins du Nord"), TitleLinks.traktTarget(Watching.Channel("7", null, "Les Jardins du Nord")))
        assertEquals(TraktTarget.Home, TitleLinks.traktTarget(Watching.App("Example Player")))
        assertNull(TitleLinks.traktTarget(Watching.Channel("7", "Canal Exemple", null)))
        assertNull(TitleLinks.traktTarget(null))
        assertEquals("https://app.trakt.tv/", TitleLinks.TRAKT_HOME)
        assertFalse("Night Rail" in TitleLinks.traktTarget(episode).toString())
    }

    @Test
    fun `episode markers are read strictly`() {
        assertEquals(Episode(1, 5), Episode.parse("S1:E5 – The Long Platform"))
        assertEquals(Episode(1, 5), Episode.parse("S01E05"))
        assertEquals(Episode(12, 103), Episode.parse("s12:e103: Finale"))
        assertEquals(Episode(0, 2), Episode.parse("S0:E2"))
        assertNull(Episode.parse(null))
        assertNull(Episode.parse("Some Artist"))
        assertNull(Episode.parse("The Long Platform S1:E5"))
        assertNull(Episode.parse("S1:E5x"))
        assertNull(Episode.parse("S1"))
        assertNull(Episode.parse("Season 1 Episode 5"))
        assertNull(Episode.parse("S1:E0"))
    }

    @Test
    fun `an episode secondary reads as marker and quoted name`() {
        val m = Watching.Media("Night Rail", secondary = "S1:E5 – The Long Platform", app = "Example Player", positionSec = 449, durationSec = 3259)
        assertEquals(
            "Je regarde «\u00a0Night Rail\u00a0» (S1:E5, «\u00a0The Long Platform\u00a0») sur Example Player, j'en suis à 7:29 sur 54:19.",
            WatchShare.sentence(m, ShareLanguage.FR),
        )
        assertEquals(
            "I'm watching “Night Rail” (S1:E5, “The Long Platform”) on Example Player, 7:29 into 54:19.",
            WatchShare.sentence(m, ShareLanguage.EN),
        )
        assertEquals("I'm watching “Night Rail” (S1:E5).", WatchShare.sentence(Watching.Media("Night Rail", secondary = "S01E05"), ShareLanguage.EN))
    }

    @Test
    fun `an episode searches trakt for its series title and asks the app first`() {
        val status = Watching.Media("Black Bird", secondary = "S1:E5 – The Place I Lie", app = "Nuvio")
        val target = TitleLinks.traktTarget(status)
        assertEquals(TraktTarget.Search("Black Bird", Episode(1, 5)), target)
        val url = "https://app.trakt.tv/search?m=media&q=Black+Bird"
        assertEquals(listOf(TraktStep.AppLink(url), TraktStep.LaunchApp, TraktStep.Web(url)), TitleLinks.traktSteps(target!!))
        assertEquals(listOf(TraktStep.LaunchApp, TraktStep.Web("https://app.trakt.tv/")), TitleLinks.traktSteps(TraktTarget.Home))
        assertFalse("Black" in TraktStep.Web(url).toString())
    }
}
