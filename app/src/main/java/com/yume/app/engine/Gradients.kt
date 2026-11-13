package com.yume.app.engine

import kotlin.math.sqrt

/**
 * Linear and radial gradients for the gradient tool (Stage 4.1).
 *
 * Pixels are computed here per document position instead of through a Skia
 * gradient shader: a shader is evaluated through the canvas matrix, which
 * differs per tile (the translation), and that is exactly what broke dense /
 * sparse identity for bitmap shaders in Stage 2b-4. Computing the colour from
 * the absolute document position makes every tile layout produce the same
 * bytes. Interpolation is in premultiplied space (so fading to transparent
 * does not darken), and a 4x4 ordered dither keyed on the document position
 * removes banding without any randomness.
 */
object Gradients {

    enum class Kind(val label: String) { Linear("Linear"), Radial("Radial") }

    private val BAYER = floatArrayOf(
        0f, 8f, 2f, 10f,
        12f, 4f, 14f, 6f,
        3f, 11f, 1f, 9f,
        15f, 7f, 13f, 5f
    )

    /**
     * Fills [out] (stride [w]) with unpremultiplied ARGB for the document rect
     * [left, top, left + w, top + h).
     */
    fun render(
        kind: Kind,
        x0: Float, y0: Float, x1: Float, y1: Float,
        c1: Int, c2: Int,
        left: Int, top: Int, w: Int, h: Int,
        out: IntArray
    ) {
        val a1 = (c1 ushr 24) / 255f
        val a2 = (c2 ushr 24) / 255f
        // Premultiplied endpoints.
        val r1 = ((c1 shr 16) and 0xFF) * a1
        val g1 = ((c1 shr 8) and 0xFF) * a1
        val b1 = (c1 and 0xFF) * a1
        val r2 = ((c2 shr 16) and 0xFF) * a2
        val g2 = ((c2 shr 8) and 0xFF) * a2
        val b2 = (c2 and 0xFF) * a2
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val len = sqrt(len2)
        for (j in 0 until h) {
            val y = top + j
            val py = y + 0.5f
            var o = j * w
            for (i in 0 until w) {
                val x = left + i
                val px = x + 0.5f
                var t = if (len2 <= 0f) {
                    0f
                } else if (kind == Kind.Linear) {
                    ((px - x0) * dx + (py - y0) * dy) / len2
                } else {
                    val ex = px - x0
                    val ey = py - y0
                    sqrt(ex * ex + ey * ey) / len
                }
                if (t < 0f) t = 0f else if (t > 1f) t = 1f
                val d = (BAYER[((y and 3) shl 2) or (x and 3)] + 0.5f) / 16f - 0.5f
                val ap = a1 + (a2 - a1) * t
                val a = (ap * 255f + d + 0.5f).toInt().coerceIn(0, 255)
                if (a == 0 || ap <= 0f) {
                    out[o++] = 0
                    continue
                }
                val r = ((r1 + (r2 - r1) * t) / ap + d + 0.5f).toInt().coerceIn(0, 255)
                val g = ((g1 + (g2 - g1) * t) / ap + d + 0.5f).toInt().coerceIn(0, 255)
                val b = ((b1 + (b2 - b1) * t) / ap + d + 0.5f).toInt().coerceIn(0, 255)
                out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
