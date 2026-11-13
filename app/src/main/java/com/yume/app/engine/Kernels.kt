package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Neighbourhood filters that are exact per tile (Stage 4.1): sharpen and
 * motion blur.
 *
 * Every output pixel is an integer function of source pixels at absolute
 * document positions, with samples outside the canvas clamped to the canvas
 * edge. The work is split into 256 px chunks; each chunk reads its source
 * (the pre-edit snapshot) with a halo at least the kernel radius. A chunk's
 * result therefore does not depend on how the canvas is tiled, and the dense
 * and sparse stores, which both go through this same code, produce identical
 * bytes (stricter than the box blur, which is within 1 level).
 */
object Kernels {

    private const val CHUNK = 256

    /** Reads unpremultiplied ARGB of [rect] (inside the canvas) into [out]. */
    fun interface Source {
        fun read(rect: Rect, out: IntArray)
    }

    /** Computes premultiplied output for [chunk] from premultiplied [src] covering [pad]. */
    private fun interface Op {
        fun apply(src: IntArray, pad: Rect, chunk: Rect, out: IntArray)
    }

    private fun premul(c: Int): Int {
        val a = c ushr 24
        if (a == 255) return c
        if (a == 0) return 0
        val r = (((c shr 16) and 0xFF) * a + 127) / 255
        val g = (((c shr 8) and 0xFF) * a + 127) / 255
        val b = ((c and 0xFF) * a + 127) / 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun unpremul(c: Int): Int {
        val a = c ushr 24
        if (a == 255 || a == 0) return if (a == 0) 0 else c
        val r = min(255, (((c shr 16) and 0xFF) * 255 + a / 2) / a)
        val g = min(255, (((c shr 8) and 0xFF) * 255 + a / 2) / a)
        val b = min(255, ((c and 0xFF) * 255 + a / 2) / a)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Runs [op] over [bounds] in chunks, reading from [source] and writing the
     * result through [write] (chunk rect, chunk bitmap of exactly that size).
     */
    private fun run(
        width: Int, height: Int, bounds: Rect, halo: Int,
        source: Source, op: Op, write: (Rect, Bitmap) -> Unit
    ) {
        val b = Rect(bounds)
        if (!b.intersect(0, 0, width, height)) return
        val chunk = Rect()
        val pad = Rect()
        var src = IntArray(0)
        val out = IntArray(CHUNK * CHUNK)
        var y = b.top
        while (y < b.bottom) {
            val ch = min(CHUNK, b.bottom - y)
            var x = b.left
            while (x < b.right) {
                val cw = min(CHUNK, b.right - x)
                chunk.set(x, y, x + cw, y + ch)
                pad.set(
                    max(0, x - halo), max(0, y - halo),
                    min(width, x + cw + halo), min(height, y + ch + halo)
                )
                val n = pad.width() * pad.height()
                if (src.size < n) src = IntArray(n)
                source.read(pad, src)
                for (i in 0 until n) src[i] = premul(src[i])
                op.apply(src, pad, chunk, out)
                for (i in 0 until cw * ch) out[i] = unpremul(out[i])
                val bmp = TileOps.scratch(cw, ch)
                bmp.setPixels(out, 0, cw, 0, 0, cw, ch)
                write(Rect(chunk), bmp)
                x += cw
            }
            y += ch
        }
    }

    /**
     * Unsharp mask: colour + strength * (colour - 5x5 binomial blur), on
     * premultiplied colour with alpha unchanged (so edges do not grow halos in
     * the alpha channel). [strength] 0..2.
     */
    fun sharpen(
        width: Int, height: Int, bounds: Rect, strength: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (strength <= 0.001f) return
        val k = intArrayOf(1, 4, 6, 4, 1)
        val s256 = (strength * 256f).roundToInt()
        run(width, height, bounds, 2, source, { src, pad, chunk, out ->
            val pw = pad.width()
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                for (x in chunk.left until chunk.right) {
                    var br = 0
                    var bg = 0
                    var bb = 0
                    for (j in -2..2) {
                        val sy = (y + j).coerceIn(0, height - 1) - pad.top
                        val wy = k[j + 2]
                        for (i in -2..2) {
                            val sx = (x + i).coerceIn(0, width - 1) - pad.left
                            val c = src[sy * pw + sx]
                            val w = wy * k[i + 2]
                            br += ((c shr 16) and 0xFF) * w
                            bg += ((c shr 8) and 0xFF) * w
                            bb += (c and 0xFF) * w
                        }
                    }
                    val c = src[(y - pad.top) * pw + (x - pad.left)]
                    val a = c ushr 24
                    val r0 = (c shr 16) and 0xFF
                    val g0 = (c shr 8) and 0xFF
                    val b0 = c and 0xFF
                    // Blur sums are scaled by 256 (16 * 16).
                    val r = (r0 + ((r0 * 256 - br) * s256 shr 16)).coerceIn(0, a)
                    val g = (g0 + ((g0 * 256 - bg) * s256 shr 16)).coerceIn(0, a)
                    val b = (b0 + ((b0 * 256 - bb) * s256 shr 16)).coerceIn(0, a)
                    out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }, write)
    }

    /**
     * Motion blur: the average of 2 * radius + 1 samples along [angleDeg],
     * at integer offsets round(k * cos), round(k * sin), on premultiplied
     * colour and alpha.
     */
    fun motionBlur(
        width: Int, height: Int, bounds: Rect, radius: Int, angleDeg: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (radius <= 0) return
        val rad = Math.toRadians(angleDeg.toDouble())
        val n = radius * 2 + 1
        val ox = IntArray(n) { ((it - radius) * cos(rad)).roundToInt() }
        val oy = IntArray(n) { ((it - radius) * sin(rad)).roundToInt() }
        run(width, height, bounds, radius, source, { src, pad, chunk, out ->
            val pw = pad.width()
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                for (x in chunk.left until chunk.right) {
                    var sa = 0
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    for (t in 0 until n) {
                        val sx = (x + ox[t]).coerceIn(0, width - 1) - pad.left
                        val sy = (y + oy[t]).coerceIn(0, height - 1) - pad.top
                        val c = src[sy * pw + sx]
                        sa += c ushr 24
                        sr += (c shr 16) and 0xFF
                        sg += (c shr 8) and 0xFF
                        sb += c and 0xFF
                    }
                    val a = (sa + n / 2) / n
                    val r = min(a, (sr + n / 2) / n)
                    val g = min(a, (sg + n / 2) / n)
                    val b = min(a, (sb + n / 2) / n)
                    out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }, write)
    }

    /** Source reading a dense snapshot bitmap. */
    fun bitmapSource(bmp: Bitmap) = Source { rect, out ->
        bmp.getPixels(out, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
    }

    /** Source reading a sparse pre-edit tile capture. */
    fun captureSource(capture: TileCapture) = Source { rect, out ->
        val patch = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        TileOps.readSnapshot(capture, rect, patch)
        patch.getPixels(out, 0, rect.width(), 0, 0, rect.width(), rect.height())
        patch.recycle()
    }
}
