package io.kud.tutti.protocol.diagnostic

import io.kud.tutti.protocol.cast.CastApp
import io.kud.tutti.protocol.cast.CastEnd
import io.kud.tutti.protocol.cast.CastHold
import io.kud.tutti.protocol.cast.CastLogLines
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import io.kud.tutti.protocol.cast.CastReading
import io.kud.tutti.protocol.cast.CastReport
import java.net.URLDecoder
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * Every value below is invented. Each secret has a distinctive spelling so a leak is found by a
 * plain substring search, whatever marker replaced it.
 */
private const val PSK = "Kettle42"
private const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0dXR0aS10ZXN0In0.c2lnbmF0dXJlLWZvci10ZXN0cw"
private const val SESSION_KEY = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
private const val PAIRING = "TW9ja1BhaXJpbmdTZWNyZXRGb3JUZXN0c09ubHk="
private const val COOKIE = "auth=MockCookieValue77; Path=/sony"
private const val CERT_BODY = "MIIBszCCAVmgAwIBAgIUTESTONLYmockcert"
private const val IPV4 = "192.168.1.20"
private const val IPV6 = "fe80::1c2d:3e4f:5a6b:7c8d"
private const val MAC = "AA:BB:CC:DD:EE:0F"
private const val SERIAL = "S01-4012345"
private const val TV_NAME = "Salon Lumière"
private const val PHONE_NAME = "Pixel de Test"

private val LEAKS = listOf(
    PSK, "eyJhbGci", SESSION_KEY, "0f1e2d3c", PAIRING, "TW9ja1Bh", "MockCookieValue77", CERT_BODY,
    "192.168", "fe80", "1c2d", MAC, "AA:BB", SERIAL, "4012345", TV_NAME, "Lumière", PHONE_NAME,
)

/** The secrets as they turn up in real lines: headers, JSON replies, key=value, traces. */
private val DIRTY = listOf(
    "pairing tls $IPV4:6466 failed, psk=$PSK",
    "X-Auth-PSK: $PSK",
    "Authorization: Bearer $TOKEN",
    "Cookie: $COOKIE",
    "Set-Cookie: $COOKIE",
    "{\"token\":\"$TOKEN\",\"session_key\":\"$SESSION_KEY\"}",
    "secret ready: pairing_secret=$PAIRING",
    "-----BEGIN CERTIFICATE-----\n$CERT_BODY\nAAAA\n-----END CERTIFICATE-----",
    "-----BEGIN PRIVATE KEY-----\n$CERT_BODY",
    "connect to [$IPV6%wlan0]:6467 refused",
    "wol to $MAC",
    "{\"model\":\"K-65XR80\",\"serial\":\"$SERIAL\",\"name\":\"$TV_NAME\",\"macAddr\":\"$MAC\"}",
    "serialNumber: $SERIAL",
    "device_name=$PHONE_NAME",
    "java.net.ConnectException: failed to connect to /$IPV4 (port 6467) from /$IPV6 (session $SESSION_KEY)",
)

class LogScrubberTest {
    private val known = listOf(PSK, TV_NAME, PHONE_NAME, IPV4)

