package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Non destructive-looking adjustments applied destructively to a layer, with
 * an undo entry created by the caller. All operations work in place so the
 * preview is immediate.
 */
object Adjustments {

    private fun applyFilter(target: Bitmap, filter: ColorFilter) {
        val temp = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(temp)
        val p = Paint()
        p.colorFilter = filter
        c.drawBitmap(target, 0f, 0f, p)

        val back = Canvas(target)
        val src = Paint()
        src.blendMode = BlendMode.SRC
        back.drawBitmap(temp, 0f, 0f, src)
        temp.recycle()
    }

    private fun hueMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble())
        val cosVal = cos(rad).toFloat()
        val sinVal = sin(rad).toFloat()
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        return ColorMatrix(
            floatArrayOf(
                lr + cosVal * (1 - lr) + sinVal * (-lr),
                lg + cosVal * (-lg) + sinVal * (-lg),
                lb + cosVal * (-lb) + sinVal * (1 - lb),
                0f, 0f,
                lr + cosVal * (-lr) + sinVal * 0.143f,
                lg + cosVal * (1 - lg) + sinVal * 0.140f,
                lb + cosVal * (-lb) + sinVal * -0.283f,
                0f, 0f,
                lr + cosVal * (-lr) + sinVal * -(1 - lr),
                lg + cosVal * (-lg) + sinVal * lg,
                lb + cosVal * (1 - lb) + sinVal * lb,
                0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /** Builds the colour matrix used for hue/saturation/brightness. */
    fun hsvMatrix(hue: Float, saturation: Float, brightness: Float): ColorMatrix {
        val matrix = ColorMatrix()
        if (kotlin.math.abs(hue) > 0.01f) matrix.postConcat(hueMatrix(hue))
        if (kotlin.math.abs(saturation - 1f) > 0.001f) {
            matrix.postConcat(ColorMatrix().apply { setSaturation(saturation) })
        }
        if (kotlin.math.abs(brightness - 1f) > 0.001f) {
            matrix.postConcat(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
        }
        return matrix
    }

    /** hue in degrees (-180..180), sat/bright as multipliers (0..2). */
    fun hsb(target: Bitmap, hue: Float, saturation: Float, brightness: Float) {
        applyFilter(target, ColorMatrixColorFilter(hsvMatrix(hue, saturation, brightness)))
    }

    /**
     * Quarter-resolution blurred copy used as a live blur preview. Upscaled by
     * the compositor it reads as a real blur and costs a fraction of the full
     * resolution pass. [reuse] avoids allocating a new bitmap every frame.
     */
    fun blurProxyInto(source: Bitmap, amount: Float, reuse: Bitmap?): Bitmap {
        val scale = 0.25f
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val small = if (reuse != null && !reuse.isRecycled &&
            reuse.width == w && reuse.height == h
        ) {
            reuse
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val c = Canvas(small)
        c.drawColor(0, BlendMode.CLEAR)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(
            source, null,
            android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()),
            p
        )
        val radius = (amount * 24f * scale).toInt().coerceIn(1, 10)
        blur(small, radius)
        return small
    }

    fun invert(target: Bitmap) {
        applyFilter(target, ColorMatrixColorFilter(ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )))
    }

    fun grayscale(target: Bitmap) {
        applyFilter(target, ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }))
    }

    /**
     * Adds film grain. Alpha is untouched so transparency is preserved, and the
     * offset is a hash of the absolute canvas position: the same pixel always
     * gets the same grain, however the work is split into tiles or bands.
     * [originX]/[originY] are the document coordinates of the patch's top-left.
     */
    fun noise(target: Bitmap, amount: Float, originX: Int = 0, originY: Int = 0) {
        if (amount <= 0.001f) return
        val w = target.width
        val h = target.height
        val px = IntArray(w * h)
        target.getPixels(px, 0, w, 0, 0, w, h)
        val strength = (amount * 90f).toInt().coerceIn(1, 120)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val a = c ushr 24
                if (a == 0) continue
                val n = grain(originX + x, originY + y, strength)
                val r = ((c shr 16 and 0xFF) + n).coerceIn(0, 255)
                val g = ((c shr 8 and 0xFF) + n).coerceIn(0, 255)
                val b = ((c and 0xFF) + n).coerceIn(0, 255)
                px[row + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        target.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Deterministic grain in [-strength, strength] for one canvas position. */
    private fun grain(x: Int, y: Int, strength: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return Math.floorMod(h, 2 * strength + 1) - strength
    }

    /**
     * Three-pass box blur on premultiplied pixels, which approximates a
     * gaussian and avoids colour fringing around transparent edges.
     */
    fun blur(target: Bitmap, radius: Int) {
        if (radius <= 0) return
        val w = target.width
        val h = target.height
        val px = IntArray(w * h)
        target.getPixels(px, 0, w, 0, 0, w, h)
        premultiply(px)
        val tmp = IntArray(w * h)
        val passes = 3
        for (i in 0 until passes) {
            blurHorizontal(px, tmp, w, h, radius)
            blurVertical(tmp, px, w, h, radius)
        }
        unpremultiply(px)
        target.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun premultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) {
                px[i] = 0
                continue
            }
            if (a == 255) continue
            val r = ((c shr 16 and 0xFF) * a + 127) / 255
            val g = ((c shr 8 and 0xFF) * a + 127) / 255
            val b = ((c and 0xFF) * a + 127) / 255
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun unpremultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) {
                px[i] = 0
                continue
            }
            if (a == 255) continue
            val r = (((c shr 16 and 0xFF) * 255 + a / 2) / a).coerceAtMost(255)
            val g = (((c shr 8 and 0xFF) * 255 + a / 2) / a).coerceAtMost(255)
            val b = (((c and 0xFF) * 255 + a / 2) / a).coerceAtMost(255)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun blurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val div = 2 * radius + 1
        val invDiv = 1f / div
        for (y in 0 until h) {
            val row = y * w
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val p = src[row + i.coerceIn(0, w - 1)]
                aSum += p ushr 24 and 0xFF
                rSum += p shr 16 and 0xFF
                gSum += p shr 8 and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] =
                    ((((aSum * invDiv).roundToInt()) and 0xFF) shl 24) or
                        ((((rSum * invDiv).roundToInt()) and 0xFF) shl 16) or
                        ((((gSum * invDiv).roundToInt()) and 0xFF) shl 8) or
                        (((bSum * invDiv).roundToInt()) and 0xFF)

                val out = src[row + (x - radius).coerceIn(0, w - 1)]
                val incoming = src[row + (x + radius + 1).coerceIn(0, w - 1)]
                aSum += (incoming ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                rSum += (incoming shr 16 and 0xFF) - (out shr 16 and 0xFF)
                gSum += (incoming shr 8 and 0xFF) - (out shr 8 and 0xFF)
                bSum += (incoming and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val div = 2 * radius + 1
        val invDiv = 1f / div
        for (x in 0 until w) {
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                aSum += p ushr 24 and 0xFF
                rSum += p shr 16 and 0xFF
                gSum += p shr 8 and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] =
                    ((((aSum * invDiv).roundToInt()) and 0xFF) shl 24) or
                        ((((rSum * invDiv).roundToInt()) and 0xFF) shl 16) or
                        ((((gSum * invDiv).roundToInt()) and 0xFF) shl 8) or
                        (((bSum * invDiv).roundToInt()) and 0xFF)

                val out = src[(y - radius).coerceIn(0, h - 1) * w + x]
                val incoming = src[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                aSum += (incoming ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                rSum += (incoming shr 16 and 0xFF) - (out shr 16 and 0xFF)
                gSum += (incoming shr 8 and 0xFF) - (out shr 8 and 0xFF)
                bSum += (incoming and 0xFF) - (out and 0xFF)
            }
        }
    }
}
