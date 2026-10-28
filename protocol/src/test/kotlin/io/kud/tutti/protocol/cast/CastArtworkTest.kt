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
        listOf(
            null,
            "",
            "http://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg",
            "https://i.ytimg.com:8443/vi/SAMPLEID01/hqdefault.jpg",
            "https://user:pass@i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg",
            "https://i.ytimg.com.example.org/vi/SAMPLEID01/hqdefault.jpg",
            "https://example.org/i.ytimg.com/hqdefault.jpg",
            "https://img.youtube.com/vi/SAMPLEID01/hqdefault.jpg",
            "https://192.168.1.20/icon.png",
            "file:///sdcard/a.jpg",
            "data:image/png;base64,AAAA",
            "//i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg",
            "https://i.ytimg.com/" + "a".repeat(CastArtwork.MAX_URL_LENGTH),
            "https://i.ytimg.com/vi/ bad uri",
        ).forEach { assertFalse(CastArtwork.isAllowed(it), "$it") }
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
}
