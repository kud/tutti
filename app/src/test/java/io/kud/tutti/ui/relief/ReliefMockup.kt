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
 * Two strips, dark theme then light; in each, rest, two tilts and a held press.
 */
class ReliefMockup {

    private val scale = 4f // px per dp
    private class Theme(val name: String, val background: Int, val key: Int, val on: Int, val disc: Int, val ok: Int, val ink: Int)
    private val themes = listOf(
        Theme("DARK THEME", 0x141218, 0x4A4458, 0xD0BCFF, 0x36343B, 0x4A4458, 0xE6E1E5),
        Theme("LIGHT THEME", 0xFEF7FF, 0xE8DEF8, 0x6750A4, 0xE6E0E9, 0xE8DEF8, 0x1D1B20),
    )

    @Test
    fun render() {
        val dir = System.getenv("RELIEF_MOCKUP_DIR")
        assumeTrue(dir != null)
        val poses = listOf(
            Triple("REST", pose(0f, 0f, 0f), 0f),
            Triple("TILT LEFT", pose(0f, 1f, 0f, -20f), 0f),
            Triple("TILT TOWARD YOU", pose(1f, 0f, 0f, 20f), 0f),
            Triple("PRESSED", pose(0f, 0f, 0f), 1f),
        )
        val panelW = (200 * scale).toInt()
        val panelH = (300 * scale).toInt()
        val stripH = panelH + 80
        val raster = Raster(panelW * poses.size, stripH * themes.size + 80, 0x000000)
        themes.forEachIndexed { row, theme ->
            val oy = row * stripH.toFloat()
            raster.rect(0, oy.toInt(), raster.width, stripH, theme.background)
            poses.forEachIndexed { index, (label, quat, press) ->
                val tracker = HeadTracker()
                tracker.update(floatArrayOf(0f, 0f, 0f, 1f), 0L)
                tracker.update(quat, 1_000_000L)
                val ox = index * panelW.toFloat()
                // Three round keys, the middle one on and, in the last panel, held down.
                for (k in 0..2) {
                    slab(raster, tracker, ox + (16 + k * 62) * scale, oy + 16 * scale, 52f, 52f, if (k == 1) 16f else 26f, 9f, if (k == 1) theme.on else theme.key, if (k == 1) press else 0f)
                }
                // Two dock tiles, the second on.
                slab(raster, tracker, ox + 16 * scale, oy + 92 * scale, 80f, 64f, 20f, 7f, theme.key, 0f)
                slab(raster, tracker, ox + 104 * scale, oy + 92 * scale, 80f, 64f, 14f, 7f, theme.on, 0f)
                // The D-pad disc and OK (the hole's walls are left out of the mock-up).
                slab(raster, tracker, ox + 30 * scale, oy + 170 * scale, 120f, 120f, 60f, 8f, theme.disc, 0f)
                slab(raster, tracker, ox + 60 * scale, oy + 200 * scale, 60f, 60f, 30f, 10f, theme.ok, press)
                raster.text(label, ox.toInt() + 16, (oy + panelH + 8).toInt(), 4, theme.ink)
            }
            raster.text(theme.name, 16, (oy + panelH + 44).toInt(), 4, theme.ink)
        }
        raster.text("GEOMETRY MOCK-UP - NOT THE APP", 16, stripH * themes.size + 24, 5, 0xFFCF5C)
        File(dir!!).mkdirs()
        File(dir, "relief-geometry-mockup-v2.png").writeBytes(raster.png())
    }

