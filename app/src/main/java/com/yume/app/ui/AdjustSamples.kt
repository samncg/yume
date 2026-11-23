package com.yume.app.ui

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import com.yume.app.engine.AdjustmentKind
import com.yume.app.engine.Adjustments
import com.yume.app.engine.EffectSettings
import com.yume.app.engine.GradientMaps
import com.yume.app.engine.Kernels
import com.yume.app.engine.ToneLut

/**
 * Small pictures for the adjustments panel: one scene (sky, sun, hills,
 * ink outlines) with each adjustment applied by the same code the editor
 * uses. Made once, off the main thread, and kept for the app's life.
 */
object AdjustSamples {
    const val W = 168
    const val H = 108

    private val cache = HashMap<AdjustmentKind, Bitmap>()
    @Volatile private var made = false

    fun get(kind: AdjustmentKind): Bitmap? = synchronized(cache) { cache[kind] }

    /** Builds every sample (call off the main thread). */
    fun build() {
        if (made) return
        val base = scene()
        for (k in AdjustmentKind.entries) {
            val bmp = runCatching { sample(base, k) }.getOrNull() ?: continue
            synchronized(cache) { cache[k] = bmp }
        }
        made = true
    }

    private fun scene(): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H * 0.7f, 0xFF2B4C8C.toInt(), 0xFFF2A65A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = RadialGradient(W * 0.68f, H * 0.42f, 18f, intArrayOf(0xFFFFFDF0.toInt(), 0xFFFFE08A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(W * 0.68f, H * 0.42f, 16f, p)
        p.shader = null
        p.color = 0xFF2E6B4F.toInt()
        val hills = android.graphics.Path().apply {
            moveTo(0f, H * 0.72f)
            cubicTo(W * 0.25f, H * 0.5f, W * 0.45f, H * 0.9f, W * 0.7f, H * 0.66f)
            cubicTo(W * 0.85f, H * 0.55f, W.toFloat(), H * 0.7f, W.toFloat(), H * 0.7f)
            lineTo(W.toFloat(), H.toFloat()); lineTo(0f, H.toFloat()); close()
        }
        c.drawPath(hills, p)
        p.color = 0xFFD94F70.toInt()
        c.drawRect(W * 0.12f, H * 0.18f, W * 0.3f, H * 0.4f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0xFF14110F.toInt()
        c.drawPath(hills, p)
        c.drawCircle(W * 0.68f, H * 0.42f, 16f, p)
        c.drawRect(W * 0.12f, H * 0.18f, W * 0.3f, H * 0.4f, p)
        return b
    }

    private fun copy(b: Bitmap) = b.copy(Bitmap.Config.ARGB_8888, true)

    private fun filtered(base: Bitmap, f: ColorMatrixColorFilter): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(base, 0f, 0f, Paint().apply { colorFilter = f })
        return out
    }

    private fun kernel(base: Bitmap, run: (Kernels.Source, (Rect, Bitmap) -> Unit) -> Unit): Bitmap {
        val out = copy(base)
        val c = Canvas(out)
        val src = Paint().apply { blendMode = BlendMode.SRC }
        run(Kernels.bitmapSource(base)) { r, bmp -> c.drawBitmap(bmp, r.left.toFloat(), r.top.toFloat(), src) }
        return out
    }

    private fun lut(base: Bitmap, l: ToneLut): Bitmap = copy(base).also { l.apply(it) }

    private fun sample(base: Bitmap, k: AdjustmentKind): Bitmap {
        val all = Rect(0, 0, W, H)
        return when (k) {
            AdjustmentKind.Hue -> filtered(base, ColorMatrixColorFilter(Adjustments.hsvMatrix(110f, 1f, 1f)))
            AdjustmentKind.Saturation -> filtered(base, ColorMatrixColorFilter(Adjustments.hsvMatrix(0f, 0.15f, 1f)))
            AdjustmentKind.Brightness -> filtered(base, ColorMatrixColorFilter(Adjustments.hsvMatrix(0f, 1f, 1.45f)))
            AdjustmentKind.Blur -> copy(base).also { Adjustments.blur(it, 4) }
            AdjustmentKind.Noise -> copy(base).also { Adjustments.noise(it, 0.6f) }
            AdjustmentKind.Sharpen -> kernel(base) { s, w -> Kernels.sharpen(W, H, all, 2f, s, w) }
            AdjustmentKind.MotionBlur -> kernel(base) { s, w -> Kernels.motionBlur(W, H, all, 9, 0f, s, w) }
            AdjustmentKind.Curves -> lut(base, ToneLut.levels(0.18f, 0.82f, 1f, 0f, 1f))
            AdjustmentKind.Levels -> lut(base, ToneLut.levels(0f, 1f, 0.55f, 0.1f, 1f))
            AdjustmentKind.ColorBalance -> filtered(
                base, ColorMatrixColorFilter(floatArrayOf(
                    1.15f, 0f, 0f, 0f, 18f, 0f, 1f, 0f, 0f, 4f, 0f, 0f, 0.8f, 0f, -10f, 0f, 0f, 0f, 1f, 0f
                ))
            )
            AdjustmentKind.GradientMap -> lut(base, ToneLut.gradientMap(GradientMaps.presets.first { it.first == "Sunset" }.second))
            AdjustmentKind.Posterize -> lut(base, ToneLut.posterize(3))
            AdjustmentKind.Threshold -> lut(base, ToneLut.threshold(0.5f))
            AdjustmentKind.Bloom -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 1f, floatArrayOf(1f, 0.3f), s, w)
            }
            AdjustmentKind.Glitch -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 0.7f, floatArrayOf(0.5f, 0.55f, 0.6f), s, w)
            }
            AdjustmentKind.Halftone -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 1f, EffectSettings.defaults(k), s, w)
            }.also { composeOnWhite(it) }
            AdjustmentKind.Aberration -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 1f, floatArrayOf(0f, 0f, 0.3f), s, w)
            }
            AdjustmentKind.Mosaic -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 0.6f, FloatArray(0), s, w)
            }
            AdjustmentKind.Glow -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 0.8f, floatArrayOf(1f), s, w)
            }
            AdjustmentKind.LensFlare -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 0.8f, floatArrayOf(0.7f, 0.35f, 0.75f, 1f, 0f), s, w)
            }
            AdjustmentKind.Clouds -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 0.9f, floatArrayOf(0.55f, 0.55f, 0.5f, 0f, 0f), s, w)
            }
            AdjustmentKind.Anime -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 1f, floatArrayOf(1f, 0.8f), s, w)
            }
            AdjustmentKind.LineArt -> kernel(base) { s, w ->
                Kernels.effect(k, W, H, all, 0.2f, floatArrayOf(0.3f, 1f), s, w)
            }.also { composeOnWhite(it) }
        }
    }

    /** Effects that leave transparency are shown over white paper. */
    private fun composeOnWhite(b: Bitmap) {
        val paper = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(paper)
        c.drawColor(0xFFFFFFFF.toInt())
        c.drawBitmap(b, 0f, 0f, null)
        Canvas(b).drawBitmap(paper, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC })
        paper.recycle()
    }
}
