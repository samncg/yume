package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One material of the library, in its group (tones, patterns, textures). */
enum class MaterialKind(val label: String, val group: String) {
    Dots("Dot tone", "Tones"),
    Lines("Line tone", "Tones"),
    Cross("Cross-hatch", "Tones"),
    Gradation("Gradation", "Tones"),
    Checker("Checker", "Patterns"),
    Grid("Grid", "Patterns"),
    Bricks("Bricks", "Patterns"),
    Honeycomb("Honeycomb", "Patterns"),
    Scales("Scales", "Patterns"),
    Waves("Waves", "Patterns"),
    Zigzag("Zigzag", "Patterns"),
    Polka("Polka dots", "Patterns"),
    Paper("Paper", "Textures"),
    Canvas("Canvas", "Textures"),
    Grain("Grain", "Textures"),
    Speckle("Speckle", "Textures"),
    Wood("Wood grain", "Textures");

    /** What the density slider does for this material (null: no slider). */
    val densityLabel: String?
        get() = when (this) {
            Dots, Lines, Cross -> "Tone"
            Gradation -> "Darkest"
            Checker -> null
            Grid, Honeycomb, Scales, Waves, Zigzag -> "Line width"
            Bricks -> "Mortar"
            Polka -> "Dot size"
            Paper, Canvas, Wood -> "Strength"
            Grain, Speckle -> "Amount"
        }

    /** Textures vary with a seed ("new variation"); the others are exact grids. */
    val seeded: Boolean get() = group == "Textures"

    /** Where the density slider starts. */
    val defaultDensity: Float
        get() = when (this) {
            Dots, Lines, Cross -> 0.3f
            Gradation -> 0.8f
            Polka -> 0.6f
            Paper, Canvas, Wood -> 0.5f
            Grain, Speckle -> 0.5f
            else -> 0.3f
        }

    companion object {
        val groups: List<Pair<String, List<MaterialKind>>> =
            entries.groupBy { it.group }.map { (g, k) -> g to k }
    }
}

/**
 * Tones, patterns and textures, made pixel by pixel from document positions:
 * the same picture whatever the tiling or layer storage (sparse and dense
 * match byte for byte), at any canvas size, rotated by [angleDeg].
 *
 * [size] is the pattern's period in document px; [density] (0..1) is the
 * material's own slider (tone value, line width, strength...). [area] is
 * where the material goes: a gradation runs across it.
 */
