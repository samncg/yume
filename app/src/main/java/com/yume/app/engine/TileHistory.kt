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

    companion object {
        /** Bytes held by all live captures (tile-cache accounting, render thread). */
        @Volatile var liveBytes: Long = 0L
    }

    private val before = HashMap<Long, Bitmap?>()
    /** Union of every captured tile, in document pixels. */
    val bounds = Rect()

    private fun key(tx: Int, ty: Int): Long =
        (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

    fun capture(tx: Int, ty: Int) {
        val k = key(tx, ty)
        if (before.containsKey(k)) return
        // Pinned until release: an active stroke / transform / adjustment never
        // has its tiles evicted from under it.
        (store as? SparseTileStore)?.pinTile(tx, ty)
        // A shared solid is immutable: keep it by reference, no copy.
        val shared = (store as? SparseTileStore)?.sharedSolidAt(tx, ty)
        val copy = shared ?: store.readTileCopy(tx, ty)
        before[k] = copy
        if (copy != null && shared == null) liveBytes += copy.byteCount.toLong()
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
        (store as? SparseTileStore)?.pinTile(tx, ty)
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
        (store as? SparseTileStore)?.let { s ->
            for (k in before.keys) s.unpinTile((k ushr 32).toInt(), k.toInt())
        }
        for ((_, tile) in before) if (tile != null && !SolidTiles.isShared(tile)) liveBytes -= tile.byteCount.toLong()
        for ((k, tile) in before) {
            if (k in keep || tile == null || SolidTiles.isShared(tile)) continue
            // Captured copies are exclusively owned by the capture, so they can
            // be reused instead of waiting for the collector.
            TilePool.release(tile)
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
    var before: Bitmap?,
    var after: Bitmap?
) {
    /** Spill keys while the crop lives on disk (Stage 3c); -1 = in memory. */
    var beforeKey: Long = -1L
    var afterKey: Long = -1L
}

/**
 * Disk tier for undo history (Stage 3c): older tile crops are written here when
 * the in-memory history passes its cap, and read back on undo / redo. Reuses
 * the CRC-checked [SpillFile] log, keyed by a running counter.
 */
class HistorySpill(file: java.io.File) {
    private val spill = SpillFile(file)
    private val scratch = SpillFile.ReadScratch()
    private var nextKey = 1L
    var writes: Long = 0L; private set
    var reads: Long = 0L; private set

    val diskBytes: Long get() = spill.liveBytes

    /** Writes [bmp]; returns its key. Throws IOException on failure. */
    fun write(bmp: Bitmap): Long {
        val k = nextKey++
        spill.write(k, bmp)
        writes++
        return k
    }

    /** Reads the crop stored under [key]. Throws IOException when it is gone or corrupt. */
    fun read(key: Long): Bitmap {
        val rec = spill.lookup(key) ?: throw java.io.IOException("history record $key missing")
        val bmp = Bitmap.createBitmap(rec.w, rec.h, Bitmap.Config.ARGB_8888)
        try {
            spill.read(rec, bmp, scratch)
        } catch (e: java.io.IOException) {
            bmp.recycle()
            throw e
        }
        reads++
        return bmp
    }

    fun drop(key: Long) {
        if (key >= 0) spill.drop(key)
    }

    fun compactIfNeeded() {
        if (spill.shouldCompact()) runCatching { spill.compact(scratch) }
    }

    fun close() = spill.close(delete = true)
}

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
                c.before?.let { if (!SolidTiles.isShared(it)) total += it.byteCount }
                c.after?.let { if (!SolidTiles.isShared(it)) total += it.byteCount }
            }
            return total
        }

    override fun undo(engine: DrawingEngine) =
        engine.applyTileChanges(layerId, changes, bounds, before = true, mask = mask)

    override fun redo(engine: DrawingEngine) =
        engine.applyTileChanges(layerId, changes, bounds, before = false, mask = mask)

    internal fun releaseTiles(spill: HistorySpill? = null) {
        for (c in changes) {
            // Shared solids belong to every store using them: never recycled.
            c.before?.let { if (!SolidTiles.isShared(it)) it.recycle() }
            c.after?.let { if (!SolidTiles.isShared(it)) it.recycle() }
            c.before = null
            c.after = null
            spill?.drop(c.beforeKey)
            spill?.drop(c.afterKey)
            c.beforeKey = -1L
            c.afterKey = -1L
        }
    }

    /** True when every crop is in memory. */
    val isResident: Boolean get() = changes.none { it.beforeKey >= 0 || it.afterKey >= 0 }

    /**
     * Moves every in-memory crop to [spill] and frees it. Returns the bytes
     * freed. On a write failure the remaining crops stay in memory.
     */
    fun spillTo(spill: HistorySpill): Long {
        var freed = 0L
        try {
            for (c in changes) {
                c.before?.takeIf { !SolidTiles.isShared(it) }?.let { b ->
                    c.beforeKey = spill.write(b)
                    freed += b.byteCount
                    b.recycle()
                    c.before = null
                }
                c.after?.takeIf { !SolidTiles.isShared(it) }?.let { b ->
                    c.afterKey = spill.write(b)
                    freed += b.byteCount
                    b.recycle()
                    c.after = null
                }
            }
        } catch (_: java.io.IOException) {
            // Keep whatever is still in memory; nothing is lost.
        }
        return freed
    }

    /** Reads spilled crops back. Throws IOException if one is unreadable. */
    fun loadFrom(spill: HistorySpill) {
        for (c in changes) {
            if (c.beforeKey >= 0) {
                c.before = spill.read(c.beforeKey)
                spill.drop(c.beforeKey)
                c.beforeKey = -1L
            }
            if (c.afterKey >= 0) {
                c.after = spill.read(c.afterKey)
                spill.drop(c.afterKey)
                c.afterKey = -1L
            }
        }
    }
}
