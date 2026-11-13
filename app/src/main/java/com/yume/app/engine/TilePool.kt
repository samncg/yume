package com.yume.app.engine

import android.graphics.Bitmap

/**
 * Pool of tile bitmaps.
 *
 * Sparse layers allocate and free 256x256 tiles constantly: every new dab area
 * materialises tiles, and erase, prune, undo-capture release and layer clear
 * drop them again. Letting the collector handle that churn shows up as GC
 * pauses inside a stroke, so tiles are instead returned here and reused.
 *
 * Ownership rule: only a bitmap the store owns exclusively may be released. The
 * store's tile map, its per-tile canvases (dropped at the same time), the
 * composite store and the undo capture copies all meet that. Bitmaps that are
 * still reachable elsewhere must be recycled or left alone, never pooled.
 *
 * The pool is bounded by total bytes; when it is full, released bitmaps are
 * recycled as before so memory cannot creep up.
 */
object TilePool {

    /** Freed pixels kept for reuse. About 96 tiles at the default tile size. */
    @Volatile
    var maxBytes: Long = 24L * 1024L * 1024L

    private val free = HashMap<Long, ArrayDeque<Bitmap>>()
    private var bytes = 0L

    /** Fresh allocations (pool was empty for this size). */
    var allocations: Long = 0L
        private set
    /** Acquisitions served from the pool. */
    var hits: Long = 0L
        private set
    /** Bitmaps returned to the pool. */
    var releases: Long = 0L
        private set
    /** Bitmaps recycled because the pool was full or the size does not fit. */
    var dropped: Long = 0L
        private set

    val heldBytes: Long get() = bytes

    private fun key(w: Int, h: Int): Long =
        (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

    /**
     * A mutable tile of exactly [w] x [h], filled with [fill]. The result is
     * always fully written, so callers never inherit a previous tile's pixels.
     */
    @Synchronized
    fun acquire(w: Int, h: Int, fill: Int = 0): Bitmap {
        val k = key(w, h)
        val q = free[k]
        val bmp = if (q != null && q.isNotEmpty()) {
            val b = q.removeLast()
            bytes -= b.allocationByteCount.toLong()
            hits++
            if (b.isRecycled || b.width != w || b.height != h) {
                // Defensive: never hand out a bitmap we cannot trust.
                allocations++
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(fill) }
            } else {
                b.eraseColor(fill)
                b
            }
        } else {
            allocations++
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(fill) }
        }
        return bmp
    }

    /** Returns an exclusively owned bitmap for reuse, or recycles it. */
    @Synchronized
    fun release(bmp: Bitmap) {
        if (bmp.isRecycled) return
        // A shared solid is never exclusively owned: other tiles still read it.
        if (!bmp.isMutable || SolidTiles.isShared(bmp)) return
        val n = bmp.allocationByteCount.toLong()
        if (bytes + n > maxBytes) {
            dropped++
            bmp.recycle()
            return
        }
        free.getOrPut(key(bmp.width, bmp.height)) { ArrayDeque() }.addLast(bmp)
        bytes += n
        releases++
    }

    /** Drops everything (document close, harness reset). */
    @Synchronized
    fun reset() {
        for (q in free.values) {
            for (b in q) if (!b.isRecycled) b.recycle()
        }
        free.clear()
        bytes = 0
    }

    @Synchronized
    fun resetCounters() {
        allocations = 0
        hits = 0
        releases = 0
        dropped = 0
    }

    @Synchronized
    fun stats(): String =
        "tilePool alloc=$allocations hit=$hits rel=$releases drop=$dropped" +
            " held=${bytes / 1024}KB"
}
