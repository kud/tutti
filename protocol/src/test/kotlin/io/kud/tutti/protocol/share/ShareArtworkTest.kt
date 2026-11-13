package io.kud.tutti.protocol.share

import io.kud.tutti.protocol.cast.CastArtwork
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invented ids only. */
class ShareArtworkTest {
    @Test
    fun `no artwork, or one the card may not show, shares the text alone`() {
        assertEquals(emptyList(), ShareArtwork.candidates(null))
        assertEquals(emptyList(), ShareArtwork.candidates(""))
        assertEquals(emptyList(), ShareArtwork.candidates("http://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg"))
        assertEquals(emptyList(), ShareArtwork.candidates("https://example.com/poster.jpg"))
        assertEquals(emptyList(), ShareArtwork.candidates("https://i.ytimg.com:8443/vi/AbCdEfGhIjK/hqdefault.jpg"))
    }

    @Test
    fun `a cinemeta backdrop or small poster tries the medium poster first, then the card's own`() {
        val bg = "https://images.metahub.space/background/medium/tt0000001/img"
        val small = "https://images.metahub.space/poster/small/tt0000001/img"
        val medium = "https://images.metahub.space/poster/medium/tt0000001/img"
        assertEquals(listOf(medium, bg), ShareArtwork.candidates(bg))
        assertEquals(listOf(medium, small), ShareArtwork.candidates(small))
        assertEquals(listOf(medium), ShareArtwork.candidates(medium))
        val large = "https://images.metahub.space/poster/large/tt0000001/img"
        assertEquals(listOf(large), ShareArtwork.candidates(large))
    }

    @Test
    fun `a small youtube thumbnail tries hqdefault, a larger one is kept`() {
        val mq = "https://i.ytimg.com/vi/AbCdEfGhIjK/mqdefault.jpg"
        assertEquals(listOf("https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg", mq), ShareArtwork.candidates(mq))
        val maxres = "https://i.ytimg.com/vi/AbCdEfGhIjK/maxresdefault.jpg"
        assertEquals(listOf(maxres), ShareArtwork.candidates(maxres))
        val hq = "https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg"
        assertEquals(listOf(hq), ShareArtwork.candidates(hq))
    }

    @Test
    fun `a small tmdb width tries w780, never original`() {
        val w342 = "https://image.tmdb.org/t/p/w342/aBcDeF123.jpg"
        assertEquals(listOf("https://image.tmdb.org/t/p/w780/aBcDeF123.jpg", w342), ShareArtwork.candidates(w342))
        val original = "https://image.tmdb.org/t/p/original/aBcDeF123.jpg"
        assertEquals(listOf(original), ShareArtwork.candidates(original))
    }

    @Test
    fun `other allowed hosts share the card's own picture`() {
        val music = "https://lh3.googleusercontent.com/abc=w544-h544"
        assertEquals(listOf(music), ShareArtwork.candidates(music))
    }

    @Test
    fun `every candidate stays on an allowed host`() {
        val urls = listOf(
            "https://images.metahub.space/logo/large/tt1234567/img",
            "https://i.ytimg.com/vi_webp/AbCdEfGhIjK/default.webp",
            "https://image.tmdb.org/t/p/w92/x.png",
            "https://lh3.googleusercontent.com/abc",
        )
        for (url in urls) {
            val got = ShareArtwork.candidates(url)
            assertTrue(got.isNotEmpty() && got.all { CastArtwork.isAllowed(it) }, url)
            assertEquals(url, got.last(), "the card's own picture is always the last resort")
        }
    }
}

class ShareArtworkPickTest {
    @Test
    fun `the best picture wins when it arrives in time`() = kotlinx.coroutines.test.runTest {
        val best = async { delay(400); "poster" }
        val card = async<String?> { "card" }
        assertEquals("poster", ShareArtwork.pick(listOf(best, card)))
    }

    @Test
    fun `a slow best picture falls back to the card's own`() = kotlinx.coroutines.test.runTest {
        val best = async<String?> { delay(5_000); "poster" }
        val card = async<String?> { "card" }
        assertEquals("card", ShareArtwork.pick(listOf(best, card)))
        assertEquals(false, best.isCancelled, "left to finish, for the next share")
        best.cancel()
    }

    @Test
    fun `a failed best picture falls back at once`() = kotlinx.coroutines.test.runTest {
        val best = async<String?> { delay(100); null }
        val card = async<String?> { delay(200); "card" }
        assertEquals("card", ShareArtwork.pick(listOf(best, card)))
        assertEquals(200L, testScheduler.currentTime)
    }

    @Test
    fun `nothing in time, or nothing at all, sends the text alone`() = kotlinx.coroutines.test.runTest {
        assertNull(ShareArtwork.pick(emptyList<Deferred<String?>>()))
        val slow = async<String?> { delay(5_000); "poster" }
        assertNull(ShareArtwork.pick(listOf(slow)))
        assertEquals(ShareArtwork.WAIT_MILLIS, testScheduler.currentTime)
        slow.cancel()
        val failed = async<String?> { null }
        assertNull(ShareArtwork.pick(listOf(failed)))
    }
}
