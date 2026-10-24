package io.kud.tutti.ui.relief

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Not a test: a geometry mock-up of "3D relief", drawn with the same [Relief] maths the app uses
 * by a tiny software rasteriser (unit tests compile against android.jar, which has no java.awt),
 * so it is NOT a screenshot of the app. Runs only when RELIEF_MOCKUP_DIR is set:
 * `RELIEF_MOCKUP_DIR=/tmp/x ./gradlew :app:testDebugUnitTest --tests '*ReliefMockup*'`.
 */
class ReliefMockup {

    private val scale = 4f // px per dp
    private val background = 0x141318
    private val keyColour = 0x4A4458
    private val onColour = 0xD0BCFF
    private val discColour = 0x36343B
    private val okColour = 0x4A4458
    private val ink = 0xE6E1E5

    @Test
    fun render() {
        val dir = System.getenv("RELIEF_MOCKUP_DIR")
        assumeTrue(dir != null)
        val poses = listOf(
            "REST" to pose(0f, 0f, 0f),
            "TILT LEFT" to pose(0f, 1f, 0f, -20f),
            "TILT TOWARD YOU" to pose(1f, 0f, 0f, 20f),
        )
        val panelW = (200 * scale).toInt()
        val panelH = (300 * scale).toInt()
        val raster = Raster(panelW * poses.size, panelH + 80, background)
        poses.forEachIndexed { index, (label, quat) ->
            val tracker = HeadTracker()
            tracker.update(floatArrayOf(0f, 0f, 0f, 1f), 0L)
            tracker.update(quat, 1_000_000L)
            val ox = index * panelW.toFloat()
            // Three round keys, the middle one on, like the row under the pad.
            for (k in 0..2) slab(raster, tracker, ox + (16 + k * 62) * scale, 16 * scale, 52f, 52f, if (k == 1) 16f else 26f, 3f, if (k == 1) onColour else keyColour)
            // Two dock tiles, the second on.
            slab(raster, tracker, ox + 16 * scale, 90 * scale, 80f, 64f, 20f, 2.5f, keyColour)
            slab(raster, tracker, ox + 104 * scale, 90 * scale, 80f, 64f, 14f, 2.5f, onColour)
            // The D-pad disc and OK (the hole's walls are left out of the mock-up).
            slab(raster, tracker, ox + 30 * scale, 168 * scale, 120f, 120f, 60f, 3f, discColour)
            slab(raster, tracker, ox + 60 * scale, 198 * scale, 60f, 60f, 30f, 4f, okColour)
            raster.text(label, ox.toInt() + 16, panelH + 8, 4, ink)
        }
        raster.text("GEOMETRY MOCK-UP - NOT THE APP", 16, panelH + 48, 4, 0xFFCF5C)
        File(dir!!).mkdirs()
        File(dir, "relief-geometry-mockup.png").writeBytes(raster.png())
    }

    private fun slab(r: Raster, eye: HeadTracker, x: Float, y: Float, wDp: Float, hDp: Float, rDp: Float, depthDp: Float, colour: Int) {
        val polygon = ReliefPolygon().apply { setRoundRect(x, y, wDp * scale, hDp * scale, rDp * scale) }
        val depth = depthDp * scale
        val top = Relief.topOffset(eye.eyeX, eye.eyeY, depth, 1900f * scale, 4f * scale)
        val shadow = Relief.shadowOffset(depth)
        val n = polygon.count
        r.fill(FloatArray(n) { polygon.xs[it] + shadow.x }, FloatArray(n) { polygon.ys[it] + shadow.y }, 0x000000, 0.22f)
        for (i in 0 until n) {
            if (!Relief.wallVisible(polygon.nx[i], polygon.ny[i], top)) continue
            val j = (i + 1) % n
            val k = 0.35f + 0.65f * (Relief.wallShade(polygon.nx[i], polygon.ny[i]) / Relief.TOP_SHADE).coerceIn(0f, 1f)
            r.fill(
                floatArrayOf(polygon.xs[i], polygon.xs[j], polygon.xs[j] + top.x, polygon.xs[i] + top.x),
                floatArrayOf(polygon.ys[i], polygon.ys[j], polygon.ys[j] + top.y, polygon.ys[i] + top.y),
                scaleColour(colour, k),
                1f,
                grow = 0.6f,
            )
        }
        r.fill(FloatArray(n) { polygon.xs[it] + top.x }, FloatArray(n) { polygon.ys[it] + top.y }, colour, 1f)
    }

