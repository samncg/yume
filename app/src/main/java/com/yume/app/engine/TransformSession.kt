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
        rebuild()
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
            c.drawBitmap(src, matrix, filterPaint)
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
        matrix.mapRect(out)
        out.inset(-2f, -2f)
        return out
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
        matrix.postTranslate(dx, dy)
        needsRebuild = true
    }

    fun scaleRotate(focusX: Float, focusY: Float, factor: Float, degrees: Float) {
        val m = Matrix()
        m.postRotate(degrees, focusX, focusY)
        m.postScale(factor, factor, focusX, focusY)
        matrix.postConcat(m)
        needsRebuild = true
    }

    fun flipHorizontal() {
        val cx = engine.width / 2f
        val m = Matrix()
        m.postScale(-1f, 1f, cx, engine.height / 2f)
        matrix.postConcat(m)
        needsRebuild = true
    }

    fun flipVertical() {
        val cy = engine.height / 2f
        val m = Matrix()
        m.postScale(1f, -1f, engine.width / 2f, cy)
        matrix.postConcat(m)
        needsRebuild = true
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
