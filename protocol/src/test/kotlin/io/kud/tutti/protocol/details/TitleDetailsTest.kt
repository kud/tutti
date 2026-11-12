package io.kud.tutti.protocol.details

import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invented titles, people and ids only ("tt0000001" is a placeholder, not a real lookup). */
class TitleDetailsTest {
    private fun playing(image: String?, secondary: String? = null) = CastNowPlaying(
        title = "Sample Film",
        secondary = secondary,
        state = CastPlayerState.PLAYING,
        positionSec = 10.0,
        durationSec = 100.0,
        playbackRate = 1.0,
        readAtMillis = 0,
        imageUrl = image,
    )

    @Test fun `the id comes only from a metahub artwork url of exactly that shape`() {
        assertEquals("tt0000001", Cinemeta.idFrom("https://images.metahub.space/poster/small/tt0000001/img"))
        assertEquals("tt1234567", Cinemeta.idFrom("https://images.metahub.space/background/medium/tt1234567/img"))
        listOf(
            null,
            "https://image.tmdb.org/t/p/w500/tt0000001.jpg",
            "http://images.metahub.space/poster/small/tt0000001/img",
            "https://images.metahub.space/poster/small/tt0000001/img?x=1",
            "https://images.metahub.space/poster/small/tt0000001/img/extra",
            "https://images.metahub.space/poster/huge/tt0000001/img",
            "https://images.metahub.space/poster/small/0000001/img",
            "https://images.metahub.space.example.org/poster/small/tt0000001/img",
            "https://images.metahub.space/poster/small/tt0000001;rm/img",
        ).forEach { assertNull(Cinemeta.idFrom(it), "$it") }
    }

    @Test fun `no artwork evidence means no request, never a title search`() {
        assertNull(Cinemeta.requestFor(playing(null)))
        assertNull(Cinemeta.requestFor(playing("https://image.tmdb.org/t/p/w500/sample.jpg")))
        assertNull(Cinemeta.requestFor(null))
    }

    @Test fun `an episode line asks the series catalogue, otherwise film then series`() {
        val film = Cinemeta.requestFor(playing("https://images.metahub.space/poster/small/tt0000001/img"))!!
        assertEquals(listOf("movie", "series"), film.types)
        assertNull(film.episode)
        val show = Cinemeta.requestFor(playing("https://images.metahub.space/poster/small/tt0000002/img", "S2:E7 – Sample Episode"))!!
        assertEquals(listOf("series"), show.types)
        assertEquals(EpisodeRef(2, 7), show.episode)
        assertEquals("https://v3-cinemeta.strem.io/meta/series/tt0000002.json", Cinemeta.url("series", show.id))
        assertTrue("tt0000002" !in show.toString())
    }

    @Test fun `episode lines parse strictly`() {
        assertEquals(EpisodeRef(1, 5), EpisodeRef.parse("S1:E5 – Sample"))
        assertEquals(EpisodeRef(1, 5), EpisodeRef.parse("S1:E5 - Sample"))
        assertEquals(EpisodeRef(10, 120), EpisodeRef.parse("S10:E120"))
        listOf(null, "", "Sample Channel", "s1:e5 – x", "S1E5", "S1:E5x", "Season 1 Episode 5", "Live S1:E5", "S1:E5 –").forEach {
            assertNull(EpisodeRef.parse(it), "$it")
        }
    }

    private val film = """
        {"meta":{"id":"tt0000001","imdb_id":"tt0000001","type":"movie","name":"Sample Film",
        "releaseInfo":"2001","runtime":"112 min","genres":["Drama","Mystery","Comedy","Western"],
        "imdbRating":"7.8","description":"  A sample   synopsis.\n","director":["Alex Example"],
        "cast":["Sam Example","Robin Example","Sam Example",""],
        "poster":"https://images.metahub.space/poster/medium/tt0000001/img","unknown":{"x":1}}}
    """.trimIndent()