    @Test
    fun `cast lines recorded in the event log carry no viewing data`() {
        val log = EventLog(clock = { 0L })
        val app = CastApp("APP1", "transport-9f8e7d6c", "session-1a2b3c")
        val reading = CastReading(0, "PLAYING", 812.4, 2589.0, mediaIncluded = true, playbackRate = 1.0, answer = false, sameSession = true)
        val shown = CastNowPlaying(
            title = "Sample Show", secondary = "S1:E5 – Sample Episode", state = CastPlayerState.PLAYING,
            positionSec = 812.4, durationSec = 2589.0, playbackRate = 1.0, readAtMillis = 0,
            imageUrl = "https://image.tmdb.org/t/p/w500/sample.jpg",
        )
        listOf(
            CastLogLines.connecting(),
            CastLogLines.ready(1_250),
            CastLogLines.app(null, app),
            CastLogLines.app(app, app.copy(transportId = "transport-0a0b0c")),
            CastLogLines.media(reading, hadTitle = true, report = CastReport.Playing(shown)),
            CastLogLines.media(null, hadTitle = false, report = CastReport.Unknown),
            CastLogLines.transportClosed(app),
            CastLogLines.clearedBy(CastHold.ClearReason.FOREGROUND, "com.example.player"),
            CastLogLines.graceRanOut(),
            CastLogLines.ended(CastEnd.Closed("SocketException"), 5_000),
        ).forEach { log.record(EventLog.Kind.CAST, it) }
        val text = log.render(ZoneOffset.UTC)
        for (leak in listOf("Sample Show", "S1:E5", "Sample Episode", "tmdb", "sample.jpg", "transport-", "session-1a2b3c", "812.4", "2589")) {
            assertFalse(leak in text, "$leak leaked: $text")
        }
        assertTrue("cast: PLAYING, title yes, position yes, duration yes, unasked, same session: playing" in text, text)
        assertTrue("com.example.player in front" in text, "the package in front is kept: $text")
    }

    @Test
    fun `no secret, address, MAC, serial or device name survives`() {
        for (line in DIRTY) {
            val out = LogScrubber.scrub(line, known)
            for (leak in LEAKS) assertFalse(leak in out, "$leak leaked from <$line>: $out")
        }
    }

    @Test
    fun `the generic patterns hold without the known values`() {
        // Each line names its secret by key, header or shape, so it goes even when nobody listed it.
        for (line in DIRTY) {
            val out = LogScrubber.scrub(line)
            for (leak in LEAKS) assertFalse(leak in out, "$leak leaked from <$line> with no known values: $out")
        }
    }

    @Test
    fun `the probe set is not empty and every line changes`() {
        // A scrubber that matched nothing would pass the two tests above on an empty list.
        assertTrue(DIRTY.size >= 15)
        for (line in DIRTY) assertTrue(LogScrubber.scrub(line) != line, "nothing scrubbed in <$line>")
    }

    @Test
    fun `prose and the useful words stay`() {
        val input = "remote ONLINE -> UNREACHABLE; host unreachable; pin mismatch on rest; " +
            "launch com.netflix.ninja: SAME_APP after 812 ms; key hold 23; model K-65XR80; app: 0.3.1 (github)"
        assertEquals(input, LogScrubber.scrub(input, listOf("ab")))
    }

    @Test
    fun `no pattern opens a class with a colon, which ICU reads as a POSIX class`() {
        for (pattern in LogScrubber.patterns) assertFalse("[:" in pattern.pattern, pattern.pattern)
    }
}

class EventLogTest {
    @Test
    fun `bounded, the oldest go first`() {
        val log = EventLog(capacity = 3, clock = { 0L })
        repeat(5) { log.record(EventLog.Kind.KEY, "hold $it") }
        assertEquals(listOf("hold 2", "hold 3", "hold 4"), log.entries.value.map { it.text })
    }

    @Test
    fun `each entry is cut to its own bound`() {
        val log = EventLog(capacity = 2, maxEntryChars = 50, clock = { 0L })
        log.record(EventLog.Kind.ERROR, "x ".repeat(1_000))
        assertTrue(log.entries.value.single().text.length <= 50)
    }

    @Test
    fun `scrubbed as recorded, with the known values read at that moment`() {
        val log = EventLog(clock = { 0L })
        var known = listOf(PSK)
        log.known = { known }
        log.record(EventLog.Kind.ERROR, "pairing with $PSK at $IPV4")
        known = listOf(TV_NAME)
        log.record(EventLog.Kind.CONNECTION, "renamed to $TV_NAME", sensitive = listOf("extra-value"))
        DIRTY.forEach { log.record(EventLog.Kind.ERROR, it) }
        log.crash(DIRTY.joinToString("\n"))
        val text = log.render(ZoneOffset.UTC)
        for (leak in LEAKS) assertFalse(leak in text, "$leak leaked: $text")
        log.entries.value.forEach { entry -> LEAKS.forEach { assertFalse(it in entry.text) } }
    }

