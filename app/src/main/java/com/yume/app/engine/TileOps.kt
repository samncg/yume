package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Tile-granular implementations of the one-shot operations: adjustments,
 * merges, copies and clears.
 *
 * Everything here works one tile at a time and never allocates a canvas-sized
 * surface, so the cost of an operation scales with the tiles it touches. Where
 * an operation has a finite support (the box blur) each tile is computed from a
 * patch grown by that support, so tiles outside the halo cannot leak in and tile
 * seams cannot appear. The dense path of each operation is left untouched at its
 * call site so the recorded golden oracle keeps reproducing byte for byte.
 */
object TileOps {

    /** Scratch bitmaps reused between operations, keyed by size. */
    private val scratch = HashMap<Long, Bitmap>()

    private val srcPaint = Paint().apply {
        blendMode = BlendMode.SRC
        isFilterBitmap = false
    }
    private val copyPaint = Paint().apply {
        blendMode = BlendMode.SRC_OVER
        isFilterBitmap = false
        isAntiAlias = false
    }

    private fun key(w: Int, h: Int): Long = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

    /** A cleared scratch bitmap of exactly [w] x [h]. Render thread only. */
    fun scratch(w: Int, h: Int): Bitmap {
        val b = scratch.getOrPut(key(w, h)) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        Canvas(b).drawColor(0, BlendMode.CLEAR)
        return b
    }

    /** Drops cached scratch bitmaps when a document closes. */
    fun releaseScratch() {
        for (b in scratch.values) b.recycle()
        scratch.clear()
    }

    // ------------------------------------------------------------------ tiles

    fun tileCountX(store: TileStore): Int =
        if (store.width <= 0) 0 else (store.width + store.tileSize - 1) / store.tileSize

    fun tileCountY(store: TileStore): Int =
        if (store.height <= 0) 0 else (store.height + store.tileSize - 1) / store.tileSize

    /** The document rect of one tile, clipped to the canvas. */
    fun tileRect(store: TileStore, tx: Int, ty: Int): Rect {
        val ts = store.tileSize
        val x0 = tx * ts
        val y0 = ty * ts
        return Rect(x0, y0, min(store.width, x0 + ts), min(store.height, y0 + ts))
    }

    /** Tile index range covered by [rect], clamped to the store. */
    fun tileRangeX(store: TileStore, rect: Rect): IntRange {
        if (rect.isEmpty) return IntRange.EMPTY
        val last = tileCountX(store) - 1
        if (last < 0) return IntRange.EMPTY
        val a = (rect.left / store.tileSize).coerceIn(0, last)
        val b = ((rect.right - 1) / store.tileSize).coerceIn(0, last)
        return if (b < a) IntRange.EMPTY else a..b
    }

    fun tileRangeY(store: TileStore, rect: Rect): IntRange {
        if (rect.isEmpty) return IntRange.EMPTY
        val last = tileCountY(store) - 1
        if (last < 0) return IntRange.EMPTY
        val a = (rect.top / store.tileSize).coerceIn(0, last)
        val b = ((rect.bottom - 1) / store.tileSize).coerceIn(0, last)
        return if (b < a) IntRange.EMPTY else a..b
    }

    /** Captures every tile of [store] that intersects [rect], before an edit. */
    fun capture(store: TileStore, rect: Rect): TileCapture {
        val c = TileCapture(store)
        for (ty in tileRangeY(store, rect)) {
            for (tx in tileRangeX(store, rect)) c.capture(tx, ty)
        }
        return c
    }

    /**
     * Visits every tile of [store] that intersects [bounds] with a patch
     * covering the tile grown by [halo] and clipped to the canvas. That is
     * exactly the neighbourhood the whole-canvas pass reads for a filter of
     * finite support, including the clamp at the canvas edge.
     */
    inline fun forEachTile(
        store: TileStore,
        bounds: Rect,
        halo: Int,
        op: (patch: Bitmap, patchRect: Rect, tile: Rect) -> Unit
    ) {
        for (ty in tileRangeY(store, bounds)) {
            for (tx in tileRangeX(store, bounds)) {
                val tile = tileRect(store, tx, ty)
                if (tile.isEmpty) continue
                if (tile.right <= bounds.left || tile.left >= bounds.right ||
                    tile.bottom <= bounds.top || tile.top >= bounds.bottom
                ) {
                    continue
                }
                val pad = Rect(tile)
                if (halo > 0) {
                    pad.left = max(0, tile.left - halo)
                    pad.top = max(0, tile.top - halo)
                    pad.right = min(store.width, tile.right + halo)
                    pad.bottom = min(store.height, tile.bottom + halo)
                }
                val patch = store.readRect(pad)
                op(patch, pad, tile)
                patch.recycle()
            }
        }
    }

    // ------------------------------------------------------------ operations

    /**
     * Applies a per-pixel colour filter over [bounds]. A colour matrix has no
     * neighbourhood, so applying it tile by tile gives exactly the pixels of a
     * whole-surface pass.
     */
    fun applyFilter(store: TileStore, bounds: Rect, filter: ColorFilter) {
        val paint = Paint()
        paint.colorFilter = filter
        paint.isFilterBitmap = false
        forEachTile(store, bounds, 0) { patch, pad, tile ->
            val tmp = scratch(patch.width, patch.height)
            Canvas(tmp).drawBitmap(patch, 0f, 0f, paint)
            writePart(store, tmp, pad, tile)
        }
    }

