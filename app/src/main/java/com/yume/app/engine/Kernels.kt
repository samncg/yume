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
        var out = IntArray(0)
        var skip = false
    }

    /** Memory the work slots may hold at once (sources and results). */
    private const val SLOT_BUDGET = 96L * 1024L * 1024L

    /**
     * Runs [op] over [bounds] in chunks, reading from [source] and writing the
     * result through [write] (chunk rect, chunk bitmap of exactly that size).
     * Reports progress through [OpCancel.progress] and stops on a cancel.
     *
     * Each chunk reads [halo] px beyond itself sideways and [haloY] up and
     * down. Chunks are [chunkW] x [chunkH]; with [align] > 1 they start on
     * multiples of it (the work area grows outwards to the grid). Fewer
     * chunks are kept in flight when they are large.
     */
    private fun run(
        width: Int, height: Int, bounds: Rect, halo: Int,
        source: Source, op: Op, write: (Rect, Bitmap) -> Unit,
        /** Leave flat chunks alone (false for ops that change flat colour). */
        skipFlat: Boolean = true,
        haloY: Int = halo,
        chunkW: Int = CHUNK,
        chunkH: Int = CHUNK,
        align: Int = 1
    ) {
        val b = Rect(bounds)
        if (!b.intersect(0, 0, width, height)) return
        if (align > 1) {
            b.left = b.left / align * align
            b.top = b.top / align * align
            b.right = min(width, (b.right + align - 1) / align * align)
            b.bottom = min(height, (b.bottom + align - 1) / align * align)
        }
        val cw0 = max(1, chunkW)
        val ch0 = max(1, chunkH)
        val cols = (b.width() + cw0 - 1) / cw0
        val rows = (b.height() + ch0 - 1) / ch0
        val total = cols * rows
        val padBytes = (min(width, cw0 + 2 * halo).toLong() * min(height, ch0 + 2 * haloY) + cw0.toLong() * ch0) * 4L
        val slotCount = (SLOT_BUDGET / padBytes.coerceAtLeast(1L)).toInt().coerceIn(2, THREADS * 2)
        val slots = Array(slotCount) { Slot() }
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
            val m = s.chunk.width() * s.chunk.height()
            if (s.out.size < m) s.out = IntArray(m)
            op.apply(src, s.pad, s.chunk, s.out)
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
            // One batch can be the whole job (a preview): look again before writing.
            OpCancel.check()
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
            val ch = min(ch0, b.bottom - y)
            var x = b.left
            while (x < b.right) {
                val cw = min(cw0, b.right - x)
                val s = slots[used++]
                s.chunk.set(x, y, x + cw, y + ch)
                s.pad.set(
                    max(0, x - halo), max(0, y - haloY),
                    min(width, x + cw + halo), min(height, y + ch + haloY)
                )
                if (used == slots.size) flushBatch()
                x += cw
            }
            y += ch
        }
        flushBatch()
    }

    /** A chunk side for a kernel reaching [halo] px: bigger chunks waste less on the halo. */
    private fun chunkFor(halo: Int): Int = (2 * halo).coerceIn(CHUNK, 1024)

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
    // pixel is an integer function of source pixels at absolute positions, so
    // dense and sparse stores agree and chunking never shows. Sizes come from
    // [EffectSettings] as shares of the canvas, so the preview (a smaller
    // copy, with its own width and height) looks like the result.

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

    private fun p(params: FloatArray, i: Int, def: Float): Float = params.getOrElse(i) { def }

    /** How far each effect reads around a pixel: (sideways, up and down). */
    fun effectHalo(kind: AdjustmentKind, width: Int, height: Int, amount: Float, params: FloatArray): Pair<Int, Int> {
        val l = max(width, height)
        return when (kind) {
            AdjustmentKind.Bloom -> {
                val r = EffectSettings.bloomRadius(p(params, 0, 0.35f), l)
                val f = max(1, r / 12)
                val h = 2 * r + 4 * f + 2
                h to h
            }
            AdjustmentKind.Glitch ->
                (EffectSettings.glitchShift(p(params, 0, 0.3f), l) + EffectSettings.glitchSplit(p(params, 2, 0.3f), l) + 2) to 0
            AdjustmentKind.Halftone -> {
                val h = kotlin.math.ceil(EffectSettings.halftoneCell(amount, l) * 1.2f).toInt() + 2
                h to h
            }
            AdjustmentKind.Aberration -> {
                val h = kotlin.math.ceil(EffectSettings.aberrationShift(amount, l)).toInt() + 2
                h to h
            }
            AdjustmentKind.Mosaic -> 0 to 0
            AdjustmentKind.Glow -> effectHalo(AdjustmentKind.Bloom, width, height, amount, floatArrayOf(p(params, 0, 0.45f), 0f))
            AdjustmentKind.Anime -> (EffectSettings.animeRadius(p(params, 0, 0.5f), l) + 1).let { it to it }
            else -> 0 to 0
        }
    }

    /** The widest area [kind] can reach on a canvas this size (any setting). */
    fun maxEffectHalo(kind: AdjustmentKind, width: Int, height: Int): Int {
        val (x, y) = effectHalo(kind, width, height, 1f, EffectSettings.widest(kind))
        // Mosaic aligns its work area out to its block grid.
        val extra = if (kind == AdjustmentKind.Mosaic) EffectSettings.mosaicBlock(1f, max(width, height)) else 0
        return max(x, y) + extra
    }

    /** Runs effect [kind] at [amount] with its [params] (see [EffectSettings]). */
    fun effect(
        kind: AdjustmentKind, width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        if (amount <= 0.001f && kind != AdjustmentKind.Halftone && kind != AdjustmentKind.Mosaic) return
        when (kind) {
            AdjustmentKind.Bloom -> bloom(width, height, bounds, amount, params, source, write)
            AdjustmentKind.Glitch -> glitch(width, height, bounds, amount, params, source, write)
            AdjustmentKind.Halftone -> halftone(width, height, bounds, amount, params, source, write)
            AdjustmentKind.Aberration -> aberration(width, height, bounds, amount, params, source, write)
            AdjustmentKind.Mosaic -> mosaic(width, height, bounds, amount, source, write)
            AdjustmentKind.LineArt -> lineArt(width, height, bounds, amount, params, source, write)
            AdjustmentKind.Glow -> glow(width, height, bounds, amount, params, source, write)
            AdjustmentKind.LensFlare -> lensFlare(width, height, bounds, amount, params, source, write)
            AdjustmentKind.Clouds -> clouds(width, height, bounds, amount, params, source, write)
            AdjustmentKind.Anime -> anime(width, height, bounds, amount, params, source, write)
            else -> Unit
        }
    }

    /**
     * Bloom: the parts brighter than the threshold, blurred (two box passes
     * on a grid of cells fixed to the canvas, then smoothly interpolated) and
     * screened back over the picture, so light glows into its surroundings,
     * transparent areas too. All in integers, so any chunking gives the same.
     */
    private fun bloom(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val l = max(width, height)
        val radius = EffectSettings.bloomRadius(p(params, 0, 0.35f), l)
        val f = max(1, radius / 12)
        val rc = max(1, (radius + f / 2) / f)
        val t0 = (p(params, 1, 0.55f).coerceIn(0f, 0.95f) * 255f).roundToInt()
        val k256 = (amount.coerceIn(0f, 1f) * 2f * 256f).roundToInt()
        val halo = effectHalo(AdjustmentKind.Bloom, width, height, amount, params).first
        val chunk = chunkFor(halo)
        run(width, height, bounds, halo, source, { src, pad, ck, out ->
            val pw = pad.width()
            // Cells fully inside the pad (or cut by the canvas edge, the same
            // for every chunk), on the canvas-fixed grid.
            val cx0 = if (pad.left == 0) 0 else (pad.left + f - 1) / f
            val cy0 = if (pad.top == 0) 0 else (pad.top + f - 1) / f
            val cx1 = if (pad.right == width) (width + f - 1) / f else pad.right / f
            val cy1 = if (pad.bottom == height) (height + f - 1) / f else pad.bottom / f
            val gw = max(1, cx1 - cx0)
            val gh = max(1, cy1 - cy0)
            val planes = Array(4) { LongArray(gw * gh) }
            // Bright pass averaged per cell (x16 for precision).
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val x0 = (cx0 + gx) * f
                val y0 = (cy0 + gy) * f
                val x1 = min(width, x0 + f)
                val y1 = min(height, y0 + f)
                var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
                var cnt = 0
                for (yy in y0 until y1) {
                    val row = (yy - pad.top) * pw
                    for (xx in x0 until x1) {
                        val c = src[row + xx - pad.left]
                        cnt++
                        val a = c ushr 24
                        if (a == 0) continue
                        val lum = min(255, luma(c) * 255 / a)
                        val w = if (lum <= t0) 0 else ((lum - t0) * 256 / max(1, 255 - t0)).coerceIn(0, 256)
                        if (w == 0) continue
                        sa += (a * w).toLong(); sr += (((c shr 16) and 0xFF) * w).toLong()
                        sg += (((c shr 8) and 0xFF) * w).toLong(); sb += ((c and 0xFF) * w).toLong()
                    }
                }
                val i = gy * gw + gx
                if (cnt > 0) {
                    planes[0][i] = sa * 16 / (256L * cnt); planes[1][i] = sr * 16 / (256L * cnt)
                    planes[2][i] = sg * 16 / (256L * cnt); planes[3][i] = sb * 16 / (256L * cnt)
                }
            }
            // Two box passes each way on the cell grid (exact integer means).
            val pre = LongArray(max(gw, gh) + 1)
            val line = LongArray(max(gw, gh))
            fun pass(a: LongArray) {
                for (gy in 0 until gh) {
                    val o = gy * gw
                    pre[0] = 0
                    for (x in 0 until gw) pre[x + 1] = pre[x] + a[o + x]
                    for (x in 0 until gw) {
                        val lo = max(0, x - rc); val hi = min(gw - 1, x + rc)
                        line[x] = (pre[hi + 1] - pre[lo]) / (hi - lo + 1)
                    }
                    for (x in 0 until gw) a[o + x] = line[x]
                }
                for (gx in 0 until gw) {
                    pre[0] = 0
                    for (y in 0 until gh) pre[y + 1] = pre[y] + a[y * gw + gx]
                    for (y in 0 until gh) {
                        val lo = max(0, y - rc); val hi = min(gh - 1, y + rc)
                        line[y] = (pre[hi + 1] - pre[lo]) / (hi - lo + 1)
                    }
                    for (y in 0 until gh) a[y * gw + gx] = line[y]
                }
            }
            for (a in planes) { pass(a); pass(a) }
            // Back to pixels: bilinear between cell centres (8-bit weights).
            var o = 0
            for (y in ck.top until ck.bottom) {
                val v256 = ((y * 2 + 1) * 128 / f) - 128 // (y + 0.5) / f - 0.5, x256
                val gyA = Math.floorDiv(v256, 256)
                val fy = v256 - gyA * 256
                val ya = (gyA - cy0).coerceIn(0, gh - 1)
                val yb = (gyA + 1 - cy0).coerceIn(0, gh - 1)
                for (x in ck.left until ck.right) {
                    val u256 = ((x * 2 + 1) * 128 / f) - 128
                    val gxA = Math.floorDiv(u256, 256)
                    val fx = u256 - gxA * 256
                    val xa = (gxA - cx0).coerceIn(0, gw - 1)
                    val xb = (gxA + 1 - cx0).coerceIn(0, gw - 1)
                    val c = src[(y - pad.top) * pw + (x - pad.left)]
                    fun glow(pl: LongArray): Int {
                        val top = pl[ya * gw + xa] * (256 - fx) + pl[ya * gw + xb] * fx
                        val bot = pl[yb * gw + xa] * (256 - fx) + pl[yb * gw + xb] * fx
                        return ((top * (256 - fy) + bot * fy) / (65536L * 16)).toInt()
                    }
                    val a = screen(c ushr 24, glow(planes[0]), k256)
                    val rr = min(a, screen((c shr 16) and 0xFF, glow(planes[1]), k256))
                    val gg = min(a, screen((c shr 8) and 0xFF, glow(planes[2]), k256))
                    val bb = min(a, screen(c and 0xFF, glow(planes[3]), k256))
                    out[o++] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }, write, skipFlat = false, chunkW = chunk, chunkH = chunk)
    }

    private fun screen(base: Int, glow0: Int, k256: Int): Int {
        val g = min(255, (glow0 * k256) shr 8)
        return min(255, base + g - base * g / 255)
    }

    /**
     * Glitch: horizontal bands slid sideways by amounts drawn from the band's
     * position (the same every time), with red and blue pulled apart in the
     * moved bands. Worked in full-width strips: the long sideways reach costs
     * nothing extra.
     */
    private fun glitch(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val l = max(width, height)
        val maxShift = EffectSettings.glitchShift(p(params, 0, 0.3f), l)
        val band = EffectSettings.glitchBand(p(params, 1, 0.3f), l)
        val split = EffectSettings.glitchSplit(p(params, 2, 0.3f), l)
        val share = ((0.05f + 0.85f * amount.coerceIn(0f, 1f)) * 1000f).toInt()
        val halo = maxShift + split + 2
        run(width, height, bounds, halo, source, { src, pad, chunk, out ->
            val pw = pad.width()
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                // Bands of mixed heights: a coarse band may split in two.
                val coarse = y / (band * 2)
                val hc = mix32(coarse * 7919 + 17)
                val fine = if ((hc and 1) == 0) coarse * 2 + ((y / band) and 1) else coarse * 2
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
        }, write, skipFlat = true, haloY = 0, chunkW = min(16384, max(1, bounds.width())), chunkH = 32)
    }

    /**
     * Halftone: a grid of dots at [params]' angle, each in its cell's colour
     * (or black ink) and as large as the cell is dark and opaque; between the
     * dots, nothing.
     */
    private fun halftone(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val s = EffectSettings.halftoneCell(amount, max(width, height))
        val ang = Math.toRadians(p(params, 0, 45f).toDouble())
        val cs = kotlin.math.cos(ang).toFloat()
        val sn = kotlin.math.sin(ang).toFloat()
        val black = p(params, 1, 0f) >= 0.5f
        val halo = effectHalo(AdjustmentKind.Halftone, width, height, amount, params).first
        val chunk = chunkFor(halo)
        run(width, height, bounds, halo, source, { src, pad, ck, out ->
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
            for (y in ck.top until ck.bottom) {
                for (x in ck.left until ck.right) {
                    val px = x + 0.5f
                    val py = y + 0.5f
                    val u = (px * cs + py * sn) / s
                    val v = (-px * sn + py * cs) / s
                    val iu = kotlin.math.floor(u).toInt()
                    val iv = kotlin.math.floor(v).toInt()
                    val key = (iu.toLong() shl 32) or (iv.toLong() and 0xFFFFFFFFL)
                    if (key != lastCell) {
                        lastCell = key
                        val uc = (iu + 0.5f) * s
                        val vc = (iv + 0.5f) * s
                        cx = uc * cs - vc * sn
                        cy = uc * sn + vc * cs
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
                            val lum = luma((255 shl 24) or (r shl 16) or (g shl 8) or b)
                            val dark = (a / 255f) * (1f - (if (black) 1f else 0.85f) * lum / 255f)
                            radius = s * 0.7072f * kotlin.math.sqrt(dark.coerceIn(0f, 1f))
                            dot = if (black) 0 else (r shl 16) or (g shl 8) or b
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
        }, write, skipFlat = false, chunkW = chunk, chunkH = chunk)
    }

    /**
     * Chromatic aberration: red and blue pulled apart, either outwards from
     * the canvas centre (more towards the edges, as [params] say) or along
     * one direction.
     */
    private fun aberration(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val d = EffectSettings.aberrationShift(amount, max(width, height))
        val linear = p(params, 0, 0f) >= 0.5f
        val dirRad = Math.toRadians(p(params, 1, 0f).toDouble())
        val lx = (kotlin.math.cos(dirRad) * d).roundToInt()
        val ly = (kotlin.math.sin(dirRad) * d).roundToInt()
        val edges = p(params, 2, 1f).coerceIn(0f, 1f)
        val cx = width / 2f
        val cy = height / 2f
        val maxDist = kotlin.math.hypot(cx, cy).coerceAtLeast(1f)
        val halo = effectHalo(AdjustmentKind.Aberration, width, height, amount, params).first
        val chunk = chunkFor(halo)
        run(width, height, bounds, halo, source, { src, pad, ck, out ->
            val pw = pad.width()
            fun at(x: Int, y: Int): Int {
                val sx = x.coerceIn(0, width - 1).coerceIn(pad.left, pad.right - 1) - pad.left
                val sy = y.coerceIn(0, height - 1).coerceIn(pad.top, pad.bottom - 1) - pad.top
                return src[sy * pw + sx]
            }
            var o = 0
            for (y in ck.top until ck.bottom) {
                for (x in ck.left until ck.right) {
                    val ox: Int
                    val oy: Int
                    if (linear) {
                        ox = lx
                        oy = ly
                    } else {
                        val dx = x + 0.5f - cx
                        val dy = y + 0.5f - cy
                        val dist = kotlin.math.hypot(dx, dy)
                        if (dist < 0.5f) {
                            out[o++] = at(x, y)
                            continue
                        }
                        // Uniform at 0, growing towards the corners at 1.
                        val k = d * (1f - edges + edges * dist / maxDist) / dist
                        ox = (dx * k).roundToInt()
                        oy = (dy * k).roundToInt()
                    }
                    val cr = at(x - ox, y - oy)
                    val cg = at(x, y)
                    val cb = at(x + ox, y + oy)
                    val a = max(cg ushr 24, max(cr ushr 24, cb ushr 24))
                    out[o++] = (a shl 24) or (cr and 0xFF0000) or (cg and 0xFF00) or (cb and 0xFF)
                }
            }
        }, write, skipFlat = true, chunkW = chunk, chunkH = chunk)
    }

    /**
     * Mosaic: square blocks on a grid fixed to the canvas, each the average
     * of the pixels it covers (premultiplied, so transparent edges stay
     * clean). Chunks follow the block grid, so no block is ever split.
     */
    private fun mosaic(
        width: Int, height: Int, bounds: Rect, amount: Float,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val cell = EffectSettings.mosaicBlock(amount, max(width, height))
        val chunk = max(1, (CHUNK + cell - 1) / cell) * cell
        run(width, height, bounds, 0, source, { src, pad, ck, out ->
            val pw = pad.width()
            val cw = ck.width()
            var by = ck.top
            while (by < ck.bottom) {
                val y1 = min(ck.bottom, by + cell)
                var bx = ck.left
                while (bx < ck.right) {
                    val x1 = min(ck.right, bx + cell)
                    var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
                    for (yy in by until y1) {
                        val row = (yy - pad.top) * pw
                        for (xx in bx until x1) {
                            val c = src[row + (xx - pad.left)]
                            sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                        }
                    }
                    val n = ((y1 - by) * (x1 - bx)).toLong()
                    val avg = ((sa / n).toInt() shl 24) or ((sr / n).toInt() shl 16) or ((sg / n).toInt() shl 8) or (sb / n).toInt()
                    for (yy in by until y1) {
                        val o = (yy - ck.top) * cw
                        for (xx in bx until x1) out[o + xx - ck.left] = avg
                    }
                    bx = x1
                }
                by = y1
            }
        }, write, skipFlat = true, chunkW = chunk, chunkH = chunk, align = cell)
    }

    /**
     * Line art extraction: paper (light pixels) turns transparent and the
     * lines stay, with their darkness as their opacity, in their own colour
     * or black. [amount] widens what counts as line; softness widens the fade.
     */
    private fun lineArt(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val lo = (12f + (1f - amount.coerceIn(0f, 1f)) * 110f).roundToInt()
        val span = (20f + p(params, 0, 0.35f).coerceIn(0f, 1f) * 200f).roundToInt()
        val black = p(params, 1, 0f) >= 0.5f
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
                    val lum = min(255, luma(c) * 255 / a)
                    val k = ((255 - lum - lo) * 255 / span).coerceIn(0, 255)
                    val na = a * k / 255
                    if (na == 0) {
                        out[o++] = 0
                        continue
                    }
                    out[o++] = if (black) na shl 24 else {
                        val r = ((c shr 16) and 0xFF) * na / a
                        val g = ((c shr 8) and 0xFF) * na / a
                        val b = (c and 0xFF) * na / a
                        (na shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }, write, skipFlat = false)
    }

    // ------------------------------------------------- light and stylize

    /** Glow: the whole picture softly blurred and screened back over it. */
    private fun glow(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) = bloom(width, height, bounds, amount, floatArrayOf(p(params, 0, 0.45f), 0f), source, write)

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Lens flare at a point (a share across and down the canvas): a hot core,
     * a halo, a ring and a streak there, and coloured ghosts along the line
     * through the canvas centre. Screened over the picture.
     */
    private fun lensFlare(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val l = max(width, height)
        val cx = p(params, 0, 0.72f) * width
        val cy = p(params, 1, 0.28f) * height
        val sz = EffectSettings.flareSize(p(params, 2, 0.4f), l)
        val ghostsK = p(params, 3, 0.6f).coerceIn(0f, 1f)
        val tint = when (p(params, 4, 0f).roundToInt()) {
            1 -> floatArrayOf(0.7f, 0.85f, 1f)
            2 -> floatArrayOf(1f, 1f, 1f)
            else -> floatArrayOf(1f, 0.86f, 0.66f)
        }
        val k = amount.coerceIn(0f, 1f) * 2f
        val ox = width / 2f
        val oy = height / 2f
        // Ghosts: position along the line (0 = flare, 1 = centre), radius, strength, colour.
        val gT = floatArrayOf(0.45f, 0.8f, 1.25f, 1.55f, 1.9f)
        val gR = floatArrayOf(0.12f, 0.32f, 0.18f, 0.5f, 0.08f)
        val gI = floatArrayOf(0.22f, 0.10f, 0.18f, 0.07f, 0.28f)
        val gC = arrayOf(
            floatArrayOf(0.6f, 1f, 0.7f), floatArrayOf(0.5f, 0.7f, 1f), floatArrayOf(1f, 0.6f, 0.4f),
            floatArrayOf(0.8f, 0.5f, 1f), floatArrayOf(1f, 0.9f, 0.6f)
        )
        run(width, height, bounds, 0, source, { src, pad, chunk, out ->
            val pw = pad.width()
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                for (x in chunk.left until chunk.right) {
                    val c = src[(y - pad.top) * pw + (x - pad.left)]
                    val px = x + 0.5f
                    val py = y + 0.5f
                    val dx = px - cx
                    val dy = py - cy
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    val core = kotlin.math.exp(-(d * d) / (2f * (0.12f * sz) * (0.12f * sz)))
                    val halo = 0.35f * kotlin.math.exp(-(d * d) / (2f * sz * sz))
                    val rd = (d - 1.5f * sz) / (0.08f * sz)
                    val ring = 0.18f * kotlin.math.exp(-rd * rd)
                    val streak = 0.55f * kotlin.math.exp(-kotlin.math.abs(dy) / (0.01f * sz + 1f)) *
                        kotlin.math.exp(-kotlin.math.abs(dx) / (3f * sz))
                    var lr = (core + halo + ring + streak) * tint[0]
                    var lg = (core + halo * 0.95f + ring * 0.9f + streak) * tint[1]
                    var lb = (core + halo * 0.9f + ring + streak) * tint[2]
                    if (ghostsK > 0f) {
                        for (g in gT.indices) {
                            val gx = cx + (ox - cx) * gT[g] * 2f
                            val gy = cy + (oy - cy) * gT[g] * 2f
                            val r = gR[g] * sz
                            val e = ((px - gx) * (px - gx) + (py - gy) * (py - gy)) / (r * r)
                            if (e >= 1f) continue
                            val v = gI[g] * ghostsK * (1f - e) * (0.6f + 0.4f * e)
                            lr += v * gC[g][0]; lg += v * gC[g][1]; lb += v * gC[g][2]
                        }
                    }
                    val ir = min(255, (lr * k * 255f).roundToInt())
                    val ig = min(255, (lg * k * 255f).roundToInt())
                    val ib = min(255, (lb * k * 255f).roundToInt())
                    val il = max(ir, max(ig, ib))
                    val a0 = c ushr 24
                    val a = min(255, a0 + il - a0 * il / 255)
                    fun scr(b: Int, li: Int) = min(a, b + li - b * li / 255)
                    out[o++] = (a shl 24) or (scr((c shr 16) and 0xFF, ir) shl 16) or
                        (scr((c shr 8) and 0xFF, ig) shl 8) or scr(c and 0xFF, ib)
                }
            }
        }, write, skipFlat = false)
    }

    private fun lattice(ix: Int, iy: Int, seed: Int): Float =
        (mix32(ix * 73856093 xor iy * 19349663 xor seed * 83492791) ushr 8) / 16777216f

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val ix = kotlin.math.floor(x).toInt()
        val iy = kotlin.math.floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = lattice(ix, iy, seed)
        val b = lattice(ix + 1, iy, seed)
        val c = lattice(ix, iy + 1, seed)
        val d = lattice(ix + 1, iy + 1, seed)
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
    }

    /**
     * Clouds: fractal noise fixed to the canvas, thresholded by coverage with
     * soft edges, painted over the picture in white (or storm grey).
     */
    private fun clouds(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val cell = EffectSettings.cloudCell(p(params, 0, 0.4f), max(width, height))
        val cover = p(params, 1, 0.5f).coerceIn(0f, 1f)
        val soft = 0.02f + p(params, 2, 0.5f).coerceIn(0f, 1f) * 0.3f
        val seed = (p(params, 3, 0f) * 1000f).roundToInt()
        val storm = p(params, 4, 0f) >= 0.5f
        val op = amount.coerceIn(0f, 1f)
        val t = 1f - cover * 0.9f - 0.05f
        run(width, height, bounds, 0, source, { src, pad, chunk, out ->
            val pw = pad.width()
            var o = 0
            for (y in chunk.top until chunk.bottom) {
                for (x in chunk.left until chunk.right) {
                    val c = src[(y - pad.top) * pw + (x - pad.left)]
                    var u = (x + 0.5f) / cell
                    var v = (y + 0.5f) / cell
                    var n = 0f
                    var amp = 0.5f
                    for (oct in 0 until 5) {
                        n += amp * valueNoise(u, v, seed + oct * 101)
                        u *= 2.03f; v *= 2.03f; amp *= 0.5f
                    }
                    val cov = smooth(t - soft, t + soft, n) * op
                    val ca = (cov * 255f).roundToInt()
                    if (ca == 0) { out[o++] = c; continue }
                    // Shading: lighter where the cloud is thick.
                    val shade = if (storm) 0.35f + 0.35f * n else 0.82f + 0.18f * n
                    val cr = (255f * shade * if (storm) 0.95f else 1f).roundToInt().coerceIn(0, 255)
                    val cg = (255f * shade * if (storm) 0.97f else 1f).roundToInt().coerceIn(0, 255)
                    val cb = (255f * shade).roundToInt().coerceIn(0, 255)
                    // Source-over in premultiplied colour.
                    val inv = 255 - ca
                    val a = ca + (c ushr 24) * inv / 255
                    val r = cr * ca / 255 + ((c shr 16) and 0xFF) * inv / 255
                    val g = cg * ca / 255 + ((c shr 8) and 0xFF) * inv / 255
                    val b = cb * ca / 255 + (c and 0xFF) * inv / 255
                    out[o++] = (a shl 24) or (min(a, r) shl 16) or (min(a, g) shl 8) or min(a, b)
                }
            }
        }, write, skipFlat = false)
    }

    /**
     * Anime background: a Kuwahara filter (each pixel takes the mean of its
     * calmest quarter, so areas flatten into clean patches and edges stay
     * sharp), then more vivid colour, mixed in by [amount]. Quarter sums come
     * from integral images, so large radii stay quick; all integers except the
     * variance comparison (same doubles everywhere).
     */
    private fun anime(
        width: Int, height: Int, bounds: Rect, amount: Float, params: FloatArray,
        source: Source, write: (Rect, Bitmap) -> Unit
    ) {
        val r = EffectSettings.animeRadius(p(params, 0, 0.5f), max(width, height))
        val viv256 = (256f + p(params, 1, 0.5f).coerceIn(0f, 1f) * 200f).roundToInt()
        val k256 = (amount.coerceIn(0f, 1f) * 256f).roundToInt()
        val halo = r + 1
        val chunk = chunkFor(halo)
        run(width, height, bounds, halo, source, { src, pad, ck, out ->
            val pw = pad.width()
            val ph = pad.height()
            val s1 = pw + 1
            val ia = LongArray(s1 * (ph + 1)); val ir = LongArray(s1 * (ph + 1))
            val ig = LongArray(s1 * (ph + 1)); val ib = LongArray(s1 * (ph + 1))
            val il = LongArray(s1 * (ph + 1)); val il2 = LongArray(s1 * (ph + 1))
            for (y in 0 until ph) {
                var ra = 0L; var rr = 0L; var rg = 0L; var rb = 0L; var rl = 0L; var rl2 = 0L
                for (x in 0 until pw) {
                    val c = src[y * pw + x]
                    val lum = luma(c).toLong()
                    ra += c ushr 24; rr += (c shr 16) and 0xFF; rg += (c shr 8) and 0xFF; rb += c and 0xFF
                    rl += lum; rl2 += lum * lum
                    val i = (y + 1) * s1 + x + 1
                    val u = y * s1 + x + 1
                    ia[i] = ia[u] + ra; ir[i] = ir[u] + rr; ig[i] = ig[u] + rg; ib[i] = ib[u] + rb
                    il[i] = il[u] + rl; il2[i] = il2[u] + rl2
                }
            }
            fun sum(t: LongArray, x0: Int, y0: Int, x1: Int, y1: Int): Long =
                t[y1 * s1 + x1] - t[y0 * s1 + x1] - t[y1 * s1 + x0] + t[y0 * s1 + x0]
            var o = 0
            for (y in ck.top until ck.bottom) {
                for (x in ck.left until ck.right) {
                    val px = x - pad.left
                    val py = y - pad.top
                    var bestVar = Double.MAX_VALUE
                    var bx0 = 0; var by0 = 0; var bx1 = 0; var by1 = 0
                    for (q in 0 until 4) {
                        // Quarters, clipped to the canvas (the pad reaches it there).
                        val x0 = if (q % 2 == 0) max(0, px - r) else px
                        val x1 = if (q % 2 == 0) px + 1 else min(pw, px + r + 1)
                        val y0 = if (q < 2) max(0, py - r) else py
                        val y1 = if (q < 2) py + 1 else min(ph, py + r + 1)
                        val n = ((x1 - x0) * (y1 - y0)).toDouble()
                        val sl = sum(il, x0, y0, x1, y1).toDouble()
                        val sl2 = sum(il2, x0, y0, x1, y1).toDouble()
                        val v = sl2 / n - (sl / n) * (sl / n)
                        if (v < bestVar) { bestVar = v; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1 }
                    }
                    val n = ((bx1 - bx0) * (by1 - by0)).toLong()
                    val a = (sum(ia, bx0, by0, bx1, by1) / n).toInt()
                    var cr = (sum(ir, bx0, by0, bx1, by1) / n).toInt()
                    var cg = (sum(ig, bx0, by0, bx1, by1) / n).toInt()
                    var cb = (sum(ib, bx0, by0, bx1, by1) / n).toInt()
                    // More vivid: push each channel away from the grey (premultiplied).
                    val g0 = (cr * 54 + cg * 183 + cb * 19) shr 8
                    cr = (g0 + ((cr - g0) * viv256 shr 8)).coerceIn(0, a)
                    cg = (g0 + ((cg - g0) * viv256 shr 8)).coerceIn(0, a)
                    cb = (g0 + ((cb - g0) * viv256 shr 8)).coerceIn(0, a)
                    val c = src[py * pw + px]
                    fun mixc(o0: Int, n0: Int) = o0 + ((n0 - o0) * k256 shr 8)
                    val oa = mixc(c ushr 24, a)
                    out[o++] = (oa shl 24) or (min(oa, mixc((c shr 16) and 0xFF, cr)) shl 16) or
                        (min(oa, mixc((c shr 8) and 0xFF, cg)) shl 8) or min(oa, mixc(c and 0xFF, cb))
                }
            }
        }, write, skipFlat = false, chunkW = chunk, chunkH = chunk)
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
