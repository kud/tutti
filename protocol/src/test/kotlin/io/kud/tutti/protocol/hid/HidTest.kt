package io.kud.tutti.protocol.hid

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HidDescriptorTest {
    /** Walks the short items and sums each report id's input bits, checking collections balance. */
    private fun inputBitsById(descriptor: ByteArray): Map<Int, Int> {
        val bits = mutableMapOf<Int, Int>()
        var size = 0
        var count = 0
        var id = 0
        var depth = 0
        var i = 0
        while (i < descriptor.size) {
            val prefix = descriptor[i].toInt() and 0xFF
            val length = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            var data = 0
            for (b in 0 until length) data = data or ((descriptor[i + 1 + b].toInt() and 0xFF) shl (8 * b))
            when (prefix and 0xFC) {
                0x74 -> size = data
                0x94 -> count = data
                0x84 -> id = data
                0x80 -> bits[id] = (bits[id] ?: 0) + size * count
                0xA0 -> depth++
                0xC0 -> depth--
            }
            assertTrue(depth >= 0, "collection closed before opened at byte $i")
            i += 1 + length
        }
        assertEquals(descriptor.size, i, "last item runs past the end")
        assertEquals(0, depth, "unbalanced collections")
        return bits
    }

    @Test
    fun `every report the encoders build is the size the descriptor declares`() {
        val bits = inputBitsById(HidDescriptor.bytes())
        assertEquals(setOf(1, 2, 3), bits.keys)
        assertEquals(HidDescriptor.KEYBOARD_SIZE * 8, bits[HidDescriptor.KEYBOARD_ID])
        assertEquals(HidDescriptor.MOUSE_SIZE * 8, bits[HidDescriptor.MOUSE_ID])
        assertEquals(HidDescriptor.CONSUMER_SIZE * 8, bits[HidDescriptor.CONSUMER_ID])
        assertEquals(HidDescriptor.KEYBOARD_SIZE, HidReports.keyboard().size)
        assertEquals(HidDescriptor.MOUSE_SIZE, HidReports.mouse().size)
        assertEquals(HidDescriptor.CONSUMER_SIZE, HidReports.consumer(0).size)
    }

    @Test
    fun `the walker catches a broken descriptor`() {
        val broken = HidDescriptor.bytes().copyOf(HidDescriptor.bytes().size - 1)
        val failed = runCatching { inputBitsById(broken) }.isFailure
        assertTrue(failed, "dropping the last End Collection must fail the check")
    }
}

class HidReportsTest {
    @Test
    fun `keyboard report is modifiers, reserved, then keys`() {
        assertContentEquals(
            byteArrayOf(0x02, 0, 0x04, 0x05, 0, 0, 0, 0),
            HidReports.keyboard(Modifier.LEFT_SHIFT, listOf(0x04, 0x05)),
        )
    }

    @Test
    fun `seven keys is roll-over, never six of them`() {
        val report = HidReports.keyboard(0, (0x04..0x0A).toList())
        assertContentEquals(byteArrayOf(0, 0, 1, 1, 1, 1, 1, 1), report)
    }

    @Test
    fun `mouse clamps to int8 and keeps three buttons`() {
        val report = HidReports.mouse(0xFF, 500, -500, -3)
        assertContentEquals(byteArrayOf(0x07, 127, -127, -3), report)
    }

    @Test
    fun `consumer usage is little-endian`() {
        assertContentEquals(byteArrayOf(0x23, 0x02), HidReports.consumer(ConsumerUsage.HOME.usage))
        assertContentEquals(byteArrayOf(0xE9.toByte(), 0), HidReports.consumer(ConsumerUsage.VOLUME_UP.usage))
    }
}

class KeyLayoutTest {
    @Test
    fun `us types ascii by position`() {
        val typed = KeyLayout.US.type("Hi!")
        assertEquals(listOf(KeyStroke(0x0B, Modifier.LEFT_SHIFT), KeyStroke(0x0C), KeyStroke(0x1E, Modifier.LEFT_SHIFT)), typed.strokes)
        assertTrue(typed.unsupported.isEmpty())
    }

    @Test
    fun `us cannot type an accent and says so`() {
        val typed = KeyLayout.US.type("café")
        assertEquals(setOf('é'), typed.unsupported)
        assertEquals(3, typed.strokes.size)
    }

    @Test
    fun `azerty swaps a q z w and moves m`() {
        val fr = KeyLayout.FR_AZERTY
        assertEquals(KeyStroke(0x14), fr.strokes('a')!!.single())
        assertEquals(KeyStroke(0x04), fr.strokes('q')!!.single())
        assertEquals(KeyStroke(0x1A), fr.strokes('z')!!.single())
        assertEquals(KeyStroke(0x1D), fr.strokes('w')!!.single())
        assertEquals(KeyStroke(0x33), fr.strokes('m')!!.single())
        assertEquals(KeyStroke(0x10), fr.strokes(',')!!.single())
    }

