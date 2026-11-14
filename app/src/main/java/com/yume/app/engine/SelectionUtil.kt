package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import kotlin.math.abs
import kotlin.math.max

object SelectionUtil {

    /** Rasterises an arbitrary path into a document region. */
    fun pathToRegion(path: Path, width: Int, height: Int): Region {
        val clip = Region(0, 0, width, height)
        val region = Region()
        region.setPath(path, clip)
        return region
    }

    /**
     * Outline of a region, used for the marching-ants contour.
     *
     * Skia's own boundary path is used here: the previous implementation probed
     * `region.contains()` for every pixel along every edge, which is millions of
     * binary searches for a large selection and made selecting the blank canvas
     * freeze the app.
     */
    fun boundaryPath(region: Region): Path = region.getBoundaryPath()

    /** Builds a filled path for a region, used for tinting the selection. */
    fun regionFillPath(region: Region): Path {
        val path = Path()
        val iterator = RegionIterator(region)
        val r = Rect()
        while (iterator.next(r)) {
            path.addRect(
                r.left.toFloat(), r.top.toFloat(),
                r.right.toFloat(), r.bottom.toFloat(), Path.Direction.CW
            )
        }
        return path
    }

    /**
     * Streaming variant of [magicWand]: the pixels come from a [WandGrid] that
     * reads the composite on demand and keeps only the cells the fill reaches.
     * The scanline algorithm, the tolerance test and the resulting region are
     * the same; only the storage behind them changes.
     */
    fun magicWandGrid(grid: WandGrid, seedX: Int, seedY: Int, tolerance: Int): Region {
        val w0 = grid.docWidth
        val h0 = grid.docHeight
        val step = grid.step
        val w = grid.gridW
        val h = grid.gridH
        if (seedX !in 0 until w0 || seedY !in 0 until h0) return Region()

        val seedXi = (seedX / step).coerceIn(0, w - 1)
        val seedYi = (seedY / step).coerceIn(0, h - 1)
        val target = grid.colour(seedXi, seedYi)
        grid.prepare(target, tolerance)
        var stack = wandStack
        var top = 0
        fun push(v: Int) {
            if (top == stack.size) {
                stack = stack.copyOf(stack.size * 2)
                wandStack = stack
            }
            stack[top++] = v
        }
        val p0 = System.nanoTime()
        // Span fill on 64-pixel words: take the whole open run through the
        // seed, mark it, and seed every open run touching it in the rows above
        // and below. The result is the seed's 4-connected component, exactly
        // what the per-pixel scanline produced.
        push(seedYi * w + seedXi)
        var visits = 0
        while (top > 0) {
            if (++visits and 1023 == 0) OpCancel.check()
            val seed = stack[--top]
            val sy = seed / w
            val sx = seed % w
            if (!grid.isOpen(sx, sy)) continue
            val x0 = grid.openRunStart(sx, sy)
            val x1 = grid.openRunEnd(sx, sy)
            grid.markRun(x0, x1, sy)
            for (d in 0..1) {
                val ny = if (d == 0) sy - 1 else sy + 1
                if (ny < 0 || ny >= h) continue
                var nx = grid.nextOpen(x0, ny, x1)
                while (nx < x1) {
                    push(ny * w + nx)
                    nx = grid.nextOpen(grid.openRunEnd(nx, ny), ny, x1)
                }
            }
        }

        val p1 = System.nanoTime()
        // Coalesce identical spans across consecutive rows into tall rectangles,
        // a band of WAND_BAND_ROWS rows at a time: each band's path becomes a
        // region on its own and the bands are merged at the end. One path for
        // the whole canvas could hold millions of rectangles, and turning it
        // into a region took gigabytes (a 16K fill around noise: 6 GB). Regions
        // are canonical, so the result is the same set of pixels either way.
        // Marked runs are found a 64-pixel word at a time.
        val path = Path()
        val bands = ArrayList<Region>()
        val open = HashMap<Long, IntArray>()
        val touched = HashSet<Long>()
        var bandTop = 0
        fun closeRun(run: IntArray, endRow: Int) {
            path.addRect(
                (run[0] * step).toFloat(), (run[2] * step).toFloat(),
                minOf(w0, run[1] * step).toFloat(), minOf(h0, endRow * step).toFloat(),
                Path.Direction.CW
            )
        }
        fun flushBand(endRow: Int) {
            for (run in open.values) closeRun(run, endRow)
            open.clear()
            if (!path.isEmpty) {
                val band = Region()
                band.setPath(path, Region(0, bandTop * step, w0, minOf(h0, endRow * step)))
                if (!band.isEmpty) bands.add(band)
                path.rewind()
            }
            bandTop = endRow
        }
        for (y in 0 until h) {
            if (y - bandTop >= WAND_BAND_ROWS) flushBand(y)
            touched.clear()
            var x = 0
            while (true) {
                val start = grid.nextMarked(x, y, w)
                if (start >= w) break
                x = grid.markedRunEnd(start, y)
                val spanKey = (start.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
                if (!open.containsKey(spanKey)) open[spanKey] = intArrayOf(start, x, y)
                touched.add(spanKey)
            }
            if (open.isNotEmpty()) {
                val iterator = open.entries.iterator()
                while (iterator.hasNext()) {
                    val e = iterator.next()
                    if (e.key !in touched) {
                        closeRun(e.value, y)
                        iterator.remove()
                    }
                }
            }
        }
        flushBand(h)
        val p2 = System.nanoTime()
        // Pairwise merge: each union touches a region about twice as big as
        // the last, instead of re-copying the whole result once per band.
        var level: List<Region> = bands
        while (level.size > 1) {
            val next = ArrayList<Region>((level.size + 1) / 2)
            var i = 0
            while (i < level.size) {
                val a = level[i]
                if (i + 1 < level.size) a.op(level[i + 1], Region.Op.UNION)
                next.add(a)
                i += 2
            }
            level = next
        }
        val region = level.firstOrNull() ?: Region()
        lastProfile = "flood=${(p1 - p0) / 1_000_000} spans=${(p2 - p1) / 1_000_000} setPath=${(System.nanoTime() - p2) / 1_000_000}"
        return region
    }

    /** Grid rows per band when building a wand region (bounds its memory). */
    private const val WAND_BAND_ROWS = 64

    /** Timing of the last wand (harness). */
    @Volatile var lastProfile: String = ""

    /** Flood-fill stack, reused between wand calls (render thread only). */
    private var wandStack: IntArray = IntArray(4096)
}
