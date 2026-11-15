package com.yume.app.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A per-pixel tone mapping baked into lookup tables: curves, levels and
 * colour balance map each channel through its own table; a gradient map
 * replaces each pixel's colour by the gradient colour at its luminance.
 * Alpha is never touched. Applied to unpremultiplied pixels by one shared
 * function, so dense layers and sparse tiles give identical results.
 */
class ToneLut private constructor(
    val r: IntArray,
    val g: IntArray,
    val b: IntArray,
    /** True: tables are indexed by luminance and give the output colour. */
    val byLuma: Boolean
) {

    fun apply(target: Bitmap) {
        val w = target.width
        val h = target.height
        val px = IntArray(w * h)
        target.getPixels(px, 0, w, 0, 0, w, h)
        applyTo(px)
        target.setPixels(px, 0, w, 0, 0, w, h)
    }

    fun applyTo(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val cr = c shr 16 and 0xFF
            val cg = c shr 8 and 0xFF
            val cb = c and 0xFF
            px[i] = if (byLuma) {
                // Rec. 709 luma, integer weights summing to 1024.
                val l = (cr * 218 + cg * 732 + cb * 74) shr 10
                (a shl 24) or (r[l] shl 16) or (g[l] shl 8) or b[l]
            } else {
                (a shl 24) or (r[cr] shl 16) or (g[cg] shl 8) or b[cb]
            }
        }
    }

    companion object {
        private fun identity() = IntArray(256) { it }

        /**
         * Curves: [master] then per-channel points, each a list of (x, y) in
         * 0..1 sorted by x. A channel runs through its own curve, then the
         * master curve (the usual order in paint apps).
         */
        fun curves(master: List<Pair<Float, Float>>, red: List<Pair<Float, Float>>,
                   green: List<Pair<Float, Float>>, blue: List<Pair<Float, Float>>): ToneLut {
            val m = curveTable(master)
            val rr = curveTable(red)
            val gg = curveTable(green)
            val bb = curveTable(blue)
            return ToneLut(
                IntArray(256) { m[rr[it]] },
                IntArray(256) { m[gg[it]] },
                IntArray(256) { m[bb[it]] },
                byLuma = false
            )
        }

        /**
         * Levels: input black/white points and gamma (0.1..10, 1 = linear),
         * then output black/white. All 0..1.
         */
        fun levels(inBlack: Float, inWhite: Float, gamma: Float, outBlack: Float, outWhite: Float): ToneLut {
            val lo = inBlack.coerceIn(0f, 0.99f)
            val hi = inWhite.coerceIn(lo + 0.01f, 1f)
            val gm = gamma.coerceIn(0.1f, 10f)
            val t = IntArray(256) { v ->
                val x = ((v / 255f - lo) / (hi - lo)).coerceIn(0f, 1f)
                val y = x.toDouble().pow(1.0 / gm).toFloat()
                ((outBlack + (outWhite - outBlack) * y) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            return ToneLut(t, t.copyOf(), t.copyOf(), byLuma = false)
        }

        /**
         * Colour balance: for shadows, midtones and highlights, a shift along
         * cyan-red, magenta-green and yellow-blue (each -1..1). [shifts] is
         * 9 values: shadows (cr, mg, yb), midtones (...), highlights (...).
         */
        fun colorBalance(shifts: FloatArray): ToneLut {
            fun channel(k: Int): IntArray = IntArray(256) { v ->
                val x = v / 255f
                // Weights of each tonal range at this level: overlapping
                // ramps that sum to about one.
                val ws = (1f - x / 0.5f).coerceIn(0f, 1f)
                val wh = ((x - 0.5f) / 0.5f).coerceIn(0f, 1f)
                val wm = (1f - abs(x - 0.5f) / 0.5f).coerceIn(0f, 1f)
                val d = shifts[k] * ws + shifts[3 + k] * wm + shifts[6 + k] * wh
                ((x + d * 0.35f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            return ToneLut(channel(0), channel(1), channel(2), byLuma = false)
        }

        /** Posterize: each channel reduced to [levels] steps (2..32). */
        fun posterize(levels: Int): ToneLut {
            val n = levels.coerceIn(2, 32)
            val t = IntArray(256) { v -> ((v * n / 256).coerceAtMost(n - 1) * 255 + (n - 1) / 2) / (n - 1) }
            return ToneLut(t, t.copyOf(), t.copyOf(), byLuma = false)
        }

        /** Threshold: black below [cut] (0..1) of the luminance, white above. */
        fun threshold(cut: Float): ToneLut {
            val c = (cut.coerceIn(0f, 1f) * 255f).toInt()
            val t = IntArray(256) { v -> if (v >= c) 255 else 0 }
            return ToneLut(t, t.copyOf(), t.copyOf(), byLuma = true)
        }

        /** Gradient map: colour stops (position 0..1, ARGB colour), sorted by position. */
        fun gradientMap(stops: List<Pair<Float, Int>>): ToneLut {
            val s = if (stops.isEmpty()) listOf(0f to 0xFF000000.toInt(), 1f to -1) else stops.sortedBy { it.first }
            val rr = IntArray(256)
            val gg = IntArray(256)
            val bb = IntArray(256)
            for (v in 0 until 256) {
                val x = v / 255f
                var k = 0
                while (k < s.size - 1 && s[k + 1].first < x) k++
                val (p0, c0) = s[k]
                val (p1, c1) = s[min(k + 1, s.size - 1)]
                val f = if (p1 - p0 < 1e-6f) 0f else ((x - p0) / (p1 - p0)).coerceIn(0f, 1f)
                fun mix(sh: Int) = (((c0 shr sh) and 0xFF) * (1f - f) + ((c1 shr sh) and 0xFF) * f + 0.5f).toInt()
                rr[v] = mix(16); gg[v] = mix(8); bb[v] = mix(0)
            }
            return ToneLut(rr, gg, bb, byLuma = true)
        }

        /** Monotone cubic (Fritsch-Carlson) through the points, as a 0..255 table. */
        fun curveTable(points: List<Pair<Float, Float>>): IntArray {
            val p = (if (points.size < 2) listOf(0f to 0f, 1f to 1f) else points).sortedBy { it.first }
            val n = p.size
            val xs = FloatArray(n) { p[it].first.coerceIn(0f, 1f) }
            val ys = FloatArray(n) { p[it].second.coerceIn(0f, 1f) }
            val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-4f, xs[it + 1] - xs[it]) }
            val m = FloatArray(n)
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            for (i in 0 until n - 1) {
                if (abs(d[i]) < 1e-6f) { m[i] = 0f; m[i + 1] = 0f; continue }
                val a = m[i] / d[i]
                val bb = m[i + 1] / d[i]
                val s = a * a + bb * bb
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * bb * d[i]
                }
            }
            return IntArray(256) { v ->
                val x = v / 255f
                val y = when {
                    x <= xs[0] -> ys[0]
                    x >= xs[n - 1] -> ys[n - 1]
                    else -> {
                        var k = 0
                        while (k < n - 2 && xs[k + 1] < x) k++
                        val h = max(1e-4f, xs[k + 1] - xs[k])
                        val t = (x - xs[k]) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] +
                            (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
                    }
                }
                (y.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            }
        }

        val IDENTITY: ToneLut get() = ToneLut(identity(), identity(), identity(), byLuma = false)
    }
}

/** Ready-made gradient maps. */
object GradientMaps {
    val presets: List<Pair<String, List<Pair<Float, Int>>>> = listOf(
        "Noir" to listOf(0f to 0xFF000000.toInt(), 1f to 0xFFFFFFFF.toInt()),
        "Sepia" to listOf(0f to 0xFF1E140C.toInt(), 0.5f to 0xFF8C6239.toInt(), 1f to 0xFFF5E6C8.toInt()),
        "Sunset" to listOf(0f to 0xFF1B0B3A.toInt(), 0.4f to 0xFFB8325C.toInt(), 0.75f to 0xFFF2994A.toInt(), 1f to 0xFFFFF0C2.toInt()),
        "Ocean" to listOf(0f to 0xFF02111F.toInt(), 0.5f to 0xFF1C6E8C.toInt(), 1f to 0xFFD6F5F2.toInt()),
        "Forest" to listOf(0f to 0xFF0C1A0F.toInt(), 0.5f to 0xFF3E7C47.toInt(), 1f to 0xFFE3F0C8.toInt()),
        "Fire" to listOf(0f to 0xFF000000.toInt(), 0.35f to 0xFF8E1A07.toInt(), 0.7f to 0xFFF29E1F.toInt(), 1f to 0xFFFFF7D6.toInt()),
        "Neon" to listOf(0f to 0xFF12002B.toInt(), 0.5f to 0xFFE0218A.toInt(), 1f to 0xFF2EF2E3.toInt()),
        "Duotone" to listOf(0f to 0xFF27264A.toInt(), 1f to 0xFFF2C14E.toInt())
    )
}
