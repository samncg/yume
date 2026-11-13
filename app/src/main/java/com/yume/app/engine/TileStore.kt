package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.yume.app.BuildConfig
import java.io.File
import java.nio.IntBuffer
import kotlin.math.max
import kotlin.math.min

/** Draws a source of pixels into a canvas in document coordinates. */
fun interface PixelSource {
    fun drawInto(canvas: Canvas, paint: Paint?)
}

/**
 * Adapts a plain bitmap to [PixelSource].
 *
 * A dense group buffer covers the whole document and is drawn at the origin.
 * A tiled one covers [region] in document coordinates, so the same bitmap is
 * drawn into that rect when the target canvas is in document space.
 */
class BitmapSource(
    private val bitmap: Bitmap,
    private val region: Rect? = null
) : PixelSource {
    override fun drawInto(canvas: Canvas, paint: Paint?) {
        val r = region
        if (r == null) {
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        } else {
            canvas.drawBitmap(bitmap, r.left.toFloat(), r.top.toFloat(), paint)
        }
    }
}

/**
 * Storage for one layer's pixels.
 *
 * The interface is tile based so sparse, disk-backed implementations can slot
 * in without touching the compositor: a missing tile means fully transparent,
 * and all reads and writes take document-space rectangles that may span tiles.
 * [SingleTileStore] holds one full-canvas bitmap; [SparseTileStore] holds only
 * the tiles that have been painted.
 */
interface TileStore : PixelSource {

    companion object {
        /** Default edge length of a sparse tile, in pixels. */
        const val TILE_SIZE = 256
    }

    val width: Int
    val height: Int

    /** Edge length of one tile. A single-tile store reports the canvas size. */
    val tileSize: Int

    /**
     * Colour of a pixel where no tile exists. Layers default to transparent;
     * a layer mask defaults to opaque white so a freshly added mask allocates
     * no tiles and reads as fully visible.
     */
    val defaultPixel: Int

    /** True for tile-backed stores (painted area only), false for one bitmap. */
    val isSparse: Boolean

    // ------------------------------------------------------------------ tiles

    /** Resident tile at (tx, ty), or null when it is missing / swapped out. */
    fun getTile(tx: Int, ty: Int): Bitmap?

    /** Allocates (or decodes) the tile so it can be drawn into, and returns it. */
    fun obtainTileForWrite(tx: Int, ty: Int): Bitmap

    /** Visits every resident tile that intersects [rect]. */
    fun forEachTileIn(rect: Rect, action: (tx: Int, ty: Int, tile: Bitmap) -> Unit)

    /** True when the tile has no pixels that differ from [defaultPixel]. */
    fun isTileEmpty(tx: Int, ty: Int): Boolean

    /**
     * True when every pixel of [rect] equals the store's default pixel, treating
     * missing tiles as the default. Used to decide whether a composite tile that
     * was just updated over [rect] holds nothing but background.
     */
    fun isRectEmpty(rect: Rect): Boolean = false

    /** Drops the tile at (tx, ty) so it reads as [defaultPixel] again. */
    fun clearTile(tx: Int, ty: Int)

    /** Records a changed document-space rectangle (for dirty-tile persistence). */
    fun markDirty(rect: Rect)

    // ------------------------------------------------------------- rect access

    /** Copies [rect] into a new bitmap. */
    fun readRect(rect: Rect): Bitmap

    /** Copies [rect] into [target] (which must be at least as large). */
    fun readRectInto(rect: Rect, target: Bitmap)

    /** Replaces [rect] with the matching part of [source] (SRC blending). */
    fun writeRect(
        rect: Rect,
        source: Bitmap,
        srcRect: Rect = Rect(0, 0, source.width, source.height)
    )

    /** Single pixel read, for the eyedropper. */
    fun getPixel(x: Int, y: Int): Int

    /** Clears [rect] to fully transparent. */
    fun clearRect(rect: Rect)

    /** Fills [rect] with [color] (SRC blending); transparent clears it. */
    fun fillRect(rect: Rect, color: Int)

    /**
     * Runs [block] with a canvas whose coordinate system is the document's,
     * clipped to [rect]. Sparse stores invoke the block once per intersecting
     * tile, so the block must only depend on the document-space pixels it
     * draws; callers that clear or redraw a region work across tiles.
     */
    fun withWriteCanvas(rect: Rect, block: (Canvas) -> Unit)

    /**
     * Draws directly into one tile. [block] receives a canvas translated so it
     * can draw in document coordinates; the tile clips the result.
     */
    fun editTile(tx: Int, ty: Int, block: (Canvas) -> Unit)

    /**
     * Replaces the [clip] region of one tile with [source] (sized exactly
     * clip.width x clip.height), or with the store default when [source] is
     * null. Used by sparse history to restore one action's pixels, cropped to
     * the region the action actually covered.
     */
    fun putTileRegion(tx: Int, ty: Int, clip: Rect, source: Bitmap?)

    /**
     * Canvas that writes into this store in document coordinates. Only the dense
     * [SingleTileStore] stroke path uses it.
     */
    fun writeCanvas(): Canvas

    /** Draws [src] into [dst] on [canvas], for smudge sampling and previews. */
    fun drawRect(canvas: Canvas, src: Rect, dst: RectF, paint: Paint?)

    /** Draws a sub-rect of this store into [canvas] in document coordinates. */
    fun drawIntoRect(canvas: Canvas, rect: Rect, paint: Paint?)

    /**
     * Same as [drawIntoRect] but the caller has already clipped [canvas] to
     * [rect]. Saves a save/clipRect/restore pair per tile on the hot path.
     */
    fun drawIntoRectPreClipped(canvas: Canvas, rect: Rect, paint: Paint?) {
        drawIntoRect(canvas, rect, paint)
    }

    /**
     * True when every pixel of the tile is fully opaque, so an opaque Normal
     * layer above it hides it completely (compositor occlusion culling).
     */
    fun isTileOpaque(tx: Int, ty: Int): Boolean = false

    /**
     * Debug verifier for the per-tile opaque cache: recomputes the flag from
     * pixels, compares it with the cached answer and returns the number of
     * stale entries (invalidating them). Stores without a cache return 0.
     */
    fun verifyOpaqueFlags(all: Boolean = true): Int = 0

    /**
     * Replaces tiles inside [rect] whose pixels are all identical with a shared
     * immutable solid (or drops them when they equal the default). Called at
     * stroke end, after one-shot ops, undo/redo and load, never per dab.
     * Returns the number of tiles that are solid afterwards. Dense stores: 0.
     */
    fun compactSolids(rect: Rect): Int = 0

    /** Tiles currently backed by a shared solid. */
    fun solidTileCount(): Int = 0

    /**
     * Debug verifier for solid tiles: every tile marked solid must be a shared
     * immutable bitmap whose pixels are uniform and equal its recorded colour,
     * and every other tile must be a private mutable bitmap. Returns violations.
     */
    fun verifySolids(): Int = 0

    /** Full copy of the pixels. */
    fun copyAll(): Bitmap

    /** A copy of one tile, or null when it is missing / fully transparent. */
    fun readTileCopy(tx: Int, ty: Int): Bitmap?

    /** Installs a tile from [source] (tile-sized pixels). Used by strokeBase. */
    fun putTile(tx: Int, ty: Int, source: Bitmap)

    /** Frees tiles inside [rect] that became fully transparent. */
    fun pruneEmptyTiles(rect: Rect)

    /** Bounding box of the non-empty tiles, or an empty rect. */
    fun nonEmptyBounds(): Rect

    fun clear()

    fun fill(color: Int)

    // -------------------------------------------------------------- residency

    val isResident: Boolean

    /** Decodes / allocates the pixels if they are currently on disk. */
    fun ensureResident()

    /** Swaps the pixels out, using [file] as the backing store. */
    fun releaseToDisk(file: File)