    @Test
    fun `azerty digits are shifted and accents are on the number row`() {
        val fr = KeyLayout.FR_AZERTY
        assertEquals(KeyStroke(0x1E, Modifier.LEFT_SHIFT), fr.strokes('1')!!.single())
        assertEquals(KeyStroke(0x1F), fr.strokes('é')!!.single())
        assertEquals(KeyStroke(0x24), fr.strokes('è')!!.single())
        assertEquals(KeyStroke(0x26), fr.strokes('ç')!!.single())
        assertEquals(KeyStroke(0x27), fr.strokes('à')!!.single())
        assertEquals(KeyStroke(0x34), fr.strokes('ù')!!.single())
        assertEquals(KeyStroke(0x27, Modifier.RIGHT_ALT), fr.strokes('@')!!.single())
        assertEquals(KeyStroke(0x08, Modifier.RIGHT_ALT), fr.strokes('€')!!.single())
    }

    @Test
    fun `circumflex and diaeresis go through the dead key`() {
        val fr = KeyLayout.FR_AZERTY
        assertEquals(listOf(KeyStroke(0x2F), KeyStroke(0x08)), fr.strokes('ê'))
        assertEquals(listOf(KeyStroke(0x2F, Modifier.LEFT_SHIFT), KeyStroke(0x0C)), fr.strokes('ï'))
        assertEquals(listOf(KeyStroke(0x2F), KeyStroke(0x08, Modifier.LEFT_SHIFT)), fr.strokes('Ê'))
    }

    @Test
    fun `what azerty cannot type comes back unsupported`() {
        val typed = KeyLayout.FR_AZERTY.type("École œuvre")
        assertEquals(setOf('É', 'œ'), typed.unsupported)
        assertNull(KeyLayout.FR_AZERTY.strokes('~'))
    }

    @Test
    fun `every printable ascii character has a stroke on us`() {
        val missing = (' '..'~').filter { KeyLayout.US.strokes(it) == null }
        assertTrue(missing.isEmpty(), "missing on US: $missing")
    }
}

class PointerMotionTest {
    @Test
    fun `fractions carry over so a slow drag still moves`() {
        val acc = MotionAccumulator()
        val steps = (1..12).flatMap { acc.add(0.25, 0.0) }
        assertEquals(3, steps.sumOf { it.dx })
    }

    @Test
    fun `a large motion splits into int8 reports`() {
        val steps = MotionAccumulator().add(300.0, -10.0)
        assertEquals(listOf(MouseStep(127, -10), MouseStep(127, 0), MouseStep(46, 0)), steps)
    }

    @Test
    fun `touchpad scrolls one detent per step of travel, natural direction`() {
        val pad = TouchpadMotion()
        assertEquals(2, pad.scroll(TouchpadMotion.SCROLL_PX * 2).sumOf { it.wheel })
    }

    @Test
    fun `a still hand moves nothing`() {
        val pointer = AirPointer()
        val steps = (1..200).flatMap { pointer.sample(0.02, -0.025, 0.01) }
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `turning left moves the cursor left, tilting up moves it up`() {
        val pointer = AirPointer()
        // Positive z rotation is counter-clockwise seen from above the screen: the top swings left.
        val left = (1..20).flatMap { pointer.sample(0.0, 1.0, 0.01) }.sumOf { it.dx }
        assertTrue(left < 0, "dx $left")
        pointer.release()
        // Positive x rotation tips the top away from the user: the phone points up.
        val up = (1..20).flatMap { pointer.sample(1.0, 0.0, 0.01) }.sumOf { it.dy }
        assertTrue(up < 0, "dy $up")
    }

    @Test
    fun `the clutch freezes and the cursor does not jump on release`() {
        val pointer = AirPointer()
        repeat(10) { pointer.sample(0.0, 2.0, 0.01) }
        pointer.hold()
        assertTrue(pointer.sample(0.0, 5.0, 0.01).isEmpty())
        pointer.release()
        val first = pointer.sample(0.0, 2.0, 0.01).sumOf { it.dx }
        // From rest the EMA lets only part of the motion through on the first sample.
        val steady = 2.0 - AirPointer.DEAD_ZONE
        assertTrue(kotlin.math.abs(first) < steady * AirPointer.BASE_GAIN * 0.01, "first $first")
    }

    @Test
    fun `sensitivity scales the motion`() {
        fun travel(sensitivity: Double): Int {
            val pointer = AirPointer(sensitivity)
            return (1..50).flatMap { pointer.sample(0.0, -1.0, 0.01) }.sumOf { it.dx }
        }
        val slow = travel(0.5)
        val fast = travel(2.0)
        assertTrue(fast in slow * 3..slow * 5, "slow $slow fast $fast")
    }

    @Test
    fun `a long gap or a bad sample moves nothing`() {
        val pointer = AirPointer()
        assertTrue(pointer.sample(0.0, 3.0, 1.0).isEmpty())
        assertTrue(pointer.sample(Double.NaN, 3.0, 0.01).isEmpty())
    }
}
