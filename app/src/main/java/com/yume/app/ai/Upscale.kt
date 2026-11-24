package com.yume.app.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.yume.app.engine.DocumentSnapshot
import com.yume.app.engine.TileStore
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How to upscale. */
data class UpscaleOptions(
    /** 2, 3 or 4. */
    val factor: Int = 2,
    /** 0 AI fast, 1 AI detailed, 2 smooth (no AI), 3 pixel art (no AI). */
    val method: Int = 0,
    /** AI methods: 1 = the model's result, lower blends in a smooth upscale. */
    val strength: Float = 1f
) {
    val model: AiModel? get() = when (method) {
        0 -> AiModel.UpscaleFast
        1 -> AiModel.UpscaleDetailed
        else -> null
    }
}

/**
 * Upscaling into a new canvas, band by band (any size fits in memory): AI
 * methods run Real-ESRGAN x4 on one model tile row at a time, with context
 * rows above and below so bands join without a seam, then scale to the
 * chosen factor. Masks always scale smoothly.
 */
class UpscaleJob(
    private val snapshot: DocumentSnapshot,
    private val opts: UpscaleOptions,
    modelFile: java.io.File?,
    private val cancelled: () -> Boolean,
    private val progress: (Float) -> Unit
) {
    private val upscaler = modelFile?.let { Upscaler(it) }
    private val f = opts.factor.coerceIn(2, 4)
    private val w = snapshot.width
    private val h = snapshot.height
    private val stride = Upscaler.TILE - 2 * Upscaler.OVERLAP

    /** Output rows per band: one model tile row of source rows. */
    val bandRows: Int = stride * f

    private val bandsPerStore = ceil(h * f / bandRows.toFloat()).toInt()
    private val totalBands = bandsPerStore * snapshot.layers.sumOf { 1 + (if (it.maskStore != null) 1 else 0) }
    private var bandsDone = 0

    fun fill(st: TileStore, mask: Boolean, y0: Int, rows: Int, px: IntArray) {
        if (cancelled()) throw AiCancelled()
        val newW = w * f
        // The source rows this band covers, plus context.
        val sy0 = y0 / f
        val sy1 = min(h, ceil((y0 + rows) / f.toFloat()).toInt())
        val ctxTop = min(Upscaler.OVERLAP, sy0)
        val ctxBottom = min(Upscaler.OVERLAP, h - sy1)
        val readTop = sy0 - ctxTop
        val readBottom = sy1 + ctxBottom
        val sh = readBottom - readTop
        var strip: Bitmap? = null
        if (!snapshot.onOwner { strip = st.readRect(Rect(0, readTop, w, readBottom)) }) {
            throw java.io.IOException("The canvas was busy; try again")
        }
        val src = strip ?: throw java.io.IOException("Could not read the canvas")
        try {
            val spx = IntArray(w * sh)
            src.getPixels(spx, 0, w, 0, 0, w, sh)
            val empty = !mask && spx.all { (it ushr 24) == 0 }
            when {
                empty -> java.util.Arrays.fill(px, 0, newW * rows, 0)
                mask || upscaler == null -> scaleInto(src, readTop, y0, rows, px, smooth = mask || opts.method != 3)
                else -> {
                    val up = upscaler.upscale4(spx, w, sh) { _, _ -> if (cancelled()) throw AiCancelled() }
                    // 4x rows of the band itself (drop the context).
                    val bandTop4 = (sy0 - readTop) * 4
                    val bandRows4 = (sy1 - sy0) * 4
                    val ai = Bitmap.createBitmap(w * 4, bandRows4, Bitmap.Config.ARGB_8888)
                    ai.setPixels(up, bandTop4 * w * 4, w * 4, 0, 0, w * 4, bandRows4)
                    val out = if (f == 4) ai else {
                        ai.setHasMipMap(true)
                        val o = Bitmap.createBitmap(newW, (sy1 - sy0) * f, Bitmap.Config.ARGB_8888)
                        val c = Canvas(o)
                        c.drawBitmap(ai, Rect(0, 0, ai.width, ai.height), Rect(0, 0, o.width, o.height),
                            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { blendMode = android.graphics.BlendMode.SRC })
                        ai.recycle()
                        o
                    }
                    // The band's rows inside this output (sy0 * f may sit above y0).
                    val offset = y0 - sy0 * f
                    out.getPixels(px, 0, newW, 0, offset, newW, min(rows, out.height - offset))
                    out.recycle()
                    if (opts.strength < 0.999f) {
                        val smooth = IntArray(newW * rows)
                        scaleInto(src, readTop, y0, rows, smooth, smooth = true)
                        blend(px, smooth, newW * rows, opts.strength.coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            src.recycle()
        }
        bandsDone++
        progress(bandsDone.toFloat() / totalBands.coerceAtLeast(1))
    }

    /** Plain scaling of the source strip into the band (bilinear or nearest). */
    private fun scaleInto(src: Bitmap, readTop: Int, y0: Int, rows: Int, px: IntArray, smooth: Boolean) {
        val newW = w * f
        val band = Bitmap.createBitmap(newW, rows, Bitmap.Config.ARGB_8888)
        val c = Canvas(band)
        val m = android.graphics.Matrix()
        m.setScale(f.toFloat(), f.toFloat())
        m.postTranslate(0f, (readTop * f - y0).toFloat())
        val paint = Paint().apply {
            isFilterBitmap = smooth
            blendMode = android.graphics.BlendMode.SRC
        }
        c.drawBitmap(src, m, paint)
        band.getPixels(px, 0, newW, 0, 0, newW, rows)
        band.recycle()
    }

    private fun blend(a: IntArray, b: IntArray, n: Int, t: Float) {
        val u = 1f - t
        for (i in 0 until n) {
            val p = a[i]
            val q = b[i]
            fun ch(s: Int) = ((((p shr s) and 255) * t + ((q shr s) and 255) * u).roundToInt()).coerceIn(0, 255)
            a[i] = (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    companion object {
        /** Model tiles needed (for the time estimate). */
        fun tiles(snapshot: DocumentSnapshot): Int {
            val stride = Upscaler.TILE - 2 * Upscaler.OVERLAP
            val per = max(1, ceil(snapshot.width / stride.toFloat()).toInt()) * max(1, ceil(snapshot.height / stride.toFloat()).toInt())
            return per * snapshot.layers.size
        }

        /** Rough seconds per tile on this tablet class (measured). */
        fun secondsPerTile(method: Int): Float = when (method) {
            0 -> 0.18f
            1 -> 3f
            else -> 0f
        }
    }
}