    fun backingFile(): File?

    /**
     * The underlying bitmap when this store is a single resident surface.
     * Used for thumbnails and PNG saving without an extra full-canvas copy;
     * sparse implementations return null.
     */
    fun sharedBitmap(): Bitmap?

    /** Bytes of bitmap currently held in RAM. */
    fun residentBytes(): Long
}

/** Debug-only storage configuration. The live default stays SingleTileStore. */
object TileStoreConfig {
    /** Select the sparse store for newly created layers (debug experiments). */
    @Volatile var useSparseLayers: Boolean = false

    /** Tile size used when [useSparseLayers] is on. */
    @Volatile var tileSize: Int = TileStore.TILE_SIZE

    /**
     * Layer and mask stores replace uniform tiles with shared immutable solids
     * (see [SolidTiles]). Composite, mip and cache stores never do.
     */
    @Volatile var solidTiles: Boolean = true

    const val DEFAULT_TILE_SIZE: Int = TileStore.TILE_SIZE
}

/** Creates the layer store selected by [TileStoreConfig]. */
object TileStoreFactory {
    fun create(
        width: Int,
        height: Int,
        pixels: Bitmap?,
        backing: File?,
        defaultPixel: Int = 0
    ): TileStore =
        if (TileStoreConfig.useSparseLayers) {
            SparseTileStore(
                width, height, TileStoreConfig.tileSize, pixels, backing, defaultPixel,
                solidTiles = TileStoreConfig.solidTiles,
                cache = TileCache.active
            )
        } else {
            SingleTileStore(width, height, pixels, backing)
        }

    fun blank(width: Int, height: Int): TileStore =
        if (TileStoreConfig.useSparseLayers) {
            // A blank sparse layer holds no tiles at all: allocating (and then
            // splitting) a full-canvas bitmap here used to cost one canvas per
            // layer creation.
            SparseTileStore(
                width, height, TileStoreConfig.tileSize,
                solidTiles = TileStoreConfig.solidTiles,
                cache = TileCache.active
            )
        } else {
            SingleTileStore(
                width, height,
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
                null
            )
        }

    /** A uniform colour: sparse stores keep it as the default and allocate nothing. */
    fun opaque(width: Int, height: Int, color: Int): TileStore {
        if (TileStoreConfig.useSparseLayers) {
            return SparseTileStore(
                width, height, TileStoreConfig.tileSize, null, null, color,
                solidTiles = TileStoreConfig.solidTiles,
                cache = TileCache.active
            )
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return SingleTileStore(width, height, bitmap, null)
    }

    /**
     * A layer mask. Sparse masks read missing pixels as opaque white, so an
     * all-white mask stores no tiles at all.
     */
    fun mask(width: Int, height: Int, pixels: Bitmap?, backing: File?): TileStore =
        if (TileStoreConfig.useSparseLayers) {
            SparseTileStore(
                width, height, TileStoreConfig.tileSize, pixels, backing, Color.WHITE,
                solidTiles = TileStoreConfig.solidTiles,
                cache = TileCache.active
            )
        } else if (pixels != null) {
            SingleTileStore(width, height, pixels, backing)
        } else if (backing != null) {
            SingleTileStore(width, height, null, backing)
        } else {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            SingleTileStore(width, height, bitmap, null)
        }

    fun maskEmpty(width: Int, height: Int): TileStore = mask(width, height, null, null)
}

/**
 * Stage 1 tile store: one full-canvas bitmap pretending to be a single tile.
 * Kept as the reference implementation (and the live default).
 */
class SingleTileStore(
    override val width: Int,
    override val height: Int,
    pixels: Bitmap? = null,
    backing: File? = null
) : TileStore {

    override val tileSize: Int = max(width, height)
    override val defaultPixel: Int = 0
    override val isSparse: Boolean = false

    private var pixels: Bitmap? = pixels
    private var canvas: Canvas? = pixels?.let { Canvas(it) }
    private var backing: File? = backing
    private var empty = pixels == null
    private val dirty = Rect()
    private val rectPaint = Paint().apply { blendMode = BlendMode.SRC }

    override val isResident: Boolean get() = pixels != null

    private fun materialize(): Bitmap {
        pixels?.let { return it }
        val file = backing
        val decoded = if (file != null && file.exists()) decodeFull(file) else null
        val bmp = decoded ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixels = bmp
        canvas = Canvas(bmp)
        empty = decoded == null
        return bmp
    }

    override fun ensureResident() {
        materialize()
    }

    override fun sharedBitmap(): Bitmap? = pixels

    override fun backingFile(): File? = backing

    override fun residentBytes(): Long =
        if (pixels != null) width.toLong() * height.toLong() * 4L else 0L

    override fun releaseToDisk(file: File) {
        backing = file
        pixels = null
        canvas = null
    }

    override fun getTile(tx: Int, ty: Int): Bitmap? =
        if (tx == 0 && ty == 0) pixels else null

    override fun obtainTileForWrite(tx: Int, ty: Int): Bitmap {
        check(tx == 0 && ty == 0) { "SingleTileStore has one tile" }
        empty = false
        return materialize()
    }

    override fun forEachTileIn(rect: Rect, action: (tx: Int, ty: Int, tile: Bitmap) -> Unit) {
        if (rect.isEmpty) return
        val tile = pixels ?: return
        action(0, 0, tile)
    }

    override fun isTileEmpty(tx: Int, ty: Int): Boolean =
        if (tx == 0 && ty == 0) empty else true

    override fun clearTile(tx: Int, ty: Int) {
        if (tx == 0 && ty == 0) clear()
    }

    override fun markDirty(rect: Rect) {
        if (rect.isEmpty) return
        if (dirty.isEmpty) dirty.set(rect) else dirty.union(rect)
    }

    /** Rectangle touched since the last [clearDirty]. */
    fun takeDirtyRect(): Rect = Rect(dirty)

    fun clearDirty() {
        dirty.setEmpty()
    }

    override fun readRect(rect: Rect): Bitmap {
        val out = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(
            materialize(),
            rect,
            RectF(0f, 0f, rect.width().toFloat(), rect.height().toFloat()),
            rectPaint
        )
        return out
    }

    override fun readRectInto(rect: Rect, target: Bitmap) {
        target.eraseColor(0)
        val c = Canvas(target)
        c.drawBitmap(
            materialize(),
            rect,
            RectF(0f, 0f, rect.width().toFloat(), rect.height().toFloat()),
            rectPaint
        )
    }

    override fun writeRect(rect: Rect, source: Bitmap, srcRect: Rect) {
        val c = canvas ?: Canvas(materialize())
        val save = c.save()
        c.clipRect(rect)
        c.drawBitmap(source, srcRect, RectF(rect), rectPaint)
        c.restoreToCount(save)
        empty = false
        markDirty(rect)
    }

    override fun getPixel(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) materialize().getPixel(x, y) else 0

    override fun clearRect(rect: Rect) {
        val r = Rect(rect)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        withWriteCanvas(r) { it.drawColor(0, BlendMode.CLEAR) }
        if (r.left <= 0 && r.top <= 0 && r.right >= width && r.bottom >= height) empty = true
    }

    override fun fillRect(rect: Rect, color: Int) {
        val r = Rect(rect)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        withWriteCanvas(r) { it.drawColor(color, BlendMode.SRC) }
        if (r.left <= 0 && r.top <= 0 && r.right >= width && r.bottom >= height) {
            empty = Color.alpha(color) == 0
        }
    }

    override fun withWriteCanvas(rect: Rect, block: (Canvas) -> Unit) {
        val c = canvas ?: Canvas(materialize())
        val save = c.save()
        c.clipRect(rect)
        block(c)
        c.restoreToCount(save)
        empty = false
        markDirty(rect)
    }

    override fun editTile(tx: Int, ty: Int, block: (Canvas) -> Unit) {
        if (tx != 0 || ty != 0) return
        withWriteCanvas(Rect(0, 0, width, height)) { block(it) }
    }

    override fun putTileRegion(tx: Int, ty: Int, clip: Rect, source: Bitmap?) {
        if (tx != 0 || ty != 0) return
        val r = Rect(clip)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        val c = canvas ?: Canvas(materialize())
        val save = c.save()
        c.clipRect(r)
        if (source == null) c.drawColor(0, BlendMode.SRC)
        else c.drawBitmap(source, null, RectF(r), rectPaint)
        c.restoreToCount(save)
        empty = false
        markDirty(r)
    }

    override fun writeCanvas(): Canvas = canvas ?: Canvas(materialize())

    override fun drawRect(canvas: Canvas, src: Rect, dst: RectF, paint: Paint?) {
        canvas.drawBitmap(materialize(), src, dst, paint)
    }

    override fun drawInto(canvas: Canvas, paint: Paint?) {
        canvas.drawBitmap(materialize(), 0f, 0f, paint)
    }

    override fun drawIntoRect(canvas: Canvas, rect: Rect, paint: Paint?) {
        canvas.drawBitmap(materialize(), 0f, 0f, paint)
    }

    override fun copyAll(): Bitmap = readRect(Rect(0, 0, width, height))

    override fun readTileCopy(tx: Int, ty: Int): Bitmap? {
        if (tx != 0 || ty != 0) return null
        return if (isTrulyEmpty()) null else copyAll()
    }

    override fun putTile(tx: Int, ty: Int, source: Bitmap) {
        if (tx != 0 || ty != 0) return
        writeRect(Rect(0, 0, width, height), source)
    }

    override fun pruneEmptyTiles(rect: Rect) {
        if (rect.left <= 0 && rect.top <= 0 && rect.right >= width && rect.bottom >= height) {
            empty = isTrulyEmpty()
        }
    }

    override fun nonEmptyBounds(): Rect =
        if (isTrulyEmpty()) Rect() else Rect(0, 0, width, height)

    override fun clear() {
        val c = canvas ?: Canvas(materialize())
        c.drawColor(0, BlendMode.CLEAR)
        empty = true
        backing = null
        markDirty(Rect(0, 0, width, height))
    }

    override fun fill(color: Int) {
        val c = canvas ?: Canvas(materialize())
        c.drawColor(color, BlendMode.SRC)
        empty = Color.alpha(color) == 0
        backing = null
        markDirty(Rect(0, 0, width, height))
    }

    private fun isTrulyEmpty(): Boolean {
        val bmp = pixels ?: return true
        val row = IntArray(bmp.width)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (x in 0 until bmp.width) if (row[x] ushr 24 != 0) return false
        }
        return true
    }

