package com.yume.app.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Wet bleeding: the watercolour blender.
 *
 * Under the brush, colours diffuse into each other the way wet pigment does on
 * damp paper: the pixels are softly averaged (a two-pass box blur, which is
 * close to a gaussian), the averaged colour is fetched through a slowly varying
 * noise displacement so the bleed front is irregular instead of a clean
 * circle, and pigment spreading into lighter or empty paper pools at its rim
 * (slightly darker, more saturated edges). The brush can also carry some of
 * its own colour into the wash.
 *
 * Everything is integer / float arithmetic on a patch read from the layer, and
 * the noise is a function of absolute canvas position, so the result does not
 * depend on how the work is split or how the layer is stored.
 */
internal object WetBleed {
    // Premultiplied channels of the patch, its blur, and a scratch row.
    private var pa = IntArray(0)
    private var pr = IntArray(0)
    private var pg = IntArray(0)
    private var pb = IntArray(0)
    private var ba = IntArray(0)
    private var br = IntArray(0)
    private var bg = IntArray(0)
    private var bb = IntArray(0)
    private var tmp = IntArray(0)

    private fun ensure(n: Int) {
        if (pa.size >= n) return
        pa = IntArray(n); pr = IntArray(n); pg = IntArray(n); pb = IntArray(n)
        ba = IntArray(n); br = IntArray(n); bg = IntArray(n); bb = IntArray(n)
        tmp = IntArray(n)
    }

    /**
     * Bleeds [px] (w x h, unpremultiplied ARGB, read and written in place)
     * where [mask] (0..255 per pixel) covers it.
     *
     * @param originX canvas x of the patch's left edge (noise is canvas-fixed)
     * @param radius brush radius in pixels
     * @param strength how far each pixel moves toward the bleed (0..1)
     * @param paint brush colour (ARGB) mixed in by [paintAmount] (0..1)
     * @param keepAlpha alpha lock: colours bleed, coverage stays
     */
    @Synchronized
    fun apply(
        px: IntArray, w: Int, h: Int, mask: IntArray,
        originX: Int, originY: Int,
        radius: Float, strength: Float,
        paint: Int, paintAmount: Float,
        keepAlpha: Boolean
    ) {
        val n = w * h
        if (n <= 0) return
        ensure(n)
        for (i in 0 until n) {
            val c = px[i]
            val a = c ushr 24
            pa[i] = a
            // Rounded, not truncated: a truncation per dab is a small loss
            // that hundreds of dabs over the same spot add up to black.
            pr[i] = (((c shr 16) and 0xFF) * a + 127) / 255
            pg[i] = (((c shr 8) and 0xFF) * a + 127) / 255
            pb[i] = ((c and 0xFF) * a + 127) / 255
        }
        // Diffusion radius grows with the brush and the strength.
        val rb = max(1, (radius * (0.18f + 0.3f * strength)).toInt())
        blur(pa, ba, w, h, rb)
        blur(pr, br, w, h, rb)
        blur(pg, bg, w, h, rb)
        blur(pb, bb, w, h, rb)

        // Irregular front: displacement noise with features about a third of
        // the brush, moving samples up to ~20% of the radius.
        val cell = max(3f, radius / 3f)
        val amp = radius * 0.2f * strength
        val pa0 = (paint ushr 24).toFloat()
        val pR = ((paint shr 16) and 0xFF) * pa0 / 255f
        val pG = ((paint shr 8) and 0xFF) * pa0 / 255f
        val pB = (paint and 0xFF) * pa0 / 255f
        for (y in 0 until h) {
            val cy = (originY + y).toFloat()
            for (x in 0 until w) {
                val i = y * w + x
                val m = mask[i]
                if (m <= 0) continue
                val cx = (originX + x).toFloat()
                val dx = noise(cx / cell, cy / cell) * amp
                val dy = noise(cx / cell + 37.1f, cy / cell - 11.7f) * amp
                val sx = (x + dx).toInt().coerceIn(0, w - 1)
                val sy = (y + dy).toInt().coerceIn(0, h - 1)
                val j = sy * w + sx
                var sa = ba[j].toFloat()
                var sr = br[j].toFloat()
                var sg = bg[j].toFloat()
                var sb = bb[j].toFloat()
                // Pigment carried by the brush, in proportion to the coverage.
                if (paintAmount > 0f) {
                    val k = paintAmount * (m / 255f)
                    sa += (pa0 - sa) * k
                    sr += (pR - sr) * k
                    sg += (pG - sg) * k
                    sb += (pB - sb) * k
                }
                val wgt = (m / 255f) * strength
                val oa0 = pa[i].toFloat()
                var oa = oa0 + (sa - oa0) * wgt
                var or = pr[i] + (sr - pr[i]) * wgt
                var og = pg[i] + (sg - pg[i]) * wgt
                var ob = pb[i] + (sb - pb[i]) * wgt
                // Nothing to move here (an even wash): leave the pixel exactly
                // as it is, so going over it again never shifts its colour.
                if (kotlin.math.abs(oa - oa0) < 0.5f && kotlin.math.abs(or - pr[i]) < 0.5f &&
                    kotlin.math.abs(og - pg[i]) < 0.5f && kotlin.math.abs(ob - pb[i]) < 0.5f
                ) continue
                // Pooling: where wet pigment creeps into lighter or empty paper,
                // its edge dries a little darker. Only pigment that arrives
                // darkens (the alpha it gains), so the total is bounded: going
                // over the same wash again and again does not keep darkening it.
                val gain = ((oa - oa0) / 255f).coerceIn(0f, 1f)
                val pool = gain * 0.5f * strength
                if (pool > 0f) {
                    val f = 1f - pool * 0.3f
                    or *= f; og *= f; ob *= f
                }
                if (keepAlpha) {
                    // Same coverage, bled colour.
                    val s = if (oa > 0.5f) oa0 / oa else 0f
                    or *= s; og *= s; ob *= s; oa = oa0
                }
                val a = (oa + 0.5f).toInt().coerceIn(0, 255)
                if (a == 0) {
                    px[i] = 0
                } else {
                    val r = (or * 255f / a + 0.5f).toInt().coerceIn(0, 255)
                    val g = (og * 255f / a + 0.5f).toInt().coerceIn(0, 255)
                    val b = (ob * 255f / a + 0.5f).toInt().coerceIn(0, 255)
                    px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    /** Two box passes (horizontal then vertical, twice): close to a gaussian. */
    private fun blur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        System.arraycopy(src, 0, dst, 0, w * h)
        repeat(2) {
            boxH(dst, tmp, w, h, r)
            boxV(tmp, dst, w, h, r)
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = (sum + div / 2) / div
                sum += src[row + min(w - 1, x + r + 1)] - src[row + max(0, x - r)]
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (k in -r..r) sum += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = (sum + div / 2) / div
                sum += src[min(h - 1, y + r + 1) * w + x] - src[max(0, y - r) * w + x]
            }
        }
    }

    /** Smooth value noise in -1..1 at a canvas-fixed lattice. */
    private fun noise(x: Float, y: Float): Float {
        val ix = kotlin.math.floor(x).toInt()
        val iy = kotlin.math.floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = hash(ix, iy)
        val b = hash(ix + 1, iy)
        val c = hash(ix, iy + 1)
        val d = hash(ix + 1, iy + 1)
        val top = a + (b - a) * ux
        val bot = c + (d - c) * ux
        return top + (bot - top) * uy
    }

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }
}
