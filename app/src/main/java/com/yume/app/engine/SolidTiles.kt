package com.yume.app.engine

import android.graphics.Bitmap
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Registry of shared, immutable "solid" tile bitmaps.
 *
 * A tile whose pixels are all the same is replaced by a reference to one
 * immutable bitmap shared by every tile (in any store) with the same raw pixel
 * value and the same size. Read paths need no special case: a solid tile is an
 * ordinary [Bitmap] that happens to be shared. Writes must go through the
 * store's unshare step first; Android refuses a [android.graphics.Canvas] on an
 * immutable bitmap, so a missed write path throws instead of corrupting every
 * other tile that shares the bitmap.
 *
 * Solids are keyed by the RAW premultiplied pixel bytes (not the unpremultiplied
 * colour int), and each one is created as a byte copy of a tile that was
 * actually produced by the normal write path. No colour conversion happens, so
 * a solid is byte-identical to the tile it replaces.
 *
 * The registry holds weak references only: a solid lives exactly as long as some
 * store references it, and is never recycled or pooled by hand.
 */
object SolidTiles {

    private val byKey = HashMap<Long, WeakReference<Bitmap>>()
    /** Identity set (Bitmap does not override equals) -> unpremultiplied colour. */
    private val colours = WeakHashMap<Bitmap, Int>()

    /** Shared solids created (a fresh immutable bitmap was made). */
    var created: Long = 0L
        private set
    /** Requests served by an existing shared solid. */
    var reused: Long = 0L
        private set

    private fun key(raw: Int, w: Int, h: Int): Long =
        (raw.toLong() shl 32) or ((w.toLong() and 0xFFFFL) shl 16) or (h.toLong() and 0xFFFFL)

    /**
     * Returns the shared solid for a uniform [sample] whose every pixel has the
     * raw value [raw] and reads back as [colour]. [sample] itself is not
     * retained. Returns null when the immutable copy cannot be made.
     */
    @Synchronized
    fun obtain(sample: Bitmap, raw: Int, colour: Int): Bitmap? {
        val k = key(raw, sample.width, sample.height)
        byKey[k]?.get()?.let {
            reused++
            return it
        }
        val shared = runCatching { sample.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: return null
        if (shared.isMutable) return null
        colours[shared] = colour
        byKey[k] = WeakReference(shared)
        created++
        return shared
    }

    @Synchronized
    fun isShared(bitmap: Bitmap): Boolean = colours.containsKey(bitmap)

    /** Unpremultiplied colour of a shared solid, or null if [bitmap] is not one. */
    @Synchronized
    fun colourOf(bitmap: Bitmap): Int? = colours[bitmap]

    /** Live shared solids and their pixel bytes (each counted once). */
    @Synchronized
    fun stats(): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        for (b in colours.keys) {
            n++
            bytes += b.allocationByteCount.toLong()
        }
        return n to bytes
    }

    @Synchronized
    fun resetCounters() {
        created = 0
        reused = 0
        byKey.entries.removeAll { it.value.get() == null }
    }
}
