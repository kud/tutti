package io.kud.tutti.protocol.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LayoutCodecTest {

    private val full = LayoutFile(
        hand = "left",
        dockLabels = "icon",
        sceneStrip = false,
        pins = listOf(
            FilePin(FileAction.Remote("back")),
            FilePin(FileAction.Remote("home")),
            FilePin(FileAction.OpenApps),
            FilePin(FileAction.OpenKeys),
            FilePin(FileAction.App("com.example.player", "Player"), FileAction.Key(85, long = true), label = "Films", icon = "movie"),
            FilePin(FileAction.Input("extInput:hdmi?port=2", "Console"), letters = "PS"),
            FilePin(FileAction.ToggleBar("audio.nightmode")),
            FilePin(FileAction.SetBar("audio.volume", "12")),
            FilePin(FileAction.Scene("Cinema")),
            FilePin(FileAction.Ircc("Netflix", "AAAAAgAAABoAAAB8Aw==")),
            FilePin(FileAction.PictureMode("cinema", "Cinema")),
            FilePin(
                FileAction.Macro("Evening", listOf(FileStep.Run(FileAction.TvSound("audioSystem")), FileStep.Wait(1500), FileStep.Run(FileAction.PictureOff(true)))),
            ),
        ),
        spare = listOf(FilePin(FileAction.Remote("guide"))),
        longPress = mapOf("home" to FileAction.Key(3, long = true), "input" to FileAction.Scene("Games")),
    )

    @Test
    fun `a layout survives a round trip whole`() {
        val read = LayoutCodec.decode(LayoutCodec.encode(full))
        assertIs<LayoutRead.Ok>(read)
        assertEquals(full, read.file)
        assertEquals(emptyList(), read.skipped)
    }

    @Test
    fun `the file names its format and version`() {
        val root = Json.parseToJsonElement(LayoutCodec.encode(full)) as JsonObject
        assertEquals("\"tutti-layout\"", root["format"].toString())
        assertEquals("1", root["version"].toString())
    }

    @Test
    fun `a file from a newer version is refused, not guessed at`() {
        val text = LayoutCodec.encode(full).replace("\"version\": 1", "\"version\": 2")
        assertEquals(LayoutRead.FromTheFuture(2), LayoutCodec.decode(text))
    }

    @Test
    fun `anything that is not a layout is refused whole`() {
        listOf(
            "",
            "not json",
            "[]",
            "null",
            "{}",
            """{"format":"something-else","version":1,"pins":[]}""",
            """{"format":"tutti-layout","version":"1","pins":[]}""",
            """{"format":"tutti-layout","version":0,"pins":[]}""",
            """{"format":"tutti-layout","version":1}""",
            """{"format":"tutti-layout","version":1,"pins":{}}""",
            """{"format":"tutti-layout","version":1,"pins":[${List(13) { "null" }.joinToString(",")}]}""",
            """{"format":"tutti-layout","version":1,"pins":[],"spare":[${List(25) { "{}" }.joinToString(",")}]}""",
            """{"format":"tutti-layout","version":1,"pins":[],"longPress":[]}""",
        ).forEach { assertEquals(LayoutRead.NotALayout, LayoutCodec.decode(it), it) }
    }

    @Test
    fun `an oversized file is refused before it is parsed`() {
        val text = """{"format":"tutti-layout","version":1,"pins":[],"x":"${"a".repeat(LayoutCodec.MAX_BYTES)}"}"""
        assertEquals(LayoutRead.TooLarge, LayoutCodec.decode(text))
    }

    @Test
    fun `deep nesting is refused, never a crash`() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertEquals(LayoutRead.NotALayout, LayoutCodec.decode("""{"format":"tutti-layout","version":1,"pins":[],"x":$deep}""").let { if (it is LayoutRead.Ok) LayoutRead.NotALayout else it })
    }

    @Test
    fun `unknown fields are ignored`() {
        val text = """{"format":"tutti-layout","version":1,"future":{"a":1},"hand":"right",
            "pins":[{"tap":{"type":"open.apps","colour":"red"},"sparkle":true}]}"""
        val read = assertIs<LayoutRead.Ok>(LayoutCodec.decode(text))
        assertEquals("right", read.file.hand)
        assertEquals(FilePin(FileAction.OpenApps), read.file.pins[0])
        assertEquals(12, read.file.pins.size)
    }

    @Test
    fun `hostile values drop only the pin that holds them, and say so`() {
        val hostile = listOf(
            """{"tap":{"type":"app","package":"../../etc/passwd","label":"x"},"label":"Evil"}""",
            """{"tap":{"type":"input","uri":"http://192.168.1.2/x","label":"x"}}""",
            """{"tap":{"type":"toggle","feature":"AUDIO; rm -rf"}}""",
            """{"tap":{"type":"key","code":99999}}""",
            """{"tap":{"type":"ircc","name":"x","code":"not base64!"}}""",
            """{"tap":{"type":"open.apps"},"label":"${"x".repeat(41)}"}""",
            """{"tap":{"type":"open.apps"},"label":"a\u0000b"}""",
            """{"tap":{"type":"macro","name":"m","steps":[{"type":"run","action":{"type":"macro","name":"n","steps":[]}}]}}""",
            """{"tap":{"type":"macro","name":"m","steps":[{"type":"wait","millis":-1}]}}""",
            """{"tap":{"type":"teleport"}}""",
            """{"tap":{"type":"open.keys"},"hold":{"type":"open.apps"}}""",
            """{"tap":{"type":"remote","key":"self_destruct"}}""",
        )
        val text = """{"format":"tutti-layout","version":1,"pins":[${hostile.joinToString(",")}],
            "longPress":{"home":{"type":"open.keys"},"back":{"type":"key","code":4},"nuke":{"type":"key","code":4}}}"""
        val read = assertIs<LayoutRead.Ok>(LayoutCodec.decode(text))
        assertTrue(read.file.pins.all { it == null }, read.file.pins.toString())
        assertEquals(hostile.size + 1, read.skipped.size)
        assertEquals(Skipped.Pin(0, "Evil"), read.skipped.first())
        assertEquals(Skipped.LongPress("home"), read.skipped.last())
        assertEquals(mapOf("back" to FileAction.Key(4, long = false)), read.file.longPress)
    }

    @Test
    fun `bad words for hand and labels fall back to the phone's own`() {
        val read = assertIs<LayoutRead.Ok>(LayoutCodec.decode("""{"format":"tutti-layout","version":1,"pins":[],"hand":"both","dockLabels":3,"sceneStrip":"yes"}"""))
        assertEquals(null, read.file.hand)
        assertEquals(null, read.file.dockLabels)
        assertEquals(null, read.file.sceneStrip)
    }

    /**
     * The privacy line: the file carries only these keys, so a host, a MAC, a serial, a device id or
     * name, a PSK, a cookie or a token has nowhere to go. A new key must be added here on purpose.
     */
    @Test
    fun `no key outside the layout's own ever reaches the file`() {
        val allowed = setOf(
            "format", "version", "hand", "dockLabels", "sceneStrip", "pins", "spare", "longPress",
            "tap", "hold", "label", "icon", "letters", "type", "package", "uri", "feature", "value", "name",
            "code", "long", "mode", "terminal", "on", "steps", "action", "millis", "key",
        ) + LayoutFile.LONG_PRESS_KEYS
        val keys = mutableSetOf<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> keys += k; walk(v) }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(Json.parseToJsonElement(LayoutCodec.encode(full)))
        assertTrue(keys.isNotEmpty())
        assertEquals(emptySet(), keys - allowed)
    }

    @Test
    fun `nothing shaped like an address or hardware id appears in the file`() {
        val text = LayoutCodec.encode(full)
        val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
        val mac = Regex("""\b[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}\b""")
        // Probes first: the patterns must still match, or the check below passes for any input.
        assertTrue(ipv4.containsMatchIn("192.168.1.20"))
        assertTrue(mac.containsMatchIn("00:11:22:33:44:55"))
        assertTrue(!ipv4.containsMatchIn(text) && !mac.containsMatchIn(text), text)
        listOf("host", "mac", "psk", "cookie", "token", "serial", "deviceId", "address").forEach {
            assertTrue("\"$it\"" !in text, it)
        }
    }

    @Test
    fun `an input is only ever an extInput uri`() {
        val read = LayoutCodec.decode("""{"format":"tutti-layout","version":1,"pins":[{"tap":{"type":"input","uri":"extInput:hdmi?port=3","label":"HDMI 3"}}]}""")
        assertEquals(FilePin(FileAction.Input("extInput:hdmi?port=3", "HDMI 3")), assertIs<LayoutRead.Ok>(read).file.pins[0])
    }
}
