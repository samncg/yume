package com.yume.app.engine

/**
 * Debug-only instrumentation of the per-tile compositor.
 *
 * The counters explain where a frame goes when a stack of sparse layers is
 * painted on: how many tiles the dirty region visits, how many layer draws and
 * tile lookups each tile costs, how often the occlusion test answers from its
 * cache, and how much of the frame is spent inside the tile blits versus the
 * bookkeeping around them. `System.nanoTime` is read per call but only when
 * [enabled] is on, so the shipping path pays one boolean check.
 *
 * The harness turns it on for the painted-stack runs; nothing in the UI reads it.
 */
object CompositeProfile {

    @Volatile
    var enabled: Boolean = false

    var frames: Long = 0L
    var tileVisits: Long = 0L
    var layerDraws: Long = 0L
    var tileLookups: Long = 0L
    var opacityQueries: Long = 0L
    var opacityHits: Long = 0L
    var cullScans: Long = 0L
    var groupDraws: Long = 0L

    var blitNanos: Long = 0L
    var lookupNanos: Long = 0L
    var opacityNanos: Long = 0L
    /** Time inside the per-tile composite pass (renderInto). */
    var compositeNanos: Long = 0L
    /** Time inside stroke stamping (dab render + tile write-back). */
    var paintNanos: Long = 0L
    var paintDabs: Long = 0L
    /** Tile bitmaps newly allocated by a write (a one-off cost per tile). */
    var tilesAllocated: Long = 0L
    /** Tiles copied by the undo capture (one copy per tile per stroke). */
    var captures: Long = 0L
    /** Pixels of composite tiles touched, and of those actually composited. */
    var compositePx: Long = 0L
    var compositeDirtyPx: Long = 0L

    @Synchronized
    fun reset() {
        frames = 0L
        tileVisits = 0L
        layerDraws = 0L
        tileLookups = 0L
        opacityQueries = 0L
        opacityHits = 0L
        cullScans = 0L
        groupDraws = 0L
        blitNanos = 0L
        lookupNanos = 0L
        opacityNanos = 0L
        compositeNanos = 0L
        paintNanos = 0L
        paintDabs = 0L
        tilesAllocated = 0L
        captures = 0L
        compositePx = 0L
        compositeDirtyPx = 0L
    }

    private fun ms(nanos: Long): String =
        "%.2f".format(nanos / 1_000_000.0)

    /** One line: counts plus aggregated milliseconds per category. */
    fun summary(): String {
        val perTile = if (tileVisits > 0) layerDraws.toDouble() / tileVisits else 0.0
        val hitRate = if (opacityQueries > 0) {
            100.0 * opacityHits / opacityQueries
        } else {
            0.0
        }
        return "tiles=$tileVisits frames=$frames draws=$layerDraws " +
            "draws/tile=${"%.2f".format(perTile)} groups=$groupDraws " +
            "lookups=$tileLookups opacity=$opacityQueries hit=${"%.0f".format(hitRate)}% " +
            "cull=$cullScans " +
            "blit=${ms(blitNanos)}ms lookup=${ms(lookupNanos)}ms " +
            "opacity=${ms(opacityNanos)}ms composite=${ms(compositeNanos)}ms " +
            "paint=${ms(paintNanos)}ms dabs=$paintDabs " +
            "allocTiles=$tilesAllocated captured=$captures " +
            "tilePx=$compositePx dirtyPx=$compositeDirtyPx"
    }
}
