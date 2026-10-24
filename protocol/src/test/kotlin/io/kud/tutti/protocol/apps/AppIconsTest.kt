package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.bravia.BraviaParsers
import io.kud.tutti.protocol.bravia.TvApp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppIconsTest {
    private val tv = "192.168.1.20"

    @Test
    fun `blank icons are absent, from the parser to the library`() {
        val apps = BraviaParsers.applications(
            Json.parseToJsonElement("""[[{"title":"Example","uri":"com.sony.dtv.org.example.player.Main","icon":"   "}]]""") as JsonArray,
        )
        assertNull(apps.single().icon)
        assertNull(AppLibrary.fromTv(TvApp("Example", "com.sony.dtv.org.example.player.Main", " "))!!.iconUrl)
        val seen = listOf(KnownApp("org.example.player", "Example", null, ""))
        assertNull(AppLibrary.remember(seen, listOf(TvApp("Example", "com.sony.dtv.org.example.player.Main"))).single().iconUrl)
        assertEquals(AppIcons.Plan.Skip(IconOutcome.BLANK, "none"), AppIcons.plan("  ", tv))
        assertEquals(IconOutcome.BLANK, (AppIcons.plan(null, tv) as AppIcons.Plan.Skip).outcome)
    }

    @Test
    fun `an icon on the tv itself is fetched as listed`() {
        val plan = AppIcons.plan("http://192.168.1.20/DIAL/icon/com.sony.dtv.org.example.player.Main.png", tv)
        assertIs<AppIcons.Plan.Fetch>(plan)
        assertEquals("http://192.168.1.20/DIAL/icon/com.sony.dtv.org.example.player.Main.png", plan.url)
        assertEquals(AppIcons.HostKind.TV, plan.kind)
        assertEquals("http /DIAL/icon/… host=tv", plan.shape)
    }

    @Test
    fun `another address of the tv is aimed back at the configured host`() {
        for ((url, kind) in listOf(
            "http://localhost:80/DIAL/icon/a.png?x=1" to AppIcons.HostKind.LOOPBACK,
            "http://127.0.0.1/icon/a.png" to AppIcons.HostKind.LOOPBACK,
            "https://10.0.0.7/icon/a.png" to AppIcons.HostKind.OTHER_LAN,
            "http://bravia.local/icon/a.png" to AppIcons.HostKind.LAN_NAME,
            "http://android/icon/a.png" to AppIcons.HostKind.LAN_NAME,
        )) {
            val plan = AppIcons.plan(url, tv)
            assertIs<AppIcons.Plan.Fetch>(plan, url)
            assertEquals(kind, plan.kind, url)
            assertTrue(plan.url.substringAfter("://").startsWith("192.168.1.20"), plan.url)
            assertFalse(plan.shape.contains("127.0.0.1") || plan.shape.contains("10.0.0.7") || plan.shape.contains("bravia"), plan.shape)
        }
        assertEquals("http://192.168.1.20:80/DIAL/icon/a.png?x=1", (AppIcons.plan("http://localhost:80/DIAL/icon/a.png?x=1", tv) as AppIcons.Plan.Fetch).url)
        val v6 = AppIcons.plan("http://[fe80::1]/icon/a.png", "fd00::20") as AppIcons.Plan.Fetch
        assertEquals("http://[fd00::20]/icon/a.png", v6.url)
    }

    @Test
    fun `internet hosts and odd urls are never fetched`() {
        assertEquals(IconOutcome.INTERNET, (AppIcons.plan("https://cdn.example.com/icons/a.png", tv) as AppIcons.Plan.Skip).outcome)
        assertEquals(IconOutcome.INTERNET, (AppIcons.plan("http://203.0.113.9/a.png", tv) as AppIcons.Plan.Skip).outcome)
        assertEquals(IconOutcome.MALFORMED, (AppIcons.plan("meta:app", tv) as AppIcons.Plan.Skip).outcome)
        assertEquals(IconOutcome.MALFORMED, (AppIcons.plan("file:///data/icon.png", tv) as AppIcons.Plan.Skip).outcome)
        assertEquals(IconOutcome.MALFORMED, (AppIcons.plan("not a url at all", tv) as AppIcons.Plan.Skip).outcome)
    }

    @Test
    fun `image sniffing accepts what BitmapFactory decodes and refuses error pages`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()
        assertTrue(AppIcons.looksLikeImage(png))
        assertTrue(AppIcons.looksLikeImage(jpeg))
        assertTrue(AppIcons.looksLikeImage(webp))
        assertTrue(AppIcons.looksLikeImage("GIF89a".encodeToByteArray()))
        assertFalse(AppIcons.looksLikeImage("<html><body>Not Found</body></html>".encodeToByteArray()))
        assertFalse(AppIcons.looksLikeImage("""{"error":[403,"Forbidden"]}""".encodeToByteArray()))
        assertFalse(AppIcons.looksLikeImage(ByteArray(0)))
    }

    @Test
    fun `failures back off, cached icons and dead ends are not retried until the url changes`() {
        val url = "http://192.168.1.20/icon/a.png"
        var book = IconBook()
        assertTrue(book.due("a.b", url, 0))
        book = book.record("a.b", url, "http /icon/a.png host=tv", IconAttempt(IconOutcome.NETWORK, "SocketTimeoutException"), 0)
        assertFalse(book.due("a.b", url, 29_999))
        assertTrue(book.due("a.b", url, 30_000))
        book = book.record("a.b", url, "s", IconAttempt(IconOutcome.HTTP_STATUS, "500"), 30_000)
        assertFalse(book.due("a.b", url, 30_000 + 119_999))
        assertTrue(book.due("a.b", url, 30_000 + 120_000))
        assertEquals(2, book.entries.getValue("a.b").failures)
        assertEquals(3_600_000, AppIcons.retryDelayMs(40))

        book = book.record("a.b", url, "s", IconAttempt(IconOutcome.CACHED), 200_000)
        assertFalse(book.due("a.b", url, Long.MAX_VALUE - 1))
        assertEquals(0, book.entries.getValue("a.b").failures)

        book = book.record("c.d", null, "none", IconAttempt(IconOutcome.BLANK), 0)
        assertFalse(book.due("c.d", null, Long.MAX_VALUE - 1))
        assertFalse(book.due("c.d", "  ", Long.MAX_VALUE - 1))
        assertTrue(book.due("c.d", "http://192.168.1.20/icon/c.png", 1), "a new url starts over")
    }

    @Test
    fun `the report counts outcomes and names the apps without an icon, never a host`() {
        val book = IconBook()
            .record("org.example.one", "http://192.168.1.20/DIAL/icon/one.png", "http /DIAL/icon/… host=tv", IconAttempt(IconOutcome.CACHED), 0)
            .record("org.example.two", "http://192.168.1.20/DIAL/icon/two.png", "http /DIAL/icon/… host=tv", IconAttempt(IconOutcome.HTTP_STATUS, "403"), 0)
            .record("org.example.three", null, "none", IconAttempt(IconOutcome.BLANK), 0)
        val report = book.report()
        assertEquals("cached 1, tv listed no icon 1, http status 1", report.first())
        assertEquals(
            listOf(
                "org.example.three: tv listed no icon, none, tries 1",
                "org.example.two: http status (403), http /DIAL/icon/… host=tv, tries 1",
            ),
            report.drop(1),
        )
        assertFalse(report.any { it.contains("192.168") })
        assertEquals(listOf("nothing tried this session"), IconBook().report())
    }
}
