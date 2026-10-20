package io.kud.salon.protocol.net

import kotlin.test.Test
import kotlin.test.assertEquals

class LineSplitterTest {
    @Test
    fun `joins a line split across reads`() {
        val splitter = LineSplitter()
        assertEquals(emptyList(), splitter.feed("{\"a\":".encodeToByteArray()))
        assertEquals(listOf("{\"a\":1}"), splitter.feed("1}\n".encodeToByteArray()))
    }

    @Test
    fun `splits merged lines and keeps the tail`() {
        val splitter = LineSplitter()
        assertEquals(listOf("one", "two"), splitter.feed("one\ntwo\r\nthr".encodeToByteArray()))
        assertEquals(listOf("three"), splitter.feed("ee\n".encodeToByteArray()))
    }

    @Test
    fun `survives a multibyte character cut in two`() {
        val bytes = "modifiée\n".encodeToByteArray()
        val cut = bytes.indexOfFirst { (it.toInt() and 0xff) == 0xc3 } + 1
        val splitter = LineSplitter()
        assertEquals(emptyList(), splitter.feed(bytes.copyOfRange(0, cut)))
        assertEquals(listOf("modifiée"), splitter.feed(bytes.copyOfRange(cut, bytes.size)))
    }

    @Test
    fun `drops an over-long line and recovers at the next newline`() {
        val splitter = LineSplitter(maxLineBytes = 8)
        assertEquals(listOf("ok"), splitter.feed("0123456789abc\nok\n".encodeToByteArray()))
    }
}
