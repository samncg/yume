package com.yume.app.engine

import android.graphics.Bitmap

/**
 * A small LRU pool of scratch bitmaps, keyed by their exact pixel size.
 *
 * The sparse compositor draws into scratch surfaces whose size is the dirty
 * region, so the sizes it asks for change with the stroke: a dab-sized rect, a
 * whole tile, a tile clipped at the canvas edge. Allocating a fresh bitmap per
 * frame would churn the collector, so buffers are kept and reused.
 *
 * Keys are exact `(w, h)` because a blit back into the composite must be a
 * whole-bitmap integer translation; a buffer that is merely *at least* big
 * enough would need a sub-rect source blit, which is measurably not
 * byte-identical (see the Stage 2b-2 notes).
 *
 * [get] never evicts, so a buffer handed out stays valid for the whole draw.
 * [trim] is called at the start of a composite pass, when nothing is in use,
 * and drops least-recently-used entries beyond the entry or byte cap.
 */
class ScratchPool(
    private val site: String,
    private val maxEntries: Int = 16,
    private val maxBytes: Long = 8L * 1024L * 1024L
) {

    /** Access-ordered so the iterator's first entry is the least recently used. */
    private val map = LinkedHashMap<Long, Bitmap>(32, 0.75f, true)

    var hits: Long = 0
        private set
    var misses: Long = 0
        private set

    /** Bytes currently held by pooled buffers. */
    var bytes: Long = 0
        private set

    val size: Int get() = map.size

    private fun key(w: Int, h: Int): Long =
        (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

    /** A buffer of exactly [w] x [h]. Contents are undefined. */
    fun get(w: Int, h: Int): Bitmap {
        val k = key(w, h)
        val existing = map[k]
        if (existing != null && !existing.isRecycled) {
            hits++
            return existing
        }
        misses++
        if (existing != null) {
            bytes -= existing.byteCount.toLong()
            map.remove(k)
        }
        val bmp = AllocTracker.bitmap(site, w, h)
        map[k] = bmp
        bytes += bmp.byteCount.toLong()
        return bmp
    }

    /** Drops the least recently used buffers beyond the caps. */
    fun trim() {
        if (map.size <= maxEntries && bytes <= maxBytes) return
        val it = map.entries.iterator()
        while (it.hasNext() && (map.size > maxEntries || bytes > maxBytes)) {
            val e = it.next()
            bytes -= e.value.byteCount.toLong()
            if (!e.value.isRecycled) e.value.recycle()
            it.remove()
        }
    }

    fun reset() {
        for (b in map.values) if (!b.isRecycled) b.recycle()
        map.clear()
        bytes = 0
    }
}
