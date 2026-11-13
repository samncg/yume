package com.yume.app.engine

import android.graphics.Bitmap

/**
 * Debug-only counter of full-canvas bitmap allocations, per creation site.
 *
 * A "full-canvas" allocation is one whose width and height are exactly the
 * document size. After the tiled compositor and dab-scratch work, a sparse
 * document may only allocate one of those: the surface handed to the PNG
 * encoder. Painting, compositing and one-shot operations must scale with
 * painted area, so any other canvas-sized allocation is a regression.
 *
 * Disabled by default; when [enabled] is false the only cost is the boolean
 * check, and [bitmap] simply allocates.
 */
object AllocTracker {

    /**
     * The one canvas-sized assembly a sparse document is allowed to build: the
     * surface handed to the PNG encoder. Both "Save now" and export go through
     * this single site so the counter stays meaningful.
     */
    const val ENCODE_SITE: String = "encode.canvas"

    /** Sites that may allocate at canvas size in a sparse document. */
    // Stage 3c: saving, loading and export are banded, so nothing may
    // allocate at canvas size any more. ENCODE_SITE is kept for the dense
    // flatten() the harness still uses as a reference.
    val allowedSites: Set<String> = emptySet()

    /** Allocates the canvas-sized surface used by the PNG encoder. */
    fun encodeCanvas(w: Int, h: Int): Bitmap = bitmap(ENCODE_SITE, w, h)

    @Volatile var enabled: Boolean = false

    @Volatile private var canvasW: Int = 0
    @Volatile private var canvasH: Int = 0

    private val counts = LinkedHashMap<String, Int>()
    private val byteTotals = LinkedHashMap<String, Long>()

    /** Starts a measurement window for a [w] x [h] document. */
    @Synchronized
    fun reset(w: Int, h: Int) {
        canvasW = w
        canvasH = h
        counts.clear()
        byteTotals.clear()
    }

    /**
     * Allocates an ARGB bitmap and records it when it matches the canvas size.
     * Use this at every site that can allocate a document-sized surface.
     */
    fun bitmap(site: String, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (enabled && w == canvasW && h == canvasH) record(site, w.toLong() * h.toLong() * 4L)
        return b
    }

    @Synchronized
    private fun record(site: String, n: Long) {
        counts[site] = (counts[site] ?: 0) + 1
        byteTotals[site] = (byteTotals[site] ?: 0L) + n
    }

    @Synchronized
    fun fullCanvasCount(): Int = counts.values.sum()

    @Synchronized
    fun sites(): Map<String, Int> = LinkedHashMap(counts)

    /** One line per site: `site=n`. */
    @Synchronized
    fun table(): String {
        if (counts.isEmpty()) return "none"
        val sb = StringBuilder()
        for ((site, n) in counts) {
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(site).append('=').append(n)
        }
        return sb.toString()
    }

    @Synchronized
    fun bytesTable(): String {
        if (byteTotals.isEmpty()) return "none"
        val sb = StringBuilder()
        for ((site, n) in byteTotals) {
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(site).append('=').append(n / (1024 * 1024)).append("MB")
        }
        return sb.toString()
    }
}
