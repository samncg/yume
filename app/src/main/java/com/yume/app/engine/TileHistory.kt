package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.min

/**
 * Per-tile copy-on-first-touch snapshot of a store while a stroke is in flight.
 *
 * A tile's "before" is captured the first time the stroke writes into it, so a
 * stroke that touches a few tiles of an 8192x8192 layer copies a few 256x256
 * tiles instead of the whole canvas. A null entry means the tile did not exist
 * before the stroke (that is, it read as the store's default pixel).
 */
class TileCapture(val store: TileStore) {

    private val before = HashMap<Long, Bitmap?>()
    /** Union of every captured tile, in document pixels. */
    val bounds = Rect()

    private fun key(tx: Int, ty: Int): Long =
        (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

    fun capture(tx: Int, ty: Int) {
        val k = key(tx, ty)
        if (before.containsKey(k)) return
        before[k] = store.readTileCopy(tx, ty)
        extendBounds(tx, ty)
        if (CompositeProfile.enabled) CompositeProfile.captures++
    }

    /**
     * Records that the tile was empty before the edit without reading it.
     * Needed by operations whose destination set is only known after they have
     * started writing (a transform moved across the canvas).
     */
    fun markEmpty(tx: Int, ty: Int) {
        val k = key(tx, ty)
        if (before.containsKey(k)) return
        before[k] = null
        extendBounds(tx, ty)
    }

    private fun extendBounds(tx: Int, ty: Int) {
        val x0 = tx * store.tileSize
        val y0 = ty * store.tileSize
        val x1 = min(store.width, x0 + store.tileSize)
        val y1 = min(store.height, y0 + store.tileSize)
        if (bounds.isEmpty) bounds.set(x0, y0, x1, y1) else bounds.union(x0, y0, x1, y1)
    }

    val size: Int get() = before.size

    fun keys(): Set<Long> = before.keys

    fun has(tx: Int, ty: Int): Boolean = before.containsKey(key(tx, ty))

    fun keyOf(tx: Int, ty: Int): Long = key(tx, ty)

    /** Captured pixel of one tile (null when the tile did not exist). */
    fun beforeAt(tx: Int, ty: Int): Bitmap? = before[key(tx, ty)]

    fun beforeTile(k: Long): Bitmap? = before[k]

    /** Puts every captured tile back, then leaves the capture reusable. */
    fun restore() {
        for ((k, tile) in before) {
            val tx = (k ushr 32).toInt()
            val ty = k.toInt()
            if (tile == null) store.clearTile(tx, ty) else store.putTile(tx, ty, tile)
        }
    }

    /**
     * Frees the snapshot. Tiles whose keys are in [keep] are transferred to a
     * history entry, so they must not be recycled here.
     */
    fun release(keep: Set<Long> = emptySet()) {
        for ((k, tile) in before) {
            if (k !in keep) tile?.recycle()
        }
        before.clear()
    }
}

/**
 * Before/after pixels for one tile, cropped to the document region [clip] that
 * the owning action covered. A null bitmap means that region is the store
 * default. Crops (not whole tiles) keep history memory proportional to the area
 * actually edited while still restoring exactly the same pixels as the dense
 * rectangle patch.
 */
class TileChange(
    val tx: Int,
    val ty: Int,
    val clip: Rect,
    val before: Bitmap?,
    val after: Bitmap?
)

/**
 * A pixel edit stored as per-tile before/after snapshots. Undo and redo touch
 * only the tiles the edit actually changed, so memory scales with painted area
 * instead of with the canvas or the stroke's bounding box.
 */
class TilePixelAction(
    override val layerId: Long,
    val bounds: Rect,
    internal val changes: List<TileChange>,
    override val label: String,
    override val kind: EditKind,
    internal val detail: String,
    internal val swatch: Int,
    internal val mask: Boolean
) : HistoryAction() {

    override val bytes: Long
        get() {
            var total = 0L
            for (c in changes) {
                total += (c.before?.byteCount ?: 0).toLong()
                total += (c.after?.byteCount ?: 0).toLong()
            }
            return total
        }

    override fun undo(engine: DrawingEngine) =
        engine.applyTileChanges(layerId, changes, bounds, before = true, mask = mask)

    override fun redo(engine: DrawingEngine) =
        engine.applyTileChanges(layerId, changes, bounds, before = false, mask = mask)

    internal fun releaseTiles() {
        for (c in changes) {
            c.before?.recycle()
            c.after?.recycle()
        }
    }
}
