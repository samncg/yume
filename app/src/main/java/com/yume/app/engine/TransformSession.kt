package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Free transform of the active layer (or the current selection within it).
 *
 * On entry the affected pixels are lifted into a snapshot and the layer is
 * rebuilt as `base + source * matrix`. Each gesture step marks the layer dirty
 * and the render loop rebuilds at most once per frame.
 *
 * A sparse layer never allocates a canvas-sized surface: the snapshot is the
 * per-tile [TileCapture], the split into base and floating source holds only the
 * tiles the selection touches, and each rebuild works one destination tile at a
 * time, reading the source it needs from a padded patch so a filtered resample
 * cannot seam at a tile border.
 */
class TransformSession(
    private val engine: DrawingEngine,
    val layer: Layer,
    private val selection: Region?
) {
    private val sparse = layer.tiles.isSparse
    private val store = layer.tiles

    // ------------------------------------------------------------- dense state
    private var originalDense: Bitmap? = null
    private var baseDense: Bitmap? = null
    private var sourceDense: Bitmap? = null

    // ------------------------------------------------------------ sparse state
    private var capture: TileCapture? = null
    private val baseTiles = HashMap<Long, Bitmap>()
    private val sourceTiles = HashMap<Long, Bitmap>()
    private val srcBounds = RectF()
    /** Region the transform has ever written; the action and undo cover it. */
    private val dirtyRect = Rect()
    private val destBufs = HashMap<Long, Bitmap>()
    private val patchBufs = HashMap<Long, Bitmap>()

    val matrix = Matrix()
    var needsRebuild = true
        private set
    var isActive = true
        private set

    val bounds: RectF = RectF()

    /** How drags on the handles reshape the content. */
    var mode = TransformMode.Free

    /**
     * The box the handles start on (document px): the selection's bounds, or
     * the painted part of the layer. Distort corners and the warp grid are
     * this box carried through the transform.
     */
    private val box = RectF()

    /**
     * Warp control points, [GRID] x [GRID] (x, y) pairs row by row, in
     * document px; null until the first warp. Once set, every move, pinch,
     * flip and distort reshapes these points instead of [matrix].
     */
    private var warp: FloatArray? = null

    /** Fine mesh the warp is drawn with: destination and source points. */
    private var meshDst: FloatArray? = null
    private var meshSrc: FloatArray? = null

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val srcPaint = Paint().apply {
        blendMode = BlendMode.SRC
        isFilterBitmap = false
    }

    init {
        val w = engine.width
        val h = engine.height
        if (sparse) {
            initSparse(w, h)
        } else {
            originalDense = layer.tiles.copyAll()
            val original = originalDense!!
            if (selection != null && !selection.isEmpty) {
                val selPath = SelectionUtil.regionFillPath(selection)

                // base = original minus the selected pixels
                val base = copyPixels(original)
                val bc = Canvas(base)
                val checkpoint = bc.save()
                bc.clipPath(selPath)
                bc.drawColor(0, BlendMode.CLEAR)
                bc.restoreToCount(checkpoint)
                baseDense = base

                // source = only the selected pixels
                val src = copyPixels(original)
                val sc = Canvas(src)
                val outside = Region(0, 0, w, h)
                outside.op(selection, Region.Op.DIFFERENCE)
                val cp = sc.save()
                sc.clipPath(SelectionUtil.regionFillPath(outside))
                sc.drawColor(0, BlendMode.CLEAR)
                sc.restoreToCount(cp)
                sourceDense = src

                val r = selection.bounds
                bounds.set(r)
            } else {
                baseDense = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                sourceDense = copyPixels(original)
                bounds.set(0f, 0f, w.toFloat(), h.toFloat())
            }
        }
        box.set(contentBox(w, h))
        rebuild()
    }

    private fun contentBox(w: Int, h: Int): RectF {
        val sel = selection?.takeIf { !it.isEmpty }
        if (sel != null) return RectF(sel.bounds)
        val content = if (sparse) Rect(
            srcBounds.left.toInt(), srcBounds.top.toInt(), srcBounds.right.toInt(), srcBounds.bottom.toInt()
        ) else store.nonEmptyBounds()
        if (!content.intersect(0, 0, w, h) || content.isEmpty) return RectF(0f, 0f, w.toFloat(), h.toFloat())
        return RectF(tighten(content))
    }

    /**
     * [r] shrunk to the pixels that are not transparent. Tile bounds are
     * whole tiles, so the handles would float away from the paint; each side
     * is read inward in strips until paint is met.
     */
    private fun tighten(r: Rect): Rect {
        val strip = 16
        fun firstRow(fromTop: Boolean): Int? {
            val buf = Bitmap.createBitmap(r.width(), strip, Bitmap.Config.ARGB_8888)
            val row = IntArray(r.width())
            try {
                var y = if (fromTop) r.top else r.bottom - strip
                while (if (fromTop) y < r.bottom else y + strip > r.top) {
                    val y0 = y.coerceIn(r.top, r.bottom - 1)
                    val rows = min(strip, r.bottom - y0)
                    store.readRectInto(Rect(r.left, y0, r.right, y0 + rows), buf)
                    val order = if (fromTop) 0 until rows else (rows - 1) downTo 0
                    for (k in order) {
                        buf.getPixels(row, 0, r.width(), 0, k, r.width(), 1)
                        if (row.any { it ushr 24 != 0 }) return y0 + k
                    }
                    y += if (fromTop) strip else -strip
                }
                return null
            } finally {
                buf.recycle()
            }
        }
        fun firstCol(fromLeft: Boolean, top: Int, bottom: Int): Int? {
            val hgt = bottom - top
            val buf = Bitmap.createBitmap(strip, hgt, Bitmap.Config.ARGB_8888)
            val col = IntArray(hgt)
            try {
                var x = if (fromLeft) r.left else r.right - strip
                while (if (fromLeft) x < r.right else x + strip > r.left) {
                    val x0 = x.coerceIn(r.left, r.right - 1)
                    val cols = min(strip, r.right - x0)
                    store.readRectInto(Rect(x0, top, x0 + cols, bottom), buf)
                    val order = if (fromLeft) 0 until cols else (cols - 1) downTo 0
                    for (k in order) {
                        buf.getPixels(col, 0, 1, k, 0, 1, hgt)
                        if (col.any { it ushr 24 != 0 }) return x0 + k
                    }
                    x += if (fromLeft) strip else -strip
                }
                return null
            } finally {
                buf.recycle()
            }
        }
        return runCatching {
            val top = firstRow(true) ?: return r
            val bottom = (firstRow(false) ?: return r) + 1
            val left = firstCol(true, top, bottom) ?: return r
            val right = (firstCol(false, top, bottom) ?: return r) + 1
            Rect(left, top, right, bottom)
        }.getOrDefault(r)
    }

    /** Sparse setup: per-tile snapshot plus base/source tiles for the selection. */
    private fun initSparse(w: Int, h: Int) {
        // Capture only the tiles that hold paint: the snapshot bounds drive the
        // rebuild set, so a mostly-empty layer must not rebuild the whole canvas.
        val content = store.nonEmptyBounds()
        content.intersect(0, 0, w, h)
        val cap = TileOps.capture(store, if (content.isEmpty) Rect() else content)
        capture = cap
        val sel = selection?.takeIf { !it.isEmpty }
        val selPath = sel?.let { SelectionUtil.regionFillPath(it) }
        val outsidePath = if (sel != null) {
            val outside = Region(0, 0, w, h)
            outside.op(sel, Region.Op.DIFFERENCE)
            SelectionUtil.regionFillPath(outside)
        } else {
            null
        }
        val ts = store.tileSize
        val hasContent = Rect(cap.bounds)
        if (hasContent.isEmpty) {
            // Nothing to transform: keep the empty bounds so a drag is a no-op.
            bounds.set(0f, 0f, w.toFloat(), h.toFloat())
            srcBounds.set(0f, 0f, 0f, 0f)
            return
        }
        srcBounds.set(hasContent)
        if (sel != null) {
            // Base is the original minus the selected pixels. Tiles that do not
            // touch the selection keep their original pixels, so they are copied
            // wholesale; only the tiles the selection crosses are split.
            for (ty in hasContent.top / ts..(hasContent.bottom - 1) / ts) {
                for (tx in hasContent.left / ts..(hasContent.right - 1) / ts) {
                    val tile = TileOps.tileRect(store, tx, ty)
                    if (tile.isEmpty) continue
                    val before = cap.beforeAt(tx, ty) ?: continue
                    val k = cap.keyOf(tx, ty)
                    if (!Rect.intersects(tile, sel.bounds)) {
                        baseTiles[k] = copyPixels(before)
                        continue
                    }
                    val baseCopy = copyPixels(before)
                    clearPath(baseCopy, selPath!!, tile)
                    if (!isBlank(baseCopy, store.defaultPixel)) baseTiles[k] = baseCopy
                    else baseCopy.recycle()
                    val srcCopy = copyPixels(before)
                    clearPath(srcCopy, outsidePath!!, tile)
                    if (!isBlank(srcCopy, store.defaultPixel)) sourceTiles[k] = srcCopy
                    else srcCopy.recycle()
                }
            }
            val r = sel.bounds
            bounds.set(r)
            srcBounds.set(r)
        } else {
            bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        }
    }

    private fun clearPath(target: Bitmap, path: android.graphics.Path, tile: Rect) {
        val c = Canvas(target)
        val cp = c.save()
        c.translate(-tile.left.toFloat(), -tile.top.toFloat())
        c.clipPath(path)
        c.drawColor(0, BlendMode.CLEAR)
        c.restoreToCount(cp)
    }

    private fun isBlank(bmp: Bitmap, def: Int): Boolean {
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) if (row[x] != def) return false
        }
        return true
    }

    fun rebuild() {
        if (sparse) rebuildSparse() else rebuildDense()
        needsRebuild = false
    }

    private fun rebuildDense() {
        val base = baseDense ?: return
        val src = sourceDense ?: return
        layer.tiles.withWriteCanvas(Rect(0, 0, engine.width, engine.height)) { c ->
            c.drawColor(0, BlendMode.CLEAR)
            c.drawBitmap(base, 0f, 0f, null)
            if (warp != null) drawMesh(c, src, 0f, 0f, null) else c.drawBitmap(src, matrix, filterPaint)
        }
        layer.touch()
        engine.markDirtyAll()
    }

    /** Rebuilds one destination tile at a time from the pre-transform snapshot. */
    private fun rebuildSparse() {
        val cap = capture ?: return
        val mapped = mappedSourceBounds()
        val dest = Rect()
        dest.set(cap.bounds)
        if (!mapped.isEmpty) {
            dest.union(
                floor(mapped.left).toInt(), floor(mapped.top).toInt(),
                ceil(mapped.right).toInt(), ceil(mapped.bottom).toInt()
            )
        }
        dest.intersect(0, 0, engine.width, engine.height)
        if (dest.isEmpty) return
        // Everything ever written stays in the rebuild set, otherwise a tile the
        // previous gesture step painted would keep a stale copy of the source.
        if (dirtyRect.isEmpty) dirtyRect.set(dest) else dirtyRect.union(dest)

        val ts = store.tileSize
        val inv = Matrix()
        if (!matrix.invert(inv)) inv.reset()
        val warped = warp != null
        val cells = if (warped) cellBounds() else null
        val r = Rect()
        for (ty in dirtyRect.top / ts..(dirtyRect.bottom - 1) / ts) {
            for (tx in dirtyRect.left / ts..(dirtyRect.right - 1) / ts) {
                val tile = TileOps.tileRect(store, tx, ty)
                if (tile.isEmpty) continue
                val k = cap.keyOf(tx, ty)
                val buf = buf(tile.width(), tile.height(), destBufs)
                val c = Canvas(buf)
                c.drawColor(0, BlendMode.CLEAR)
                c.translate(-tile.left.toFloat(), -tile.top.toFloat())
                // Base: the untouched rest of the layer.
                val base = baseTiles[k]
                if (base != null) c.drawBitmap(base, tile.left.toFloat(), tile.top.toFloat(), srcPaint)
                // Floating source: read the patch this tile needs, then draw it
                // with the matrix shifted so the patch's origin lands right.
                if (warped) {
                    val area = RectF(tile)
                    val src = meshSourceFor(area, cells!!)
                    if (src != null) {
                        src.inset(-2f, -2f)
                        r.set(
                            floor(src.left).toInt(), floor(src.top).toInt(),
                            ceil(src.right).toInt(), ceil(src.bottom).toInt()
                        )
                        if (r.intersect(0, 0, engine.width, engine.height) && !r.isEmpty) {
                            val patch = buf(r.width(), r.height(), patchBufs)
                            readSource(r, patch)
                            drawMesh(c, patch, r.left.toFloat(), r.top.toFloat(), area, cells)
                        }
                    }
                    store.writeRect(tile, buf, Rect(0, 0, tile.width(), tile.height()))
                    if (!cap.has(tx, ty)) cap.markEmpty(tx, ty)
                    continue
                }
                val near = RectF(tile)
                inv.mapRect(near)
                near.inset(-2f, -2f)
                r.set(
                    floor(near.left).toInt(), floor(near.top).toInt(),
                    ceil(near.right).toInt(), ceil(near.bottom).toInt()
                )
                if (r.intersect(0, 0, engine.width, engine.height) && !r.isEmpty) {
                    val patch = buf(r.width(), r.height(), patchBufs)
                    readSource(r, patch)
                    // The patch's top-left corresponds to document (r.left, r.top),
                    // so the mapping for a patch pixel p is M(p + r.origin).
                    // Android's preTranslate composes as M * T, which is exactly
                    // that; postTranslate would add the translation twice (or, for
                    // a flip, add a reflection of it).
                    val m = Matrix(matrix)
                    m.preTranslate(r.left.toFloat(), r.top.toFloat())
                    c.drawBitmap(patch, m, filterPaint)
                }
                store.writeRect(tile, buf, Rect(0, 0, tile.width(), tile.height()))
                // Tiles this rebuild created must know they were empty before.
                if (!cap.has(tx, ty)) cap.markEmpty(tx, ty)
            }
        }
        store.pruneEmptyTiles(dirtyRect)
        layer.touch()
        engine.markDirty(dirtyRect)
    }

    private fun mappedSourceBounds(): RectF {
        val out = RectF(srcBounds)
        if (out.isEmpty) return out
        val mesh = if (warp != null) ensureMesh() else null
        if (mesh != null) {
            out.set(mesh[0], mesh[1], mesh[0], mesh[1])
            var i = 0
            while (i < mesh.size) {
                out.union(mesh[i], mesh[i + 1])
                i += 2
            }
        } else {
            matrix.mapRect(out)
        }
        out.inset(-2f, -2f)
        return out
    }

    // ------------------------------------------------------- distort and warp

    /** The box's corners (TL, TR, BR, BL) as they now sit on the canvas. */
    fun corners(): FloatArray {
        val w = warp
        if (w != null) {
            val n = GRID
            val out = FloatArray(8)
            for ((k, i) in intArrayOf(0, n - 1, n * n - 1, n * (n - 1)).withIndex()) {
                out[k * 2] = w[i * 2]
                out[k * 2 + 1] = w[i * 2 + 1]
            }
            return out
        }
        val out = boxCorners()
        matrix.mapPoints(out)
        return out
    }

    private fun boxCorners(): FloatArray = floatArrayOf(
        box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom
    )

    /** The draggable points for the current mode, as (x, y) pairs. */
    fun handles(): FloatArray = when (mode) {
        TransformMode.Free -> FloatArray(0)
        TransformMode.Distort -> corners()
        TransformMode.Warp -> warp?.copyOf() ?: gridFromMatrix()
    }

    /**
     * Lines to draw over the content, each a polyline of (x, y) pairs: the
     * outline, and in warp mode the grid through the control points.
     */
    fun outlines(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        if (warp == null && mode != TransformMode.Warp) {
            val c = corners()
            out.add(floatArrayOf(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[0], c[1]))
            return out
        }
        val pts = warp ?: gridFromMatrix()
        val per = FINE
        val n = (GRID - 1) * per
        val tmp = FloatArray(2)
        val lines = if (mode == TransformMode.Warp) (0 until GRID).toList() else listOf(0, GRID - 1)
        for (k in lines) {
            val row = FloatArray((n + 1) * 2)
            val col = FloatArray((n + 1) * 2)
            for (i in 0..n) {
                val u = i.toFloat() / per
                evalWarp(pts, u, k.toFloat(), tmp)
                row[i * 2] = tmp[0]; row[i * 2 + 1] = tmp[1]
                evalWarp(pts, k.toFloat(), u, tmp)
                col[i * 2] = tmp[0]; col[i * 2 + 1] = tmp[1]
            }
            out.add(row)
            out.add(col)
        }
        return out
    }

    /** The control grid the current matrix gives the box (a warp's start). */
    private fun gridFromMatrix(): FloatArray {
        val n = GRID
        val pts = FloatArray(n * n * 2)
        for (j in 0 until n) for (i in 0 until n) {
            val k = (j * n + i) * 2
            pts[k] = box.left + box.width() * i / (n - 1)
            pts[k + 1] = box.top + box.height() * j / (n - 1)
        }
        matrix.mapPoints(pts)
        return pts
    }

    /** Moves handle [index] (see [handles]) to ([x], [y]). */
    fun moveHandle(index: Int, x: Float, y: Float) {
        when (mode) {
            TransformMode.Free -> return
            TransformMode.Distort -> {
                val from = corners()
                val to = from.copyOf()
                to[index * 2] = x
                to[index * 2 + 1] = y
                // A corner dragged across the opposite edge would fold the
                // picture over itself (and the inverse would not exist).
                if (!isConvex(to)) return
                val m = Matrix()
                val w = warp
                if (w != null) {
                    if (!m.setPolyToPoly(from, 0, to, 0, 4)) return
                    m.mapPoints(w)
                } else {
                    if (!m.setPolyToPoly(boxCorners(), 0, to, 0, 4)) return
                    matrix.set(m)
                }
            }
            TransformMode.Warp -> {
                val w = warp ?: gridFromMatrix().also { warp = it }
                w[index * 2] = x
                w[index * 2 + 1] = y
            }
        }
        meshDst = null
        needsRebuild = true
    }

    private fun isConvex(q: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[i * 2]; val ay = q[i * 2 + 1]
            val bx = q[(i + 1) % 4 * 2]; val by = q[(i + 1) % 4 * 2 + 1]
            val cx = q[(i + 2) % 4 * 2]; val cy = q[(i + 2) % 4 * 2 + 1]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (kotlin.math.abs(cross) < 1e-3f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Applies [m] to whatever holds the shape (the warp grid, or the matrix). */
    private fun applyAffine(m: Matrix) {
        val w = warp
        if (w != null) {
            m.mapPoints(w)
            meshDst = null
        } else {
            matrix.postConcat(m)
        }
        needsRebuild = true
    }

    /**
     * A point of the warped surface at grid coordinates ([u], [v]) (0 to
     * GRID - 1): Catmull-Rom through the control points both ways, so the
     * surface passes through every handle, and an unwarped grid gives back
     * the plain mapping exactly.
     */
    private fun evalWarp(pts: FloatArray, u: Float, v: Float, out: FloatArray) {
        val n = GRID
        val iu = floor(u).toInt().coerceIn(0, n - 2)
        val iv = floor(v).toInt().coerceIn(0, n - 2)
        val tu = u - iu
        val tv = v - iv
        var x = 0f
        var y = 0f
        for (dj in -1..2) {
            val wv = cr(dj, tv)
            for (di in -1..2) {
                val wgt = cr(di, tu) * wv
                if (wgt == 0f) continue
                x += wgt * ctrl(pts, iu + di, iv + dj, 0)
                y += wgt * ctrl(pts, iu + di, iv + dj, 1)
            }
        }
        out[0] = x
        out[1] = y
    }

    /** Catmull-Rom weight of the point [k] (-1..2) steps from the segment start, at [t]. */
    private fun cr(k: Int, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return when (k) {
            -1 -> (-t3 + 2f * t2 - t) * 0.5f
            0 -> (3f * t3 - 5f * t2 + 2f) * 0.5f
            1 -> (-3f * t3 + 4f * t2 + t) * 0.5f
            else -> (t3 - t2) * 0.5f
        }
    }

    /** Control point (i, j), extended past the edges in a straight line. */
    private fun ctrl(pts: FloatArray, i: Int, j: Int, axis: Int): Float {
        val n = GRID
        fun at(a: Int, b: Int): Float = pts[(b * n + a) * 2 + axis]
        fun col(a: Int, b: Int): Float = when {
            b < 0 -> 2f * at(a, 0) - at(a, 1)
            b >= n -> 2f * at(a, n - 1) - at(a, n - 2)
            else -> at(a, b)
        }
        return when {
            i < 0 -> 2f * col(0, j) - col(1, j)
            i >= n -> 2f * col(n - 1, j) - col(n - 2, j)
            else -> col(i, j)
        }
    }

    /** The fine mesh's destination points (built once per change of the grid). */
    private fun ensureMesh(): FloatArray? {
        val w = warp ?: return null
        meshDst?.let { return it }
        val per = FINE
        val n = (GRID - 1) * per
        val dst = FloatArray((n + 1) * (n + 1) * 2)
        val src = FloatArray((n + 1) * (n + 1) * 2)
        val tmp = FloatArray(2)
        for (j in 0..n) for (i in 0..n) {
            val k = (j * (n + 1) + i) * 2
            evalWarp(w, i.toFloat() / per, j.toFloat() / per, tmp)
            dst[k] = tmp[0]
            dst[k + 1] = tmp[1]
            src[k] = box.left + box.width() * i / n
            src[k + 1] = box.top + box.height() * j / n
        }
        meshDst = dst
        meshSrc = src
        return dst
    }

    /** Each fine cell's destination bounds (left, top, right, bottom). */
    private fun cellBounds(): FloatArray {
        val dst = ensureMesh() ?: return FloatArray(0)
        val n = (GRID - 1) * FINE
        val out = FloatArray(n * n * 4)
        for (j in 0 until n) for (i in 0 until n) {
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for ((di, dj) in CORNERS) {
                val k = ((j + dj) * (n + 1) + i + di) * 2
                l = min(l, dst[k]); r = maxOf(r, dst[k])
                t = min(t, dst[k + 1]); b = maxOf(b, dst[k + 1])
            }
            val c = (j * n + i) * 4
            out[c] = l; out[c + 1] = t; out[c + 2] = r; out[c + 3] = b
        }
        return out
    }

    private fun cellTouches(cells: FloatArray, c: Int, area: RectF): Boolean {
        val o = c * 4
        return !(cells[o] > area.right || cells[o + 2] < area.left ||
            cells[o + 1] > area.bottom || cells[o + 3] < area.top)
    }

    /** Source bounds of the fine cells that land in [area], or null if none do. */
    private fun meshSourceFor(area: RectF, cells: FloatArray): RectF? {
        val src = meshSrc ?: return null
        val n = (GRID - 1) * FINE
        var out: RectF? = null
        for (c in 0 until n * n) {
            if (!cellTouches(cells, c, area)) continue
            val i = c % n
            val j = c / n
            val k0 = (j * (n + 1) + i) * 2
            val k1 = ((j + 1) * (n + 1) + i + 1) * 2
            if (out == null) out = RectF(src[k0], src[k0 + 1], src[k1], src[k1 + 1])
            else out.union(src[k0], src[k0 + 1], src[k1], src[k1 + 1])
        }
        return out
    }

    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var meshVerts = FloatArray(0)
    private var meshTexs = FloatArray(0)

    /**
     * Draws [image] (whose top-left is document ([ox], [oy])) through the warp
     * mesh into [c] (drawing in document coordinates) as textured triangles:
     * only the cells that touch [area], or all of them when it is null.
     */
    private fun drawMesh(
        c: Canvas, image: Bitmap, ox: Float, oy: Float,
        area: RectF?, cells: FloatArray? = null
    ) {
        val dst = ensureMesh() ?: return
        val src = meshSrc ?: return
        val n = (GRID - 1) * FINE
        if (meshVerts.size < n * n * 12) {
            meshVerts = FloatArray(n * n * 12)
            meshTexs = FloatArray(n * n * 12)
        }
        val verts = meshVerts
        val texs = meshTexs
        var count = 0
        for (j in 0 until n) for (i in 0 until n) {
            if (area != null && cells != null && !cellTouches(cells, j * n + i, area)) continue
            for ((di, dj) in TRIANGLES) {
                val k = ((j + dj) * (n + 1) + i + di) * 2
                verts[count] = dst[k]
                verts[count + 1] = dst[k + 1]
                texs[count] = src[k] - ox
                texs[count + 1] = src[k + 1] - oy
                count += 2
            }
        }
        if (count == 0) return
        meshPaint.shader = android.graphics.BitmapShader(
            image, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
        )
        c.drawVertices(
            Canvas.VertexMode.TRIANGLES, count, verts, 0, texs, 0,
            null, 0, null, 0, 0, meshPaint
        )
        meshPaint.shader = null
    }

    /** Everything the shape depends on, for putting a cancelled drag back. */
    fun state(): Any = Pair(Matrix(matrix), warp?.copyOf())

    @Suppress("UNCHECKED_CAST")
    fun restoreState(state: Any) {
        val (m, w) = state as Pair<Matrix, FloatArray?>
        matrix.set(m)
        warp = w?.copyOf()
        meshDst = null
        needsRebuild = true
    }

    /** Stitches the floating source out of its tiles for one patch rect. */
    private fun readSource(r: Rect, patch: Bitmap) {
        val c = Canvas(patch)
        c.drawColor(0, BlendMode.CLEAR)
        val ts = store.tileSize
        for (ty in r.top / ts..(r.bottom - 1) / ts) {
            for (tx in r.left / ts..(r.right - 1) / ts) {
                val k = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                val t = if (selection != null && !selection.isEmpty) {
                    sourceTiles[k]
                } else {
                    capture?.beforeAt(tx, ty)
                } ?: continue
                val x0 = tx * ts
                val y0 = ty * ts
                val l = maxOf(r.left, x0)
                val t0 = maxOf(r.top, y0)
                val rr = min(r.right, x0 + t.width)
                val b = min(r.bottom, y0 + t.height)
                if (rr <= l || b <= t0) continue
                c.drawBitmap(
                    t,
                    Rect(l - x0, t0 - y0, rr - x0, b - y0),
                    Rect(l - r.left, t0 - r.top, rr - r.left, b - r.top),
                    srcPaint
                )
            }
        }
    }

    /**
     * A cleared buffer of exactly [w] x [h], cached per size in [cache]. The
     * destination tile and the floating patch use separate caches, so a patch
     * that happens to be tile sized cannot clobber the tile being built.
     */
    private fun buf(w: Int, h: Int, cache: HashMap<Long, Bitmap>): Bitmap {
        val k = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)
        val b = cache.getOrPut(k) {
            // A drag can ask for many near-equal patch sizes; bound the cache.
            if (cache.size >= 12) {
                for (v in cache.values) v.recycle()
                cache.clear()
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        Canvas(b).drawColor(0, BlendMode.CLEAR)
        return b
    }

    fun ensureRebuilt() {
        if (needsRebuild) rebuild()
    }

    /** Puts the transform back exactly as [m] (a cancelled drag). */
    fun restoreMatrix(m: android.graphics.Matrix) {
        matrix.set(m)
        needsRebuild = true
    }

    fun translate(dx: Float, dy: Float) {
        applyAffine(Matrix().apply { setTranslate(dx, dy) })
    }

    fun scaleRotate(focusX: Float, focusY: Float, factor: Float, degrees: Float) {
        val m = Matrix()
        m.postRotate(degrees, focusX, focusY)
        m.postScale(factor, factor, focusX, focusY)
        applyAffine(m)
    }

    fun flipHorizontal() {
        val m = Matrix()
        m.postScale(-1f, 1f, engine.width / 2f, engine.height / 2f)
        applyAffine(m)
    }

    fun flipVertical() {
        val m = Matrix()
        m.postScale(1f, -1f, engine.width / 2f, engine.height / 2f)
        applyAffine(m)
    }

    /** Writes the transformed pixels back and returns the undo action. */
    fun commit(): HistoryAction? {
        ensureRebuilt()
        isActive = false
        if (sparse) {
            val cap = capture ?: return null
            capture = null
            releaseBuffers()
            if (dirtyRect.isEmpty) return null
            return engine.makeTilePatch(
                layer, cap, dirtyRect, "Transform", EditKind.Transform
            )
        }
        val original = originalDense ?: return null
        val after = layer.tiles.copyAll()
        val rect = Rect(0, 0, engine.width, engine.height)
        val action = PixelAction(
            layer.id, rect, original, after, "Transform", EditKind.Transform
        )
        originalDense = null
        return action
    }

    fun cancel() {
        isActive = false
        if (sparse) {
            val cap = capture ?: return
            capture = null
            cap.restore()
            cap.release()
            releaseBuffers()
            layer.touch()
            if (!dirtyRect.isEmpty) engine.markDirty(dirtyRect) else engine.markDirtyAll()
            return
        }
        val original = originalDense ?: return
        layer.tiles.withWriteCanvas(Rect(0, 0, engine.width, engine.height)) { c ->
            val p = Paint()
            p.blendMode = BlendMode.SRC
            c.drawBitmap(original, 0f, 0f, p)
        }
        layer.touch()
        engine.markDirtyAll()
    }

    private companion object {
        /** Warp handles per side. */
        const val GRID = 4
        /** Mesh cells per warp cell. */
        const val FINE = 8
        val CORNERS = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
        /** Two triangles per cell: (0,0)-(1,0)-(1,1) and (0,0)-(1,1)-(0,1). */
        val TRIANGLES = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 0, 1 to 1, 0 to 1)
    }

    private fun releaseBuffers() {
        for (b in baseTiles.values) b.recycle()
        baseTiles.clear()
        for (b in sourceTiles.values) b.recycle()
        sourceTiles.clear()
        for (b in destBufs.values) b.recycle()
        destBufs.clear()
        for (b in patchBufs.values) b.recycle()
        patchBufs.clear()
    }
}
