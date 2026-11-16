package io.kud.tutti.protocol.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invented ids only ("SAMPLEID01"). */
class CastArtworkTest {
    private fun json(text: String) = CastStatusParser.parse(text)!!

    @Test fun `only https on the default port to an allowed host`() {
        assertTrue(CastArtwork.isAllowed("https://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg"))
        assertTrue(CastArtwork.isAllowed("https://I.YTIMG.COM:443/vi/SAMPLEID01/mqdefault.jpg?sqp=x"))
        assertTrue(CastArtwork.isAllowed("https://lh3.googleusercontent.com/sample=w544-h544"))
        // Nuvio: TMDB enrichment and Cinemeta's metahub posters and backgrounds.
        assertTrue(CastArtwork.isAllowed("https://image.tmdb.org/t/p/w500/sampleposter.jpg"))
        assertTrue(CastArtwork.isAllowed("https://images.metahub.space/poster/small/tt0000001/img"))
        assertTrue(CastArtwork.isAllowed("https://images.metahub.space/background/medium/tt0000001/img"))
        listOf(
            null,
            "",
            "http://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg",
            "https://i.ytimg.com:8443/vi/SAMPLEID01/hqdefault.jpg",
            "https://user:pass@i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg",
            "https://i.ytimg.com.example.org/vi/SAMPLEID01/hqdefault.jpg",
            "https://example.org/i.ytimg.com/hqdefault.jpg",
            "https://img.youtube.com/vi/SAMPLEID01/hqdefault.jpg",
            "http://image.tmdb.org/t/p/w500/sampleposter.jpg",
            "https://image.tmdb.org:8443/t/p/w500/sampleposter.jpg",
            "https://themoviedb.org/t/p/w500/sampleposter.jpg",
            "https://episodes.metahub.space/tt0000001/1/1/w780.jpg",
            "https://images.metahub.space.example.org/poster/small/tt0000001/img",
            "https://v3-cinemeta.strem.io/meta/series/tt0000001.json",
            "https://192.168.1.20/icon.png",
            "file:///sdcard/a.jpg",
            "data:image/png;base64,AAAA",
            "//i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg",
            "https://i.ytimg.com/" + "a".repeat(CastArtwork.MAX_URL_LENGTH),
            "https://i.ytimg.com/vi/ bad uri",
        ).forEach { assertFalse(CastArtwork.isAllowed(it), "$it") }
    }

    @Test fun `the picture of what plays is cast's allowed image, else none`() {
        fun playing(image: String?) = CastNowPlaying("An invented film", null, CastPlayerState.PLAYING, 1.0, 10.0, 1.0, 0, imageUrl = image)
        assertEquals("https://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg", CastArtwork.of(playing("https://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg")))
        assertNull(CastArtwork.of(null))
        assertNull(CastArtwork.of(playing(null)))
        // Built by hand past the reducer: still never a host off the list, nor cleartext.
        assertNull(CastArtwork.of(playing("http://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg")))
        assertNull(CastArtwork.of(playing("https://192.168.1.20/art.jpg")))
    }

    @Test fun `the first allowed image is picked, the rest ignored`() {
        val metadata = json(
            """{"title":"Sample Title","images":[
              {"url":"http://i.ytimg.com/vi/SAMPLEID01/0.jpg"},
              {"width":480},
              {"url":"https://cdn.example.org/a.jpg"},
              {"url":" https://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg ","width":480,"height":360},
              {"url":"https://lh3.googleusercontent.com/sample"}
            ]}""",
        )
        assertEquals("https://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg", CastArtwork.pick(metadata))
        assertNull(CastArtwork.pick(json("""{"images":[{"url":"https://cdn.example.org/a.jpg"}]}""")))
        assertNull(CastArtwork.pick(json("""{"images":"nope"}""")))
        assertNull(CastArtwork.pick(json("""{"title":"x"}""")))
        assertNull(CastArtwork.pick(null))
    }

    private fun status(withMedia: Boolean, images: String = """[{"url":"https://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg"}]""", session: Int = 3) = json(
        """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":$session,"playbackRate":1,"playerState":"PLAYING","currentTime":10
          ${if (withMedia) ""","media":{"duration":600,"metadata":{"title":"Sample Title","images":$images}}""" else ""}}]}""",
    )

    @Test fun `the reducer carries the image, keeps it across partial statuses and drops it with the session`() {
        val reducer = CastStatusReducer()
        val first = reducer.onMediaStatus(status(withMedia = true), 0)!!
        assertEquals("https://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg", first.imageUrl)
        assertEquals(first.imageUrl, reducer.onMediaStatus(status(withMedia = false), 15_000)!!.imageUrl)
        assertNull(reducer.onMediaStatus(status(withMedia = true, images = "[]", session = 4), 30_000)!!.imageUrl)
        assertNull(reducer.onMediaStatus(status(withMedia = true, images = """[{"url":"https://cdn.example.org/a.jpg"}]""", session = 5), 45_000)!!.imageUrl)
    }

    @Test fun `toString never prints the image url`() {
        val now = CastStatusReducer().onMediaStatus(status(withMedia = true), 0)!!
        assertFalse("SAMPLEID01" in now.toString())
        assertTrue("image=<redacted>" in now.toString())
    }

    @Test
    fun `an imdb id is read only from a cinemeta artwork url of the exact shape`() {
        assertEquals("tt0000001", CastArtwork.imdbId("https://images.metahub.space/poster/small/tt0000001/img"))
        assertEquals("tt0000001", CastArtwork.imdbId("https://images.metahub.space/background/medium/tt0000001/img"))
        assertEquals("tt0000001", CastArtwork.imdbId("https://images.metahub.space/logo/large/tt0000001/img"))
        assertNull(CastArtwork.imdbId(null))
        assertNull(CastArtwork.imdbId("https://image.tmdb.org/t/p/w500/abc.jpg"))
        assertNull(CastArtwork.imdbId("http://images.metahub.space/poster/small/tt0000001/img"))
        assertNull(CastArtwork.imdbId("https://images.metahub.space/poster/huge/tt0000001/img"))
        assertNull(CastArtwork.imdbId("https://images.metahub.space/poster/small/tt0000001/img?x=1"))
        assertNull(CastArtwork.imdbId("https://images.metahub.space/poster/small/nm0000001/img"))
        assertNull(CastArtwork.imdbId("https://evil.example/images.metahub.space/poster/small/tt0000001/img"))
    }
}
