package com.yume.app.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Background removal helpers shared by the editor tool and the reference window. */
object CutOut {
    const val N = Segmenter.SIZE

    /**
     * The picture on the model's square grid (stretched, as IS-Net expects),
     * over white where it is transparent. Unpremultiplied ARGB.
     */
    fun grid(src: Bitmap): IntArray {
        val g = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        val c = Canvas(g)
        c.drawColor(0xFFFFFFFF.toInt())
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, N, N), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(N * N)
        g.getPixels(px, 0, N, 0, 0, N, N)
        g.recycle()
        return px
    }

    /** The matte value at document pixel ([x], [y]) of a [w] x [h] picture (bilinear). */
    fun sample(m: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val u = ((x + 0.5f) * N / w - 0.5f).coerceIn(0f, (N - 1).toFloat())
        val v = ((y + 0.5f) * N / h - 0.5f).coerceIn(0f, (N - 1).toFloat())
        val x0 = floor(u).toInt()
        val y0 = floor(v).toInt()
        val x1 = min(N - 1, x0 + 1)
        val y1 = min(N - 1, y0 + 1)
        val fx = u - x0
        val fy = v - y0
        val a = m[y0 * N + x0] * (1 - fx) + m[y0 * N + x1] * fx
        val b = m[y1 * N + x0] * (1 - fx) + m[y1 * N + x1] * fx
        return a * (1 - fy) + b * fy
    }

    /**
     * Multiplies alpha of [px] (unpremultiplied, the [rect] of a [w] x [h]
     * picture) by the matte. [maskMode]: the pixels are a mask (white with
     * coverage in alpha); otherwise picture pixels.
     */
    fun multiply(px: IntArray, rect: Rect, w: Int, h: Int, m: FloatArray) {
        val rw = rect.width()
        for (y in rect.top until rect.bottom) {
            val row = (y - rect.top) * rw
            for (x in rect.left until rect.right) {
                val i = row + (x - rect.left)
                val p = px[i]
                val a = p ushr 24
                if (a == 0) continue
                val k = sample(m, w, h, x, y)
                val na = (a * k).roundToInt().coerceIn(0, 255)
                px[i] = (na shl 24) or (p and 0x00FFFFFF)
            }
        }
    }

    /** A copy of [src] with the matte applied (preview, reference window). */
    fun applied(src: Bitmap, m: FloatArray): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        multiply(px, Rect(0, 0, w, h), w, h, m)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Which model "Auto" uses for this picture. */
    fun modelFor(subject: Int, grid: IntArray): AiModel = when (subject) {
        1 -> AiModel.SegmentIllustration
        2 -> AiModel.SegmentPhoto
        else -> if (Matte.looksDrawn(grid, N)) AiModel.SegmentIllustration else AiModel.SegmentPhoto
    }

    /** Document px -> matte grid px (the grid stretches; the larger side decides). */
    fun gridScale(w: Int, h: Int): Float = N.toFloat() / max(w, h)

    /**
     * One-shot cut-out with default settings (the reference window): picks
     * the model, runs it, refines, applies. Null when the model is missing.
     */
    fun cutOut(store: ModelStore, src: Bitmap, settings: BgSettings = BgSettings()): Bitmap? {
        val grid = grid(src)
        val model = modelFor(settings.subject, grid)
        if (!store.isReady(model)) return null
        val seg = Segmenter(store.file(model))
        val raw = seg.matte(grid)
        if (seg.lastPeak < Segmenter.NO_SUBJECT) return null
        val m = Matte.refine(raw, grid, null, settings, gridScale(src.width, src.height))
        return applied(src, m)
    }
}
