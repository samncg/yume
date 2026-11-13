package com.yume.app.engine

import android.app.ActivityManager

/**
 * Coarse memory budget for canvas pixels.
 *
 * The engine allocates whole-canvas surfaces (the flattened composite and the
 * pre-stroke snapshot) next to one bitmap per layer, so the costs below are
 * estimates rather than exact accounting. They exist to refuse impossible
 * documents with a clear message instead of dying with an OutOfMemoryError,
 * which is what used to happen when a large canvas or many layers were created.
 *
 * Sparse layers and the sparse composite make the layer and composite terms
 * proportional to painted area instead of canvas area, so those call sites are
 * budgeted from resident tile bytes (see DrawingEngine.memoryReport). The
 * dense terms below still gate the dense oracle path and the PNG encoder.
 */
object MemoryBudget {

    private const val MB = 1024L * 1024L

    /** Surfaces every document allocates regardless of layer count. */
    private const val SCRATCH_SURFACES = 2

    /** Fraction of the heap we are willing to spend on canvas pixels. */
    private const val BUDGET_FRACTION_NUM = 7L
    private const val BUDGET_FRACTION_DEN = 10L

    @Volatile
    private var heapBytes: Long = Runtime.getRuntime().maxMemory()

    /** Called once at start-up; ActivityManager can report a smaller large-heap. */
    fun configure(activityManager: ActivityManager?) {
        val runtimeMax = Runtime.getRuntime().maxMemory()
        val declared = (activityManager?.largeMemoryClass ?: 0).toLong() * MB
        heapBytes = if (declared in 1..runtimeMax) declared else runtimeMax
    }

    val heap: Long get() = heapBytes

    val canvasBudget: Long
        get() = heapBytes / BUDGET_FRACTION_DEN * BUDGET_FRACTION_NUM

    /** Bytes one dense ARGB layer of this size needs. */
    fun layerBytes(width: Int, height: Int): Long =
        width.toLong() * height.toLong() * 4L

    /** Bytes a document of this size and layer count is expected to hold. */
    fun estimateBytes(width: Int, height: Int, layers: Int): Long =
        layerBytes(width, height) * (layers + SCRATCH_SURFACES).toLong()

    /** How many layers fit comfortably at this canvas size. */
    fun maxLayers(width: Int, height: Int): Int {
        val per = layerBytes(width, height)
        if (per <= 0L) return 512
        val usable = canvasBudget / per - SCRATCH_SURFACES
        return usable.coerceIn(0L, 512L).toInt()
    }

    /** How many full-canvas bitmaps fit in the budget at this size. */
    fun maxBitmaps(width: Int, height: Int): Int {
        val per = layerBytes(width, height)
        if (per <= 0L) return 512
        return (canvasBudget / per).coerceIn(0L, 512L).toInt()
    }

    /** False when [bitmaps] full-canvas surfaces would exceed the budget. */
    fun fitsBitmaps(width: Int, height: Int, bitmaps: Int): Boolean {
        if (bitmaps <= 0) return true
        return layerBytes(width, height) * bitmaps.toLong() <= canvasBudget
    }

    /** Human readable refusal for an operation that would decode extra layers. */
    fun decodeRefusal(width: Int, height: Int, extra: Int): String {
        val per = layerBytes(width, height)
        return "Not enough memory to show that at $width × $height " +
            "(needs ≈ ${formatMb(per * extra.toLong())} more, budget ${formatMb(canvasBudget)})"
    }

    /** False when creating or extending this document would likely OOM. */
    fun fits(width: Int, height: Int, layers: Int): Boolean =
        estimateBytes(width, height, layers) <= canvasBudget

    /** Human readable refusal shown when a layer cannot be added. */
    fun layerRefusal(width: Int, height: Int, layers: Int): String {
        val needed = estimateBytes(width, height, layers)
        return "Not enough memory for another layer at $width × $height " +
            "(≈ ${formatMb(layerBytes(width, height))} per layer, " +
            "${formatMb(needed)} needed, ${formatMb(canvasBudget)} budget)"
    }

    /** Human readable warning shown when a canvas looks too large for the device. */
    fun canvasRefusal(width: Int, height: Int): String {
        val per = layerBytes(width, height)
        return "$width × $height needs ≈ ${formatMb(per)} per layer; this device has " +
            "${formatMb(canvasBudget)} for canvas pixels. Try a smaller canvas."
    }

    fun formatMb(bytes: Long): String = "${bytes / MB} MB"
}
