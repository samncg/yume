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

    private fun similar(a: Int, b: Int, tolerance: Int): Boolean {
        val da = abs(Color.alpha(a) - Color.alpha(b))
        if (da > tolerance) return false
        val dr = abs(Color.red(a) - Color.red(b))
        val dg = abs(Color.green(a) - Color.green(b))
        val db = abs(Color.blue(a) - Color.blue(b))
        return dr <= tolerance && dg <= tolerance && db <= tolerance
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
        var stack = wandStack
        var top = 0
        stack[top++] = seedYi * w + seedXi

        while (top > 0) {
            val seed = stack[--top]
            if (grid.isMarked(seed % w, seed / w)) continue
            val sy = seed / w
            var sx = seed % w
            while (sx > 0 && !grid.isMarked(sx - 1, sy) &&
                similar(grid.colour(sx - 1, sy), target, tolerance)
            ) {
                sx--
            }
            var upPending = false
            var downPending = false
            var x = sx
            while (x < w && !grid.isMarked(x, sy) &&
                similar(grid.colour(x, sy), target, tolerance)
            ) {
                grid.mark(x, sy)
                if (sy > 0) {
                    if (!grid.isMarked(x, sy - 1) &&
                        similar(grid.colour(x, sy - 1), target, tolerance)
                    ) {
                        if (!upPending) {
                            if (top == stack.size) {
                                stack = stack.copyOf(stack.size * 2)
                                wandStack = stack
                            }
                            stack[top++] = (sy - 1) * w + x
                            upPending = true
                        }
                    } else {
                        upPending = false
                    }
                }
                if (sy < h - 1) {
                    if (!grid.isMarked(x, sy + 1) &&
                        similar(grid.colour(x, sy + 1), target, tolerance)
                    ) {
                        if (!downPending) {
                            if (top == stack.size) {
                                stack = stack.copyOf(stack.size * 2)
                                wandStack = stack
                            }
                            stack[top++] = (sy + 1) * w + x
                            downPending = true
                        }
                    } else {
                        downPending = false
                    }
                }
                x++
            }
        }

        val region = Region()
        val path = Path()
        // Coalesce identical spans across consecutive rows into tall rectangles.
        // The grid is scanned cell by cell, which splits a span at a cell border
        // but covers exactly the same pixels, so the region is unchanged.
        val open = HashMap<Long, IntArray>()
        val touched = HashSet<Long>()
        for (y in 0 until h) {
            touched.clear()
            var x = 0
            while (x < w) {
                if (!grid.isMarked(x, y)) {
                    x++
                    continue
                }
                val start = x
                while (x < w && grid.isMarked(x, y)) x++
                val spanKey = (start.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
                if (open.containsKey(spanKey)) {
                    touched.add(spanKey)
                } else {
                    open[spanKey] = intArrayOf(start, x, y)
                    touched.add(spanKey)
                }
            }
            if (open.isNotEmpty()) {
                val iterator = open.keys.iterator()
                while (iterator.hasNext()) {
                    val spanKey = iterator.next()
                    if (spanKey !in touched) {
                        val run = open[spanKey] ?: continue
                        path.addRect(
                            (run[0] * step).toFloat(), (run[2] * step).toFloat(),
                            minOf(w0, run[1] * step).toFloat(),
                            minOf(h0, y * step).toFloat(),
                            Path.Direction.CW
                        )
                        iterator.remove()
                    }
                }
            }
        }
        for (run in open.values) {
            path.addRect(
                (run[0] * step).toFloat(), (run[2] * step).toFloat(),
                minOf(w0, run[1] * step).toFloat(), minOf(h0, h * step).toFloat(),
                Path.Direction.CW
            )
        }
        region.setPath(path, Region(0, 0, w0, h0))
        return region
    }

    /** Flood-fill stack, reused between wand calls (render thread only). */
    private var wandStack: IntArray = IntArray(4096)
}