class Material(
    val kind: MaterialKind,
    val size: Float,
    val angleDeg: Float,
    val density: Float,
    val area: Rect,
    val seed: Int = 1
) {
    private val s = size.coerceAtLeast(1f)
    private val t = density.coerceIn(0f, 1f)
    private val ca = cos(Math.toRadians(angleDeg.toDouble())).toFloat()
    private val sa = sin(Math.toRadians(angleDeg.toDouble())).toFloat()

    // Gradation: how far along the rotated axis the area runs.
    private val u0: Float
    private val u1: Float

    init {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (cx in intArrayOf(area.left, area.right)) for (cy in intArrayOf(area.top, area.bottom)) {
            val u = cx * ca + cy * sa
            lo = min(lo, u); hi = max(hi, u)
        }
        u0 = lo
        u1 = if (hi > lo) hi else lo + 1f
    }

    /** Ink coverage 0..1 at pixel ([x], [y]) (its centre is sampled). */
    fun coverage(x: Int, y: Int): Float = coverageAt(x + 0.5f, y + 0.5f)

    /** Ink coverage 0..1 at document point ([px], [py]) (previews sample between pixels). */
    fun coverageAt(px: Float, py: Float): Float {
        val u = px * ca + py * sa
        val v = -px * sa + py * ca
        return when (kind) {
            MaterialKind.Dots -> dots(u, v, t)
            MaterialKind.Lines -> aa(abs(centred(v / s)) * s - t * s / 2f)
            MaterialKind.Cross -> {
                // Two line sets whose union covers [t] of the area.
                val w = (1f - sqrt(1f - t)) * s
                max(aa(abs(centred(v / s)) * s - w / 2f), aa(abs(centred(u / s)) * s - w / 2f))
            }
            MaterialKind.Gradation -> dots(u, v, t * ((u - u0) / (u1 - u0)).coerceIn(0f, 1f))
            MaterialKind.Checker -> {
                val parity = (fastFloor(u / s) + fastFloor(v / s)) and 1
                val e = min(abs(centred(u / s)), abs(centred(v / s))) * s
                if (parity == 1) aa(-e) else aa(e)
            }
            MaterialKind.Grid -> {
                val w = line(0.5f)
                aa(min(abs(centred(u / s)), abs(centred(v / s))) * s - w / 2f)
            }
            MaterialKind.Bricks -> {
                val rowH = s / 2f
                val row = fastFloor(v / rowH)
                val off = if (row and 1 == 1) s / 2f else 0f
                // Beds where the row (and its offset) changes; joints between bricks.
                val joint = abs(centred((u + off) / s)) * s
                val bed = abs(centred(v / rowH)) * rowH
                aa(min(joint, bed) - line(0.25f) / 2f)
            }
            MaterialKind.Honeycomb -> {
                val q = hexLocal(u / s, v / s)
                val hx = abs(hexX(q))
                val hd = max(hx, hx * 0.5f + abs(hexY(q)) * 0.8660254f)
                aa(abs(0.5f - hd) * s - line(0.3f) / 2f)
            }
            MaterialKind.Scales -> scales(u, v)
            MaterialKind.Waves -> {
                val lambda = 2f * s
                val amp = 0.3f * s
                val ph = (2.0 * Math.PI / lambda).toFloat()
                val yy = v - amp * sin(u * ph)
                val slope = amp * ph * cos(u * ph)
                aa(abs(centred(yy / s)) * s / sqrt(1f + slope * slope) - line(0.5f) / 2f)
            }
            MaterialKind.Zigzag -> {
                val lambda = 2f * s
                val amp = 0.3f * s
                val tri = amp * (4f * abs(centred(u / lambda)) - 1f)
                val k = 4f * amp / lambda
                aa(abs(centred((v - tri) / s)) * s / sqrt(1f + k * k) - line(0.5f) / 2f)
            }
            MaterialKind.Polka -> {
                val q = hexLocal(u / s, v / s)
                val hx = hexX(q)
                val hy = hexY(q)
                val dist = sqrt(hx * hx + hy * hy) * s
                aa(dist - s * 0.5f * sqrt(t) * 0.95f)
            }
            MaterialKind.Paper -> {
                val fibres = 0.6f * fbm(px / s, py / s, 5) + 0.4f * fbm(u / (3f * s), v / (0.4f * s), 3)
                val k = ((fibres - 0.3f) / 0.6f).coerceIn(0f, 1f)
                t * k * sqrt(k)
            }
            MaterialKind.Canvas -> {
                val cu = fastFloor(u / s)
                val cv = fastFloor(v / s)
                val over = ((cu + cv) and 1) == 0
                val fu = u / s - cu
                val fv = v / s - cv
                val shade = if (over) sin(Math.PI.toFloat() * fv) else sin(Math.PI.toFloat() * fu)
                t * (0.15f + 0.85f * (1f - shade)) * (0.75f + 0.25f * noise(px / 3f, py / 3f))
            }
            MaterialKind.Grain -> {
                val c = max(1f, s / 8f)
                val h = hash(fastFloor(px / c), fastFloor(py / c))
                t * h * h
            }
            MaterialKind.Speckle -> {
                val n = fbm(px / s, py / s, 3)
                val dots = if (hash(fastFloor(px), fastFloor(py)) > 1f - 0.04f * t) 1f else 0f
                max(((n - (0.78f - 0.3f * t)) * 10f).coerceIn(0f, 1f), dots)
            }
            MaterialKind.Wood -> {
                val r = v / s + fbm(u / (6f * s), v / s, 3) * 3f
                val ring = 1f - abs(centred(r)) * 2f
                t * ring * ring * ring * (0.7f + 0.3f * fbm(u / (0.5f * s), v / (8f * s), 2))
            }
        }
    }

    /**
     * Fills [chunk] with premultiplied ARGB of [color] (alpha ignored, the
     * material is opaque ink) at each pixel's coverage, row by row into [out].
     */
    fun fill(chunk: Rect, color: Int, out: IntArray) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        var o = 0
        for (y in chunk.top until chunk.bottom) for (x in chunk.left until chunk.right) {
            val a = (coverage(x, y).coerceIn(0f, 1f) * 255f).roundToInt()
            out[o++] = if (a == 0) 0 else
                (a shl 24) or (((r * a + 127) / 255) shl 16) or (((g * a + 127) / 255) shl 8) or ((b * a + 127) / 255)
        }
    }

    /** A small picture of the material (unpremultiplied), for the library's buttons. */
    fun sample(w: Int, h: Int, color: Int): Bitmap {
        val px = IntArray(w * h)
        fill(Rect(0, 0, w, h), color, px)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a in 1..254) {
                val rr = min(255, (((c shr 16) and 0xFF) * 255 + a / 2) / a)
                val gg = min(255, (((c shr 8) and 0xFF) * 255 + a / 2) / a)
                val bb = min(255, ((c and 0xFF) * 255 + a / 2) / a)
                px[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------ shapes

    /** Anti-aliased ink from a signed distance in px (negative inside). */
    private fun aa(d: Float): Float = (0.5f - d).coerceIn(0f, 1f)

    /** Line width for outline patterns: [share] of the period at full density. */
    private fun line(share: Float): Float = max(0.6f, t * s * share)

    /**
     * Halftone dots covering [tone] of each cell. A smooth spot function, so
     * round dots grow into a checkerboard at half and then into round holes
     * with no jump (a gradation runs through every value).
     */
    private fun dots(u: Float, v: Float, tone: Float): Float {
        if (tone <= 0f) return 0f
        if (tone >= 1f) return 1f
        val k = TWO_PI / s
        val a = u * k
        val b = v * k
        val f = 0.5f + 0.25f * (cos(a) + cos(b))
        val sa = sin(a)
        val sb = sin(b)
        // Distance to the threshold line in px, from the spot function's slope.
        val grad = 0.25f * k * sqrt(sa * sa + sb * sb)
        val d = (spotThreshold(tone) - f) / max(grad, 0.05f / s)
        return aa(d)
    }

    /**
     * Offset from the nearest centre of a hexagonal grid (spacing 1), both
     * coordinates packed in one Long (chunks run on several threads at once,
     * so nothing is kept in fields).
     */
    private fun hexLocal(x: Float, y: Float): Long {
        val ry = 1.7320508f
        val ax = x - fastFloor(x) - 0.5f
        val ay = y - fastFloor(y / ry) * ry - ry / 2f
        val bxRaw = x - 0.5f
        val byRaw = y - ry / 2f
        val bx = bxRaw - fastFloor(bxRaw) - 0.5f
        val by = byRaw - fastFloor(byRaw / ry) * ry - ry / 2f
        return if (ax * ax + ay * ay < bx * bx + by * by) pack(ax, ay) else pack(bx, by)
    }

    private fun pack(a: Float, b: Float): Long =
        (java.lang.Float.floatToRawIntBits(a).toLong() shl 32) or (java.lang.Float.floatToRawIntBits(b).toLong() and 0xFFFFFFFFL)

    private fun hexX(q: Long): Float = java.lang.Float.intBitsToFloat((q ushr 32).toInt())
    private fun hexY(q: Long): Float = java.lang.Float.intBitsToFloat(q.toInt())

    /** Overlapping fish scales: each row's arcs cover the row above. */
    private fun scales(u: Float, v: Float): Float {
        val rad = s / 2f
        val rowH = s / 2f
        val w = line(0.2f)
        val j0 = fastFloor(v / rowH)
        for (j in j0 + 2 downTo j0 - 1) {
            val off = if (j and 1 == 1) s / 2f else 0f
            val cx = Math.round((u - off) / s) * s + off
            val cy = j * rowH
            val dx = u - cx
            val dy = v - cy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < rad + w / 2f) return aa(abs(dist - rad) - w / 2f)
        }
        return 0f
    }

    // ------------------------------------------------------------ noise

    private fun hash(ix: Int, iy: Int): Float {
        var h = ix * 374761393 + iy * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    private fun noise(x: Float, y: Float): Float {
        val ix = fastFloor(x)
        val iy = fastFloor(y)
        val fx = x - ix
        val fy = y - iy
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = hash(ix, iy)
        val b = hash(ix + 1, iy)
        val c = hash(ix, iy + 1)
        val d = hash(ix + 1, iy + 1)
        val top = a + (b - a) * sx
        val bottom = c + (d - c) * sx
        return top + (bottom - top) * sy
    }

    private fun fbm(x: Float, y: Float, octaves: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var f = 1f
        var norm = 0f
        for (i in 0 until octaves) {
            sum += amp * noise(x * f + i * 17.3f, y * f + i * 9.1f)
            norm += amp
            amp *= 0.5f
            f *= 2f
        }
        return sum / norm
    }

    companion object {
        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        /**
         * The spot function's values over one cell, sorted: the threshold
         * that inks a share [tone] of the cell is the value at 1 - tone.
         */
        private val spotValues: FloatArray by lazy {
            val n = 256
            val out = FloatArray(n * n)
            var i = 0
            for (y in 0 until n) for (x in 0 until n) {
                val a = TWO_PI * (x + 0.5f) / n
                val b = TWO_PI * (y + 0.5f) / n
                out[i++] = 0.5f + 0.25f * (cos(a) + cos(b))
            }
            out.sort()
            out
        }

        private fun spotThreshold(tone: Float): Float {
            val v = spotValues
            val pos = (1f - tone.coerceIn(0f, 1f)) * (v.size - 1)
            val i = pos.toInt().coerceIn(0, v.size - 2)
            val f = pos - i
            return v[i] + (v[i + 1] - v[i]) * f
        }

        private fun fastFloor(f: Float): Int {
            val i = f.toInt()
            return if (f < i) i - 1 else i
        }

        /** [f] minus its nearest whole number: -0.5 until 0.5. */
        private fun centred(f: Float): Float = f - fastFloor(f + 0.5f)

        /** Pattern period in px for the size slider (0..1) on a canvas whose longer side is [longEdge]. */
        fun sizeFor(slider: Float, longEdge: Int): Float {
            val k = slider.coerceIn(0f, 1f)
            return 3f + k * k * 0.08f * longEdge
        }
    }
}