    @Test fun `a film meta reads into the shown fields`() {
        val meta = CinemetaParser.parse(film, "tt0000001")!!
        val details = TitleDetails.of(meta, null)
        assertEquals("Sample Film", details.title)
        assertEquals("2001", details.year)
        assertEquals("112 min", details.runtime)
        assertEquals(listOf("Drama", "Mystery", "Comedy"), details.genres)
        assertEquals("7.8", details.imdbRating)
        assertEquals("A sample synopsis.", details.synopsis)
        assertEquals(listOf("Alex Example"), details.director)
        assertEquals(listOf("Sam Example", "Robin Example"), details.cast)
        assertEquals("https://images.metahub.space/poster/medium/tt0000001/img", details.poster)
        assertNull(details.episode)
        assertTrue("Sample" !in details.toString())
    }

    @Test fun `a meta for another id, an empty answer or junk gives nothing`() {
        assertNull(CinemetaParser.parse(film, "tt0000009"))
        assertNull(CinemetaParser.parse("{}", "tt0000001"))
        assertNull(CinemetaParser.parse("""{"meta":null}""", "tt0000001"))
        assertNull(CinemetaParser.parse("not json", "tt0000001"))
        assertNull(CinemetaParser.parse("[]", "tt0000001"))
    }

    @Test fun `wrong types and missing fields are left out, not fatal`() {
        val meta = CinemetaParser.parse(
            """{"meta":{"id":"tt0000003","name":42,"genres":"Drama","imdbRating":"N/A","director":null,
            "cast":[1,{"name":"x"},"Kim Example"],"poster":"http://elsewhere.example/p.jpg","videos":"none"}}""",
            "tt0000003",
        )!!
        val details = TitleDetails.of(meta, EpisodeRef(1, 1))
        assertNull(details.title)
        assertEquals(listOf("Drama"), details.genres)
        assertNull(details.imdbRating)
        assertEquals(emptyList(), details.director)
        assertEquals(listOf("Kim Example"), details.cast)
        assertNull(details.poster)
        assertNull(details.episode)
    }

    @Test fun `ratings out of range are dropped`() {
        for (bad in listOf("0", "11", "-1", "\"\"")) {
            val meta = CinemetaParser.parse("""{"meta":{"id":"tt0000004","imdbRating":$bad}}""", "tt0000004")!!
            assertNull(meta.imdbRating, bad)
        }
        assertEquals("8.0", CinemetaParser.parse("""{"meta":{"id":"tt0000004","imdbRating":8}}""", "tt0000004")!!.imdbRating)
    }

    private val series = """
        {"meta":{"id":"tt0000002","type":"series","name":"Sample Show","releaseInfo":"2010–2014",
        "videos":[
          {"id":"tt0000002:1:1","season":1,"episode":1,"name":"Pilot Sample","overview":"First."},
          {"id":"tt0000002:2:7","season":2,"episode":7,"title":"Seventh Sample","description":"Seventh."},
          {"id":"tt0000002:3:1","season":3,"number":1,"name":"Dup"},
          {"id":"tt0000002:3:1b","season":3,"episode":1,"name":"Dup again"},
          {"id":"bad","season":"x"}
        ]}}
    """.trimIndent()

    @Test fun `the episode named by the cast line is matched exactly once`() {
        val meta = CinemetaParser.parse(series, "tt0000002")!!
        val first = TitleDetails.of(meta, EpisodeRef(1, 1)).episode
        assertNotNull(first)
        assertEquals("Pilot Sample", first.title)
        assertEquals("First.", first.overview)
        val seventh = TitleDetails.of(meta, EpisodeRef(2, 7)).episode!!
        assertEquals("Seventh Sample", seventh.title)
        assertEquals("Seventh.", seventh.overview)
        // Listed twice: not a confident match.
        assertNull(TitleDetails.of(meta, EpisodeRef(3, 1)).episode)
        assertNull(TitleDetails.of(meta, EpisodeRef(9, 9)).episode)
        assertEquals("2010–2014", TitleDetails.of(meta, null).year)
    }

    @Test fun `long text is cut at a word`() {
        val long = "word ".repeat(400)
        val cut = CinemetaParser.clean(long, 50)!!
        assertTrue(cut.length <= 51, cut)
        assertTrue(cut.endsWith("word…"), cut)
        assertNull(CinemetaParser.clean("   ", 10))
    }
}
