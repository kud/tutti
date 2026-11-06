package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which picture leads the now-showing tile. Invented ids and titles only. */
class LeadArtworkTest {
    private val thumb = "https://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg"

    private fun cast(image: String?) =
        CastNowPlaying("An invented film", null, CastPlayerState.PLAYING, 12.0, 600.0, 1.0, readAtMillis = 0, imageUrl = image)

    private val app = CardShowing.Showing(NowShowing.App("Invented Player", "com.example.player"))

    @Test
    fun `the content's own picture wins over the app's icon whenever cast names one`() {
        assertEquals(thumb, leadArtwork(app, cast(thumb)))
        assertEquals(thumb, leadArtwork(CardShowing.Home, cast(thumb)))
        assertEquals(thumb, leadArtwork(CardShowing.Showing(NowShowing.Channel("2", "Invented One", null)), cast(thumb)))
    }

    @Test
    fun `no picture, or no cast, falls back to the app's icon or the glyph`() {
        assertNull(leadArtwork(app, null))
        assertNull(leadArtwork(app, cast(null)))
        assertNull(leadArtwork(CardShowing.Home, null))
    }

    @Test
    fun `a host off the list or cleartext never leads`() {
        assertNull(leadArtwork(app, cast("http://i.ytimg.com/vi/SAMPLEID01/hqdefault.jpg")))
        assertNull(leadArtwork(app, cast("https://images.example.org/poster.jpg")))
    }

    @Test
    fun `a held cast item never pictures the bar alone, standby or a lost link`() {
        listOf(
            CardShowing.OnTheBar(BarAlone("spotify", tvAsleep = true)),
            CardShowing.Standby(barOn = true),
            CardShowing.Reconnecting,
            CardShowing.Offline,
        ).forEach { assertNull("$it", leadArtwork(it, cast(thumb))) }
    }
}