    /**
     * Three-pass box blur over [bounds], computed one tile at a time from a
     * patch grown by the full support of three passes (3 * radius). The result
     * is identical to blurring the whole surface, including the edge clamp.
     *
     * [capture] is the pre-edit snapshot of the same region: a blurred tile must
     * not read a neighbour that has already been blurred, so the source comes
     * from the snapshot rather than the live store.
     */
    fun blur(store: TileStore, capture: TileCapture, bounds: Rect, radius: Int) {
        if (radius <= 0) return
        val halo = radius * 3
        for (ty in tileRangeY(store, bounds)) {
            for (tx in tileRangeX(store, bounds)) {
                val tile = tileRect(store, tx, ty)
                if (tile.isEmpty) continue
                if (!intersects(tile, bounds)) continue
                val pad = Rect(tile)
                pad.left = max(0, tile.left - halo)
                pad.top = max(0, tile.top - halo)
                pad.right = min(store.width, tile.right + halo)
                pad.bottom = min(store.height, tile.bottom + halo)
                val patch = scratch(pad.width(), pad.height())
                readSnapshot(capture, pad, patch)
                Adjustments.blur(patch, radius)
                writePart(store, patch, pad, tile)
            }
        }
    }

    /**
     * Adds film grain over [bounds]. The grain is a hash of the absolute canvas
     * position, so the same pixel always gets the same offset no matter how the
     * work is split into tiles.
     */
    fun noise(store: TileStore, bounds: Rect, amount: Float) {
        if (amount <= 0.001f) return
        forEachTile(store, bounds, 0) { patch, pad, tile ->
            Adjustments.noise(patch, amount, pad.left, pad.top)
            writePart(store, patch, pad, tile)
        }
    }

    /** Tone mapping (curves, levels, colour balance, gradient map), tile by tile. */
    fun tone(store: TileStore, bounds: Rect, lut: ToneLut) {
        forEachTile(store, bounds, 0) { patch, pad, tile ->
            lut.apply(patch)
            writePart(store, patch, pad, tile)
        }
    }

    /** Fills [patch] (covering [rect]) from the captured pre-edit snapshot. */
    fun readSnapshot(capture: TileCapture, rect: Rect, patch: Bitmap) {
        val store = capture.store
        val c = Canvas(patch)
        c.drawColor(store.defaultPixel, BlendMode.SRC)
        for (ty in tileRangeY(store, rect)) {
            for (tx in tileRangeX(store, rect)) {
                if (!capture.has(tx, ty)) continue
                val tile = capture.beforeAt(tx, ty) ?: continue
                val x0 = tx * store.tileSize
                val y0 = ty * store.tileSize
                val l = max(rect.left, x0)
                val t = max(rect.top, y0)
                val r = min(rect.right, x0 + tile.width)
                val b = min(rect.bottom, y0 + tile.height)
                if (r <= l || b <= t) continue
                c.drawBitmap(
                    tile,
                    Rect(l - x0, t - y0, r - x0, b - y0),
                    Rect(l - rect.left, t - rect.top, r - rect.left, b - rect.top),
                    srcPaint
                )
            }
        }
    }

    private fun intersects(a: Rect, b: Rect): Boolean =
        a.right > b.left && a.left < b.right && a.bottom > b.top && a.top < b.bottom

    /** Writes the [tile] part of [patch] (whose origin is [patchRect]) back. */
    fun writePart(store: TileStore, patch: Bitmap, patchRect: Rect, tile: Rect) {
        val l = max(tile.left, patchRect.left)
        val t = max(tile.top, patchRect.top)
        val r = min(tile.right, patchRect.right)
        val b = min(tile.bottom, patchRect.bottom)
        if (r <= l || b <= t) return
        val src = Rect(l - patchRect.left, t - patchRect.top, r - patchRect.left, b - patchRect.top)
        store.writeRect(Rect(l, t, r, b), patch, src)
    }

    /**
     * Draws [source] into [dest] over [bounds] with [paint] (alpha and blend
     * mode), one tile at a time. Blending is per pixel, so the result equals a
     * whole-surface composite. Missing source tiles read as transparent, which
     * is a no-op for every blend mode in use.
     */
    fun mergeInto(dest: TileStore, source: TileStore, bounds: Rect, paint: Paint) {
        for (ty in tileRangeY(dest, bounds)) {
            for (tx in tileRangeX(dest, bounds)) {
                val tile = tileRect(dest, tx, ty)
                if (tile.isEmpty) continue
                if (tile.right <= bounds.left || tile.left >= bounds.right ||
                    tile.bottom <= bounds.top || tile.top >= bounds.bottom
                ) {
                    continue
                }
                val s = source.getTile(tx, ty)
                if (s == null) continue
                val d = dest.getTile(tx, ty)
                val tmp = scratch(tile.width(), tile.height())
                val c = Canvas(tmp)
                if (d != null) c.drawBitmap(d, 0f, 0f, copyPaint)
                c.drawBitmap(s, 0f, 0f, paint)
                dest.writeRect(tile, tmp, Rect(0, 0, tile.width(), tile.height()))
            }
        }
    }

    /** Copies every non-empty tile of [source] into the empty [dest]. */
    fun copyTiles(source: TileStore, dest: TileStore) {
        val rect = Rect(0, 0, source.width, source.height)
        for (ty in tileRangeY(source, rect)) {
            for (tx in tileRangeX(source, rect)) {
                if (source.isTileEmpty(tx, ty)) continue
                val tile = source.getTile(tx, ty) ?: continue
                dest.putTile(tx, ty, tile)
            }
        }
    }
}
