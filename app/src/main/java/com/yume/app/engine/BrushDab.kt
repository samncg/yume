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
    @Synchronized
    fun cachedPreview(preset: BrushPreset, px: Int): Bitmap =
        previewCache.getOrPut("${preset.dabKey}-${preset.size.toInt()}-$px") {
            preview(preset, px)
        }

    @Synchronized
    fun dab(preset: BrushPreset): Bitmap = cache.getOrPut(preset.dabKey) {
        generate(
            preset.hardness.coerceIn(0f, 1f), preset.grain.coerceIn(0f, 1f), preset.glow,
            BrushAssets.shape(preset.shapeId), BrushAssets.grain(preset.grainId)
        ).also { if (preset.wetEdges > 0f) applyWetEdges(it, preset.wetEdges.coerceIn(0f, 1f)) }
    }

    /**
     * Wet edges: the dab's centre is thinned and its rim kept, so overlapping
     * dabs build a stroke with a darker border, like watercolour drying with
     * its pigment pushed to the edge.
     */
    private fun applyWetEdges(bmp: Bitmap, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val rr = minOf(cx, cy).coerceAtLeast(1f)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val a = px[i] ushr 24
                if (a == 0) continue
                val d = (kotlin.math.hypot(x - cx, y - cy) / rr).coerceIn(0f, 1f)
                // 0 at the centre, rising to 1 over the outer third.
                val rim = ((d - 0.45f) / 0.4f).coerceIn(0f, 1f)
                val rimS = rim * rim * (3f - 2f * rim)
                val f = 1f - amount * 0.9f * (1f - rimS)
                val na = (a * f).toInt().coerceIn(0, 255)
                px[i] = (na shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun generate(
        hardness: Float, grain: Float, glow: Boolean,
        shape: Bitmap? = null, grainTexture: Bitmap? = null
    ): Bitmap {
        val bmp = Bitmap.createBitmap(RES, RES, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = RES / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (shape != null) {
            // An imported stamp: its coverage is the dab.
            paint.isFilterBitmap = true
            canvas.drawBitmap(shape, null, android.graphics.RectF(0f, 0f, RES.toFloat(), RES.toFloat()), paint)
            if (grainTexture != null) {
                applyGrain(bmp, if (grain > 0.01f) grain else 0.6f, grainTexture)
            } else if (grain > 0.01f) {
                applyGrain(bmp, grain)
            }
            return bmp
        }
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

        if (grainTexture != null) {
            applyGrain(bmp, if (grain > 0.01f) grain else 0.6f, grainTexture)
        } else if (grain > 0.01f) {
            applyGrain(bmp, grain)
        }
        return bmp
    }

    /** An imported grain texture, tiled across the dab and mixed by [amount]. */
    private fun applyGrain(target: Bitmap, amount: Float, texture: Bitmap) {
        val tiled = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tiled)
        // Coverage 1 where the texture is clear, (1 - amount) where it is full.
        tc.drawColor(Color.WHITE)
        val sp = Paint(Paint.FILTER_BITMAP_FLAG)
        sp.shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        sp.alpha = (amount.coerceIn(0f, 1f) * 255f).toInt()
        sp.blendMode = BlendMode.DST_OUT
        tc.drawRect(0f, 0f, target.width.toFloat(), target.height.toFloat(), sp)
        val paint = Paint()
        paint.blendMode = BlendMode.DST_IN
        Canvas(target).drawBitmap(tiled, 0f, 0f, paint)
        tiled.recycle()
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