    companion object {
        fun blank(width: Int, height: Int): SingleTileStore = SingleTileStore(
            width, height, Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), null
        )

        fun opaque(width: Int, height: Int, color: Int): SingleTileStore {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return SingleTileStore(width, height, bitmap, null)
        }

        private fun decodeFull(file: File): Bitmap? = runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
            )
        }.getOrNull()
    }
}

/**
 * Sparse tile store: only painted 256x256 (configurable) tiles exist.
 *
 * Missing tiles are fully transparent. A tile is allocated the first time
 * something is written into it and can be freed again with [pruneEmptyTiles].
 * Edge tiles are clipped to the canvas, so dimensions need not be multiples of
 * the tile size. Identical pixels to [SingleTileStore] for the same operations,
 * with memory proportional to painted area instead of canvas area.
 */
class SparseTileStore(
    override val width: Int,
    override val height: Int,
    override val tileSize: Int = TileStore.TILE_SIZE,
    pixels: Bitmap? = null,
    backing: File? = null,
    override val defaultPixel: Int = 0,
    /** Replace uniform tiles with shared immutable solids ([SolidTiles]). */
    val solidTiles: Boolean = false,
    /** Document tile cache: tiles of this store may be evicted to its spill file. */
    cache: TileCache? = null
) : TileStore {

    // ------------------------------------------------------- eviction (3b-1b)
    /** The cache this store spills to, or null (no eviction). */
    var cache: TileCache? = cache
        private set
    /** This store's spill log; null when it is not cached. */
    var spillFile: SpillFile? = cache?.register(this)
        private set
    /**
     * Tiles that exist but are not resident: their newest pixels are the spill
     * record. Invariant: an evicted tile is never empty (empty tiles are removed
     * instead) and never solid (solids hold no private bytes and stay resident).
     */
    private val evicted = HashSet<Long>()
    /** Last access tick per resident tile (LRU). Only maintained when cached. */
    private val lastUse = HashMap<Long, Long>()
    /** Tiles pinned by a live [TileCapture] (stroke, transform, adjustment). */
    private val pins = HashMap<Long, Int>()
    /** The tile most recently handed out for writing; never evicted. */
    private var lastWritable: Long = Long.MIN_VALUE
    /** Evicted tiles whose spill record could not be read back (CRC / IO). */
    private val lost = HashSet<Long>()

    class EvictCandidate(val store: SparseTileStore, val key: Long, val lastUse: Long)

    val evictedCount: Int get() = evicted.size
    val lostTiles: Int get() = lost.size
    val pinCount: Int get() = pins.size

    fun pinTile(tx: Int, ty: Int) {
        if (cache == null) return
        val k = key(tx, ty)
        pins[k] = (pins[k] ?: 0) + 1
    }

    fun unpinTile(tx: Int, ty: Int) {
        if (cache == null) return
        val k = key(tx, ty)
        val n = (pins[k] ?: return) - 1
        if (n <= 0) pins.remove(k) else pins[k] = n
    }

    private fun touch(k: Long) {
        val c = cache ?: return
        c.tick++
        lastUse[k] = c.tick
    }

    /**
     * The resident tile at [k], loading it from the spill file when it was
     * evicted. Every read of a tile goes through here.
     */
    private fun tileAt(k: Long): Bitmap? {
        val t = tiles[k]
        if (t != null) {
            if (cache != null) touch(k)
            return t
        }
        if (evicted.isEmpty() || k !in evicted) return null
        return loadTile(k)
    }

    private fun loadTile(k: Long): Bitmap? {
        val c = cache
        val f = spillFile
        val rec = f?.lookup(k)
        if (c == null || f == null || rec == null) {
            evicted.remove(k)
            lost.add(k)
            return null
        }
        val bmp = TilePool.acquire(rec.w, rec.h)
        try {
            f.read(rec, bmp, c.readScratch)
        } catch (e: java.io.IOException) {
            TilePool.release(bmp)
            c.readFailures++
            c.lastError = "Could not read a cached tile back (${e.message}); " +
                "this layer will not be saved until it is repainted"
            // Mark it lost: reads show the default, and saving this layer
            // refuses with a message instead of writing incomplete pixels.
            evicted.remove(k)
            lost.add(k)
            return null
        }
        tiles[k] = bmp
        evicted.remove(k)
        lost.remove(k)
        c.loads++
        c.syncLoads++
        if (!c.blockingLoads) c.syncLoadsRender++
        touch(k)
        // Loading can push far past the budget inside one long operation; trim
        // other tiles (never [k]'s bitmap from under the caller: evicted
        // bitmaps are dropped, not pooled, and [lastWritable] is skipped).
        if (c.blockingLoads) c.evictIfFarOver()
        return bmp
    }

    /** Drops any spill state for [k]: its pixels are being replaced. */
    private fun forgetSpill(k: Long) {
        if (spillFile == null) return
        spillFile?.drop(k)
        evicted.remove(k)
        lost.remove(k)
    }

    /** Adds every evictable resident tile to [out]. */
    fun collectEvictable(out: MutableList<EvictCandidate>) {
        if (spillFile == null) return
        for (k in tiles.keys) {
            if (k in solids || k == lastWritable || pins.containsKey(k)) continue
            out.add(EvictCandidate(this, k, lastUse[k] ?: 0L))
        }
    }

    /**
     * Evicts one tile: a clean one (current spill record) is just dropped, a
     * dirty one is written first. Returns the private bytes freed, 0 if the
     * tile stays (pinned, solid, write failure, no disk space).
     */
    fun evictTile(k: Long): Long {
        val c = cache ?: return 0L
        val f = spillFile ?: return 0L
        if (k in solids || k == lastWritable || pins.containsKey(k)) return 0L
        val t = tiles[k] ?: return 0L
        val bytes = t.byteCount.toLong()
        val tx = (k ushr 32).toInt()
        val ty = k.toInt()
        if (isTileEmpty(tx, ty)) {
            removeTile(k)
            return bytes
        }
        if (!f.has(k)) {
            if (!c.diskHasRoom(bytes + SpillFile.HEADER)) {
                c.writeFailures++
                c.lastError = "Low storage: keeping canvas tiles in memory"
                return 0L
            }
            try {
                f.write(k, t)
            } catch (e: java.io.IOException) {
                c.writeFailures++
                c.lastError = "Could not write the tile cache (${e.message}); " +
                    "keeping tiles in memory"
                return 0L
            }
            c.dirtyWrites++
        }
        tiles.remove(k)
        canvases.remove(k)
        lastUse.remove(k)
        evicted.add(k)
        c.evictions++
        // Not pooled: a caller may still hold this bitmap for a read.
        return bytes
    }

    fun evictAllUnpinned(): Int {
        if (spillFile == null) return 0
        var n = 0
        for (k in ArrayList(tiles.keys)) if (evictTile(k) > 0L) n++
        return n
    }

    /** Installs a tile the prefetcher decoded, if it is still the current data. */
    fun adoptPrefetched(k: Long, rec: SpillFile.Record, bmp: Bitmap): Boolean {
        if (k !in evicted || spillFile?.lookup(k) !== rec) return false
        tiles[k] = bmp
        evicted.remove(k)
        touch(k)
        return true
    }

    /** Schedules every evicted tile intersecting [rect] for background decode. */
    fun prefetchIn(rect: Rect) {
        val c = cache ?: return
        if (evicted.isEmpty()) return
        for (ty in tyRange(rect)) for (tx in txRange(rect)) {
            val k = key(tx, ty)
            if (k !in evicted) continue
            val rec = spillFile?.lookup(k) ?: continue
            c.requestPrefetch(this, k, rec)
        }
    }

    /** True when a tile inside [rect] is evicted (it would need a disk read). */
    fun hasEvictedIn(rect: Rect): Boolean {
        if (evicted.isEmpty()) return false
        for (ty in tyRange(rect)) for (tx in txRange(rect)) {
            if (key(tx, ty) in evicted) return true
        }
        return false
    }

    /** Synchronous load of one evicted tile (a failed prefetch is retried here). */
    fun loadEvicted(k: Long) {
        if (k in evicted) loadTile(k)
    }

    /** Loads every evicted tile back (before the cache goes away). */
    fun loadAll() {
        for (k in ArrayList(evicted)) loadTile(k)
    }

    /** The cache is closing (document closed): forget the spill file. */
    fun detachCache() {
        spillFile?.close(delete = true)
        spillFile = null
        cache = null
        lost.addAll(evicted)
        evicted.clear()
        lastUse.clear()
        pins.clear()
    }

    /** Keys of every existing tile, resident or evicted (a snapshot). */
    private fun allKeys(): List<Long> {
        if (evicted.isEmpty()) return ArrayList(tiles.keys)
        val out = ArrayList<Long>(tiles.size + evicted.size)
        out.addAll(tiles.keys)
        out.addAll(evicted)
        return out
    }

    override val isSparse: Boolean = true

    private val tiles = HashMap<Long, Bitmap>()
    private val canvases = HashMap<Long, Canvas>()
    /**
     * Tiles backed by a shared immutable solid, with its unpremultiplied colour.
     * Invariant: `tiles[k]` is a shared solid iff `k in solids`. Every write goes
     * through [writableTile], which swaps a solid for a private copy first.
     */
    private val solids = HashMap<Long, Int>()
    /** Solid -> private copies made because something wrote into a solid. */
    var unshares: Long = 0L
        private set
    private var backing: File? = if (pixels != null) null else backing
    private var materialized = pixels != null || backing == null
    private val dirty = Rect()

    private val tileCountX = if (width <= 0) 0 else (width + tileSize - 1) / tileSize
    private val tileCountY = if (height <= 0) 0 else (height + tileSize - 1) / tileSize
    private val srcPaint = Paint().apply {
        blendMode = BlendMode.SRC
        isFilterBitmap = false
    }
    private val scanRow = IntArray(tileSize)
    private val fillPaint = Paint()
    /** Per-tile "fully opaque" answers, dropped whenever the tile is written. */
    private val opaqueTiles = HashMap<Long, Boolean>()
    /** Tiles whose cached opacity has already been re-verified (debug only). */
    private val opaqueVerified = HashSet<Long>()

    init {
        if (pixels != null) splitInto(pixels)
    }

    private fun key(tx: Int, ty: Int): Long =
        (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

    private fun tileW(tx: Int): Int = min(tileSize, width - tx * tileSize)
    private fun tileH(ty: Int): Int = min(tileSize, height - ty * tileSize)

    private fun txRange(rect: Rect): IntRange {
        if (rect.isEmpty || tileCountX == 0) return IntRange.EMPTY
        val a = (rect.left / tileSize).coerceIn(0, tileCountX - 1)
        val b = ((rect.right - 1) / tileSize).coerceIn(0, tileCountX - 1)
        return if (b < a) IntRange.EMPTY else a..b
    }

    private fun tyRange(rect: Rect): IntRange {
        if (rect.isEmpty || tileCountY == 0) return IntRange.EMPTY
        val a = (rect.top / tileSize).coerceIn(0, tileCountY - 1)
        val b = ((rect.bottom - 1) / tileSize).coerceIn(0, tileCountY - 1)
        return if (b < a) IntRange.EMPTY else a..b
    }

    private fun canvasFor(k: Long, tile: Bitmap): Canvas {
        if (BuildConfig.DEBUG && (k in solids || !tile.isMutable)) {
            throw IllegalStateException("write canvas requested for shared solid tile $k")
        }
        return canvases.getOrPut(k) { Canvas(tile) }
    }

    /** Returns a private bitmap (never pooled or shared) to the pool. */
    private fun releaseOwned(k: Long, b: Bitmap) {
        if (solids.remove(k) != null) return
        TilePool.release(b)
    }

    private fun removeTile(k: Long) {
        val b = tiles.remove(k)
        canvases.remove(k)
        lastUse.remove(k)
        forgetSpill(k)
        // A removed tile reads as missing, so any cached opacity is stale.
        opaqueTiles.remove(k)
        opaqueVerified.remove(k)
        // A private tile goes back to the pool; a shared solid is just dropped.
        if (b != null) releaseOwned(k, b) else solids.remove(k)
    }

    private fun releaseAll() {
        for ((k, b) in tiles) if (k !in solids) TilePool.release(b)
        tiles.clear()
        evicted.clear()
        lost.clear()
        lastUse.clear()
        spillFile?.dropAll()
        solids.clear()
        canvases.clear()
        opaqueTiles.clear()
        opaqueVerified.clear()
    }

    /**
     * THE write entry point for a tile: returns a private, mutable bitmap for
     * (tx, ty), allocating it at the default pixel when missing and replacing a
     * shared solid with a byte copy of itself. Pixels are unchanged by this call,
     * so cached opacity stays valid until the caller's write marks it dirty.
     */
    private fun writableTile(k: Long, tx: Int, ty: Int): Bitmap {
        if (spillFile != null) {
            // Protect the tile from eviction until the next one is handed out,
            // and invalidate its spill record: the caller is about to change it.
            lastWritable = k
            val cur0 = tileAt(k)
            spillFile?.drop(k)
            if (cur0 == null) lost.remove(k)
        }
        val cur = tiles[k]
        if (cur != null) {
            if (solids.isEmpty() || k !in solids) return cur
            val fresh = TilePool.acquire(cur.width, cur.height)
            copyCanvas.setBitmap(fresh)
            copyCanvas.drawBitmap(cur, 0f, 0f, srcPaint)
            copyCanvas.setBitmap(null)
            solids.remove(k)
            canvases.remove(k)
            tiles[k] = fresh
            unshares++
            return fresh
        }
        if (CompositeProfile.enabled) CompositeProfile.tilesAllocated++
        val t = TilePool.acquire(tileW(tx), tileH(ty), defaultPixel)
        tiles[k] = t
        return t
    }

    private val copyCanvas = Canvas()

    /** Raw-pixel scratch for uniformity checks. */
    private var rawFull: IntArray? = null

    /**
     * The raw premultiplied pixel value when every pixel of [tile] has the same
     * bytes, as a non-negative Long; -1 when the tile is not uniform.
     */
    private fun uniformRaw(tile: Bitmap): Long {
        val n = tile.width * tile.height
        if (n == 0 || tile.rowBytes != tile.width * 4) return -1L
        var buf = rawFull
        if (buf == null || buf.size < n) {
            buf = IntArray(n)
            rawFull = buf
        }
        tile.copyPixelsToBuffer(IntBuffer.wrap(buf, 0, n))
        val v = buf[0]
        var i = 1
        while (i < n) {
            if (buf[i] != v) return -1L
            i++
        }
        return v.toLong() and 0xFFFFFFFFL
    }

    /**
     * Turns the private tile at [k] into a shared solid when it is uniform, or
     * drops it when it is uniformly the default. Returns true if it is solid.
     */
    private fun compactTile(k: Long): Boolean {
        if (k in solids) return true
        val t = tiles[k] ?: return false
        val raw = uniformRaw(t)
        if (raw < 0L) return false
        val colour = t.getPixel(0, 0)
        if (colour == defaultPixel) {
            removeTile(k)
            return false
        }
        val shared = SolidTiles.obtain(t, raw.toInt(), colour) ?: return false
        canvases.remove(k)
        tiles[k] = shared
        solids[k] = colour
        // Solids stay resident (no private bytes), so a spill record is moot.
        spillFile?.drop(k)
        TilePool.release(t)
        return true
    }

    /**
     * Installs the shared solid [shared] (colour [colour]) at [k], releasing
     * whatever private tile was there. Pixels change, so the caller marks dirty.
     */
    private fun assignSolid(k: Long, shared: Bitmap, colour: Int) {
        forgetSpill(k)
        val old = tiles[k]
        if (old != null && old !== shared) releaseOwned(k, old)
        canvases.remove(k)
        tiles[k] = shared
        solids[k] = colour
        opaqueTiles.remove(k)
        opaqueVerified.remove(k)
    }

    /**
     * Shared solid of size [w] x [h] holding exactly the bytes [produce] writes
     * into a fresh tile, or null when that result is not uniform / is default.
     */
    private fun solidFrom(w: Int, h: Int, produce: (Bitmap) -> Unit): Pair<Bitmap, Int>? {
        val sample = TilePool.acquire(w, h, defaultPixel)
        produce(sample)
        val raw = uniformRaw(sample)
        val colour = sample.getPixel(0, 0)
        val shared = if (raw < 0L || colour == defaultPixel) null
        else SolidTiles.obtain(sample, raw.toInt(), colour)
        TilePool.release(sample)
        return shared?.let { it to colour }
    }

    override fun compactSolids(rect: Rect): Int {
        if (!solidTiles || !materialized) return 0
        val r = Rect(rect)
        if (!r.intersect(0, 0, width, height)) return 0
        var n = 0
        for (ty in tyRange(r)) {
            for (tx in txRange(r)) {
                val k = key(tx, ty)
                if (tiles.containsKey(k) && compactTile(k)) n++
            }
        }
        return n
    }

    override fun solidTileCount(): Int = solids.size

    override fun verifySolids(): Int {
        var bad = 0
        for ((k, t) in tiles) {
            val colour = solids[k]
            if (colour == null) {
                if (!t.isMutable || SolidTiles.isShared(t)) bad++
                continue
            }
            if (t.isMutable || !SolidTiles.isShared(t)) {
                bad++
                continue
            }
            if (SolidTiles.colourOf(t) != colour || canvases.containsKey(k)) {
                bad++
                continue
            }
            val n = t.width * t.height
            val buf = scanBuf(n)
            t.getPixels(buf, 0, t.width, 0, 0, t.width, t.height)
            var i = 0
            while (i < n) {
                if (buf[i] != colour) {
                    bad++
                    break
                }
                i++
            }
            val tx = (k ushr 32).toInt()
            val ty = k.toInt()
            if (t.width != tileW(tx) || t.height != tileH(ty)) bad++
        }
        for (k in solids.keys) if (!tiles.containsKey(k)) bad++
        for (k in evicted) if (tiles.containsKey(k) || k in solids) bad++
        return bad
    }

    private fun splitInto(source: Bitmap) {
        for (ty in 0 until tileCountY) {
            for (tx in 0 until tileCountX) {
                val tw = tileW(tx)
                val th = tileH(ty)
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                var any = false
                for (y in 0 until th) {
                    source.getPixels(scanRow, 0, tw, x0, y0 + y, tw, 1)
                    var x = 0
                    while (x < tw) {
                        if (scanRow[x] != defaultPixel) {
                            any = true
                            break
                        }
                        x++
                    }
                    if (any) break
                }
                if (!any) continue
                val tile = TilePool.acquire(tw, th)
                val c = Canvas(tile)
                c.drawBitmap(
                    source,
                    Rect(x0, y0, x0 + tw, y0 + th),
                    RectF(0f, 0f, tw.toFloat(), th.toFloat()),
                    srcPaint
                )
                val k = key(tx, ty)
                tiles[k] = tile
                if (solidTiles) compactTile(k)
                // Opening a large document splits layer after layer: keep the
                // resident set inside the budget while doing it.
                cache?.evictIfFarOver()
            }
        }
    }

    override val isResident: Boolean get() = materialized

    override fun ensureResident() {
        if (materialized) return
        val file = backing
        if (file == null) {
            materialized = true
            return
        }
        val decoded = runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
            )
        }.getOrNull()
        if (decoded != null) {
            splitInto(decoded)
            decoded.recycle()
            materialized = true
        }
    }

    override fun backingFile(): File? = backing

    override fun sharedBitmap(): Bitmap? = null

    override fun residentBytes(): Long {
        var total = 0L
        // Arithmetic, not Bitmap.byteCount (a JNI call per tile): the cache
        // sums this over every store at each safe point.
        for (k in tiles.keys) {
            if (k in solids) continue
            total += tileW((k ushr 32).toInt()).toLong() * tileH(k.toInt()).toLong() * 4L
        }
        return total
    }

    override fun releaseToDisk(file: File) {
        releaseAll()
        backing = file
        materialized = false
    }

    override fun getTile(tx: Int, ty: Int): Bitmap? {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return null
        return tileAt(key(tx, ty))
    }

    override fun obtainTileForWrite(tx: Int, ty: Int): Bitmap {
        ensureResident()
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) {
            throw IndexOutOfBoundsException("tile ($tx, $ty) outside ${tileCountX}x$tileCountY")
        }
        return writableTile(key(tx, ty), tx, ty)
    }

    override fun forEachTileIn(rect: Rect, action: (tx: Int, ty: Int, tile: Bitmap) -> Unit) {
        if (rect.isEmpty) return
        for (ty in tyRange(rect)) {
            for (tx in txRange(rect)) {
                tileAt(key(tx, ty))?.let { action(tx, ty, it) }
            }
        }
    }

    override fun isTileEmpty(tx: Int, ty: Int): Boolean {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return true
        val k = key(tx, ty)
        // Evicted tiles are never empty (an empty tile is removed instead).
        if (evicted.isNotEmpty() && k in evicted) return false
        val tile = tiles[k] ?: return true
        solids[k]?.let { return it == defaultPixel }
        val w = tile.width
        val n = w * tile.height
        val buf = scanBuf(n)
        tile.getPixels(buf, 0, w, 0, 0, w, tile.height)
        var i = 0
        while (i < n) {
            if (buf[i] != defaultPixel) return false
            i++
        }
        return true
    }

    override fun clearTile(tx: Int, ty: Int) {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return
        removeTile(key(tx, ty))
    }

    override fun markDirty(rect: Rect) {
        if (rect.isEmpty) return
        invalidateOpaque(rect)
        if (dirty.isEmpty) dirty.set(rect) else dirty.union(rect)
    }

    override fun readRect(rect: Rect): Bitmap {
        ensureResident()
        val out = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(defaultPixel, BlendMode.SRC)
        forEachTileIn(rect) { tx, ty, tile ->
            val x0 = tx * tileSize
            val y0 = ty * tileSize
            val l = max(rect.left, x0)
            val t = max(rect.top, y0)
            val r = min(rect.right, x0 + tile.width)
            val b = min(rect.bottom, y0 + tile.height)
            if (r > l && b > t) {
                c.drawBitmap(
                    tile,
                    Rect(l - x0, t - y0, r - x0, b - y0),
                    Rect(l - rect.left, t - rect.top, r - rect.left, b - rect.top),
                    srcPaint
                )
            }
        }
        return out
    }

    override fun readRectInto(rect: Rect, target: Bitmap) {
        ensureResident()
        val c = Canvas(target)
        c.drawColor(defaultPixel, BlendMode.SRC)
        forEachTileIn(rect) { tx, ty, tile ->
            val x0 = tx * tileSize
            val y0 = ty * tileSize
            val l = max(rect.left, x0)
            val t = max(rect.top, y0)
            val r = min(rect.right, x0 + tile.width)
            val b = min(rect.bottom, y0 + tile.height)
            if (r > l && b > t) {
                c.drawBitmap(
                    tile,
                    Rect(l - x0, t - y0, r - x0, b - y0),
                    Rect(l - rect.left, t - rect.top, r - rect.left, b - rect.top),
                    srcPaint
                )
            }
        }
    }

    override fun writeRect(rect: Rect, source: Bitmap, srcRect: Rect) {
        ensureResident()
        for (ty in tyRange(rect)) {
            for (tx in txRange(rect)) {
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val l = max(rect.left, x0)
                val t = max(rect.top, y0)
                val r = min(rect.right, x0 + tileW(tx))
                val b = min(rect.bottom, y0 + tileH(ty))
                if (r <= l || b <= t) continue
                val tile = obtainTileForWrite(tx, ty)
                val src = Rect(
                    srcRect.left + (l - rect.left),
                    srcRect.top + (t - rect.top),
                    srcRect.left + (r - rect.left),
                    srcRect.top + (b - rect.top)
                )
                Canvas(tile).drawBitmap(
                    source,
                    src,
                    Rect(l - x0, t - y0, r - x0, b - y0),
                    srcPaint
                )
            }
        }
        markDirty(rect)
    }

    override fun getPixel(x: Int, y: Int): Int {
        ensureResident()
        if (x !in 0 until width || y !in 0 until height) return defaultPixel
        val tx = x / tileSize
        val ty = y / tileSize
        val tile = tileAt(key(tx, ty)) ?: return defaultPixel
        return tile.getPixel(x - tx * tileSize, y - ty * tileSize)
    }

    override fun clearRect(rect: Rect) {
        ensureResident()
        val r = Rect(rect)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        val remove = ArrayList<Long>()
        for (ty in tyRange(r)) {
            for (tx in txRange(r)) {
                val k = key(tx, ty)
                if (!tiles.containsKey(k) && (evicted.isEmpty() || k !in evicted)) continue
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val covers = r.left <= x0 && r.top <= y0 &&
                    r.right >= x0 + tileW(tx) && r.bottom >= y0 + tileH(ty)
                if (covers) {
                    remove.add(k)
                } else {
                    val c = canvasFor(k, writableTile(k, tx, ty))
                    val save = c.save()
                    c.clipRect(r.left - x0, r.top - y0, r.right - x0, r.bottom - y0)
                    c.drawColor(defaultPixel, BlendMode.SRC)
                    c.restoreToCount(save)
                }
            }
        }
        for (k in remove) removeTile(k)
        pruneEmptyTiles(r)
        markDirty(r)
    }

    override fun fillRect(rect: Rect, color: Int) {
        ensureResident()
        if (color == defaultPixel) {
            clearRect(rect)
            return
        }
        val r = Rect(rect)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        // Tiles the rect covers completely become one shared solid each: the
        // solid is the byte result of this same drawColor on a fresh tile, and
        // SRC does not depend on what was there before.
        var shapes: HashMap<Long, Pair<Bitmap, Int>?>? = null
        for (ty in tyRange(r)) {
            for (tx in txRange(r)) {
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                if (solidTiles && r.left <= x0 && r.top <= y0 &&
                    r.right >= x0 + tileW(tx) && r.bottom >= y0 + tileH(ty)
                ) {
                    val m = shapes ?: HashMap<Long, Pair<Bitmap, Int>?>().also { shapes = it }
                    val wh = key(tileW(tx), tileH(ty))
                    val solid = if (m.containsKey(wh)) m[wh] else solidFrom(tileW(tx), tileH(ty)) {
                        Canvas(it).drawColor(color, BlendMode.SRC)
                    }.also { m[wh] = it }
                    if (solid != null) {
                        assignSolid(key(tx, ty), solid.first, solid.second)
                        continue
                    }
                }
                val tile = obtainTileForWrite(tx, ty)
                val c = Canvas(tile)
                val save = c.save()
                c.clipRect(r.left - x0, r.top - y0, r.right - x0, r.bottom - y0)
                c.drawColor(color, BlendMode.SRC)
                c.restoreToCount(save)
            }
        }
        markDirty(r)
    }

    override fun withWriteCanvas(rect: Rect, block: (Canvas) -> Unit) {
        val r = Rect(rect)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        ensureResident()
        for (ty in tyRange(r)) {
            for (tx in txRange(r)) {
                if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) continue
                val tile = obtainTileForWrite(tx, ty)
                val k = key(tx, ty)
                val c = canvasFor(k, tile)
                val save = c.save()
                c.translate(-(tx * tileSize).toFloat(), -(ty * tileSize).toFloat())
                c.clipRect(r)
                block(c)
                c.restoreToCount(save)
            }
        }
        markDirty(r)
    }

    override fun editTile(tx: Int, ty: Int, block: (Canvas) -> Unit) {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return
        val tile = obtainTileForWrite(tx, ty)
        val k = key(tx, ty)
        val c = canvasFor(k, tile)
        val save = c.save()
        c.translate(-(tx * tileSize).toFloat(), -(ty * tileSize).toFloat())
        c.clipRect(tx * tileSize, ty * tileSize, tx * tileSize + tile.width, ty * tileSize + tile.height)
        block(c)
        c.restoreToCount(save)
        markDirty(
            Rect(tx * tileSize, ty * tileSize, tx * tileSize + tile.width, ty * tileSize + tile.height)
        )
    }

    private fun dirtyUnion(l: Int, t: Int, r: Int, b: Int) {
        if (opaqueTiles.isNotEmpty()) {
            for (ty in t / tileSize..(b - 1) / tileSize) {
                for (tx in l / tileSize..(r - 1) / tileSize) {
                    val k = key(tx, ty)
                    opaqueTiles.remove(k)
                    opaqueVerified.remove(k)
                }
            }
        }
        if (dirty.isEmpty) {
            dirty.set(l, t, r, b)
        } else {
            if (l < dirty.left) dirty.left = l
            if (t < dirty.top) dirty.top = t
            if (r > dirty.right) dirty.right = r
            if (b > dirty.bottom) dirty.bottom = b
        }
    }

    override fun putTileRegion(tx: Int, ty: Int, clip: Rect, source: Bitmap?) {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return
        val tx0 = tx * tileSize
        val ty0 = ty * tileSize
        val r = Rect(clip)
        r.intersect(tx0, ty0, tx0 + tileW(tx), ty0 + tileH(ty))
        if (r.isEmpty) return
        ensureResident()
        val k = key(tx, ty)
        if (source == null) {
            if (!tiles.containsKey(k) && (evicted.isEmpty() || k !in evicted)) return
            val c = canvasFor(k, writableTile(k, tx, ty))
            val save = c.save()
            c.clipRect(r.left - tx0, r.top - ty0, r.right - tx0, r.bottom - ty0)
            c.drawColor(defaultPixel, BlendMode.SRC)
            c.restoreToCount(save)
            if (isTileEmpty(tx, ty)) removeTile(k)
        } else {
            val tile = obtainTileForWrite(tx, ty)
            val c = canvasFor(k, tile)
            val save = c.save()
            c.drawBitmap(
                source,
                null,
                Rect(r.left - tx0, r.top - ty0, r.right - tx0, r.bottom - ty0),
                srcPaint
            )
            c.restoreToCount(save)
        }
        dirtyUnion(r.left, r.top, r.right, r.bottom)
    }

    override fun writeCanvas(): Canvas {
        throw UnsupportedOperationException(
            "SparseTileStore has no whole-canvas writer; stamp through editTile"
        )
    }

    override fun drawRect(canvas: Canvas, src: Rect, dst: RectF, paint: Paint?) {
        ensureResident()
        val r = Rect(src)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        val patch = readRect(r)
        canvas.drawBitmap(patch, null, dst, paint)
        patch.recycle()
    }

    /**
     * Draws this store into [canvas] with [paint], as if the whole surface had
     * been drawn in one pass.
     *
     * A normal layer (default = transparent) skips missing tiles: blending a
     * transparent source never changes the destination, so that is exact for
     * every blend mode. A default-pixel store (a mask, default = opaque white)
     * must draw its default for missing tiles too, otherwise a blend mode would
     * see a hole where the layer is simply untouched. Missing tiles are filled
     * with a solid default rect through the same paint, so every destination
     * pixel is written exactly once either way. Only the canvas' current clip is
     * visited, so compositing a viewport stays cheap.
     */
    override fun drawInto(canvas: Canvas, paint: Paint?) {
        ensureResident()
        if (defaultPixel == 0) {
            for (k in allKeys()) {
                val tile = tileAt(k) ?: continue
                canvas.drawBitmap(
                    tile, ((k ushr 32).toInt() * tileSize).toFloat(),
                    (k.toInt() * tileSize).toFloat(), paint
                )
            }
            return
        }
        val r = Rect()
        canvas.getClipBounds(r)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        drawDefaultTiles(canvas, r, paint)
    }

    override fun drawIntoRect(canvas: Canvas, rect: Rect, paint: Paint?) {
        ensureResident()
        val save = canvas.save()
        canvas.clipRect(rect)
        if (defaultPixel == 0) {
            forEachTileIn(rect) { tx, ty, tile ->
                canvas.drawBitmap(
                    tile, (tx * tileSize).toFloat(), (ty * tileSize).toFloat(), paint
                )
            }
        } else {
            val r = Rect(rect)
            r.intersect(0, 0, width, height)
            if (!r.isEmpty) drawDefaultTiles(canvas, r, paint)
        }
        canvas.restoreToCount(save)
    }

    /**
     * [drawIntoRect] without the clip dance: the caller already clipped the
     * canvas to [rect]. The tile lookup is hoisted per row, which is what the
     * per-tile compositor does hundreds of times per frame.
     */
    override fun drawIntoRectPreClipped(canvas: Canvas, rect: Rect, paint: Paint?) {
        ensureResident()
        if (defaultPixel == 0) {
            val txr = txRange(rect)
            if (txr.isEmpty()) return
            val prof = CompositeProfile.enabled
            for (ty in tyRange(rect)) {
                // Tile keys pack tx in the high bits and ty in the low bits.
                val y0 = (ty * tileSize).toFloat()
                val tyBits = ty.toLong() and 0xFFFFFFFFL
                for (tx in txr) {
                    val key = (tx.toLong() shl 32) or tyBits
                    if (!prof) {
                        val tile = tileAt(key) ?: continue
                        canvas.drawBitmap(tile, (tx * tileSize).toFloat(), y0, paint)
                        continue
                    }
                    val t0 = System.nanoTime()
                    val tile = tileAt(key)
                    CompositeProfile.lookupNanos += System.nanoTime() - t0
                    if (tile == null) continue
                    CompositeProfile.tileLookups++
                    canvas.drawBitmap(tile, (tx * tileSize).toFloat(), y0, paint)
                }
            }
        } else {
            val r = Rect(rect)
            r.intersect(0, 0, width, height)
            if (!r.isEmpty) drawDefaultTiles(canvas, r, paint)
        }
    }

    /**
     * True when every pixel of the tile is fully opaque. Cached until something
     * writes into that tile, so the compositor can skip whole layers below an
     * opaque Normal layer without paying for a scan per frame.
     */
    override fun isTileOpaque(tx: Int, ty: Int): Boolean {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return false
        val k = key(tx, ty)
        if (CompositeProfile.enabled) {
            CompositeProfile.opacityQueries++
            val cached = opaqueTiles[k]
            if (cached != null) {
                CompositeProfile.opacityHits++
                return cached
            }
            val t0 = System.nanoTime()
            val all = computeOpaque(k)
            CompositeProfile.opacityNanos += System.nanoTime() - t0
            opaqueTiles[k] = all
            opaqueVerified.remove(k)
            return all
        }
        opaqueTiles[k]?.let { return it }
        val all = computeOpaque(k)
        opaqueTiles[k] = all
        opaqueVerified.remove(k)
        return all
    }

    /** Scans one tile for full opacity; the raw truth behind [isTileOpaque]. */
    private fun computeOpaque(k: Long, trustSolid: Boolean = true): Boolean {
        val t = tileAt(k)
        // A missing tile is the store default, which is opaque for a mask.
        if (t == null || t.isRecycled) return (defaultPixel ushr 24) == 255
        // A solid's colour is its every pixel. The verifier passes false so it
        // re-derives the answer from the pixels instead of from metadata.
        if (trustSolid) solids[k]?.let { return (it ushr 24) == 255 }
        val n = t.width * t.height
        val buf = scanBuf(n)
        // One bulk read instead of one JNI call per row: the scan runs on the
        // first touch of every tile, so it is on the tail-latency path.
        t.getPixels(buf, 0, t.width, 0, 0, t.width, t.height)
        var i = 0
        while (i < n) {
            if ((buf[i] ushr 24) != 255) return false
            i++
        }
        return true
    }

    /** Reusable pixel buffer for full-tile scans; grown on demand. */
    private var scanFull: IntArray? = null

    private fun scanBuf(n: Int): IntArray {
        val cur = scanFull
        if (cur != null && cur.size >= n) return cur
        val b = IntArray(n)
        scanFull = b
        return b
    }

    /**
     * True when every pixel of [rect] is the store default. Missing tiles count
     * as the default, so a rect over untouched area is empty.
     */
    override fun isRectEmpty(rect: Rect): Boolean {
        val r = Rect(rect)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return true
        for (ty in tyRange(r)) {
            for (tx in txRange(r)) {
                val tile = tileAt(key(tx, ty)) ?: continue
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val l = max(r.left, x0)
                val t = max(r.top, y0)
                val rr = min(r.right, x0 + tile.width)
                val bb = min(r.bottom, y0 + tile.height)
                if (rr <= l || bb <= t) continue
                val w = rr - l
                val h = bb - t
                val buf = scanBuf(w * h)
                tile.getPixels(buf, 0, w, l - x0, t - y0, w, h)
                var i = 0
                while (i < w * h) {
                    if (buf[i] != defaultPixel) return false
                    i++
                }
            }
        }
        return true
    }

    /**
     * Debug verifier for the per-tile opacity cache: recomputes the flag from
     * pixels and compares it with the cached answer, invalidating and counting
     * mismatches. [all] checks every cached tile; otherwise only tiles that
     * have not been verified since they were last written.
     */
    override fun verifyOpaqueFlags(all: Boolean): Int {
        if (opaqueTiles.isEmpty()) return 0
        var bad = 0
        val keys = if (all) ArrayList(opaqueTiles.keys)
        else opaqueTiles.keys.filter { it !in opaqueVerified }
        for (k in keys) {
            val cached = opaqueTiles[k] ?: continue
            val truth = computeOpaque(k, trustSolid = false)
            opaqueVerified.add(k)
            if (truth != cached) {
                bad++
                opaqueTiles.remove(k)
                opaqueVerified.remove(k)
            }
        }
        return bad
    }

    /** Drops the cached opacity of every tile [rect] touches. */
    private fun invalidateOpaque(rect: Rect) {
        if (opaqueTiles.isEmpty()) return
        for (ty in tyRange(rect)) {
            for (tx in txRange(rect)) {
                val k = key(tx, ty)
                opaqueTiles.remove(k)
                opaqueVerified.remove(k)
            }
        }
    }

    private fun drawDefaultTiles(canvas: Canvas, rect: Rect, paint: Paint?) {
        for (ty in rect.top / tileSize..(rect.bottom - 1) / tileSize) {
            for (tx in rect.left / tileSize..(rect.right - 1) / tileSize) {
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val tile = tileAt(key(tx, ty))
                if (tile != null) {
                    canvas.drawBitmap(tile, x0.toFloat(), y0.toFloat(), paint)
                } else {
                    fillPaint.reset()
                    fillPaint.color = defaultPixel
                    if (paint != null) {
                        fillPaint.alpha = paint.alpha
                        fillPaint.blendMode = paint.blendMode
                        fillPaint.colorFilter = paint.colorFilter
                        fillPaint.isAntiAlias = paint.isAntiAlias
                        fillPaint.isDither = paint.isDither
                    }
                    canvas.drawRect(
                        x0.toFloat(), y0.toFloat(),
                        min(width, x0 + tileSize).toFloat(),
                        min(height, y0 + tileSize).toFloat(),
                        fillPaint
                    )
                }
            }
        }
    }

    override fun copyAll(): Bitmap {
        ensureResident()
        val out = AllocTracker.bitmap("store.copyAll", width, height)
        val c = Canvas(out)
        c.drawColor(defaultPixel, BlendMode.SRC)
        for (k in allKeys()) {
            val tile = tileAt(k) ?: continue
            val tx = (k ushr 32).toInt()
            val ty = k.toInt()
            c.drawBitmap(tile, (tx * tileSize).toFloat(), (ty * tileSize).toFloat(), srcPaint)
        }
        return out
    }

    override fun readTileCopy(tx: Int, ty: Int): Bitmap? {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return null
        val tile = tileAt(key(tx, ty)) ?: return null
        if (isTileEmpty(tx, ty)) return null
        val out = TilePool.acquire(tile.width, tile.height)
        Canvas(out).drawBitmap(tile, 0f, 0f, null)
        return out
    }

    override fun putTile(tx: Int, ty: Int, source: Bitmap) {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return
        ensureResident()
        val tw = tileW(tx)
        val th = tileH(ty)
        val k = key(tx, ty)
        // A shared solid of the right size is immutable, so the tile can simply
        // reference it: the source's other users can never see a write.
        if (solidTiles && !source.isMutable && source.width == tw && source.height == th) {
            val colour = SolidTiles.colourOf(source)
            if (colour != null) {
                if (colour == defaultPixel) removeTile(k) else assignSolid(k, source, colour)
                return
            }
        }
        var any = false
        for (y in 0 until min(th, source.height)) {
            source.getPixels(scanRow, 0, min(tw, source.width), 0, y, min(tw, source.width), 1)
            var x = 0
            while (x < min(tw, source.width)) {
                if (scanRow[x] != defaultPixel) {
                    any = true
                    break
                }
                x++
            }
            if (any) break
        }
        if (!any) {
            removeTile(k)
            return
        }
        val tile = TilePool.acquire(tw, th)
        Canvas(tile).drawBitmap(
            source,
            Rect(0, 0, min(tw, source.width), min(th, source.height)),
            Rect(0, 0, tw, th),
            srcPaint
        )
        forgetSpill(k)
        val old = tiles[k]
        if (old != null && old !== source) releaseOwned(k, old)
        solids.remove(k)
        canvases.remove(k)
        opaqueTiles.remove(k)
        opaqueVerified.remove(k)
        tiles[k] = tile
        if (solidTiles) compactTile(k)
    }

    override fun pruneEmptyTiles(rect: Rect) {
        ensureResident()
        val r = Rect(rect)
        r.intersect(0, 0, width, height)
        if (r.isEmpty) return
        val remove = ArrayList<Long>()
        for (ty in tyRange(r)) {
            for (tx in txRange(r)) {
                val k = key(tx, ty)
                if (tiles.containsKey(k) && isTileEmpty(tx, ty)) remove.add(k)
            }
        }
        for (k in remove) removeTile(k)
    }

    override fun nonEmptyBounds(): Rect {
        ensureResident()
        val out = Rect()
        // Evicted tiles are non-empty by invariant, so they count without a load.
        for (k in allKeys()) {
            val tx = (k ushr 32).toInt()
            val ty = k.toInt()
            if (isTileEmpty(tx, ty)) continue
            val x0 = tx * tileSize
            val y0 = ty * tileSize
            val x1 = x0 + tileW(tx)
            val y1 = y0 + tileH(ty)
            if (out.isEmpty) out.set(x0, y0, x1, y1) else out.union(x0, y0, x1, y1)
        }
        return out
    }

    override fun clear() {
        releaseAll()
        backing = null
        materialized = true
        markDirty(Rect(0, 0, width, height))
    }

    override fun fill(color: Int) {
        releaseAll()
        backing = null
        materialized = true
        if (color != defaultPixel) {
            // O(tiles) metadata: at most four tile shapes (full, right edge,
            // bottom edge, corner), each one shared solid. The solid holds the
            // same bytes the per-tile eraseColor below would have produced.
            val shapes = HashMap<Long, Pair<Bitmap, Int>?>()
            for (ty in 0 until tileCountY) {
                for (tx in 0 until tileCountX) {
                    val k = key(tx, ty)
                    val tw = tileW(tx)
                    val th = tileH(ty)
                    if (solidTiles) {
                        val wh = key(tw, th)
                        val solid = if (shapes.containsKey(wh)) shapes[wh]
                        else solidFrom(tw, th) { it.eraseColor(color) }.also { shapes[wh] = it }
                        if (solid != null) {
                            tiles[k] = solid.first
                            solids[k] = solid.second
                            continue
                        }
                    }
                    tiles[k] = TilePool.acquire(tw, th, color)
                }
            }
        }
        markDirty(Rect(0, 0, width, height))
    }
}