    private fun slab(r: Raster, eye: HeadTracker, x: Float, y: Float, wDp: Float, hDp: Float, rDp: Float, depthDp: Float, colour: Int, sink: Float) {
        val p = ReliefPolygon().apply { setRoundRect(x, y, wDp * scale, hDp * scale, rDp * scale) }
        val ref = 10f * scale
        val max = 14f * scale
        val rest = depthDp * scale
        val depth = Relief.pressedDepth(rest, sink)
        val top = Relief.topOffset(eye.eyeX, eye.eyeY, depth, ref, max)
        val k = Relief.closeness(depth, ref)
        val n = p.count
        val dark = luminance(colour) <= Relief.DARK_LUMINANCE
        fun tx(i: Int) = p.centreX + top.x + (p.xs[i] - p.centreX) * k
        fun ty(i: Int) = p.centreY + top.y + (p.ys[i] - p.centreY) * k
        fun grown(grow: Float, dx: Float, dy: Float): Pair<FloatArray, FloatArray> {
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            for (i in 0 until n) {
                val prev = (i + n - 1) % n
                var vx = p.nx[prev] + p.nx[i]
                var vy = p.ny[prev] + p.ny[i]
                val l = kotlin.math.sqrt(vx * vx + vy * vy).takeIf { it > 0f } ?: 1f
                vx /= l; vy /= l
                xs[i] = p.xs[i] + vx * grow + dx
                ys[i] = p.ys[i] + vy * grow + dy
            }
            return xs to ys
        }
        // Cast shadow, four stacked layers, gone when pressed; then the contact shadow.
        val cast = Relief.castShadow(depth, top)
        val spread = Relief.shadowSpread(rest)
        for (layer in 1..4) {
            val (xs, ys) = grown(spread * layer / 4f, cast.x, cast.y)
            r.fill(xs, ys, 0x000000, 0.30f * (1f - sink) / 4f)
        }
        grown(0.75f * scale, -Relief.LIGHT_X * scale, -Relief.LIGHT_Y * scale).let { (xs, ys) -> r.fill(xs, ys, 0x000000, 0.28f) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val mx = (p.xs[i] + p.xs[j]) / 2f - p.centreX
            val my = (p.ys[i] + p.ys[j]) / 2f - p.centreY
            val d = androidx.compose.ui.geometry.Offset(top.x + mx * (k - 1f), top.y + my * (k - 1f))
            if (!Relief.wallVisible(p.nx[i], p.ny[i], d)) continue
            val f = Relief.wallFactor(Relief.wallShade(p.nx[i], p.ny[i]))
            val lift = Relief.wallLift(p.nx[i], p.ny[i], dark)
            fun band(from: Float, to: Float, c: Int, alpha: Float) = r.fill(
                floatArrayOf(p.xs[i] + (tx(i) - p.xs[i]) * from, p.xs[j] + (tx(j) - p.xs[j]) * from, p.xs[j] + (tx(j) - p.xs[j]) * to, p.xs[i] + (tx(i) - p.xs[i]) * to),
                floatArrayOf(p.ys[i] + (ty(i) - p.ys[i]) * from, p.ys[j] + (ty(j) - p.ys[j]) * from, p.ys[j] + (ty(j) - p.ys[j]) * to, p.ys[i] + (ty(i) - p.ys[i]) * to),
                c, alpha, grow = 0.6f,
            )
            band(0f, 1f, tone(colour, f, lift), 1f)
            band(0f, 0.45f, 0x000000, 0.18f)
            band(0.78f, 1f, 0xFFFFFF, if (dark) 0.20f else 0.12f)
        }
        r.fill(FloatArray(n) { tx(it) }, FloatArray(n) { ty(it) }, colour, 1f)
        val gleam = Relief.highlightDirection(top, max)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val g = Relief.highlight(p.nx[i], p.ny[i], gleam) * (if (dark) 0.45f else 0.55f) * (1f - 0.7f * sink)
            val w = 1.5f * scale
            if (g > 0.01f) r.fill(
                floatArrayOf(tx(i), tx(j), tx(j) - p.nx[i] * w, tx(i) - p.nx[i] * w),
                floatArrayOf(ty(i), ty(j), ty(j) - p.ny[i] * w, ty(i) - p.ny[i] * w),
                0xFFFFFF, g, grow = 0.3f,
            )
            if (sink > 0.01f) {
                val s = 4f * scale * k
                r.fill(
                    floatArrayOf(tx(i), tx(j), tx(j) - p.nx[i] * s, tx(i) - p.nx[i] * s),
                    floatArrayOf(ty(i), ty(j), ty(j) - p.ny[i] * s, ty(i) - p.ny[i] * s),
                    0x000000, 0.22f * sink, grow = 0.3f,
                )
            }
        }
    }

    private fun luminance(c: Int): Float {
        fun ch(v: Int): Float = (v / 255f).let { if (it <= 0.03928f) it / 12.92f else Math.pow(((it + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
        return 0.2126f * ch(c shr 16 and 0xFF) + 0.7152f * ch(c shr 8 and 0xFF) + 0.0722f * ch(c and 0xFF)
    }

    private fun tone(c: Int, k: Float, lift: Float): Int {
        fun ch(v: Int) = ((v / 255f * k * (1f - lift) + lift) * 255f).toInt().coerceIn(0, 255)
        return (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF)
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

    fun rect(x: Int, y: Int, w: Int, h: Int, colour: Int) {
        for (py in max(0, y) until min(height, y + h)) for (px in max(0, x) until min(width, x + w)) pixels[py * width + px] = colour
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