    @Test
    fun `a known-values source that throws still scrubs by shape`() {
        val log = EventLog(clock = { 0L })
        log.known = { error("store not ready") }
        log.record(EventLog.Kind.ERROR, "to $IPV4")
        assertFalse(IPV4 in log.entries.value.single().text)
    }

    @Test
    fun `renders time, kind and the crash last`() {
        val log = EventLog(clock = { 3_723_004L })
        log.record(EventLog.Kind.LAUNCH, "com.example.video: OPENED")
        log.crash("java.lang.IllegalStateException: boom\n  at io.kud.tutti.X")
        assertEquals(
            "01:02:03.004 launch: com.example.video: OPENED\nlast crash:\njava.lang.IllegalStateException: boom\n  at io.kud.tutti.X",
            log.render(ZoneOffset.UTC),
        )
        log.clear()
        assertEquals("", log.render(ZoneOffset.UTC))
    }
}

class IssueLinkTest {
    private val facts = listOf("app" to "0.3.1-dev.5 (github)", "android" to "15 (API 35)", "tv" to "K-xxXR80")

    private fun decodedBody(url: String): String {
        val query = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        return URLDecoder.decode(assertNotNull(query["body"]), Charsets.UTF_8.name())
    }

    @Test
    fun `a new-issue link with title and body, nothing else`() {
        val url = IssueLink.build("Launch failed: Netflix", facts, "outcome: timeout", "10:00:00.000 key: hold 23")
        assertTrue(url.startsWith("https://github.com/kud/tutti/issues/new?title=Launch%20failed%3A%20Netflix&body="), url)
        assertFalse('+' in url.substringAfter('?'), url)
        val body = decodedBody(url)
        assertTrue("- app: 0.3.1-dev.5 (github)" in body, body)
        assertTrue("outcome: timeout" in body && "hold 23" in body, body)
        assertFalse(IssueLink.CUT in body)
    }

    @Test
    fun `a long log keeps its newest lines under the limit, with the marker`() {
        val log = (1..2_000).joinToString("\n") { "10:00:00.000 key: hold line-$it" }
        val url = IssueLink.build("Key not sent", facts, "detail", log)
        assertTrue(url.length <= IssueLink.MAX_LENGTH, "${url.length}")
        val body = decodedBody(url)
        assertTrue(IssueLink.CUT in body)
        assertTrue("line-2000" in body)
        assertFalse("line-1\n" in body)
        // As many as fit: the link is close to the limit, not cut far short of it.
        assertTrue(url.length > IssueLink.MAX_LENGTH - 200, "${url.length}")
    }

    @Test
    fun `a huge detail is halved until it fits`() {
        val url = IssueLink.build("Crash", facts, "at frame\n".repeat(5_000), "a line")
        assertTrue(url.length <= IssueLink.MAX_LENGTH, "${url.length}")
        assertTrue(IssueLink.DETAIL_CUT in decodedBody(url))
    }

    @Test
    fun `whatever goes in, no secret comes out`() {
        val url = IssueLink.build("pairing $IPV4 psk=$PSK", facts + ("tv name" to "name=$TV_NAME"), DIRTY.joinToString("\n"), DIRTY.joinToString("\n"))
        val decoded = URLDecoder.decode(url, Charsets.UTF_8.name())
        for (leak in LEAKS.filter { it != TV_NAME && it != "Lumière" && it != PHONE_NAME }) {
            assertFalse(leak in decoded, "$leak leaked: $decoded")
        }
        assertFalse(TV_NAME in decoded, decoded)
    }

    @Test
    fun `the model family drops the screen size`() {
        assertEquals("K-xxXR80", IssueLink.modelFamily("K-65XR80"))
        assertEquals("KD-xxX85J", IssueLink.modelFamily(" KD-55X85J "))
        assertEquals(null, IssueLink.modelFamily(""))
        assertEquals(null, IssueLink.modelFamily(null))
        assertEquals(null, IssueLink.modelFamily("<script>"))
    }
}
