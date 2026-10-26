package io.kud.tutti.protocol.bravia

import io.kud.tutti.protocol.apps.AppLibrary
import io.kud.tutti.protocol.apps.KnownApp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTextTest {
    @Test
    fun `named and numeric entities become text`() {
        assertEquals("Live TV & Catch Up", HtmlText.decode("Live TV &amp; Catch Up"))
        assertEquals("<a> \"b\" 'c' 'd'", HtmlText.decode("&lt;a&gt; &quot;b&quot; &#39;c&#39; &apos;d&apos;"))
        assertEquals("Café é", HtmlText.decode("Caf&#233; &#xE9;"))
        assertEquals("Up 🚀", HtmlText.decode("Up &#x1F680;"))
    }

    @Test
    fun `it decodes once and leaves what it does not know`() {
        assertEquals("Tom &amp; Jerry", HtmlText.decode("Tom &amp;amp; Jerry"))
        assertEquals("Fish & Chips", HtmlText.decode("Fish & Chips"))
        assertEquals("&copy2; &zzz; &#0; &#xD800; &#99999999;", HtmlText.decode("&copy2; &zzz; &#0; &#xD800; &#99999999;"))
        assertEquals("", HtmlText.decode(""))
    }

    @Test
    fun `app titles from the TV are decoded at parse time`() {
        val apps = BraviaParsers.applications(
            Json.parseToJsonElement("""[[{"title":"Live TV &amp; Catch Up","uri":"com.sony.dtv.com.example.live.com.example.live.Main","icon":""}]]""").jsonArray,
        )
        assertEquals("Live TV & Catch Up", apps.single().title)
    }

    @Test
    fun `a name stored escaped reads decoded`() {
        val shown = AppLibrary.all(listOf(KnownApp("com.example.live", "Live TV &amp; Catch Up")))
        assertEquals("Live TV & Catch Up", shown.first { it.packageName == "com.example.live" }.name)
    }
}
