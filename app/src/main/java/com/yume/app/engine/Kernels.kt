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
        source: Source, op: Op, write: (Rect, Bitmap) -> Unit
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
            if ((v ushr 24) == 255 || v == 0) {
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
                if (source.flat(s.pad)) {
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
