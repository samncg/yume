package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Tile-backed colour grid for the magic wand.
 *
 * The wand used to materialise the whole composite and then downscale it. That
 * costs a canvas-sized bitmap (268 MB at 8192²) even to select a small area.
 * This grid instead reads the composite on demand, one 256-document-pixel cell
 * at a time, and keeps only the cells the flood fill actually reaches. Memory
 * therefore scales with the selected area, not with the document.
 *
 * [step] is the sampling stride the wand uses on large documents: 2 halves the
 * grid, which is what keeps a tap on a big blank area interactive. Each grid
 * cell is filled with the same filtered downscale the old whole-image path
 * produced, computed from exactly the composite region that cell covers.
 */
class WandGrid(
    val docWidth: Int,
    val docHeight: Int,
    val step: Int,
    private val composite: SparseTileStore?,
    private val dense: Bitmap?,
    private val bg: Int
) {
    val gridW: Int = max(1, docWidth / step)
    val gridH: Int = max(1, docHeight / step)

    /** Grid cells per axis in one cached cell. */
    private val cell: Int = max(1, TILE / step)

    /** Grid pixels of one cell, allocated only when the cell is first read. */
    private val colours = HashMap<Long, IntArray>()
    private val marked = HashMap<Long, BooleanArray>()

    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val readPaint = Paint().apply {
        blendMode = android.graphics.BlendMode.SRC
        isFilterBitmap = false
    }

    private fun key(cx: Int, cy: Int): Long =
        (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    private fun cellPixels(cx: Int, cy: Int): IntArray {
        val k = key(cx, cy)
        colours[k]?.let { return it }
        val gx0 = cx * cell
        val gy0 = cy * cell
        val gw = min(cell, gridW - gx0)
        val gh = min(cell, gridH - gy0)
        val out = IntArray(cell * cell)
        if (gw <= 0 || gh <= 0) {
            colours[k] = out
            return out
        }
        // Document region this grid cell covers, clipped to the canvas.
        val dx0 = gx0 * step
        val dy0 = gy0 * step
        val dx1 = min(docWidth, (gx0 + gw) * step)
        val dy1 = min(docHeight, (gy0 + gh) * step)
        val src = Rect(dx0, dy0, dx1, dy1)
        if (src.isEmpty) {
            colours[k] = out
            return out
        }
        if (step == 1) {
            // 1:1: read the composite region straight into the grid.
            val target = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            readRegion(src, target)
            target.getPixels(out, 0, cell, 0, 0, gw, gh)
            target.recycle()
        } else {
            // Filtered downscale of exactly this region: the same operation the
            // old whole-image path applied, so the sampled colours match.
            val patch = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
            readRegion(src, patch)
            val target = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            val c = Canvas(target)
            c.drawColor(0, android.graphics.BlendMode.SRC)
            c.drawBitmap(
                patch, null, RectF(0f, 0f, gw.toFloat(), gh.toFloat()), scalePaint
            )
            target.getPixels(out, 0, cell, 0, 0, gw, gh)
            target.recycle()
            patch.recycle()
        }
        colours[k] = out
        return out
    }

    private fun readRegion(src: Rect, target: Bitmap) {
        val st = composite
        if (st != null) {
            st.readRectInto(src, target)
        } else {
            val c = Canvas(target)
            c.drawColor(bg, android.graphics.BlendMode.SRC)
            dense?.let { c.drawBitmap(it, src, Rect(0, 0, src.width(), src.height()), readPaint) }
        }
    }

    fun colour(gx: Int, gy: Int): Int {
        if (gx < 0 || gy < 0 || gx >= gridW || gy >= gridH) return bg
        val px = cellPixels(gx / cell, gy / cell)
        return px[(gy % cell) * cell + (gx % cell)]
    }

    fun isMarked(gx: Int, gy: Int): Boolean {
        if (gx < 0 || gy < 0 || gx >= gridW || gy >= gridH) return false
        val m = marked[key(gx / cell, gy / cell)] ?: return false
        return m[(gy % cell) * cell + (gx % cell)]
    }

    fun mark(gx: Int, gy: Int) {
        if (gx < 0 || gy < 0 || gx >= gridW || gy >= gridH) return
        val k = key(gx / cell, gy / cell)
        val m = marked.getOrPut(k) { BooleanArray(cell * cell) }
        m[(gy % cell) * cell + (gx % cell)] = true
    }

    /** Drops every cached cell (the grid is single-use). */
    fun release() {
        colours.clear()
        marked.clear()
    }

    private companion object {
        const val TILE = 256
    }
}