    private fun scaleColour(c: Int, k: Float): Int {
        val red = ((c shr 16 and 0xFF) * k).toInt()
        val green = ((c shr 8 and 0xFF) * k).toInt()
        val blue = ((c and 0xFF) * k).toInt()
        return (red shl 16) or (green shl 8) or blue
    }

    private fun pose(x: Float, y: Float, z: Float, degrees: Float = 0f): FloatArray {
        val half = Math.toRadians(degrees.toDouble()) / 2
        val s = sin(half).toFloat()
        return floatArrayOf(x * s, y * s, z * s, cos(half).toFloat())
    }
}

/** An RGB canvas with 4x4-supersampled convex polygon fills, a 3x5 pixel font and a PNG writer. */
private class Raster(val width: Int, val height: Int, background: Int) {
    private val pixels = IntArray(width * height) { background }

    fun fill(xs: FloatArray, ys: FloatArray, colour: Int, alpha: Float, grow: Float = 0f) {
        val n = xs.size
        val cx = xs.average().toFloat()
        val cy = ys.average().toFloat()
        // Convex and wound one way: a point is inside when it is on the same side of every edge.
        val gx = FloatArray(n) { xs[it] + (xs[it] - cx).let { d -> if (d == 0f) 0f else d / kotlin.math.abs(d) * grow } }
        val gy = FloatArray(n) { ys[it] + (ys[it] - cy).let { d -> if (d == 0f) 0f else d / kotlin.math.abs(d) * grow } }
        val x0 = max(0, floor(gx.min()).toInt())
        val x1 = min(width - 1, ceil(gx.max()).toInt())
        val y0 = max(0, floor(gy.min()).toInt())
        val y1 = min(height - 1, ceil(gy.max()).toInt())
        for (py in y0..y1) for (px in x0..x1) {
            var hits = 0
            for (sy in 0..3) for (sx in 0..3) {
                if (inside(gx, gy, px + (sx + 0.5f) / 4f, py + (sy + 0.5f) / 4f)) hits++
            }
            if (hits == 0) continue
            blend(px, py, colour, alpha * hits / 16f)
        }
    }

    private fun inside(xs: FloatArray, ys: FloatArray, x: Float, y: Float): Boolean {
        var sign = 0
        for (i in xs.indices) {
            val j = (i + 1) % xs.size
            val cross = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i])
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun blend(x: Int, y: Int, colour: Int, a: Float) {
        val old = pixels[y * width + x]
        fun ch(shift: Int) = (((old shr shift) and 0xFF) * (1f - a) + ((colour shr shift) and 0xFF) * a).toInt().coerceIn(0, 255)
        pixels[y * width + x] = (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun text(text: String, x: Int, y: Int, size: Int, colour: Int) {
        var cursor = x
        for (c in text) {
            val glyph = FONT[c] ?: 0
            for (row in 0..4) for (col in 0..2) {
                if (glyph shr (14 - (row * 3 + col)) and 1 == 1) {
                    for (dy in 0 until size) for (dx in 0 until size) {
                        val px = cursor + col * size + dx
                        val py = y + row * size + dy
                        if (px in 0 until width && py in 0 until height) pixels[py * width + px] = colour
                    }
                }
            }
            cursor += 4 * size
        }
    }

    fun png(): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                raw.write(p shr 16 and 0xFF); raw.write(p shr 8 and 0xFF); raw.write(p and 0xFF)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray() + body
            data.write(typed)
            data.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) } }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private companion object {
        /** 3x5 glyphs, 15 bits, top row first. */
        val FONT = mapOf(
            'A' to 0b010101111101101, 'C' to 0b011100100100011, 'D' to 0b110101101101110,
            'E' to 0b111100110100111, 'F' to 0b111100110100100, 'G' to 0b011100101101011,
            'H' to 0b101101111101101, 'I' to 0b111010010010111, 'K' to 0b101101110101101, 'L' to 0b100100100100111,
            'M' to 0b101111111101101, 'N' to 0b110101101101101, 'O' to 0b010101101101010,
            'P' to 0b110101110100100, 'R' to 0b110101110101101, 'S' to 0b011100010001110,
            'T' to 0b111010010010010, 'U' to 0b101101101101111, 'W' to 0b101101111111101,
            'Y' to 0b101101010010010, '-' to 0b000000111000000, ' ' to 0,
        )
    }
}
