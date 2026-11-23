package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Rect
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
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
 *
 * Chunks are computed in parallel; reading the source and writing results
 * stay on the calling thread. A chunk whose whole source is one opaque colour
 * (or empty) is left as it is: both kernels map a constant to itself.
 */
object Kernels {

    private const val CHUNK = 256

    /** Reads unpremultiplied ARGB of [rect] (inside the canvas) into [out]. */
    fun interface Source {
        fun read(rect: Rect, out: IntArray)

        /**
         * True when every pixel of [rect] is known, without reading it, to be
         * one opaque colour or transparent (so the kernels leave it unchanged).
         */
        fun flat(rect: Rect): Boolean = false
    }

    /** Computes premultiplied output for [chunk] from premultiplied [src] covering [pad]. */
    private fun interface Op {
        fun apply(src: IntArray, pad: Rect, chunk: Rect, out: IntArray)
    }

    private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private val pool by lazy {
        Executors.newFixedThreadPool(THREADS) { r ->
            Thread(r, "yume-kernel").apply { isDaemon = true }
        }
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

    private class Slot {
        val chunk = Rect()
        val pad = Rect()
        var src = IntArray(0)
        val out = IntArray(CHUNK * CHUNK)
        var skip = false
    }

    /**
     * Runs [op] over [bounds] in chunks, reading from [source] and writing the
     * result through [write] (chunk rect, chunk bitmap of exactly that size).
     * Reports progress through [OpCancel.progress] and stops on a cancel.
     */
    private fun run(
        width: Int, height: Int, bounds: Rect, halo: Int,
        source: Source, op: Op, write: (Rect, Bitmap) -> Unit,
        /** Leave flat chunks alone (false for ops that change flat colour). */
        skipFlat: Boolean = true
    ) {
        val b = Rect(bounds)
        if (!b.intersect(0, 0, width, height)) return
        val cols = (b.width() + CHUNK - 1) / CHUNK
        val rows = (b.height() + CHUNK - 1) / CHUNK
        val total = cols * rows
        val slots = Array(THREADS * 2) { Slot() }
        val jobs = ArrayList<Future<*>>(slots.size)
        var done = 0
        var used = 0

        fun compute(s: Slot) {
            val n = s.pad.width() * s.pad.height()
            val src = s.src
            for (i in 0 until n) src[i] = premul(src[i])
            // One opaque colour (or nothing) everywhere: unchanged.
            val v = src[0]
            if (skipFlat && ((v ushr 24) == 255 || v == 0)) {
                var i = 1
                while (i < n && src[i] == v) i++
                if (i == n) {
                    s.skip = true
                    return
                }
            }
            op.apply(src, s.pad, s.chunk, s.out)
            val m = s.chunk.width() * s.chunk.height()
            val out = s.out
            for (i in 0 until m) out[i] = unpremul(out[i])
        }

        fun flushBatch() {
            if (used == 0) return
            // Reads on this thread (sources are not thread-safe).
            for (k in 0 until used) {
                OpCancel.check()
                val s = slots[k]
                if (skipFlat && source.flat(s.pad)) {
                    s.skip = true
                    continue
                }
                s.skip = false
                val n = s.pad.width() * s.pad.height()
                if (s.src.size < n) s.src = IntArray(n)
                source.read(s.pad, s.src)
            }
            jobs.clear()
            for (k in 0 until used) {
                val s = slots[k]
                if (!s.skip) jobs.add(pool.submit { compute(s) })
            }
            try {
                for (j in jobs) j.get()
            } catch (e: ExecutionException) {
                for (j in jobs) j.cancel(false)
                throw e.cause ?: e
            }
            for (k in 0 until used) {
                val s = slots[k]
                if (s.skip) continue
                val cw = s.chunk.width()
                val ch = s.chunk.height()
                val bmp = TileOps.scratch(cw, ch)
                bmp.setPixels(s.out, 0, cw, 0, 0, cw, ch)
                write(Rect(s.chunk), bmp)
            }
            done += used
            used = 0
            OpCancel.progress = done.toFloat() / total
        }

        var y = b.top
        while (y < b.bottom) {
            val ch = min(CHUNK, b.bottom - y)
            var x = b.left
            while (x < b.right) {
                val cw = min(CHUNK, b.right - x)
                val s = slots[used++]
                s.chunk.set(x, y, x + cw, y + ch)
                s.pad.set(
                    max(0, x - halo), max(0, y - halo),
                    min(width, x + cw + halo), min(height, y + ch + halo)
                )
                if (used == slots.size) flushBatch()
                x += cw
            }
            y += ch
        }
        flushBatch()
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

    /** Per-thread buffers of the segment path. */
    private class SegWork {
        var p1 = LongArray(0)
        var p2 = LongArray(0)
        var tsrc = IntArray(0)
        var tout = IntArray(0)
    }

    private val segWork = ThreadLocal.withInitial { SegWork() }

    /**
     * Motion blur: the average of 2 * radius + 1 samples along [angleDeg],
     * at integer offsets round(k * cos), round(k * sin), on premultiplied
     * colour and alpha.
     *
     * The samples of one row (nearly horizontal angles) or one column (nearly
     * vertical ones) are always a run of neighbouring pixels, so near those
     * angles each run is read from prefix sums: a few reads per pixel instead
     * of one per sample. The sums are the same integers either way, so the
     * result is identical to sampling one by one.
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
        val horizontal = abs(cos(rad)) >= abs(sin(rad))
        // Runs along the major axis: (minor offset, first, last).
        val segs = segmentsOf(if (horizontal) ox else oy, if (horizontal) oy else ox)
        val useSegs = segs != null && segs.size / 3 * 2 < n
        val op: Op = if (useSegs) {
            val s = segs!!
            if (horizontal) Op { src, pad, chunk, out ->
                segmentPass(
                    src, pad.width(), pad.height(), pad.left, pad.top,
                    chunk.left, chunk.top, chunk.width(), chunk.height(),
                    width, height, s, n, out, segWork.get()!!
                )
            } else Op { src, pad, chunk, out ->
                // Transpose, run the row pass, transpose back.
                val w = segWork.get()!!
                val pw = pad.width()
                val ph = pad.height()
                if (w.tsrc.size < pw * ph) w.tsrc = IntArray(pw * ph)
                val t = w.tsrc
                for (yy in 0 until ph) {
                    val row = yy * pw
                    for (xx in 0 until pw) t[xx * ph + yy] = src[row + xx]
                }
                val cw = chunk.width()
                val ch = chunk.height()
                if (w.tout.size < cw * ch) w.tout = IntArray(cw * ch)
                val to = w.tout
                segmentPass(
                    t, ph, pw, pad.top, pad.left,
                    chunk.top, chunk.left, ch, cw,
                    height, width, s, n, to, w
                )
                for (xx in 0 until cw) {
                    val row = xx * ch
                    for (yy in 0 until ch) out[yy * cw + xx] = to[row + yy]
                }
            }
        } else {
            tapsOp(width, height, ox, oy, n)
        }
        run(width, height, bounds, radius, source, op, write)
    }

    /**
     * Groups the samples by their minor-axis offset into runs of major-axis
     * offsets, flattened as (minor, lo, hi) triples. Null if a group is not a
     * run of consecutive offsets (never for a straight line, but checked).
     */
    private fun segmentsOf(major: IntArray, minor: IntArray): IntArray? {
        val map = java.util.TreeMap<Int, IntArray>()
        for (t in major.indices) {
            val e = map.getOrPut(minor[t]) { intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE, 0) }
            e[0] = min(e[0], major[t])
            e[1] = max(e[1], major[t])
            e[2]++
        }
        val out = IntArray(map.size * 3)
        var i = 0
        for ((m, e) in map) {
            if (e[1] - e[0] + 1 != e[2]) return null
            out[i++] = m
            out[i++] = e[0]
            out[i++] = e[1]
        }
        return out
    }

    /**
     * Row-run pass. [src] is premultiplied, [pw] x [ph], at ([px], [py]) in a
     * [cW] x [cH] canvas; output for the [cw] x [ch] chunk at ([cx], [cy]).
     * Each run (dy, lo, hi) adds the pixels x + lo .. x + hi of row y + dy,
     * with columns and rows outside the canvas clamped to its edge.
     */
    private fun segmentPass(
        src: IntArray, pw: Int, ph: Int, px: Int, py: Int,
        cx: Int, cy: Int, cw: Int, ch: Int,
        cW: Int, cH: Int, segs: IntArray, n: Int, out: IntArray, w: SegWork
    ) {
        val stride = pw + 1
        val size = ph * stride
        if (w.p1.size < size) {
            w.p1 = LongArray(size)
            w.p2 = LongArray(size)
        }
        val p1 = w.p1
        val p2 = w.p2
        // Prefix sums per row: p1 = red << 32 | blue, p2 = alpha << 32 | green.
        for (yy in 0 until ph) {
            var s1 = 0L
            var s2 = 0L
            val base = yy * stride
            val row = yy * pw
            p1[base] = 0L
            p2[base] = 0L
            for (xx in 0 until pw) {
                val c = src[row + xx]
                s1 += (((c shr 16) and 0xFF).toLong() shl 32) or (c and 0xFF).toLong()
                s2 += ((c ushr 24).toLong() shl 32) or ((c shr 8) and 0xFF).toLong()
                p1[base + xx + 1] = s1
                p2[base + xx + 1] = s2
            }
        }
        val k = segs.size / 3
        val half = n / 2
        val mask = 0xFFFFFFFFL
        var o = 0
        for (y in cy until cy + ch) {
            for (x in cx until cx + cw) {
                var s1 = 0L
                var s2 = 0L
                var j = 0
                while (j < k) {
                    val sy = (y + segs[j * 3]).coerceIn(0, cH - 1) - py
                    val a0 = x + segs[j * 3 + 1]
                    val a1 = x + segs[j * 3 + 2]
                    j++
                    val base = sy * stride
                    val c0 = if (a0 < 0) 0 else a0
                    val c1 = if (a1 > cW - 1) cW - 1 else a1
                    if (c0 <= c1) {
                        s1 += p1[base + c1 - px + 1] - p1[base + c0 - px]
                        s2 += p2[base + c1 - px + 1] - p2[base + c0 - px]
                    }
                    if (a0 < 0) {
                        val cnt = (min(a1, -1) - a0 + 1).toLong()
                        val e = src[sy * pw + (0 - px)]
                        s1 += cnt * ((((e shr 16) and 0xFF).toLong() shl 32) or (e and 0xFF).toLong())
                        s2 += cnt * (((e ushr 24).toLong() shl 32) or ((e shr 8) and 0xFF).toLong())
                    }
                    if (a1 > cW - 1) {
                        val cnt = (a1 - max(a0, cW) + 1).toLong()
                        val e = src[sy * pw + (cW - 1 - px)]
                        s1 += cnt * ((((e shr 16) and 0xFF).toLong() shl 32) or (e and 0xFF).toLong())
                        s2 += cnt * (((e ushr 24).toLong() shl 32) or ((e shr 8) and 0xFF).toLong())
                    }
                }
                val a = (((s2 ushr 32).toInt()) + half) / n
                val g = min(a, ((s2 and mask).toInt() + half) / n)
                val r = min(a, ((s1 ushr 32).toInt() + half) / n)
                val b = min(a, ((s1 and mask).toInt() + half) / n)
                out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** One read per sample; a chunk that needs no clamping skips the clamps. */
    private fun tapsOp(width: Int, height: Int, ox: IntArray, oy: IntArray, n: Int): Op {
        val minX = ox.min()
        val maxX = ox.max()
        val minY = oy.min()
        val maxY = oy.max()
        val half = n / 2
        return Op { src, pad, chunk, out ->
            val pw = pad.width()
            val inside = chunk.left + minX >= 0 && chunk.right - 1 + maxX <= width - 1 &&
                chunk.top + minY >= 0 && chunk.bottom - 1 + maxY <= height - 1
            var o = 0
            if (inside) {
                val d = IntArray(n) { oy[it] * pw + ox[it] }
                for (y in chunk.top until chunk.bottom) {
                    var base = (y - pad.top) * pw + (chunk.left - pad.left)
                    for (x in chunk.left until chunk.right) {
                        // Two channels per int: 97 * 255 fits in 16 bits.
                        var rb = 0
                        var ag = 0
                        for (t in 0 until n) {
                            val c = src[base + d[t]]
                            rb += c and 0xFF00FF
                            ag += (c ushr 8) and 0xFF00FF
                        }
                        base++
                        val a = ((ag ushr 16) + half) / n
                        val g = min(a, ((ag and 0xFFFF) + half) / n)
                        val r = min(a, ((rb ushr 16) + half) / n)
                        val b = min(a, ((rb and 0xFFFF) + half) / n)
                        out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            } else {
                for (y in chunk.top until chunk.bottom) {
                    for (x in chunk.left until chunk.right) {
                        var rb = 0
                        var ag = 0
                        for (t in 0 until n) {
                            val sx = (x + ox[t]).coerceIn(0, width - 1) - pad.left
                            val sy = (y + oy[t]).coerceIn(0, height - 1) - pad.top
                            val c = src[sy * pw + sx]
                            rb += c and 0xFF00FF
                            ag += (c ushr 8) and 0xFF00FF
                        }
                        val a = ((ag ushr 16) + half) / n
                        val g = min(a, ((ag and 0xFFFF) + half) / n)
                        val r = min(a, ((rb ushr 16) + half) / n)
                        val b = min(a, ((rb and 0xFFFF) + half) / n)
                        out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
    }

    /** Source reading a dense snapshot bitmap. */
    // ================================================================ effects
    //
    // Procreate-style effects, on the same exact chunked footing: every output
    // pixel is a function of source pixels at absolute positions, so dense and
    // sparse stores agree. [unit] scales every distance (1 at full size; the
    // preview's scale on its smaller copy), so the preview looks the same.

    private fun mix32(v0: Int): Int {
        var v = v0
        v = v xor (v ushr 16)
        v *= -0x7a143595
        v = v xor (v ushr 13)
        v *= -0x3d4d51cb
        v = v xor (v ushr 16)
        return v
    }

    private fun luma(c: Int): Int =
        (((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19) shr 8

    /** Per-thread buffers of [bloom]: four channel planes and a scratch line. */
    private class BloomWork {
        var planes = Array(4) { IntArray(0) }
        var line = IntArray(0)
        var tmp = IntArray(0)
    }

    private val bloomWork = ThreadLocal.withInitial { BloomWork() }

    private fun screen(base: Int, glow0: Int, k256: Int): Int {
        val g = min(255, (glow0 * k256) shr 8)
        return min(255, base + g - base * g / 255)
    }

    /** Halo [bloom] needs at [amount]. */
    fun bloomHalo(amount: Float, unit: Float = 1f): Int = 2 * bloomRadius(amount, unit) + 2

    private fun bloomRadius(amount: Float, unit: Float): Int =
        max(1, ((4f + 28f * amount.coerceIn(0f, 1f)) * unit).roundToInt())

    /**
     * Bloom: the bright parts (above about 55% brightness) blurred (two box
     * passes) and screened back over the picture, so light glows into its
     * surroundings (also onto transparent areas).
     */
    fun bloom(
        width: Int, height: Int, bounds: Rect, amount: Float, unit: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (amount <= 0.001f) return
        val r = bloomRadius(amount, unit)
        val k256 = (amount.coerceIn(0f, 1f) * 1.3f * 256f).roundToInt()
        run(width, height, bounds, 2 * r + 2, source, { src, pad, chunk, out ->
            val pw = pad.width()
            val ph = pad.height()
            val n = pw * ph
            // Bright pass, premultiplied, per channel (a, r, g, b), in
            // buffers kept per thread (a chunk's planes are 2 MB).
            val work = bloomWork.get()!!
            if (work.planes[0].size < n) work.planes = Array(4) { IntArray(n) }
            val ch = work.planes
            for (a in ch) java.util.Arrays.fill(a, 0, n, 0)
            val m = max(pw, ph) + 1
            if (work.tmp.size < m) {
                work.tmp = IntArray(m)
                work.line = IntArray(m)
            }
            val tmp = work.tmp
            val line = work.line
            for (i in 0 until n) {
                val c = src[i]
                val a = c ushr 24
                if (a == 0) continue
                val l = luma(c) * 255 / a
                val w = ((l - 140) * 256 / 115).coerceIn(0, 256)
                if (w == 0) continue
                ch[0][i] = (a * w) shr 8
                ch[1][i] = (((c shr 16) and 0xFF) * w) shr 8
                ch[2][i] = (((c shr 8) and 0xFF) * w) shr 8
                ch[3][i] = ((c and 0xFF) * w) shr 8
            }
            fun boxRows(a: IntArray) {
                val row = line
                for (y in 0 until ph) {
                    val o = y * pw
                    tmp[0] = 0
                    for (x in 0 until pw) tmp[x + 1] = tmp[x] + a[o + x]
                    for (x in 0 until pw) {
                        val lo = max(0, x - r)
                        val hi = min(pw - 1, x + r)
                        row[x] = (tmp[hi + 1] - tmp[lo]) / (hi - lo + 1)
                    }
                    System.arraycopy(row, 0, a, o, pw)
                }
            }
            fun boxCols(a: IntArray) {
                val col = line
                for (x in 0 until pw) {
                    tmp[0] = 0
                    for (y in 0 until ph) tmp[y + 1] = tmp[y] + a[y * pw + x]
                    for (y in 0 until ph) {
                        val lo = max(0, y - r)
                        val hi = min(ph - 1, y + r)
                        col[y] = (tmp[hi + 1] - tmp[lo]) / (hi - lo + 1)
                    }
                    for (y in 0 until ph) a[y * pw + x] = col[y]
                }
            }
            for (a in ch) {
                boxRows(a); boxCols(a); boxRows(a); boxCols(a)
            }
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                val py = y - pad.top
                for (x in chunk.left until chunk.right) {
                    val i = py * pw + (x - pad.left)
                    val c = src[i]
                    val a = screen(c ushr 24, ch[0][i], k256)
                    val rr = min(a, screen((c shr 16) and 0xFF, ch[1][i], k256))
                    val gg = min(a, screen((c shr 8) and 0xFF, ch[2][i], k256))
                    val bb = min(a, screen(c and 0xFF, ch[3][i], k256))
                    out[o++] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }, write, skipFlat = false)
    }

    private fun glitchShift(amount: Float, unit: Float): Int =
        max(1, (amount.coerceIn(0f, 1f) * 80f * unit).roundToInt())

    private fun glitchSplit(amount: Float, unit: Float): Int =
        (amount.coerceIn(0f, 1f) * 6f * unit).roundToInt()

    /** Halo [glitch] needs at [amount]. */
    fun glitchHalo(amount: Float, unit: Float = 1f): Int = glitchShift(amount, unit) + glitchSplit(amount, unit) + 2

    /**
     * Glitch: horizontal bands slid sideways by amounts drawn from the band's
     * position (the same every time), with red and blue pulled apart in the
     * moved bands.
     */
    fun glitch(
        width: Int, height: Int, bounds: Rect, amount: Float, unit: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (amount <= 0.001f) return
        val maxShift = glitchShift(amount, unit)
        val split = glitchSplit(amount, unit)
        val share = (0.12f + 0.5f * amount.coerceIn(0f, 1f)) * 1000f
        run(width, height, bounds, glitchHalo(amount, unit), source, { src, pad, chunk, out ->
            val pw = pad.width()
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                // Bands of varying height: a coarse band picks its own split.
                val coarse = (y / (24f * unit)).toInt()
                val hc = mix32(coarse * 7919 + 17)
                val fine = if ((hc and 1) == 0) coarse * 2 + ((y / (12f * unit)).toInt() and 1) else coarse * 2
                val h = mix32(fine * 2654435 + 101)
                val moved = ((h ushr 8) % 1000) < share
                val shift = if (moved) ((h ushr 18) % (2 * maxShift + 1)) - maxShift else 0
                val sp = if (moved) split else 0
                val py = y - pad.top
                for (x in chunk.left until chunk.right) {
                    fun at(dx: Int): Int {
                        val sx = (x - shift + dx).coerceIn(0, width - 1) - pad.left
                        return src[py * pw + sx.coerceIn(0, pw - 1)]
                    }
                    if (sp == 0) {
                        out[o++] = at(0)
                        continue
                    }
                    val cr = at(-sp)
                    val cg = at(0)
                    val cb = at(sp)
                    val a = max(cg ushr 24, max(cr ushr 24, cb ushr 24))
                    out[o++] = (a shl 24) or (cr and 0xFF0000) or (cg and 0xFF00) or (cb and 0xFF)
                }
            }
        }, write, skipFlat = true)
    }

    private fun halftoneCell(amount: Float, unit: Float): Float =
        max(2f, (4f + 36f * amount.coerceIn(0f, 1f)) * unit)

    /** Halo [halftone] needs at [amount]. */
    fun halftoneHalo(amount: Float, unit: Float = 1f): Int = kotlin.math.ceil(halftoneCell(amount, unit) * 1.2f).toInt() + 2

    /**
     * Halftone: a 45-degree grid of dots, each in its cell's colour and as
     * large as the cell is dark (and opaque); between the dots, nothing.
     */
    fun halftone(
        width: Int, height: Int, bounds: Rect, amount: Float, unit: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (amount <= 0.001f) return
        val s = halftoneCell(amount, unit)
        val inv = 1f / kotlin.math.sqrt(2f)
        run(width, height, bounds, halftoneHalo(amount, unit), source, { src, pad, chunk, out ->
            val pw = pad.width()
            fun sample(fx: Float, fy: Float): Int {
                val sx = fx.toInt().coerceIn(0, width - 1).coerceIn(pad.left, pad.right - 1) - pad.left
                val sy = fy.toInt().coerceIn(0, height - 1).coerceIn(pad.top, pad.bottom - 1) - pad.top
                return src[sy * pw + sx]
            }
            var o = 0
            var lastCell = Long.MIN_VALUE
            var cx = 0f
            var cy = 0f
            var dot = 0
            var radius = 0f
            for (y in chunk.top until chunk.bottom) {
                for (x in chunk.left until chunk.right) {
                    val px = x + 0.5f
                    val py = y + 0.5f
                    val u = (px + py) * inv / s
                    val v = (py - px) * inv / s
                    val iu = kotlin.math.floor(u).toInt()
                    val iv = kotlin.math.floor(v).toInt()
                    val key = (iu.toLong() shl 32) or (iv.toLong() and 0xFFFFFFFFL)
                    if (key != lastCell) {
                        lastCell = key
                        val uc = (iu + 0.5f) * s
                        val vc = (iv + 0.5f) * s
                        cx = (uc - vc) * inv
                        cy = (uc + vc) * inv
                        // The cell's colour: five samples, premultiplied.
                        var sa = 0; var sr = 0; var sg = 0; var sb = 0
                        val q = s * 0.25f
                        for ((dx, dy) in arrayOf(0f to 0f, q to 0f, -q to 0f, 0f to q, 0f to -q)) {
                            val c = sample(cx + dx, cy + dy)
                            sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                        }
                        val a = sa / 5
                        if (a == 0) {
                            dot = 0
                            radius = 0f
                        } else {
                            val r = min(255, sr * 255 / sa)
                            val g = min(255, sg * 255 / sa)
                            val b = min(255, sb * 255 / sa)
                            val dark = (a / 255f) * (1f - 0.85f * luma((255 shl 24) or (r shl 16) or (g shl 8) or b) / 255f)
                            radius = s * 0.7072f * kotlin.math.sqrt(dark.coerceIn(0f, 1f))
                            dot = (r shl 16) or (g shl 8) or b
                        }
                    }
                    if (radius <= 0f) {
                        out[o++] = 0
                        continue
                    }
                    val d = kotlin.math.hypot(px - cx, py - cy)
                    val cov = (radius - d + 0.5f).coerceIn(0f, 1f)
                    val a = (cov * 255f + 0.5f).toInt()
                    if (a == 0) {
                        out[o++] = 0
                        continue
                    }
                    val r = (((dot shr 16) and 0xFF) * a + 127) / 255
                    val g = (((dot shr 8) and 0xFF) * a + 127) / 255
                    val b = ((dot and 0xFF) * a + 127) / 255
                    out[o++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }, write, skipFlat = false)
    }

    private fun aberrationShift(amount: Float, unit: Float): Float =
        amount.coerceIn(0f, 1f) * 32f * unit

    /** Halo [aberration] needs at [amount]. */
    fun aberrationHalo(amount: Float, unit: Float = 1f): Int =
        kotlin.math.ceil(aberrationShift(amount, unit)).toInt() + 2

    /**
     * Chromatic aberration: red pushed outwards and blue inwards from the
     * canvas centre, by more the further out (nothing at the centre).
     */
    fun aberration(
        width: Int, height: Int, bounds: Rect, amount: Float, unit: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (amount <= 0.001f) return
        val d = aberrationShift(amount, unit)
        val cx = width / 2f
        val cy = height / 2f
        val maxDist = kotlin.math.hypot(cx, cy).coerceAtLeast(1f)
        run(width, height, bounds, aberrationHalo(amount, unit), source, { src, pad, chunk, out ->
            val pw = pad.width()
            fun at(x: Int, y: Int): Int {
                val sx = x.coerceIn(0, width - 1).coerceIn(pad.left, pad.right - 1) - pad.left
                val sy = y.coerceIn(0, height - 1).coerceIn(pad.top, pad.bottom - 1) - pad.top
                return src[sy * pw + sx]
            }
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                for (x in chunk.left until chunk.right) {
                    val dx = x + 0.5f - cx
                    val dy = y + 0.5f - cy
                    val dist = kotlin.math.hypot(dx, dy)
                    if (dist < 0.5f) {
                        out[o++] = at(x, y)
                        continue
                    }
                    // Displacement grows towards the corners.
                    val k = d / maxDist
                    val ox = (dx * k).roundToInt()
                    val oy = (dy * k).roundToInt()
                    val cr = at(x - ox, y - oy)
                    val cg = at(x, y)
                    val cb = at(x + ox, y + oy)
                    val a = max(cg ushr 24, max(cr ushr 24, cb ushr 24))
                    out[o++] = (a shl 24) or (cr and 0xFF0000) or (cg and 0xFF00) or (cb and 0xFF)
                }
            }
        }, write, skipFlat = true)
    }

    /**
     * Line art extraction: paper (light pixels) turns transparent and the
     * lines stay, with their darkness as their opacity, in their own colour.
     * [amount] widens what counts as line (0: only the darkest strokes).
     */
    fun lineArt(
        width: Int, height: Int, bounds: Rect, amount: Float, unit: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (amount <= 0.001f) return
        val lo = (12f + (1f - amount.coerceIn(0f, 1f)) * 110f).roundToInt()
        val span = 90
        run(width, height, bounds, 0, source, { src, pad, chunk, out ->
            val pw = pad.width()
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                for (x in chunk.left until chunk.right) {
                    val c = src[(y - pad.top) * pw + (x - pad.left)]
                    val a = c ushr 24
                    if (a == 0) {
                        out[o++] = 0
                        continue
                    }
                    // Lightness of the colour itself (premultiplied -> straight).
                    val l = min(255, luma(c) * 255 / a)
                    val dark = 255 - l
                    val k = ((dark - lo) * 255 / span).coerceIn(0, 255)
                    val na = a * k / 255
                    if (na == 0) {
                        out[o++] = 0
                        continue
                    }
                    // Keep the line's colour: scale the premultiplied channels.
                    val r = ((c shr 16) and 0xFF) * na / a
                    val g = ((c shr 8) and 0xFF) * na / a
                    val b = (c and 0xFF) * na / a
                    out[o++] = (na shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }, write, skipFlat = false)
    }

    private fun mosaicCell(amount: Float, unit: Float): Int =
        max(2, ((2f + 62f * amount.coerceIn(0f, 1f)) * unit).roundToInt())

    /** Halo [mosaic] needs at [amount]. */
    fun mosaicHalo(amount: Float, unit: Float = 1f): Int = mosaicCell(amount, unit) + 1

    /**
     * Mosaic: square blocks on a grid fixed to the canvas, each the average of
     * the pixels it covers (premultiplied, so transparent edges stay clean).
     */
    fun mosaic(
        width: Int, height: Int, bounds: Rect, amount: Float, unit: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (amount <= 0.001f) return
        val cell = mosaicCell(amount, unit)
        run(width, height, bounds, mosaicHalo(amount, unit), source, { src, pad, chunk, out ->
            val pw = pad.width()
            var o = 0
            var lastKey = Long.MIN_VALUE
            var avg = 0
            for (y in chunk.top until chunk.bottom) {
                val by = y / cell
                for (x in chunk.left until chunk.right) {
                    val bx = x / cell
                    val key = (bx.toLong() shl 32) or (by.toLong() and 0xFFFFFFFFL)
                    if (key != lastKey) {
                        lastKey = key
                        val x0 = bx * cell
                        val y0 = by * cell
                        val x1 = min(width, x0 + cell)
                        val y1 = min(height, y0 + cell)
                        var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
                        var n = 0
                        for (yy in y0 until y1) {
                            val row = (yy - pad.top) * pw
                            for (xx in x0 until x1) {
                                val c = src[row + (xx - pad.left)]
                                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                                n++
                            }
                        }
                        avg = if (n == 0) 0 else
                            ((sa / n).toInt() shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
                    }
                    out[o++] = avg
                }
            }
        }, write, skipFlat = true)
    }

    fun bitmapSource(bmp: Bitmap) = Source { rect, out ->
        bmp.getPixels(out, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
    }

    /**
     * Source reading a sparse pre-edit tile capture. With [lazy] the capture
     * fills as the result is written (tiles not in it yet are read from the
     * store, which they still match).
     */
    fun captureSource(capture: TileCapture, lazy: Boolean = false): Source = object : Source {
        private var patch: Bitmap? = null

        override fun read(rect: Rect, out: IntArray) {
            var p = patch
            if (p == null || p.width != rect.width() || p.height != rect.height()) {
                p?.recycle()
                p = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                patch = p
            }
            TileOps.readSnapshot(capture, rect, p!!, lazy)
            p.getPixels(out, 0, rect.width(), 0, 0, rect.width(), rect.height())
        }

        override fun flat(rect: Rect): Boolean = TileOps.uniformSnapshot(capture, rect, lazy)
    }

    /** The one-sample-at-a-time motion blur, kept as the reference for tests. */
    fun motionBlurReference(
        width: Int, height: Int, bounds: Rect, radius: Int, angleDeg: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (radius <= 0) return
        val rad = Math.toRadians(angleDeg.toDouble())
        val n = radius * 2 + 1
        val ox = IntArray(n) { ((it - radius) * cos(rad)).roundToInt() }
        val oy = IntArray(n) { ((it - radius) * sin(rad)).roundToInt() }
        val b = Rect(bounds)
        if (!b.intersect(0, 0, width, height)) return
        val pad = Rect(0, 0, width, height)
        val src = IntArray(width * height)
        source.read(pad, src)
        for (i in src.indices) src[i] = premul(src[i])
        val out = IntArray(b.width() * b.height())
        var o = 0
        for (y in b.top until b.bottom) {
            for (x in b.left until b.right) {
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (t in 0 until n) {
                    val c = src[(y + oy[t]).coerceIn(0, height - 1) * width + (x + ox[t]).coerceIn(0, width - 1)]
                    sa += c ushr 24
                    sr += (c shr 16) and 0xFF
                    sg += (c shr 8) and 0xFF
                    sb += c and 0xFF
                }
                val a = (sa + n / 2) / n
                val r = min(a, (sr + n / 2) / n)
                val g = min(a, (sg + n / 2) / n)
                val bb = min(a, (sb + n / 2) / n)
                out[o++] = unpremul((a shl 24) or (r shl 16) or (g shl 8) or bb)
            }
        }
        val bmp = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, b.width(), 0, 0, b.width(), b.height())
        write(Rect(b), bmp)
        bmp.recycle()
    }
}
