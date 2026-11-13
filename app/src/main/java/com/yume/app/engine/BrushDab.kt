package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import kotlin.random.Random

/**
 * Procedurally builds the single stamp bitmap used by a brush.
 *
 * All brushes share one high resolution dab which is scaled down at paint time.
 * That keeps allocation out of the stroke hot path while still letting the
 * brush be soft, grainy or bloomed.
 */
object BrushDab {

    private const val RES = 256
    private val cache = HashMap<String, Bitmap>()
    private val previewCache = HashMap<String, Bitmap>()

    /** Cached thumbnail for the brush browser (one per dab variant/size). */
    fun cachedPreview(preset: BrushPreset, px: Int): Bitmap =
        previewCache.getOrPut("${preset.dabKey}-${preset.size.toInt()}-$px") {
            preview(preset, px)
        }

    fun dab(preset: BrushPreset): Bitmap = cache.getOrPut(preset.dabKey) {
        generate(preset.hardness.coerceIn(0f, 1f), preset.grain.coerceIn(0f, 1f), preset.glow)
    }

    private fun generate(hardness: Float, grain: Float, glow: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(RES, RES, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = RES / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (hardness >= 0.995f) {
            paint.color = Color.WHITE
            canvas.drawCircle(r, r, r - 0.5f, paint)
        } else {
            // A soft brush is a radial falloff from opaque core to a feather.
            val core = (hardness * 0.92f).coerceIn(0f, 0.92f)
            val mid = (core + (1f - core) * 0.55f).coerceIn(core, 0.99f)
            val colors = intArrayOf(
                Color.WHITE,
                Color.WHITE,
                // soft shoulder
                (0xCC shl 24) or 0x00FFFFFF,
                (0x33 shl 24) or 0x00FFFFFF,
                0x00FFFFFF
            )
            val stops = floatArrayOf(0f, core, mid, (mid + 1f) * 0.5f, 1f)
            paint.shader = RadialGradient(r, r, r, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawCircle(r, r, r, paint)
        }

        if (glow) {
            val bloom = Paint(Paint.ANTI_ALIAS_FLAG)
            bloom.shader = RadialGradient(
                r, r, r,
                intArrayOf(0x40FFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0.2f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(r, r, r, bloom)
        }

        if (grain > 0.01f) {
            applyGrain(bmp, grain)
        }
        return bmp
    }

    /**
     * Multiplies a low frequency noise texture into the dab's alpha channel so
     * the brush deposits a dry, broken edge.
     */
    private fun applyGrain(target: Bitmap, amount: Float) {
        val noiseRes = 64
        val noise = Bitmap.createBitmap(noiseRes, noiseRes, Bitmap.Config.ARGB_8888)
        val rnd = Random(target.hashCode().toLong())
        val pixels = IntArray(noiseRes * noiseRes)
        for (i in pixels.indices) {
            val n = rnd.nextFloat()
            // Bias the noise so most of the dab stays opaque.
            val a = (255f * (1f - amount * n * 1.35f)).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or 0x00FFFFFF
        }
        noise.setPixels(pixels, 0, noiseRes, 0, 0, noiseRes, noiseRes)

        // Upscale the noise so grain reads as texture rather than per-pixel noise.
        val scaled = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        val sc = Canvas(scaled)
        val sp = Paint(Paint.FILTER_BITMAP_FLAG)
        sp.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        // wrap in a shader-matrix so the tile covers 1/4 of the dab
        sp.shader.setLocalMatrix(android.graphics.Matrix().apply {
            setScale(target.width / 64f * 2f, target.height / 64f * 2f)
        })
        sc.drawRect(0f, 0f, target.width.toFloat(), target.height.toFloat(), sp)

        val paint = Paint()
        paint.blendMode = BlendMode.DST_IN
        Canvas(target).drawBitmap(scaled, 0f, 0f, paint)
        noise.recycle()
        scaled.recycle()
    }

    /** Small preview bitmap of a brush, for the brush browser. */
    fun preview(preset: BrushPreset, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dab = dab(preset)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.alpha = 235
        val inset = px * 0.12f
        val dst = Rect()
        val radius = px * 0.5f - inset
        val cx = px * 0.5f
        val cy = px * 0.5f
        dst.set(
            (cx - radius).toInt(), (cy - radius).toInt(),
            (cx + radius).toInt(), (cy + radius).toInt()
        )
        c.drawBitmap(dab, null, dst, p)
        return bmp
    }
}
