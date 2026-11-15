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

    private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private val pool by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(THREADS) { r ->
            Thread(r, "yume-bleed").apply { isDaemon = true }
        }
    }

    /**
     * Runs [body] over [0, n) split into one band per core. Every pass here
     * writes disjoint rows or columns, so the result is the same as one
     * thread's, just several times sooner (a 300 px wet-bleed dab was 75 ms).
     */
    /** Set per call: only a big patch is worth handing to other cores. */
    private var parallel = false

    /** Stage timings (ns), read by the harness. */
    @JvmField var tPremul = 0L
    @JvmField var tBlur = 0L
    @JvmField var tMain = 0L

    private fun par(n: Int, body: (Int, Int) -> Unit) {
        if (!parallel || n < 32 || THREADS == 1) {
            body(0, n)
            return
        }
        // Small chunks taken from a shared counter: the big cores take more
        // of them than the little ones, instead of every dab waiting for the
        // band that landed on the slowest core.
        val chunk = max(4, n / (THREADS * 4))
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val work = Runnable {
            while (true) {
                val a = next.getAndAdd(chunk)
                if (a >= n) break
                body(a, min(n, a + chunk))
            }
        }
        val jobs = ArrayList<java.util.concurrent.Future<*>>(THREADS - 1)
        for (t in 1 until THREADS) jobs.add(pool.submit(work))
        work.run()
        try {
            for (j in jobs) j.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    // Reduced copies of the four channels, and the displacement grid.
    private var la = IntArray(0)
    private var lr = IntArray(0)
    private var lg = IntArray(0)
    private var lb = IntArray(0)
    private var gridX = FloatArray(0)
    private var gridY = FloatArray(0)

    private fun ensureLow(n: Int) {
        if (la.size >= n) return
        la = IntArray(n); lr = IntArray(n); lg = IntArray(n); lb = IntArray(n)
    }

    private fun ensureGrid(n: Int) {
        if (gridX.size >= n) return
        gridX = FloatArray(n); gridY = FloatArray(n)
    }

    /** Box-averages [src] (w x h) into [dst] (lw x lh), [f] x [f] blocks, rounded. */
    private fun downsample(src: IntArray, dst: IntArray, w: Int, h: Int, f: Int, lw: Int, lh: Int) {
        if (f == 1) {
            System.arraycopy(src, 0, dst, 0, w * h)
            return
        }
        par(lh) { v0, v1 ->
            for (v in v0 until v1) {
                val y0 = v * f
                val y1 = min(h, y0 + f)
                for (u in 0 until lw) {
                    val x0 = u * f
                    val x1 = min(w, x0 + f)
                    var sum = 0
                    for (y in y0 until y1) {
                        val row = y * w
                        for (x in x0 until x1) sum += src[row + x]
                    }
                    val cnt = (y1 - y0) * (x1 - x0)
                    dst[v * lw + u] = (sum + cnt / 2) / cnt
                }
            }
        }
    }

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
        // Below ~200 x 200 the hand-off to other threads costs more than it saves.
        parallel = n >= 40_000
        val t0 = System.nanoTime()
        par(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = px[i]
                val a = c ushr 24
                pa[i] = a
                // Rounded, not truncated: a truncation per dab is a small loss
                // that hundreds of dabs over the same spot add up to black.
                pr[i] = (((c shr 16) and 0xFF) * a + 127) / 255
                pg[i] = (((c shr 8) and 0xFF) * a + 127) / 255
                pb[i] = ((c and 0xFF) * a + 127) / 255
            }
        }
        val t1 = System.nanoTime()
        tPremul += t1 - t0
        // Diffusion radius grows with the brush and the strength.
        val rb = max(1, (radius * (0.18f + 0.3f * strength)).toInt())
        // A blur that wide is smooth: it is computed on a copy reduced so the
        // radius is ~6 px and sampled back with bilinear filtering. At 300 px
        // that is ~1/100 of the work of blurring every pixel.
        val f = max(1, rb / 6)
        val lw = (w + f - 1) / f
        val lh = (h + f - 1) / f
        val lrb = max(1, (rb + f / 2) / f)
        ensureLow(lw * lh)
        downsample(pa, la, w, h, f, lw, lh)
        downsample(pr, lr, w, h, f, lw, lh)
        downsample(pg, lg, w, h, f, lw, lh)
        downsample(pb, lb, w, h, f, lw, lh)
        val wasParallel = parallel
        parallel = lw * lh >= 40_000
        blur(la, ba, lw, lh, lrb)
        blur(lr, br, lw, lh, lrb)
        blur(lg, bg, lw, lh, lrb)
        blur(lb, bb, lw, lh, lrb)
        parallel = wasParallel

        val t2 = System.nanoTime()
        tBlur += t2 - t1
        // Irregular front: displacement noise with features about a third of
        // the brush, moving samples up to ~20% of the radius.
        val cell = max(3f, radius / 3f)
        val amp = radius * 0.2f * strength
        // The displacement is smooth over ~cell px: computed on a canvas-fixed
        // grid a quarter cell apart and interpolated (exact when g = 1).
        val g = max(2, (cell / 4f).toInt())
        val gx0 = Math.floorDiv(originX, g)
        val gy0 = Math.floorDiv(originY, g)
        val gw = Math.floorDiv(originX + w - 1, g) - gx0 + 2
        val gh = Math.floorDiv(originY + h - 1, g) - gy0 + 2
        ensureGrid(gw * gh)
        val gdx = gridX
        val gdy = gridY
        for (j in 0 until gh) {
            val cy = ((gy0 + j) * g).toFloat()
            for (i in 0 until gw) {
                val cx = ((gx0 + i) * g).toFloat()
                gdx[j * gw + i] = noise(cx / cell, cy / cell) * amp
                gdy[j * gw + i] = noise(cx / cell + 37.1f, cy / cell - 11.7f) * amp
            }
        }
        val invF = 1f / f
        val pa0 = (paint ushr 24).toFloat()
        val pR = ((paint shr 16) and 0xFF) * pa0 / 255f
        val pG = ((paint shr 8) and 0xFF) * pa0 / 255f
        val pB = (paint and 0xFF) * pa0 / 255f
        // A pixel whose weight cannot move it by one level is left alone.
        val minMask = if (strength > 0f) (0.5f / strength).toInt() else 256
        val invG = 1f / g
        val rem0 = originX - gx0 * g
        // The main pass is the heavy one: worth all cores even for mid sizes.
        parallel = n >= 8_000
        par(h) { yFrom, yTo ->
        val rowDx = FloatArray(gw)
        val rowDy = FloatArray(gw)
        for (y in yFrom until yTo) {
            // The grid rows above and below, blended once for the whole row.
            val gya = originY + y
            val qy = Math.floorDiv(gya, g)
            val ty = (gya - qy * g) * invG
            val ra = (qy - gy0) * gw
            val rbk = ra + gw
            for (k in 0 until gw) {
                rowDx[k] = gdx[ra + k] * (1f - ty) + gdx[rbk + k] * ty
                rowDy[k] = gdy[ra + k] * (1f - ty) + gdy[rbk + k] * ty
            }
            var cell0 = 0
            var rem = rem0
            for (x in 0 until w) {
                val i = y * w + x
                val m = mask[i]
                val c0 = cell0
                val r0 = rem
                if (++rem == g) {
                    rem = 0
                    cell0++
                }
                if (m <= minMask) continue
                // Displacement, bilinear on the grid (canvas fixed).
                val tx = r0 * invG
                val dx = rowDx[c0] * (1f - tx) + rowDx[c0 + 1] * tx
                val dy = rowDy[c0] * (1f - tx) + rowDy[c0 + 1] * tx
                val sx = (x + dx).toInt().coerceIn(0, w - 1)
                val sy = (y + dy).toInt().coerceIn(0, h - 1)
                // The blurred colour there, bilinear on the reduced copy.
                val u = ((sx + 0.5f) * invF - 0.5f).coerceIn(0f, (lw - 1).toFloat())
                val v = ((sy + 0.5f) * invF - 0.5f).coerceIn(0f, (lh - 1).toFloat())
                val u0 = u.toInt()
                val v0 = v.toInt()
                val u1 = min(lw - 1, u0 + 1)
                val v1 = min(lh - 1, v0 + 1)
                val fu = u - u0
                val fv = v - v0
                val j00 = v0 * lw + u0
                val j01 = v0 * lw + u1
                val j10 = v1 * lw + u0
                val j11 = v1 * lw + u1
                val w00 = (1f - fu) * (1f - fv)
                val w01 = fu * (1f - fv)
                val w10 = (1f - fu) * fv
                val w11 = fu * fv
                var sa = ba[j00] * w00 + ba[j01] * w01 + ba[j10] * w10 + ba[j11] * w11
                var sr = br[j00] * w00 + br[j01] * w01 + br[j10] * w10 + br[j11] * w11
                var sg = bg[j00] * w00 + bg[j01] * w01 + bg[j10] * w10 + bg[j11] * w11
                var sb = bb[j00] * w00 + bb[j01] * w01 + bb[j10] * w10 + bb[j11] * w11
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
        tMain += System.nanoTime() - t2
    }

    /** Two box passes (horizontal then vertical, twice): close to a gaussian. */
    private fun blur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        System.arraycopy(src, 0, dst, 0, w * h)
        val t = tmp
        repeat(2) {
            par(h) { a, b -> boxH(dst, t, w, h, r, a, b) }
            par(w) { a, b -> boxV(t, dst, w, h, r, a, b) }
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, y0: Int, y1: Int) {
        val div = 2 * r + 1
        for (y in y0 until y1) {
            val row = y * w
            var sum = 0
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = (sum + div / 2) / div
                sum += src[row + min(w - 1, x + r + 1)] - src[row + max(0, x - r)]
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, x0: Int, x1: Int) {
        val div = 2 * r + 1
        for (x in x0 until x1) {
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
