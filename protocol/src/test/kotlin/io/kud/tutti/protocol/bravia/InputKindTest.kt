package io.kud.tutti.protocol.bravia

import kotlin.test.assertEquals
import kotlin.test.Test

class InputKindTest {
    private fun input(port: Int, title: String = "HDMI $port", label: String = "", connected: Boolean = true, icon: String? = null) =
        ExternalInput("extInput:hdmi?port=$port", title, label, connected, icon)

    @Test
    fun `the rows from the dev 13 screenshot each get their kind`() {
        assertEquals(InputKind.SOUNDBAR, input(1, label = "HT-A9000").kind())
        assertEquals(InputKind.COMPUTER, input(1, label = "PC", connected = false).kind())
        assertEquals(InputKind.PORT, input(2, connected = false).kind())
        assertEquals(InputKind.SOUNDBAR, input(3, title = "HDMI 3 (eARC/ARC)").kind())
        assertEquals(InputKind.COMPUTER, input(4, label = "macOS", connected = false).kind())
    }

    @Test
    fun `a computer set up in tutti on an input wins over whatever the TV calls it`() {
        val plain = input(2)
        assertEquals(InputKind.PORT, plain.kind())
        assertEquals(InputKind.COMPUTER, plain.kind(computerInputs = setOf(plain.uri)))
        assertEquals(InputKind.COMPUTER, input(3, title = "HDMI 3 (eARC/ARC)").kind(setOf("extInput:hdmi?port=3")))
    }

    @Test
    fun `the TV's own icon is read before the words`() {
        assertEquals(InputKind.GAME, input(1, icon = "meta:game").kind())
        assertEquals(InputKind.COMPUTER, input(1, icon = "meta:pc").kind())
        assertEquals(InputKind.SOUNDBAR, input(1, icon = "meta:audiosystem").kind())
        assertEquals(InputKind.SOUNDBAR, input(1, icon = "meta:hometheater").kind())
        assertEquals(InputKind.PORT, input(1, icon = "meta:hdmi").kind())
    }

    @Test
    fun `consoles and computers are told by their CEC names`() {
        for (name in listOf("PS5", "PlayStation 5", "Xbox Series X", "Nintendo Switch", "NintendoSwitch")) {
            assertEquals(InputKind.GAME, input(1, label = name).kind(), name)
        }
        for (name in listOf("Windows", "Linux", "MacBook Pro", "Mac mini", "Gaming PC")) {
            assertEquals(InputKind.COMPUTER, input(1, label = name).kind(), name)
        }
        for (name in listOf("HT-S2000", "Soundbar", "BRAVIA Theatre Bar 9")) {
            assertEquals(InputKind.SOUNDBAR, input(1, label = name).kind(), name)
        }
    }

    @Test
    fun `an empty eARC port is just a port, and words that only look alike stay ports`() {
        assertEquals(InputKind.PORT, input(3, title = "HDMI 3 (eARC/ARC)", connected = false).kind())
        assertEquals(InputKind.PORT, input(1, label = "Apple TV").kind())
        assertEquals(InputKind.PORT, input(1, label = "Chromecast").kind())
        assertEquals(InputKind.PORT, input(1, label = "Search").kind())
        assertEquals(InputKind.PORT, ExternalInput("extInput:hdmi?port=1", "", "", true).kind())
    }
}
