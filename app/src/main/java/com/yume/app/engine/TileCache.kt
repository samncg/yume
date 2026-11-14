package com.yume.app.engine

import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.StatFs
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Per-document tile cache with a byte budget and LRU eviction to disk
 * (Stage 3b-1b).
 *
 * Every layer and mask [SparseTileStore] created while a cache is [active]
 * registers here and gets its own [SpillFile] in the cache directory. The
 * budget covers everything tile shaped the document holds: layer and mask
 * tiles (evictable), composite / mip / undo-capture tiles and the tile pool
 * (accounted, not evictable; the pool is trimmed under pressure).
 *
 * Ownership and threading: the render thread owns all tile mutation, including
 * eviction and adoption of prefetched tiles. The prefetch thread only reads
 * spill records into staging bitmaps it acquired itself and hands them back
 * through [adopted]; the render thread installs them in [adoptPrefetched].
 *
 * Never evicted: tiles pinned by a [TileCapture] (active stroke, transform,
 * adjustment), the tile a store most recently handed out for writing, solid
 * tiles (they hold no private bytes) and tiles of stores that are not
 * registered. Evicted bitmaps are dropped, never pooled, so a caller still
 * holding a reference (a composite pass mid-draw) keeps reading valid pixels.
 */
class TileCache(
    val dir: File,
    budgetBytes: Long
) {
    @Volatile var budgetBytes: Long = budgetBytes

    init {
        dir.mkdirs()
    }

    /** Disk headroom below which spilling refuses and tiles stay in memory. */
    @Volatile var minFreeDiskBytes: Long = 64L * 1024L * 1024L

    private val stores = ArrayList<WeakReference<SparseTileStore>>()
    private var nextStoreId = 1

    /** Logical clock for LRU; bumped on every tile access by registered stores. */
    var tick: Long = 0L
        internal set

    // ---------------------------------------------------------------- counters
    var evictions: Long = 0L; internal set
    var dirtyWrites: Long = 0L; internal set
    var loads: Long = 0L; internal set
    var syncLoads: Long = 0L; internal set
    var syncLoadsRender: Long = 0L; internal set
    var prefetchLoads: Long = 0L; internal set
    var deferredTiles: Long = 0L; internal set
    /** Of [deferredTiles]: deferred because the frame's composite time ran out. */
    var deferredTime: Long = 0L; internal set
    var writeFailures: Long = 0L; internal set
    var readFailures: Long = 0L; internal set
    var peakResident: Long = 0L; internal set

    /** Last human-readable problem (disk full, write error), for the UI. */
    @Volatile var lastError: String? = null

    /**
     * False while the render thread composites a frame: a non-active layer's
     * evicted tile is then scheduled for prefetch instead of read synchronously,
     * and the composite tile keeps its previous (stale) pixels until it lands.
     */
    @Volatile var blockingLoads: Boolean = true

    /** Debug: enforce the budget after every operation (harness eviction modes). */
    @Volatile var evictEveryOp: Boolean = false

    /** The store the user is painting into; its tiles may always load inline. */
    var activeStore: TileStore? = null

    /** Extra accounted bytes reported by the engine (composite, mip, captures). */
    var externalBytes: () -> Map<String, Long> = { emptyMap() }

    internal val readScratch = SpillFile.ReadScratch()

    // ------------------------------------------------------------ registration

    fun register(store: SparseTileStore): SpillFile {
        val id = nextStoreId++
        stores.add(WeakReference(store))
        return SpillFile(File(dir, "s$id.spill"))
    }

    fun liveStores(): List<SparseTileStore> {
        val out = ArrayList<SparseTileStore>(stores.size)
        val it = stores.iterator()
        while (it.hasNext()) {
            val s = it.next().get()
            if (s == null) it.remove() else out.add(s)
        }
        return out
    }

    // -------------------------------------------------------------- accounting

    /** Bytes by category; "layers" and "masks" are the evictable part. */
    fun accounting(): LinkedHashMap<String, Long> {
        val m = LinkedHashMap<String, Long>()
        var layers = 0L
        var masks = 0L
        var composite = 0L
        for (s in liveStores()) {
            when (s.cacheRole) {
                "composite" -> composite += s.residentBytes()
                "mask" -> masks += s.residentBytes()
                else -> layers += s.residentBytes()
            }
        }
        m["layers"] = layers
        m["masks"] = masks
        m["composite"] = composite
        for ((k, v) in externalBytes()) m[k] = v
        m["pool"] = TilePool.heldBytes
        m["solids"] = SolidTiles.stats().second
        return m
    }

    fun residentBytes(): Long = accounting().values.sum()

    // ---------------------------------------------------------------- eviction

    /**
     * Evicts least recently used tiles until the accounted total is at most
     * [target] (default: the budget). Dirty tiles are written first; a tile
     * whose write fails stays resident. Returns the number evicted.
     */
    // Hot ring: the last [HOT] tiles handed out by any registered store. Code
    // holds at most a couple of tile references across a load (merge reads a
    // source and a destination tile; history reads before and after), so a
    // tile in the ring is never evicted and an evicted bitmap can go straight
    // back to the pool instead of piling up native memory until a GC.
    private val hotStore = arrayOfNulls<SparseTileStore>(HOT)
    private val hotKey = LongArray(HOT)
    private var hotNext = 0

    fun markHot(store: SparseTileStore, key: Long) {
        hotStore[hotNext] = store
        hotKey[hotNext] = key
        hotNext = (hotNext + 1) % HOT
    }

    fun isHot(store: SparseTileStore, key: Long): Boolean {
        for (i in 0 until HOT) if (hotStore[i] === store && hotKey[i] == key) return true
        return false
    }

    /** An evicted private bitmap: nothing outside the hot ring can hold it. */
    fun bury(bmp: Bitmap) {
        TilePool.release(bmp)
    }

    fun releaseBuried() = Unit

    /**
     * Evicts least recently used tiles until the accounted total is at most
     * [target]. At most [maxDirtyWrites] dirty tiles are written (the render
     * thread passes a small number so a frame never deflates many tiles); clean
     * ones are free to drop. If that leaves the cache far over budget, dirty
     * tiles are flushed regardless.
     */
    fun evictTo(target: Long = budgetBytes, maxDirtyWrites: Int): Int {
        var n = evictLimited(target, maxDirtyWrites)
        if (maxDirtyWrites != Int.MAX_VALUE) {
            // Well over: write more this frame, but a bounded number (writing
            // everything at once was a 180 ms frame). Only at twice the budget
            // does memory win over smoothness.
            val total = residentBytes()
            if (total > target * 2) n += evictLimited(target, Int.MAX_VALUE)
            else if (total > target + target / 2) n += evictLimited(target, OVER_DIRTY_WRITES)
        }
        return n
    }

    fun evictTo(target: Long = budgetBytes): Int = evictLimited(target, Int.MAX_VALUE)

    private fun evictLimited(target: Long, maxDirtyWrites: Int): Int {
        var total = residentBytes()
        if (total > peakResident) peakResident = total
        if (total <= target) return 0
        // The pool is pure slack: give it back before touching real tiles.
        if (TilePool.heldBytes > 0) {
            TilePool.reset()
            total = residentBytes()
            if (total <= target) return 0
        }
        val cands = ArrayList<SparseTileStore.EvictCandidate>()
        for (s in liveStores()) s.collectEvictable(cands)
        cands.sortBy { it.lastUse }
        var n = 0
        var dirty = 0
        for (c in cands) {
            if (total <= target) break
            if (!c.clean) {
                if (dirty >= maxDirtyWrites) continue
                dirty++
            }
            val freed = c.store.evictTile(c.key)
            if (freed > 0) {
                total -= freed
                n++
            }
        }
        maybeCompact()
        return n
    }

    private var loadsSinceCheck = 0
    private var lastAcq = -1L

    /** Called after each synchronous load: trims when far over budget. */
    fun evictIfFarOver() {
        if (++loadsSinceCheck < 16) return
        loadsSinceCheck = 0
        if (residentBytes() > budgetBytes + budgetBytes / 4) evictTo(budgetBytes)
    }

    /**
     * Budget check at a safe point. Skipped when no tile bitmap was acquired
     * since the last check, because nothing can then have grown.
     */
    fun evictIfNeeded(): Int {
        releaseBuried()
        val acq = TilePool.acquisitions
        if (acq == lastAcq) return 0
        // Upper bound on growth since the last exact count: every acquisition a
        // full tile. Only when that could exceed the budget is the (per-store)
        // exact accounting walked, so a normal frame costs two field reads.
        val grown = (acq - lastAcq).coerceAtLeast(0L) * 256L * 256L * 4L
        if (lastAcq >= 0 && lastTotal + grown <= budgetBytes) return 0
        lastAcq = acq
        // A frame safe point: write at most a few dirty tiles.
        val n = evictTo(budgetBytes, FRAME_DIRTY_WRITES)
        lastTotal = residentBytes()
        releaseBuried()
        return n
    }

    private var lastTotal = Long.MAX_VALUE / 4

    /** Evicts every unpinned tile (harness "evict everything" mode). */
    fun evictAll(): Int {
        var n = 0
        for (s in liveStores()) n += s.evictAllUnpinned()
        maybeCompact()
        releaseBuried()
        return n
    }

    /** Safe point between operations / frames: adopt prefetched tiles, enforce the budget. */
    fun safePoint() {
        releaseBuried()
        adoptPrefetched()
        evictTo()
        lastTotal = residentBytes()
        releaseBuried()
    }

    private fun maybeCompact() {
        for (s in liveStores()) {
            val f = s.spillFile ?: continue
            if (f.shouldCompact()) {
                runCatching { f.compact(readScratch) }.onFailure {
                    lastError = "Tile cache compaction failed: ${it.message}"
                }
            }
        }
    }

    /** True when the cache directory still has room for [bytes] more. */
    fun diskHasRoom(bytes: Long): Boolean {
        // StatFs needs an existing path: use the nearest existing ancestor.
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        val avail = runCatching { StatFs((d ?: dir).path).availableBytes }.getOrNull()
            ?: return false
        return avail - bytes >= minFreeDiskBytes
    }

    // ---------------------------------------------------------------- prefetch

    private class Request(
        val store: SparseTileStore,
        val key: Long,
        val record: SpillFile.Record
    )

    private class Result(
        val store: SparseTileStore,
        val key: Long,
        val record: SpillFile.Record,
        val bitmap: Bitmap
    )

    private val requests = LinkedBlockingQueue<Request>()
    private val adopted = ConcurrentLinkedQueue<Result>()
    private val queued = HashSet<Pair<SparseTileStore, Long>>()
    @Volatile private var running = true
    /** Called on the render thread when prefetched tiles were adopted. */
    var onAdopted: ((SparseTileStore, Long) -> Unit)? = null

    private fun prefetchLoop() {
        val scratch = SpillFile.ReadScratch()
        while (running) {
            val req = try {
                requests.take()
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            val f = req.store.spillFile ?: continue
            val bmp = TilePool.acquire(req.record.w, req.record.h)
            val ok = runCatching { f.read(req.record, bmp, scratch) }.isSuccess
            if (ok) {
                adopted.add(Result(req.store, req.key, req.record, bmp))
            } else {
                TilePool.release(bmp)
                // The render thread will see the tile still evicted and load it
                // synchronously (which reports a corrupt record properly).
                adopted.add(Result(req.store, req.key, req.record, FAILED))
            }
        }
    }

    /** Two readers: one inflate-bound thread cannot keep up with a fast stroke. */
    private val prefetchers = List(2) { n ->
        Thread({ prefetchLoop() }, "YumeTilePrefetch$n").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    /** Queues an evicted tile for background decoding (render thread). */
    fun requestPrefetch(store: SparseTileStore, key: Long, record: SpillFile.Record) {
        // Bounded in flight: prefetching more than a fraction of the budget
        // only evicts tiles that were prefetched a moment ago (thrashing).
        if (queued.size >= maxInFlight()) return
        if (!queued.add(store to key)) return
        requests.add(Request(store, key, record))
    }

    /** Installs every finished prefetch (render thread). */
    fun adoptPrefetched(): Int {
        var n = 0
        while (true) {
            val r = adopted.poll() ?: break
            queued.remove(r.store to r.key)
            if (r.bitmap === FAILED) {
                // Retry synchronously: a real corruption is then recorded as a
                // lost tile (with a message) instead of being deferred forever.
                r.store.loadEvicted(r.key)
                continue
            }
            if (r.store.adoptPrefetched(r.key, r.record, r.bitmap)) {
                prefetchLoads++
                loads++
                n++
                onAdopted?.invoke(r.store, r.key)
            } else {
                TilePool.release(r.bitmap)
            }
        }
        return n
    }

    fun pendingPrefetches(): Int = queued.size

    /** At most a quarter of the budget in flight, in 256 KB tiles, at least 8. */
    fun maxInFlight(): Int =
        (budgetBytes / 4 / (256L * 256L * 4L)).coerceIn(8L, 256L).toInt()

    /**
     * Composite work allowed per non-blocking frame, in nanoseconds. The rest of
     * the dirty area stays dirty (stale on screen) and continues next frame.
     */
    @Volatile var frameCompositeBudgetNs: Long = 4_000_000L

    /** Prefetches every evicted tile of [stores] that intersects [rect]. */
    fun prefetchRect(stores: List<TileStore>, rect: android.graphics.Rect) {
        for (s in stores) (s as? SparseTileStore)?.prefetchIn(rect)
    }

    fun close() {
        running = false
        prefetchers.forEach { it.interrupt() }
        while (true) {
            val r = adopted.poll() ?: break
            if (r.bitmap !== FAILED) TilePool.release(r.bitmap)
        }
        for (s in liveStores()) s.detachCache()
        stores.clear()
        dir.deleteRecursively()
        if (active === this) active = null
    }

    fun resetCounters() {
        evictions = 0; dirtyWrites = 0; loads = 0; syncLoads = 0; syncLoadsRender = 0
        prefetchLoads = 0; deferredTiles = 0; deferredTime = 0; writeFailures = 0; readFailures = 0
        peakResident = residentBytes()
    }

    fun spillStats(): String {
        var files = 0
        var bytes = 0L
        var written = 0L
        var compactions = 0
        for (s in liveStores()) {
            val f = s.spillFile ?: continue
            files++
            bytes += f.fileBytes
            written += f.bytesWritten
            compactions += f.compactions
        }
        return "spill files=$files disk=${bytes / 1024}KB written=${written / 1024}KB" +
            " compactions=$compactions"
    }

    fun stats(): String =
        "cache budget=${budgetBytes / 1024}KB peak=${peakResident / 1024}KB" +
            " evict=$evictions dirtyWrites=$dirtyWrites loads=$loads sync=$syncLoads" +
            " syncRender=$syncLoadsRender prefetch=$prefetchLoads deferred=$deferredTiles" +
            "(time=$deferredTime)" +
            " writeFail=$writeFailures readFail=$readFailures " + spillStats()

    companion object {
        private val FAILED: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

        private const val HOT = 16

        /** Dirty tiles a render-frame safe point may write (deflate) at most. */
        const val FRAME_DIRTY_WRITES = 1

        /** Dirty tiles a frame may write while 1.5-2x over budget (16 = 4 MB). */
        const val OVER_DIRTY_WRITES = 16

        /** The cache new layer / mask stores register with (null = no eviction). */
        @Volatile var active: TileCache? = null

        /** Debug override of the budget in bytes (1/4/16 MB in the harness). */
        @Volatile var debugBudgetOverride: Long = 0L

        /**
         * Default budget: half of the large-heap class (or of the normal
         * class), never below 32 MB. Covers layer, mask, composite, mip,
         * capture and pooled tiles.
         */
        fun defaultBudget(am: ActivityManager?): Long {
            if (debugBudgetOverride > 0L) return debugBudgetOverride
            val mb = 1024L * 1024L
            val cls = (am?.largeMemoryClass ?: 0).takeIf { it > 0 } ?: (am?.memoryClass ?: 256)
            return (cls.toLong() * mb / 2).coerceAtLeast(32L * mb)
        }

        /** Removes cache directories left behind by a crash (app start). */
        fun removeStale(root: File) {
            File(root, "tilecache").listFiles()?.forEach { it.deleteRecursively() }
        }

        fun open(root: File, documentId: String, budget: Long): TileCache {
            active?.close()
            val dir = File(File(root, "tilecache"), "$documentId-${System.nanoTime()}")
            dir.mkdirs()
            return TileCache(dir, budget).also { active = it }
        }
    }
}
