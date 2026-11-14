package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tile-backed colour grid for the magic wand and the paint bucket.
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
 *
 * Once the seed colour is known ([prepare]) every cell is reduced to one bit
 * per pixel, "within tolerance", and the fill works on 64-pixel words of
 * those bits and of its marks: whole runs of a row are found and marked a
 * word at a time. A cell that is one colour throughout shares an all-ones or
 * all-zeros word array. The pixels selected are exactly the 4-connected
 * component the per-pixel scanline found.
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

    /** Grid pixels per axis in one cached cell. */
    private val cell: Int = max(1, TILE / step)

    init {
        // Rows of a cell are whole 64-bit words.
        require(cell % 64 == 0 && cell and (cell - 1) == 0) { "unsupported wand step $step" }
    }

    private val shift: Int = Integer.numberOfTrailingZeros(cell)
    private val lowBits: Int = cell - 1
    /** Words per cell row, and its log2. */
    private val rowWords: Int = cell ushr 6
    private val rowShift: Int = Integer.numberOfTrailingZeros(rowWords)

    private val cellsX: Int = (gridW + cell - 1) / cell
    private val cellsY: Int = (gridH + cell - 1) / cell
    private val words: Int = (cell * cell) ushr 6

    /** Colours of cells read before [prepare] (the seed's cell). */
    private val colours = arrayOfNulls<IntArray>(cellsX * cellsY)
    private val loaded = BooleanArray(cellsX * cellsY)
    private val uniform = BooleanArray(cellsX * cellsY)
    private val uniformColour = IntArray(cellsX * cellsY)
    /** "Within tolerance" bits, once prepared. */
    private val sim = arrayOfNulls<LongArray>(cellsX * cellsY)
    private val marked = arrayOfNulls<LongArray>(cellsX * cellsY)

    private val ones = LongArray(words) { -1L }
    private val zeros = LongArray(words)

    private var prepared = false
    private var target = 0
    private var tolerance = 0

    /** Reused between cells. */
    private var spare: IntArray? = null
    private var fullTarget: Bitmap? = null
    private var fullPatch: Bitmap? = null

    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val readPaint = Paint().apply {
        blendMode = android.graphics.BlendMode.SRC
        isFilterBitmap = false
    }

    private fun cellIndex(gx: Int, gy: Int): Int = (gy ushr shift) * cellsX + (gx ushr shift)

    private fun fullBitmap(w: Int, h: Int, patch: Boolean): Bitmap {
        val have = if (patch) fullPatch else fullTarget
        if (have != null && have.width == w && have.height == h) return have
        have?.recycle()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (patch) fullPatch = b else fullTarget = b
        return b
    }

    private fun loadCell(ci: Int) {
        loaded[ci] = true
        val gx0 = (ci % cellsX) * cell
        val gy0 = (ci / cellsX) * cell
        val gw = min(cell, gridW - gx0)
        val gh = min(cell, gridH - gy0)
        // Document region this grid cell covers, clipped to the canvas.
        val src = Rect(gx0 * step, gy0 * step, min(docWidth, (gx0 + gw) * step), min(docHeight, (gy0 + gh) * step))
        if (gw <= 0 || gh <= 0 || src.isEmpty) {
            setUniform(ci, 0)
            return
        }
        val full = gw == cell && gh == cell
        val st = composite
        if (st != null && step == 1 && full && st.tileSize == cell) {
            // The cell is exactly one tile: no copy through a canvas. A missing
            // tile reads as the default, a shared solid as its colour.
            st.ensureResident()
            val tile = st.getTile(gx0 / cell, gy0 / cell)
            if (tile == null) {
                setUniform(ci, defaultRead(st))
                return
            }
            val solid = SolidTiles.colourOf(tile)
            if (solid != null) {
                setUniform(ci, solid)
                return
            }
            if (tile.width >= gw && tile.height >= gh) {
                val out = spare ?: IntArray(cell * cell)
                spare = null
                tile.getPixels(out, 0, cell, 0, 0, gw, gh)
                keep(ci, out, gw, gh)
                return
            }
        }
        val out = spare ?: IntArray(cell * cell)
        spare = null
        if (step == 1) {
            // 1:1: read the composite region straight into the grid.
            val target = if (full) fullBitmap(gw, gh, false) else Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            readRegion(src, target)
            target.getPixels(out, 0, cell, 0, 0, gw, gh)
            if (!full) target.recycle()
        } else {
            // Filtered downscale of exactly this region: the same operation the
            // old whole-image path applied, so the sampled colours match.
            val patch = if (full) fullBitmap(src.width(), src.height(), true)
            else Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
            readRegion(src, patch)
            val target = if (full) fullBitmap(gw, gh, false) else Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            val c = Canvas(target)
            c.drawColor(0, android.graphics.BlendMode.SRC)
            c.drawBitmap(
                patch, null, RectF(0f, 0f, gw.toFloat(), gh.toFloat()), scalePaint
            )
            target.getPixels(out, 0, cell, 0, 0, gw, gh)
            if (!full) {
                target.recycle()
                patch.recycle()
            }
        }
        keep(ci, out, gw, gh)
    }

    private var defaultReadValue = 0
    private var defaultReadDone = false

    /** The store's default pixel as reading it back yields (premultiplied round trip). */
    private fun defaultRead(st: SparseTileStore): Int {
        if (!defaultReadDone) {
            val one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            Canvas(one).drawColor(st.defaultPixel, android.graphics.BlendMode.SRC)
            defaultReadValue = one.getPixel(0, 0)
            one.recycle()
            defaultReadDone = true
        }
        return defaultReadValue
    }

    private fun setUniform(ci: Int, colour: Int) {
        uniform[ci] = true
        uniformColour[ci] = colour
        if (prepared) sim[ci] = if (similar(colour)) ones else zeros
    }

    /** Stores a read cell: its tolerance bits once prepared, else its colours. */
    private fun keep(ci: Int, out: IntArray, gw: Int, gh: Int) {
        val first = out[0]
        var flat = true
        var y = 0
        while (flat && y < gh) {
            val row = y * cell
            for (x in row until row + gw) {
                if (out[x] != first) {
                    flat = false
                    break
                }
            }
            y++
        }
        if (flat) {
            setUniform(ci, first)
            spare = out
        } else if (prepared) {
            sim[ci] = simBits(out, gw, gh)
            spare = out
        } else {
            colours[ci] = out
        }
    }

    /** Pixels outside the grid stay 0 (never within tolerance). */
    private fun simBits(px: IntArray, gw: Int, gh: Int): LongArray {
        val bits = LongArray(words)
        for (y in 0 until gh) {
            val row = y * cell
            for (x in 0 until gw) {
                val i = row + x
                if (similar(px[i])) bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
            }
        }
        return bits
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

    /** The colour at a grid pixel. Only before [prepare] (the seed colour). */
    fun colour(gx: Int, gy: Int): Int {
        if (gx < 0 || gy < 0 || gx >= gridW || gy >= gridH) return bg
        val ci = cellIndex(gx, gy)
        if (!loaded[ci]) loadCell(ci)
        if (uniform[ci]) return uniformColour[ci]
        return colours[ci]!![((gy and lowBits) shl shift) + (gx and lowBits)]
    }

    /** The per-channel test the wand has always used (alpha included). */
    private fun similar(c: Int): Boolean {
        val t = tolerance
        val a = target
        if (abs((c ushr 24) - (a ushr 24)) > t) return false
        if (abs(((c shr 16) and 0xFF) - ((a shr 16) and 0xFF)) > t) return false
        if (abs(((c shr 8) and 0xFF) - ((a shr 8) and 0xFF)) > t) return false
        return abs((c and 0xFF) - (a and 0xFF)) <= t
    }

    /** Fixes the seed colour and tolerance: cells become tolerance bits from here on. */
    fun prepare(target: Int, tolerance: Int) {
        this.target = target
        this.tolerance = tolerance
        prepared = true
        for (ci in colours.indices) {
            if (!loaded[ci]) continue
            if (uniform[ci]) {
                sim[ci] = if (similar(uniformColour[ci])) ones else zeros
            } else {
                val px = colours[ci] ?: continue
                val gx0 = (ci % cellsX) * cell
                val gy0 = (ci / cellsX) * cell
                sim[ci] = simBits(px, min(cell, gridW - gx0), min(cell, gridH - gy0))
                colours[ci] = null
            }
        }
    }

    // ------------------------------------------------------- word access

    /** Word index of grid pixel ([gx], [gy]) inside its cell. */
    private fun wordIndex(gx: Int, gy: Int): Int =
        ((gy and lowBits) shl rowShift) + ((gx and lowBits) ushr 6)

    /** Open (within tolerance and not yet marked) bits of the word holding (gx, gy). */
    private fun openWord(gx: Int, gy: Int): Long {
        val ci = cellIndex(gx, gy)
        if (!loaded[ci]) loadCell(ci)
        val s = sim[ci] ?: return 0L
        val wi = wordIndex(gx, gy)
        val m = marked[ci]
        return if (m == null) s[wi] else s[wi] and m[wi].inv()
    }

    private fun markedWord(gx: Int, gy: Int): Long {
        val m = marked[cellIndex(gx, gy)] ?: return 0L
        return m[wordIndex(gx, gy)]
    }

    fun isOpen(gx: Int, gy: Int): Boolean {
        if (gx < 0 || gy < 0 || gx >= gridW || gy >= gridH) return false
        return (openWord(gx, gy) ushr (gx and 63)) and 1L != 0L
    }

    /** First open pixel of row [gy] in [gx, limit), or [limit]. */
    fun nextOpen(gx: Int, gy: Int, limit: Int): Int {
        var x = gx
        while (x < limit) {
            val bit = x and 63
            val word = openWord(x, gy) ushr bit
            if (word != 0L) return min(limit, x + java.lang.Long.numberOfTrailingZeros(word))
            x += 64 - bit
        }
        return limit
    }

    /** End (exclusive) of the open run of row [gy] containing open pixel [gx]. */
    fun openRunEnd(gx: Int, gy: Int): Int {
        var x = gx
        while (x < gridW) {
            val bit = x and 63
            val word = openWord(x, gy).inv() ushr bit
            if (word != 0L) return min(gridW, x + java.lang.Long.numberOfTrailingZeros(word))
            x += 64 - bit
        }
        return gridW
    }

    /** Start of the open run of row [gy] containing open pixel [gx]. */
    fun openRunStart(gx: Int, gy: Int): Int {
        var x = gx
        while (x >= 0) {
            val bit = x and 63
            val closed = openWord(x, gy).inv()
            val below = if (bit == 63) closed else closed and ((1L shl (bit + 1)) - 1)
            if (below != 0L) return x - bit + (63 - java.lang.Long.numberOfLeadingZeros(below)) + 1
            x -= bit + 1
        }
        return 0
    }

    /** Marks row [gy] from [x0] to [x1] (exclusive). */
    fun markRun(x0: Int, x1: Int, gy: Int) {
        var x = x0
        while (x < x1) {
            val bit = x and 63
            val n = min(64 - bit, x1 - x)
            val mask = (if (n == 64) -1L else (1L shl n) - 1) shl bit
            val ci = cellIndex(x, gy)
            val m = marked[ci] ?: LongArray(words).also { marked[ci] = it }
            val wi = wordIndex(x, gy)
            m[wi] = m[wi] or mask
            x += n
        }
    }

    /** First marked pixel of row [gy] in [gx, limit), or [limit]. */
    fun nextMarked(gx: Int, gy: Int, limit: Int): Int {
        var x = gx
        while (x < limit) {
            if (marked[cellIndex(x, gy)] == null) {
                // Nothing marked in this cell: skip it whole.
                x = ((x ushr shift) + 1) shl shift
                continue
            }
            val bit = x and 63
            val word = markedWord(x, gy) ushr bit
            if (word != 0L) return min(limit, x + java.lang.Long.numberOfTrailingZeros(word))
            x += 64 - bit
        }
        return limit
    }

    /** End (exclusive) of the marked run of row [gy] containing marked pixel [gx]. */
    fun markedRunEnd(gx: Int, gy: Int): Int {
        var x = gx
        while (x < gridW) {
            val bit = x and 63
            val word = markedWord(x, gy).inv() ushr bit
            if (word != 0L) return min(gridW, x + java.lang.Long.numberOfTrailingZeros(word))
            x += 64 - bit
        }
        return gridW
    }

    /** Drops every cached cell (the grid is single-use). */
    fun release() {
        colours.fill(null)
        sim.fill(null)
        marked.fill(null)
        loaded.fill(false)
        uniform.fill(false)
        spare = null
        fullTarget?.recycle()
        fullTarget = null
        fullPatch?.recycle()
        fullPatch = null
    }

    private companion object {
        const val TILE = 256
    }
}
