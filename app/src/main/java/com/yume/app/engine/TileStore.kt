package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
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
            SparseTileStore(width, height, TileStoreConfig.tileSize, pixels, backing, defaultPixel)
        } else {
            SingleTileStore(width, height, pixels, backing)
        }

    fun blank(width: Int, height: Int): TileStore =
        if (TileStoreConfig.useSparseLayers) {
            // A blank sparse layer holds no tiles at all: allocating (and then
            // splitting) a full-canvas bitmap here used to cost one canvas per
            // layer creation.
            SparseTileStore(width, height, TileStoreConfig.tileSize)
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
                width, height, TileStoreConfig.tileSize, null, null, color
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
                width, height, TileStoreConfig.tileSize, pixels, backing, Color.WHITE
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
    override val defaultPixel: Int = 0
) : TileStore {

    override val isSparse: Boolean = true

    private val tiles = HashMap<Long, Bitmap>()
    private val canvases = HashMap<Long, Canvas>()
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

    private fun canvasFor(k: Long, tile: Bitmap): Canvas =
        canvases.getOrPut(k) { Canvas(tile) }

    private fun removeTile(k: Long) {
        tiles.remove(k)
        canvases.remove(k)
        // A removed tile reads as missing, so any cached opacity is stale.
        opaqueTiles.remove(k)
        opaqueVerified.remove(k)
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
                val tile = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                val c = Canvas(tile)
                c.drawBitmap(
                    source,
                    Rect(x0, y0, x0 + tw, y0 + th),
                    RectF(0f, 0f, tw.toFloat(), th.toFloat()),
                    srcPaint
                )
                tiles[key(tx, ty)] = tile
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
        for (tile in tiles.values) total += tile.byteCount.toLong()
        return total
    }

    override fun releaseToDisk(file: File) {
        backing = file
        tiles.clear()
        canvases.clear()
        opaqueTiles.clear()
        opaqueVerified.clear()
        materialized = false
    }

    override fun getTile(tx: Int, ty: Int): Bitmap? {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return null
        return tiles[key(tx, ty)]
    }

    override fun obtainTileForWrite(tx: Int, ty: Int): Bitmap {
        ensureResident()
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) {
            throw IndexOutOfBoundsException("tile ($tx, $ty) outside ${tileCountX}x$tileCountY")
        }
        val k = key(tx, ty)
        return tiles.getOrPut(k) {
            if (CompositeProfile.enabled) CompositeProfile.tilesAllocated++
            val bmp = Bitmap.createBitmap(tileW(tx), tileH(ty), Bitmap.Config.ARGB_8888)
            if (defaultPixel != 0) bmp.eraseColor(defaultPixel)
            bmp
        }
    }

    override fun forEachTileIn(rect: Rect, action: (tx: Int, ty: Int, tile: Bitmap) -> Unit) {
        if (rect.isEmpty) return
        for (ty in tyRange(rect)) {
            for (tx in txRange(rect)) {
                tiles[key(tx, ty)]?.let { action(tx, ty, it) }
            }
        }
    }

    override fun isTileEmpty(tx: Int, ty: Int): Boolean {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return true
        val tile = tiles[key(tx, ty)] ?: return true
        val row = scanRow
        for (y in 0 until tile.height) {
            tile.getPixels(row, 0, tile.width, 0, y, tile.width, 1)
            var x = 0
            while (x < tile.width) {
                if (row[x] != defaultPixel) return false
                x++
            }
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
        val tile = tiles[key(tx, ty)] ?: return defaultPixel
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
                val tile = tiles[k] ?: continue
                val x0 = tx * tileSize
                val y0 = ty * tileSize
                val covers = r.left <= x0 && r.top <= y0 &&
                    r.right >= x0 + tile.width && r.bottom >= y0 + tile.height
                if (covers) {
                    remove.add(k)
                } else {
                    val c = canvasFor(k, tile)
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
        for (ty in tyRange(r)) {
            for (tx in txRange(r)) {
                val x0 = tx * tileSize
                val y0 = ty * tileSize
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
            val tile = tiles[k] ?: return
            val c = canvasFor(k, tile)
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
            for ((k, tile) in tiles) {
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
                        val tile = tiles[key] ?: continue
                        canvas.drawBitmap(tile, (tx * tileSize).toFloat(), y0, paint)
                        continue
                    }
                    val t0 = System.nanoTime()
                    val tile = tiles[key]
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
    private fun computeOpaque(k: Long): Boolean {
        val t = tiles[k]
        // A missing tile is the store default, which is opaque for a mask.
        if (t == null || t.isRecycled) return (defaultPixel ushr 24) == 255
        for (y in 0 until t.height) {
            t.getPixels(scanRow, 0, t.width, 0, y, t.width, 1)
            var x = 0
            while (x < t.width) {
                if ((scanRow[x] ushr 24) != 255) return false
                x++
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
            val truth = computeOpaque(k)
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
                val tile = tiles[key(tx, ty)]
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
        for ((k, tile) in tiles) {
            val tx = (k ushr 32).toInt()
            val ty = k.toInt()
            c.drawBitmap(tile, (tx * tileSize).toFloat(), (ty * tileSize).toFloat(), srcPaint)
        }
        return out
    }

    override fun readTileCopy(tx: Int, ty: Int): Bitmap? {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return null
        val tile = tiles[key(tx, ty)] ?: return null
        if (isTileEmpty(tx, ty)) return null
        val out = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(tile, 0f, 0f, null)
        return out
    }

    override fun putTile(tx: Int, ty: Int, source: Bitmap) {
        if (tx !in 0 until tileCountX || ty !in 0 until tileCountY) return
        ensureResident()
        val tw = tileW(tx)
        val th = tileH(ty)
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
        val k = key(tx, ty)
        if (!any) {
            removeTile(k)
            return
        }
        val tile = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawBitmap(
            source,
            Rect(0, 0, min(tw, source.width), min(th, source.height)),
            Rect(0, 0, tw, th),
            srcPaint
        )
        canvases.remove(k)
        opaqueTiles.remove(k)
        opaqueVerified.remove(k)
        tiles[k] = tile
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
        for ((k, tile) in tiles) {
            val tx = (k ushr 32).toInt()
            val ty = k.toInt()
            if (isTileEmpty(tx, ty)) continue
            val x0 = tx * tileSize
            val y0 = ty * tileSize
            if (out.isEmpty) {
                out.set(x0, y0, x0 + tile.width, y0 + tile.height)
            } else {
                out.union(x0, y0, x0 + tile.width, y0 + tile.height)
            }
        }
        return out
    }

    override fun clear() {
        tiles.clear()
        canvases.clear()
        backing = null
        materialized = true
        markDirty(Rect(0, 0, width, height))
    }

    override fun fill(color: Int) {
        tiles.clear()
        canvases.clear()
        backing = null
        materialized = true
        if (color != defaultPixel) {
            for (ty in 0 until tileCountY) {
                for (tx in 0 until tileCountX) {
                    val tile = Bitmap.createBitmap(tileW(tx), tileH(ty), Bitmap.Config.ARGB_8888)
                    tile.eraseColor(color)
                    tiles[key(tx, ty)] = tile
                }
            }
        }
        markDirty(Rect(0, 0, width, height))
    }
}
