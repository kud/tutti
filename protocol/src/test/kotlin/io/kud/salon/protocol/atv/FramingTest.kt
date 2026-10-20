package io.kud.salon.protocol.atv

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FramingTest {
    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `length prefix is a protobuf varint`() {
        assertContentEquals(byteArrayOf(3, 1, 2, 3), Framing.frame(byteArrayOf(1, 2, 3)))
        val big = Framing.frame(payload(300))
        assertEquals(0xac.toByte(), big[0])
        assertEquals(0x02.toByte(), big[1])
        assertEquals(302, big.size)
    }

    @Test
    fun `reassembles a frame split inside its length prefix`() {
        val framed = Framing.frame(payload(300))
        val decoder = FrameDecoder()
        assertEquals(0, decoder.feed(framed.copyOfRange(0, 1)).size)
        assertEquals(0, decoder.feed(framed.copyOfRange(1, 100)).size)
        val frames = decoder.feed(framed.copyOfRange(100, framed.size))
        assertEquals(1, frames.size)
        assertContentEquals(payload(300), frames[0])
    }

    @Test
    fun `splits merged frames and keeps a partial tail`() {
        val a = Framing.frame(byteArrayOf(1))
        val b = Framing.frame(byteArrayOf(2, 2))
        val c = Framing.frame(byteArrayOf(3, 3, 3))
        val decoder = FrameDecoder()
        val first = decoder.feed(a + b + c.copyOfRange(0, 2))
        assertEquals(2, first.size)
        assertContentEquals(byteArrayOf(2, 2), first[1])
        val second = decoder.feed(c.copyOfRange(2, c.size))
        assertContentEquals(byteArrayOf(3, 3, 3), second.single())
    }

    @Test
    fun `byte by byte delivery yields every frame`() {
        val stream = Framing.frame(payload(10)) + Framing.frame(ByteArray(0)) + Framing.frame(payload(200))
        val decoder = FrameDecoder()
        val frames = stream.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf(10, 0, 200), frames.map { it.size })
    }

    @Test
    fun `oversize and overlong prefixes are rejected`() {
        assertFailsWith<FramingException> { FrameDecoder(maxFrameBytes = 16).feed(Framing.frame(payload(17))) }
        assertFailsWith<FramingException> { FrameDecoder().feed(ByteArray(6) { 0xff.toByte() }) }
    }
}
