package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the document: a stack of layers plus a cached flattened composite.
 *
 * Everything in here must be touched from the render thread only. The
 * [composite] is rebuilt lazily over the union of the dirty rectangles that
 * strokes and layer edits report, which keeps frame cost proportional to what
 * actually changed rather than to the document size.
 */
class DrawingEngine(val width: Int, val height: Int) {

    val layers = ArrayList<Layer>()
    val groups = ArrayList<LayerGroup>()
    var activeIndex: Int = 0
        private set
    /** Non-zero when the layers panel has a group selected instead of a layer. */
    var activeGroupId: Long = 0L
        private set

    /**
     * Sparse mode: layers are sparse tiles and so is the composite. Captured at
     * construction; the dense path stays exactly as it was for the golden
     * oracle.
     */
    private val sparseMode: Boolean = TileStoreConfig.useSparseLayers

    var backgroundColor: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            if (sparseMode) {
                compositeTiles = newCompositeStore(value)
                markDirtyAll()
            }
        }

    var selection: Region? = null
        private set
    /** Filled path of [selection], used to clip painting. */
    var selectionClip: Path? = null
        private set
    /** Outline of [selection], used to draw the marching-ants overlay. */
    var selectionOutline: Path? = null
        private set

    var symmetryX: Boolean = false
    var symmetryY: Boolean = false
    /** Mirror axis positions in document pixels. */
    var symmetryXPos: Float = width / 2f
    var symmetryYPos: Float = height / 2f
    var symmetryLocked: Boolean = false
    /**
     * Radial (rotational) symmetry: number of segments around the symmetry
     * centre ([symmetryXPos], [symmetryYPos]); 0 = off. With [symmetryMirror]
     * every segment is also mirrored (a kaleidoscope).
     */
    @Volatile var symmetryRadial: Int = 0
    @Volatile var symmetryMirror: Boolean = false

    /** Drawing guides (grid, perspective, ruler). */
    val guides = Guides(width, height)

    /** Strokes painted in this document over its lifetime (shown in the gallery). */
    @Volatile var strokeCount: Long = 0L

    /** Document settings beyond the core manifest, as one JSON object. */
    fun extrasJson(): String = org.json.JSONObject().apply {
        put("guides", guides.toJson())
        put("radial", symmetryRadial)
        put("mirror", symmetryMirror)
        put("strokes", strokeCount)
    }.toString()

    fun loadExtras(json: String?) {
        if (json.isNullOrBlank()) return
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        guides.load(o.optJSONObject("guides"))
        symmetryRadial = o.optInt("radial", 0).let { if (it < 2) 0 else it.coerceAtMost(32) }
        symmetryMirror = o.optBoolean("mirror", false)
        strokeCount = o.optLong("strokes", 0L).coerceAtLeast(0L)
    }

    /** When set, only this layer is composited ("isolate layer"). */
    var soloLayerId: Long? = null
        private set

    /**
     * Non-destructive preview of an adjustment. The filter is a colour matrix
     * (GPU cost only) and/or a pre-blurred proxy bitmap drawn stretched over
     * the document, so dragging an adjustment slider stays interactive.
     */
    var preview: LayerPreview? = null

    /**
     * Sparse composite (sparse mode only). A missing tile reads as
     * [backgroundColor], so an empty document costs nothing and a tile is only
     * allocated where some layer actually covers it.
     */
    var compositeTiles: SparseTileStore? = null
        private set

    val compositeIsSparse: Boolean get() = compositeTiles != null

    /** Dense composite buffer. Only present in dense mode. */
    private var denseComposite: Bitmap? = null
    private var compositeCanvas: Canvas? = null

    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val bgPaint = Paint()
    /** Whole-bitmap staging blit: no filtering, no AA, no dither. */
    private val stagePaint = Paint().apply {
        blendMode = BlendMode.SRC
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }

    private var strokeBaseBitmap: Bitmap? = null

    /**
     * Pre-stroke snapshot of the active layer. Dense mode only: a sparse stroke
     * snapshots the tiles it touches instead (see [TileCapture]), so this is
     * never allocated there.
     */
    val strokeBase: Bitmap
        get() = strokeBaseBitmap ?: AllocTracker
            .bitmap("engine.strokeBase", width, height)
            .also { strokeBaseBitmap = it }

    private val dirty = Rect()
    private val layerPaint = Paint()
    private val groupPaint = Paint()
    private val maskPaint = Paint()
    /** Scratch surfaces for groups, masks and clipping; allocated on demand. */
    private var groupBuffer: Bitmap? = null
    private var layerBuffer: Bitmap? = null
    /** Sparse-mode scratch, pooled by exact size (dense keeps one canvas each). */
    private val groupPool = ScratchPool("engine.groupBuffer")
    private val layerPool = ScratchPool("engine.layerBuffer")
    /** Staging surface for one dirty sub-rect of a composite tile. */
    private val stagePool = ScratchPool("engine.compositeStage")
    private var nextId = 1L

    init {
        if (sparseMode) {
            compositeTiles = newCompositeStore(backgroundColor)
        } else {
            val b = AllocTracker.bitmap("engine.composite", width, height)
            denseComposite = b
            compositeCanvas = Canvas(b)
        }
    }

    /**
     * Composite sub-rects skipped this flush because a non-active layer's tile
     * was evicted and frames must not block on disk. They stay dirty (their
     * composite pixels are stale) until the prefetched tiles are adopted.
     */
    private val pending = ArrayList<Rect>()
    /** Start of the current flush, for the per-frame composite time budget. */
    private var frameStart = 0L
    /** Cold composite tiles the current flush may still queue for prefetch. */
    private var prefetchLeft = 0

    init {
        TileCache.active?.let { c ->
            c.externalBytes = {
                mapOf(
                    "mip" to (mipTiles?.residentBytes() ?: 0L),
                    "strokeCache" to strokeCacheBytes(),
                    "captures" to TileCapture.liveBytes
                )
            }
        }
    }

    /**
     * True when compositing [r] would read an evicted tile of a layer other than
     * the active one. Schedules those tiles for prefetch.
     */
    private fun needsDisk(r: Rect, prefetch: Boolean = true): Boolean {
        val active = activeLayer
        var found = false
        // Layers the stroke caches already cover for this tile are not read by
        // the composite, so their evicted tiles do not matter (r is within one
        // tile: compositeRegion works tile by tile).
        val ts = TileStoreConfig.tileSize
        val tk = ((r.left / ts).toLong() shl 32) or ((r.top / ts).toLong() and 0xFFFFFFFFL)
        val below = belowTiles
        val belowCovered = below != null && belowFrom in 1 until layers.size &&
            (belowSeeded.contains(tk) || below.getTile(r.left / ts, r.top / ts) != null)
        val aboveCovered = aboveTiles != null && aboveFrom in 1..layers.size &&
            aboveBuilt.contains(tk)
        for ((i, l) in layers.withIndex()) {
            if (!l.visible || l === active) continue
            if (belowCovered && i < belowFrom) continue
            if (aboveCovered && i >= aboveFrom) continue
            val st = l.tiles as? SparseTileStore
            if (st != null && st.hasEvictedIn(r, prefetch)) found = true
            val m = l.maskTiles as? SparseTileStore
            if (m != null && l.maskEnabled && m.hasEvictedIn(r, prefetch)) found = true
        }
        return found
    }

    /**
     * The sparse composite. Stage 3c: it is registered with the tile cache too,
     * because an 8192^2 document with an opaque background would otherwise pin
     * a full 256 MB composite. Its viewport tiles are touched every frame, so
     * LRU evicts layer tiles and off-screen composite tiles first.
     */
    private fun newCompositeStore(bg: Int): SparseTileStore =
        SparseTileStore(
            width, height, TileStoreConfig.tileSize, null, null, bg,
            // Flat composite tiles (a filled area, any number of flat layers
            // at any opacity) are one shared solid instead of 256 KB each.
            solidTiles = true,
            cache = TileCache.active
        ).also { it.cacheRole = "composite" }

    val activeLayer: Layer? get() = layers.getOrNull(activeIndex)
    val isTransparentBackground: Boolean get() = Color.alpha(backgroundColor) < 255

    /**
     * The flattened document as a bitmap. In sparse mode this materialises the
     * composite tiles, so only use it where a full image is genuinely needed
     * (export, the harness, a full-canvas eyedropper).
     */
    fun compositeBitmap(): Bitmap = compositeTiles?.copyAll() ?: composite

    /** One composite pixel, without materialising the document. */
    fun compositePixel(x: Int, y: Int): Int {
        val st = compositeTiles
        if (st == null) {
            return if (x in 0 until width && y in 0 until height) composite.getPixel(x, y) else 0
        }
        return st.getPixel(x, y)
    }

    /**
     * The composite pixel as it is now, not as the last frame left it: damage
     * not yet flushed (a stroke the eyedropper just cancelled, a deferred
     * tile) is composited for that one pixel first.
     */
    fun currentCompositePixel(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val p = Rect(x, y, x + 1, y + 1)
        if (Rect.intersects(dirty, p) || pending.any { Rect.intersects(it, p) }) {
            val cache = TileCache.active
            val old = cache?.blockingLoads
            if (cache != null) cache.blockingLoads = true
            try {
                compositeRegion(p)
            } finally {
                if (cache != null && old != null) cache.blockingLoads = old
            }
        }
        return compositePixel(x, y)
    }

    /**
     * Colour grid for the streaming magic wand. It reads the composite on demand
     * and keeps only the cells the flood fill reaches, so auto-select no longer
     * materialises the whole document.
     */
    fun compositeGrid(step: Int): WandGrid = WandGrid(
        width, height, step,
        compositeTiles,
        if (compositeTiles == null) composite else null,
        backgroundColor
    )

    /**
     * The composite scaled down to fit [maxPx]: exactly what the canvas shows,
     * so a thumbnail honours groups, clipping, masks and blend modes. Null while
     * the composite is not the plain document (solo view or a live preview).
     */
    fun compositeThumb(maxPx: Int): Bitmap? {
        if (soloLayerId != null || preview != null) return null
        val scale = maxPx.toFloat() / maxOf(width, height)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val mip = mipTiles
        if (mip != null && mipReady() && maxOf(mipWidth, mipHeight) >= maxPx) {
            // The quarter-size mip is current and big enough: a few tiles
            // instead of every composite tile (which may be on disk at 8K).
            c.scale(w.toFloat() / mipWidth, h.toFloat() / mipHeight)
            mip.drawInto(c, paint)
            return out
        }
        c.scale(w.toFloat() / width, h.toFloat() / height)
        val st = compositeTiles
        if (st != null) st.drawInto(c, paint) else c.drawBitmap(composite, 0f, 0f, paint)
        return out
    }

    /** The dense composite surface. Only valid in dense mode. */
    val composite: Bitmap
        get() = denseComposite
            ?: error("dense composite requested in sparse mode; use compositeBitmap()")

    // ---------------------------------------------------------------- layers

    fun ensureLayer(): Layer {
        if (layers.isEmpty()) {
            val l = Layer(
                id = nextId++,
                name = "Layer 1",
                tiles = Layer.blank(width, height)
            )
            layers.add(l)
            activeIndex = 0
            markDirtyAll()
        }
        return layers[activeIndex.coerceIn(0, layers.size - 1)]
    }

    /**
     * Adds a layer. When [bitmap] is null and [backingFile] is set the layer
     * starts lazy: its pixels stay on disk until something reads them.
     */
    fun newLayer(
        name: String? = null,
        bitmap: Bitmap? = null,
        at: Int = -1,
        backingFile: File? = null
    ): Layer {
        val index = if (at < 0) layers.size else at.coerceIn(0, layers.size)
        val pixels = bitmap
            ?: if (backingFile == null && !sparseMode) {
                AllocTracker.bitmap("engine.newLayer", width, height)
            } else {
                null
            }
        val layer = Layer(
            id = nextId++,
            name = name ?: "Layer ${layers.size + 1}",
            tiles = TileStoreFactory.create(width, height, pixels, backingFile)
        )
        layers.add(index, layer)
        activeIndex = index
        markDirtyAll()
        return layer
    }

    fun layerById(id: Long): Layer? = layers.firstOrNull { it.id == id }

    fun groupById(id: Long): LayerGroup? = groups.firstOrNull { it.id == id }

    fun indexOf(id: Long): Int = layers.indexOfFirst { it.id == id }

    fun selectLayer(id: Long): Boolean {
        val i = indexOf(id)
        if (i < 0) return false
        activeIndex = i
        activeGroupId = 0L
        return true
    }

    fun selectGroup(id: Long): Boolean {
        val group = groupById(id) ?: return false
        activeGroupId = group.id
        // Painting targets the group's topmost member.
        val top = layers.indexOfLast { it.groupId == group.id }
        if (top >= 0) activeIndex = top
        return true
    }

    /** Creates an empty group. Layers join it by setting their groupId. */
    fun newGroup(name: String = "Group"): LayerGroup {
        val group = LayerGroup(nextId++, name)
        groups.add(group)
        return group
    }

    /** Creates a group around [id] (or wraps the group it already sits in). */
    fun groupLayer(id: Long): LayerGroup? {
        val i = indexOf(id)
        if (i < 0) return null
        val layer = layers[i]
        val group = groupById(layer.groupId)
            ?: LayerGroup(nextId++, "Group ${groups.size + 1}").also { groups.add(it) }
        layer.groupId = group.id
        // The group owns the opacity of its children.
        layer.opacity = 1f
        activeGroupId = group.id
        activeIndex = i
        markDirtyAll()
        return group
    }

    /** Removes a group but keeps its layers. */
    fun ungroup(id: Long): Boolean {
        val group = groupById(id) ?: return false
        for (layer in layers) if (layer.groupId == group.id) layer.groupId = 0L
        groups.remove(group)
        if (activeGroupId == group.id) activeGroupId = 0L
        markDirtyAll()
        return true
    }

    private fun pruneGroups() {
        val used = layers.mapTo(HashSet()) { it.groupId }
        groups.removeAll { it.id !in used }
        if (groups.none { it.id == activeGroupId }) activeGroupId = 0L
    }

    fun deleteLayer(id: Long): Boolean {
        if (layers.size <= 1) return false
        val i = indexOf(id)
        if (i < 0) return false
        layers.removeAt(i)
        activeIndex = activeIndex.coerceIn(0, layers.size - 1)
        pruneGroups()
        markDirtyAll()
        return true
    }

    fun duplicateLayer(id: Long): Layer? {
        val i = indexOf(id)
        if (i < 0) return null
        val src = layers[i]
        // A sparse source is copied tile by tile, so the duplicate costs exactly
        // the source's painted area instead of a canvas-sized surface.
        val pixels = if (src.tiles.isSparse) {
            val store = TileStoreFactory.blank(width, height)
            TileOps.copyTiles(src.tiles, store)
            store
        } else {
            TileStoreFactory.create(width, height, src.tiles.copyAll(), null)
        }
        // The mask, clipping and group come along: a duplicate looks the same.
        val maskCopy: TileStore? = src.maskTiles?.let { m ->
            if (m.isSparse) {
                TileStoreFactory.mask(width, height, null, null).also { TileOps.copyTiles(m, it) }
            } else {
                TileStoreFactory.mask(width, height, m.copyAll(), null)
            }
        }
        val copy = Layer(
            id = nextId++,
            name = src.name,
            tiles = pixels,
            opacity = src.opacity,
            blend = src.blend,
            visible = src.visible,
            alphaLock = src.alphaLock,
            clipping = src.clipping,
            maskTiles = maskCopy,
            maskEnabled = src.maskEnabled,
            groupId = src.groupId
        )
        layers.add(i + 1, copy)
        activeIndex = i + 1
        markDirtyAll()
        return copy
    }

    fun mergeDown(id: Long): Boolean {
        val i = indexOf(id)
        if (i <= 0) return false
        val upper = layers[i]
        val lower = layers[i - 1]
        // Copy-on-write: the undo snapshot taken before the merge still holds
        // the old lower layer, so the merged pixels must never be written into
        // its store. Build a fresh store and a fresh Layer that owns it; the
        // snapshot keeps the untouched pair and undo restores them.
        val merged: TileStore
        if (lower.tiles.isSparse) {
            // Per tile: no canvas-sized surface, and only painted tiles are
            // copied. Blending is per pixel, so this matches the dense merge.
            val store = TileStoreFactory.blank(width, height)
            TileOps.copyTiles(lower.tiles, store)
            val p = Paint()
            p.alpha = (upper.opacity * 255f).toInt().coerceIn(0, 255)
            p.blendMode = upper.blend.mode
            p.isFilterBitmap = false
            val all = Rect(0, 0, width, height)
            TileOps.mergeInto(store, upper.tiles, all, p)
            store.pruneEmptyTiles(all)
            store.compactSolids(all)
            merged = store
        } else {
            val bmp = lower.tiles.copyAll()
            val c = Canvas(bmp)
            val p = Paint()
            p.alpha = (upper.opacity * 255f).toInt().coerceIn(0, 255)
            p.blendMode = upper.blend.mode
            upper.tiles.drawInto(c, p)
            merged = TileStoreFactory.create(width, height, bmp, null)
        }
        val result = Layer(
            id = lower.id,
            name = lower.name,
            tiles = merged,
            opacity = lower.opacity,
            blend = lower.blend,
            visible = lower.visible,
            alphaLock = lower.alphaLock,
            locked = lower.locked,
            clipping = lower.clipping,
            maskTiles = lower.maskTiles,
            maskEnabled = lower.maskEnabled,
            groupId = lower.groupId,
            editMask = lower.editMask
        )
        // Keep the revision moving forward so previews recompute.
        result.revision = lower.revision + 1
        layers[i - 1] = result
        layers.removeAt(i)
        activeIndex = (i - 1).coerceIn(0, layers.size - 1)
        markDirtyAll()
        return true
    }

    fun clearLayer(id: Long) {
        val layer = layerById(id) ?: return
        layer.paintStore.clear()
        layer.touch()
        markDirtyAll()
    }

    fun addMask(layer: Layer): Boolean {
        if (layer.hasMask) return false
        layer.maskTiles = TileStoreFactory.maskEmpty(width, height)
        layer.maskEnabled = true
        layer.editMask = true
        layer.touch()
        markDirtyAll()
        return true
    }

    fun removeMask(layer: Layer) {
        layer.maskTiles = null
        layer.editMask = false
        layer.touch()
        markDirtyAll()
    }

    fun fillLayer(id: Long, color: Int) {
        val layer = layerById(id) ?: return
        layer.paintStore.fill(color)
        layer.touch()
        markDirtyAll()
    }

    /**
     * Moves a layer and sets its group explicitly: [groupId] 0 leaves any
     * group, an existing group id joins it (the caller drops it next to that
     * group's members, so membership stays contiguous).
     */
    fun moveLayerInto(from: Int, to: Int, groupId: Long) {
        if (from !in layers.indices) return
        val target = to.coerceIn(0, layers.size - 1)
        val layer = layers.removeAt(from)
        layers.add(target, layer)
        val g = groupById(groupId)
        layer.groupId = g?.id ?: 0L
        // A grouped layer's opacity belongs to its group.
        if (g != null) layer.opacity = 1f
        var at = target
        if (g == null) {
            // Leaving a group from its middle would split the group in two:
            // the layer goes just below the group instead.
            val below = layers.getOrNull(at - 1)?.groupId ?: 0L
            val above = layers.getOrNull(at + 1)?.groupId ?: 0L
            if (below != 0L && below == above) {
                layers.removeAt(at)
                at = layers.indexOfFirst { it.groupId == below }.coerceAtLeast(0)
                layers.add(at, layer)
            }
        }
        activeIndex = at
        pruneGroups()
        markDirtyAll()
    }

    /** Deletes a group and its layers (refused if it holds every layer). */
    fun deleteGroup(id: Long): Boolean {
        val members = layers.filter { it.groupId == id }
        if (members.isEmpty() || members.size >= layers.size) return false
        layers.removeAll { it.groupId == id }
        groups.removeAll { it.id == id }
        activeGroupId = 0L
        activeIndex = activeIndex.coerceIn(0, layers.size - 1)
        pruneGroups()
        markDirtyAll()
        return true
    }

    /** Copies a group and its layers; the copy sits just above the original. */
    fun duplicateGroup(id: Long): Boolean {
        val group = groupById(id) ?: return false
        val members = layers.filter { it.groupId == id }
        if (members.isEmpty()) return false
        val top = layers.indexOfLast { it.groupId == id }
        val copyGroup = LayerGroup(
            nextId++, group.name + " copy", group.opacity, group.blend, group.visible, false, group.expanded
        )
        groups.add(copyGroup)
        val copies = ArrayList<Layer>()
        for (m in members) {
            val c = duplicateLayer(m.id) ?: continue
            layers.remove(c)
            c.groupId = copyGroup.id
            copies.add(c)
        }
        val at = (layers.indexOfLast { it.groupId == id } + 1).coerceAtLeast(top + 1).coerceAtMost(layers.size)
        layers.addAll(at, copies)
        activeGroupId = copyGroup.id
        activeIndex = at + copies.size - 1
        markDirtyAll()
        return true
    }

    /**
     * Flattens a group into one layer: its members merge bottom up, and the
     * result takes the group's opacity and blend mode.
     */
    fun mergeGroup(id: Long): Boolean {
        val group = groupById(id) ?: return false
        while (true) {
            val members = layers.filter { it.groupId == id }
            if (members.size < 2) break
            if (!mergeDown(members[1].id)) return false
        }
        val last = layers.firstOrNull { it.groupId == id } ?: return false
        last.opacity = group.opacity
        last.blend = group.blend
        last.groupId = 0L
        last.name = group.name
        groups.remove(group)
        activeGroupId = 0L
        activeIndex = indexOf(last.id).coerceAtLeast(0)
        markDirtyAll()
        return true
    }

    fun moveLayer(from: Int, to: Int) {
        if (from !in layers.indices) return
        val target = to.coerceIn(0, layers.size - 1)
        val layer = layers.removeAt(from)
        layers.add(target, layer)
        // Dropping a layer next to a group's members joins that group; dropping
        // it anywhere else leaves the group. Membership stays contiguous.
        layer.groupId = layers.getOrNull(target - 1)?.groupId ?: 0L
        activeIndex = target
        pruneGroups()
        markDirtyAll()
    }

    // ------------------------------------------------------------ compositing

    fun markDirty(r: Rect) {
        val x0 = max(0, r.left)
        val y0 = max(0, r.top)
        val x1 = min(width, r.right)
        val y1 = min(height, r.bottom)
        if (x1 <= x0 || y1 <= y0) return
        if (dirty.isEmpty) dirty.set(x0, y0, x1, y1) else dirty.union(x0, y0, x1, y1)
    }

    fun markDirtyAll() {
        dirty.set(0, 0, width, height)
    }

    fun hasDirty(): Boolean = !dirty.isEmpty || pending.isNotEmpty()

    /**
     * The document area on screen (set by the renderer each frame; empty =
     * no preference). Damage there is composited before damage elsewhere.
     */
    val priorityRect = Rect()

    /**
     * Rebuilds the dirty part of the composite. Returns true if anything changed.
     *
     * Composite tiles a non-blocking frame could not finish (time budget, or an
     * evicted tile still loading) are kept as their own per-tile rects, never
     * merged into one bounding box: a box would re-include tiles already done
     * and, at 8K, never converge.
     */
    fun flush(): Boolean {
        if (dirty.isEmpty && pending.isEmpty()) return false
        frameStart = System.nanoTime()
        prefetchLeft = 4
        val retry = ArrayList(pending)
        pending.clear()
        val prio = priorityRect
        if (!dirty.isEmpty) {
            // New damage first: it is what the user is looking at right now.
            val d = Rect(dirty)
            dirty.setEmpty()
            if (prio.isEmpty || !Rect.intersects(prio, d) || prio.contains(d)) {
                compositeRegion(d)
            } else {
                // The part on screen first (tile aligned, so no tile is split),
                // then the bands around it: a big undo or fill refreshes where
                // the user is looking before the off-screen rest.
                val ts = TileStoreConfig.tileSize
                val p = Rect(
                    prio.left / ts * ts, prio.top / ts * ts,
                    min(width, (prio.right + ts - 1) / ts * ts), min(height, (prio.bottom + ts - 1) / ts * ts)
                )
                p.intersect(d)
                compositeRegion(p)
                if (d.top < p.top) compositeRegion(Rect(d.left, d.top, d.right, p.top))
                if (p.bottom < d.bottom) compositeRegion(Rect(d.left, p.bottom, d.right, d.bottom))
                if (d.left < p.left) compositeRegion(Rect(d.left, p.top, p.left, p.bottom))
                if (p.right < d.right) compositeRegion(Rect(p.right, p.top, d.right, p.bottom))
            }
            if (sparseMode) mipDirtyUnion(d)
        }
        if (!prio.isEmpty && retry.size > 1) {
            // Deferred tiles on screen go first too.
            retry.sortBy { if (Rect.intersects(it, prio)) 0 else 1 }
        }
        val cache = TileCache.active
        for ((n, r) in retry.withIndex()) {
            // Out of frame time: carry the rest over untouched instead of
            // visiting thousands of deferred tiles only to defer them again.
            if (cache != null && !cache.blockingLoads &&
                System.nanoTime() - frameStart > cache.frameCompositeBudgetNs
            ) {
                for (k in n until retry.size) pending.add(retry[k])
                break
            }
            compositeRegion(r)
            if (sparseMode) mipDirtyUnion(r)
        }
        TileCache.active?.let { if (it.evictEveryOp) it.evictTo() }
        return true
    }

    /** Composite area still waiting for prefetched tiles (stale on screen). */
    fun deferredRect(): Rect {
        val out = Rect(dirty)
        for (r in pending) if (out.isEmpty) out.set(r) else out.union(r)
        return out
    }

    fun compositeAll() {
        pending.clear()
        compositeRegion(Rect(0, 0, width, height))
        if (sparseMode) mipDirtyUnion(Rect(0, 0, width, height))
        dirty.setEmpty()
    }

    private fun compositeRegion(rect: Rect) {
        val st = compositeTiles
        if (st == null) {
            val c = compositeCanvas ?: return
            renderInto(c, rect)
            return
        }
        // Sparse composite: composite only the part of each touched tile that is
        // actually dirty. A tile is a separate surface whose missing pixels read
        // as the background, and the work is proportional to the dirty area
        // instead of to the whole tiles it happens to touch.
        //
        // The dirty part is rendered into a staging surface of exactly that size
        // and then blitted as a *whole bitmap* at its integer document position,
        // never as a sub-rect of a wider buffer: a sub-rect source blit is not
        // byte-identical (Stage 2b-2 notes).
        val ts = st.tileSize
        val tx0 = rect.left / ts
        val tx1 = (rect.right - 1) / ts
        val ty0 = rect.top / ts
        val ty1 = (rect.bottom - 1) / ts
        val r = Rect()
        val sub = Rect()
        if (CompositeProfile.enabled) CompositeProfile.frames++
        // Nothing is in use between frames, so this is the safe point to shrink
        // the pools to their caps.
        groupPool.trim()
        layerPool.trim()
        stagePool.trim()
        val cache = TileCache.active
        val nonBlocking = cache != null && !cache.blockingLoads
        val passStart = if (nonBlocking) frameStart else System.nanoTime()
        // Only the next few cold tiles (in scan order) are prefetched: queueing
        // every deferred tile of a whole-canvas recomposite loads tiles in an
        // order the compositor does not consume them in, and they are evicted
        // again before use.
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                r.set(
                    tx * ts, ty * ts,
                    min(width, (tx + 1) * ts), min(height, (ty + 1) * ts)
                )
                if (r.isEmpty) continue
                if (CompositeProfile.enabled) {
                    CompositeProfile.tileVisits++
                    CompositeProfile.compositePx += r.width().toLong() * r.height().toLong()
                }
                // No disk read just to learn whether the tile exists.
                val existed = st.hasTile(tx, ty)
                sub.set(rect)
                sub.intersect(r)
                if (sub.isEmpty) continue
                // First touch of a tile by a stroke with a below cache: composite
                // the whole tile once so the cache is seeded (a whole-tile pass
                // produces the same pixels as the sub-rect pass, see Stage 3a).
                // Later frames then read ~3 sources instead of every layer, and
                // the layers below no longer need to stay resident.
                val below = belowTiles
                if (below != null && sub != r && belowBytes < belowBudget &&
                    below.getTile(tx, ty) == null &&
                    !belowSeeded.contains((tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL))
                ) {
                    sub.set(r)
                }
                val overTime = nonBlocking &&
                    System.nanoTime() - passStart > cache!!.frameCompositeBudgetNs
                if (overTime) cache!!.deferredTime++
                val compositeCold = nonBlocking && sub != r && st.isEvicted(tx, ty)
                val mayPrefetch = !overTime && prefetchLeft > 0
                if (compositeCold && mayPrefetch) st.prefetchIn(r)
                if (nonBlocking && (overTime || compositeCold || needsDisk(sub, mayPrefetch))) {
                    if (mayPrefetch) prefetchLeft--
                    // Keep the stale composite pixels; retry once the tiles land.
                    pending.add(Rect(sub))
                    cache.deferredTiles++
                    continue
                }
                if (sub == r && flatStack(tx, ty)) {
                    // Every source here is one colour: the whole tile is the
                    // composite of one pixel, rendered through the same path.
                    val p = flatPixel(r.left, r.top)
                    if ((p ushr 24) == 255) {
                        if (CompositeProfile.enabled) CompositeProfile.flatTiles++
                        st.putSolidTile(tx, ty, p)
                        continue
                    }
                }
                if (sub == r && st.isEvicted(tx, ty)) {
                    // Fully re-rendered below: its old pixels are not needed.
                    // Only now, once the tile is really being drawn: clearing
                    // it before a deferral left a blank (white) square on
                    // screen until its turn came.
                    st.clearTile(tx, ty)
                }
                if (CompositeProfile.enabled) {
                    CompositeProfile.compositeDirtyPx += sub.width().toLong() * sub.height().toLong()
                }
                val t0 = if (CompositeProfile.enabled) System.nanoTime() else 0L
                if (sub == r) {
                    // The whole tile is dirty: render straight into it so the
                    // tile-granular stroke caches can still be seeded.
                    st.editTile(tx, ty) { c -> renderInto(c, r) }
                } else {
                    val buf = stagePool.get(sub.width(), sub.height())
                    val bc = Canvas(buf)
                    bc.drawColor(0, BlendMode.CLEAR)
                    val cp = bc.save()
                    bc.translate(-sub.left.toFloat(), -sub.top.toFloat())
                    renderInto(bc, sub)
                    bc.restoreToCount(cp)
                    st.editTile(tx, ty) { c ->
                        c.drawBitmap(buf, sub.left.toFloat(), sub.top.toFloat(), stagePaint)
                    }
                }
                if (CompositeProfile.enabled) {
                    CompositeProfile.compositeNanos += System.nanoTime() - t0
                }
                // A tile that only ever held background must not stay allocated.
                // A tile created by this pass is default everywhere except [sub],
                // so checking [sub] is enough and avoids a whole-tile scan.
                if (!existed && st.isRectEmpty(sub)) st.clearTile(tx, ty)
            }
        }
    }

    /**
     * True when every source of composite tile ([tx], [ty]) is flat: each
     * visible layer's tile (and mask tile) is missing or a shared solid, with
     * no live preview and no stroke caches in play. Then every pixel of the
     * tile composites to the same value.
     */
    private fun flatStack(tx: Int, ty: Int): Boolean {
        if (!TileStoreConfig.flatComposite) return false
        if (belowTiles != null || aboveTiles != null || preview != null) return false
        for (l in layers) {
            if (!l.visible) continue
            val t = l.tiles as? SparseTileStore ?: return false
            if (!t.isFlatTile(tx, ty)) return false
            if (l.maskEnabled && l.hasMask) {
                val m = l.maskTiles as? SparseTileStore ?: return false
                if (!m.isFlatTile(tx, ty)) return false
            }
        }
        return true
    }

    private var flatProbe: Bitmap? = null

    /** The composite at one pixel, through the ordinary render path. */
    private fun flatPixel(x: Int, y: Int): Int {
        val b = flatProbe ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { flatProbe = it }
        val c = Canvas(b)
        c.drawColor(0, BlendMode.CLEAR)
        c.translate(-x.toFloat(), -y.toFloat())
        renderInto(c, Rect(x, y, x + 1, y + 1))
        return b.getPixel(0, 0)
    }

    /**
     * Draws the visible part of the composite into [c] (document coordinates).
     * Sparse mode reads only the composite tiles inside [rect]; the dense path
     * stays a single blit.
     */
    fun drawComposite(c: Canvas, rect: Rect) {
        val st = compositeTiles
        if (st == null) {
            c.drawBitmap(composite, 0f, 0f, blitPaint)
            return
        }
        // An opaque background is one rect; opaque composite tiles then replace
        // it exactly. A transparent background needs no plate.
        if (Color.alpha(backgroundColor) != 0) {
            bgPaint.blendMode = BlendMode.SRC
            bgPaint.color = backgroundColor
            c.drawRect(rect, bgPaint)
        }
        val ts = st.tileSize
        st.forEachTileIn(rect) { tx, ty, tile ->
            c.drawBitmap(tile, (tx * ts).toFloat(), (ty * ts).toFloat(), blitPaint)
        }
    }

    // ------------------------------------------------------------- mip level

    /**
     * Downscaled copy of the composite for zoomed-out views: at fit-to-screen a
     * 8192^2 document would otherwise blit a thousand full-resolution tiles per
     * frame, while the mip reads a handful.
     *
     * A mip tile covers [mipScale] x [mipScale] document tiles, so it is
     * regenerated from exactly the document region it represents and is
     * unaffected by partial dirty updates elsewhere.
     */
    val mipScale: Int = 4
    val mipWidth: Int = (width + mipScale - 1) / mipScale
    val mipHeight: Int = (height + mipScale - 1) / mipScale

    var mipTiles: SparseTileStore? = null
        private set
    private val mipDirty = Rect()
    private var mipPatch: Bitmap? = null
    private val mipPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private fun mipDirtyUnion(r: Rect) {
        if (mipDirty.isEmpty) mipDirty.set(r) else mipDirty.union(r)
    }

    val hasMipDirty: Boolean get() = !mipDirty.isEmpty

    /** Materialises the mip store (sparse compositing only). */
    fun enableMip() {
        if (mipTiles == null) {
            mipTiles = SparseTileStore(
                mipWidth, mipHeight, TileStoreConfig.tileSize, null, null, backgroundColor,
                solidTiles = true
            )
            mipDirty.set(0, 0, width, height)
        }
    }

    /** True when the whole document is covered by the current mip. */
    fun mipReady(): Boolean = mipTiles != null && mipDirty.isEmpty

    /**
     * Rebuilds the mip tiles whose document region changed since the last call.
     * Each tile is a fresh downscale of the document region it covers, so a
     * partial update produces the same result as a full rebuild.
     */
    fun updateMip(): Boolean {
        val st = mipTiles ?: return false
        if (mipDirty.isEmpty) return false
        val doc = Rect(mipDirty)
        mipDirty.setEmpty()
        val ts = st.tileSize
        val span = ts * mipScale
        val patch = mipPatchBitmap(span)
        val src = Rect()
        for (mty in doc.top / span..(doc.bottom - 1) / span) {
            for (mtx in doc.left / span..(doc.right - 1) / span) {
                val x0 = mtx * span
                val y0 = mty * span
                val x1 = min(width, x0 + span)
                val y1 = min(height, y0 + span)
                if (x1 <= x0 || y1 <= y0) continue
                if (x1 <= doc.left || x0 >= doc.right || y1 <= doc.top || y0 >= doc.bottom) continue
                src.set(x0, y0, x1, y1)
                val mx0 = mtx * ts
                val my0 = mty * ts
                val mw = min(ts, mipWidth - mx0)
                val mh = min(ts, mipHeight - my0)
                // Every composite tile under it one and the same flat colour:
                // the downscale is that colour, no 1024 px read and filter.
                val flat = flatComposite(src)
                if (flat >= 0L && mw == min(ts, mipWidth - mx0) && mx0 + mw <= mipWidth) {
                    st.putSolidTile(mtx, mty, flat.toInt())
                    continue
                }
                readCompositeInto(patch, src)
                st.editTile(mtx, mty) { c ->
                    c.drawBitmap(
                        patch,
                        Rect(0, 0, x1 - x0, y1 - y0),
                        Rect(mx0, my0, mx0 + mw, my0 + mh),
                        mipPaint
                    )
                }
            }
        }
        return true
    }

    /** The single flat colour of composite area [r] (tile aligned), or -1. */
    private fun flatComposite(r: Rect): Long {
        val st = compositeTiles ?: return -1L
        val ts = st.tileSize
        var colour = -1L
        for (ty in r.top / ts..(r.bottom - 1) / ts) {
            for (tx in r.left / ts..(r.right - 1) / ts) {
                val c = st.flatColourAt(tx, ty)
                if (c < 0L || (colour >= 0L && c != colour)) return -1L
                colour = c
            }
        }
        // Only opaque colours: a translucent flat tile's filtered downscale is
        // left to the real filter.
        return if (colour >= 0L && (colour ushr 24) == 0xFFL) colour else -1L
    }

    /** The mip as one bitmap, for tests and thumbnails. */
    fun mipBitmap(): Bitmap? {
        updateMip()
        return mipTiles?.copyAll()
    }

    /** Draws the mip scaled up over the document, for a zoomed-out view. */
    fun drawMip(c: Canvas) {
        val st = mipTiles ?: return
        if (Color.alpha(backgroundColor) != 0) {
            bgPaint.blendMode = BlendMode.SRC
            bgPaint.color = backgroundColor
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }
        val s = mipScale.toFloat()
        val ts = st.tileSize
        c.save()
        c.scale(s, s)
        st.forEachTileIn(Rect(0, 0, mipWidth, mipHeight)) { tx, ty, tile ->
            c.drawBitmap(tile, (tx * ts).toFloat(), (ty * ts).toFloat(), blitPaint)
        }
        c.restore()
    }

    private fun mipPatchBitmap(span: Int): Bitmap {
        mipPatch?.let { if (it.width == span && it.height == span) return it }
        mipPatch?.recycle()
        return AllocTracker.bitmap("engine.mipPatch", span, span).also { mipPatch = it }
    }

    /** Reads a document rect of the composite into the top-left of [patch]. */
    private fun readCompositeInto(patch: Bitmap, r: Rect) {
        val st = compositeTiles
        if (st != null) {
            st.readRectInto(r, patch)
        } else {
            val c = Canvas(patch)
            c.drawColor(backgroundColor, BlendMode.SRC)
            c.drawBitmap(composite, r, Rect(0, 0, r.width(), r.height()), blitPaint)
        }
    }

    // ------------------------------------------- stroke-scoped composite cache

    /**
     * Flattened "everything below the active layer", per tile, for the duration
     * of one stroke.
     *
     * A sparse composite otherwise blends every layer for every dirty tile, so a
     * 30-layer document costs 30 draws per tile per frame. Caching the layers
     * below the active one turns that into one blit plus the layers above it,
     * which for the usual "paint on the top layer" case is two draws.
     *
     * The cache is exact, not approximate: a tile is filled by running the normal
     * compositing code for that same tile and drawn back with SRC, so it holds
     * bit-identical pixels. It exists only while a stroke is live, only for tiles
     * the stroke touches, and is dropped when the stroke ends, so there is no
     * invalidation rule that could be missed.
     */
    private var belowTiles: SparseTileStore? = null
    private var belowFrom = -1
    private var belowBytes = 0L
    private val belowBudget = 24L * 1024L * 1024L
    /**
     * Tiles whose below-cache entry was seeded. A seeded tile can be absent from
     * [belowTiles] when the stack below is pure background (putTile drops
     * default tiles), which then means "background", not "not cached".
     */
    private val belowSeeded = HashSet<Long>()
    private val belowPaint = Paint().apply {
        blendMode = BlendMode.SRC
        isFilterBitmap = false
    }

    val strokeCacheActive: Boolean get() = belowTiles != null

    /**
     * Flattened "everything above the active layer" for the same stroke window.
     *
     * Only built when every layer above the active one is a plain Normal,
     * ungrouped, unmasked, unclipped layer: SRC_OVER is associative, so that
     * stack can be flattened and applied in one draw. A Multiply or Overlay
     * layer has to see the destination, so any such layer disables this cache
     * and the compositor falls back to walking the stack.
     */
    private var aboveTiles: SparseTileStore? = null
    private var aboveFrom = -1
    private var aboveBytes = 0L
    private val aboveBuilt = HashSet<Long>()
    private var aboveBuf: Bitmap? = null
    private val abovePaint = Paint().apply {
        blendMode = BlendMode.SRC_OVER
        isFilterBitmap = false
    }

    /** Bytes held by the stroke cache, for the memory report. */
    fun strokeCacheBytes(): Long = belowBytes + aboveBytes

    /** Starts caching the flattened layers below the active layer. */
    fun beginStrokeCache() {
        endStrokeCache()
        if (!sparseMode) return
        val active = activeLayer ?: return
        if (soloLayerId != null || preview != null) return
        // A group or a clipping layer straddles the split point, so the stack
        // below cannot be flattened independently of what is above it.
        if (active.groupId != 0L || active.clipping) return
        if (activeIndex <= 0) return
        belowTiles = SparseTileStore(
            width, height, TileStoreConfig.tileSize, null, null, backgroundColor
        )
        belowSeeded.clear()
        belowFrom = activeIndex
        belowBytes = 0L
        // The above stack can be flattened only if it is all plain Normal.
        val i = activeIndex
        var simple = i + 1 < layers.size
        if (simple) {
            for (j in i + 1 until layers.size) {
                val l = layers[j]
                if (l.groupId != 0L || l.clipping || l.blend != LayerBlend.Normal ||
                    (l.maskEnabled && l.hasMask) ||
                    (preview?.covers(l.id) == true)
                ) {
                    simple = false
                    break
                }
            }
        }
        if (simple) {
            aboveTiles = SparseTileStore(
                width, height, TileStoreConfig.tileSize, null, null, 0
            )
            aboveFrom = i + 1
            aboveBytes = 0L
            aboveBuilt.clear()
        }
    }

    fun endStrokeCache() {
        belowSeeded.clear()
        belowTiles = null
        belowFrom = -1
        belowBytes = 0L
        aboveTiles = null
        aboveFrom = -1
        aboveBytes = 0L
        aboveBuilt.clear()
        aboveBuf?.recycle()
        aboveBuf = null
    }

    /**
     * Draws the document into [c] inside [rect]: background, then layers and
     * groups bottom to top, applying masks and clipping as it goes.
     */
    private fun renderInto(c: Canvas, rect: Rect) {
        val checkpoint = c.save()
        c.clipRect(rect)
        val cache = belowTiles
        if (cache != null && belowFrom in 1 until layers.size) {
            val ts = cache.tileSize
            val tx = rect.left / ts
            val ty = rect.top / ts
            val tileX = tx * ts
            val tileY = ty * ts
            val tileRight = min(width, tileX + ts)
            val tileBottom = min(height, tileY + ts)
            // True for a whole tile and for any dirty sub-rect of one tile.
            val withinTile = rect.left >= tileX && rect.top >= tileY &&
                rect.right <= tileRight && rect.bottom <= tileBottom
            if (withinTile) {
                val above = aboveTiles
                val stop = if (above != null && aboveFrom in 1..layers.size) aboveFrom
                else layers.size
                val cached = cache.getTile(tx, ty)
                val seededBackground = cached == null &&
                    belowSeeded.contains((tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL))
                if (seededBackground) {
                    // Seeded, and everything below the active layer is background.
                    c.drawColor(backgroundColor, BlendMode.SRC)
                    renderLayers(c, rect, belowFrom, stop)
                    if (above != null && aboveFrom in 1..layers.size) {
                        drawAboveInto(c, rect, tx, ty, above)
                    }
                    c.restoreToCount(checkpoint)
                    return
                }
                if (cached != null) {
                    // The cached tile already includes the background and SRC
                    // replaces it exactly, so this is the same composite. It is
                    // blitted as a whole bitmap at its own integer origin, and the
                    // clip keeps the result to [rect].
                    c.drawBitmap(cached, tileX.toFloat(), tileY.toFloat(), belowPaint)
                    renderLayers(c, rect, belowFrom, stop)
                    if (above != null && aboveFrom in 1..layers.size) {
                        drawAboveInto(c, rect, tx, ty, above)
                    }
                    c.restoreToCount(checkpoint)
                    return
                }
                // Seeding a tile-granular cache needs the whole tile, so only a
                // whole-tile pass may build one.
                val wholeTile = rect.left == tileX && rect.top == tileY &&
                    rect.right == tileRight && rect.bottom == tileBottom
                if (wholeTile) {
                    c.drawColor(backgroundColor, BlendMode.SRC)
                    renderLayers(c, rect, 0, belowFrom)
                    val own = compositeTiles?.getTile(tx, ty)
                    if (own != null && belowBytes < belowBudget) {
                        val copy = Bitmap.createBitmap(
                            own.width, own.height, Bitmap.Config.ARGB_8888
                        )
                        Canvas(copy).drawBitmap(own, 0f, 0f, belowPaint)
                        cache.putTile(tx, ty, copy)
                        belowBytes += copy.byteCount.toLong()
                        copy.recycle()
                        belowSeeded.add((tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL))
                    }
                    renderLayers(c, rect, belowFrom, stop)
                    if (above != null && aboveFrom in 1..layers.size) {
                        drawAboveInto(c, rect, tx, ty, above)
                    }
                    c.restoreToCount(checkpoint)
                    return
                }
            }
        }
        c.drawColor(backgroundColor, BlendMode.SRC)
        val solo = soloLayerId
        if (solo != null) {
            val layer = layerById(solo)
            if (layer != null && layer.visible) drawLayerInto(c, layer, rect, null)
            c.restoreToCount(checkpoint)
            return
        }
        renderLayers(c, rect, 0, layers.size)
        c.restoreToCount(checkpoint)
    }

    /**
     * Draws the cached above-stack tile with SRC_OVER, building it on first use.
     * The stack is flattened onto transparency, which is exactly the increment
     * it contributes to whatever is below it (OVER is associative).
     */
    private fun drawAboveInto(c: Canvas, rect: Rect, tx: Int, ty: Int, above: SparseTileStore) {
        val k = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        val ts = above.tileSize
        val tileX = tx * ts
        val tileY = ty * ts
        val t = above.getTile(tx, ty)
        if (t != null) {
            // Whole-bitmap blit at the tile's own integer origin; [rect] is
            // already clipped, so a dirty sub-rect only lands where it should.
            c.drawBitmap(t, tileX.toFloat(), tileY.toFloat(), abovePaint)
            return
        }
        // Already built and it contributed nothing (fully transparent).
        if (aboveBuilt.contains(k)) return
        if (aboveBytes >= belowBudget) return
        val tileRight = min(width, tileX + ts)
        val tileBottom = min(height, tileY + ts)
        // Only a whole tile can be stored in the tile-granular cache.
        if (rect.left != tileX || rect.top != tileY ||
            rect.right != tileRight || rect.bottom != tileBottom
        ) {
            // Partial tile: composite the layers above directly, without caching.
            // [c] is already in document space and clipped to [rect].
            renderLayers(c, rect, aboveFrom, layers.size)
            return
        }
        val w = rect.width()
        val h = rect.height()
        val buf = aboveBuf?.takeIf { !it.isRecycled && it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { aboveBuf = it }
        val bc = Canvas(buf)
        bc.drawColor(0, BlendMode.CLEAR)
        bc.save()
        bc.translate(-rect.left.toFloat(), -rect.top.toFloat())
        renderLayers(bc, rect, aboveFrom, layers.size)
        bc.restore()
        above.putTile(tx, ty, buf)
        aboveBytes += w.toLong() * h.toLong() * 4L
        aboveBuilt.add(k)
        c.drawBitmap(buf, rect.left.toFloat(), rect.top.toFloat(), abovePaint)
    }

    /** Composites layers [[from], [to]) onto [c] inside [rect]. */
    private fun renderLayers(c: Canvas, rect: Rect, from: Int, to: Int) {
        var start = from
        if (sparseMode && to > from) {
            // Occlusion culling: the topmost fully opaque Normal layer hides
            // everything below it in this tile, so compositing can start there.
            val ts = TileStoreConfig.tileSize
            val tx = rect.left / ts
            val ty = rect.top / ts
            // The opacity flag is per tile, so it applies to any sub-rect of it.
            if (rect.left >= tx * ts && rect.top >= ty * ts &&
                rect.right <= min(width, (tx + 1) * ts) &&
                rect.bottom <= min(height, (ty + 1) * ts)
            ) {
                var i = to - 1
                while (i > from) {
                    if (CompositeProfile.enabled) CompositeProfile.cullScans++
                    val l = layers[i]
                    if (l.visible && l.opacity >= 0.999f && l.blend == LayerBlend.Normal &&
                        !(l.maskEnabled && l.hasMask) && !l.clipping && l.groupId == 0L &&
                        (preview?.covers(l.id) != true) &&
                        l.tiles.isTileOpaque(tx, ty)
                    ) {
                        start = i
                        break
                    }
                    i--
                }
            }
        }
        var base: PixelSource? = null
        var i = start
        while (i < to) {
            val layer = layers[i]
            val gid = layer.groupId
            if (gid != 0L) {
                var end = i
                while (end < to && layers[end].groupId == gid) end++
                val group = groupById(gid)
                if (group != null && group.visible && group.opacity > 0.002f) {
                    if (CompositeProfile.enabled) CompositeProfile.groupDraws++
                    val buf = ensureGroupBuffer(rect)
                    val gc = Canvas(buf)
                    val gcp = gc.save()
                    bufferOrigin(gc, rect)
                    gc.clipRect(rect)
                    gc.drawColor(0, BlendMode.CLEAR)
                    var inner: PixelSource? = null
                    for (k in i until end) {
                        val child = layers[k]
                        if (!child.visible || child.opacity <= 0.002f) continue
                        drawLayerInto(gc, child, rect, inner)
                        inner = child.tiles
                    }
                    gc.restoreToCount(gcp)
                    groupPaint.reset()
                    groupPaint.isFilterBitmap = true
                    groupPaint.alpha = (group.opacity * 255f).toInt().coerceIn(0, 255)
                    groupPaint.blendMode = group.blend.mode
                    drawBuffer(c, buf, rect, groupPaint)
                    base = BitmapSource(buf, if (sparseMode) rect else null)
                }
                i = end
            } else {
                if (layer.visible && layer.opacity > 0.002f) {
                    drawLayerInto(c, layer, rect, base)
                    base = layer.tiles
                }
                i++
            }
        }
    }

    /** Translates a scratch canvas so document coordinates land at its origin. */
    private fun bufferOrigin(c: Canvas, rect: Rect) {
        if (sparseMode) c.translate(-rect.left.toFloat(), -rect.top.toFloat())
    }

    /**
     * Draws a scratch buffer onto the composite. In sparse mode the buffer is
     * exactly the size of [rect] and holds document coordinates translated by
     * -[rect]'s origin, so blitting its origin at [rect]'s origin is an integer
     * translation: no resampling and no second copy of the surface.
     */
    private fun drawBuffer(c: Canvas, buf: Bitmap, rect: Rect, paint: Paint) {
        if (sparseMode) {
            c.drawBitmap(buf, rect.left.toFloat(), rect.top.toFloat(), paint)
        } else {
            c.drawBitmap(buf, 0f, 0f, paint)
        }
    }

    /** Splits the sparse composite between dirty area and whole-tile area. */
    fun poolReport(): String =
        "pool stage=${stagePool.hits}/${stagePool.hits + stagePool.misses}" +
            "(${stagePool.size}b,${stagePool.bytes / 1024}KB)" +
            " group=${groupPool.hits}/${groupPool.hits + groupPool.misses}" +
            "(${groupPool.size}b,${groupPool.bytes / 1024}KB)" +
            " clip=${layerPool.hits}/${layerPool.hits + layerPool.misses}" +
            "(${layerPool.size}b,${layerPool.bytes / 1024}KB) " +
            TilePool.stats()

    /**
     * Draws a layer's pixels into [c]. Sparse stores are asked for tiles that
     * intersect [rect] only, so a tile-sized composite does not walk the whole
     * layer; the dense store ignores the rect and keeps its original path.
     */
    private fun drawStoreInto(c: Canvas, store: TileStore, rect: Rect, paint: Paint?) {
        // Every caller has already clipped the canvas to [rect], so the sparse
        // store can skip its save/clipRect/restore pair.
        if (!CompositeProfile.enabled) {
            if (sparseMode) store.drawIntoRectPreClipped(c, rect, paint)
            else store.drawInto(c, paint)
            return
        }
        val t0 = System.nanoTime()
        if (sparseMode) store.drawIntoRectPreClipped(c, rect, paint)
        else store.drawInto(c, paint)
        CompositeProfile.blitNanos += System.nanoTime() - t0
    }

    private fun drawLayerInto(c: Canvas, layer: Layer, rect: Rect, clipBase: PixelSource?) {
        if (CompositeProfile.enabled) CompositeProfile.layerDraws++
        val hasMask = layer.maskEnabled && layer.hasMask
        val clipped = layer.clipping && clipBase != null
        val pv = preview
        val previewed = pv != null && pv.covers(layer.id)
        if (!hasMask && !clipped) {
            layerPaint.reset()
            layerPaint.isFilterBitmap = true
            layerPaint.isAntiAlias = false
            layerPaint.alpha = (layer.opacity * 255f).toInt().coerceIn(0, 255)
            layerPaint.blendMode = layer.blend.mode
            if (previewed) {
                val filter = pv!!.filter
                val proxy = pv.proxy
                if (filter != null) layerPaint.colorFilter = filter
                if (proxy != null) {
                    c.drawBitmap(
                        proxy,
                        null,
                        RectF(0f, 0f, width.toFloat(), height.toFloat()),
                        layerPaint
                    )
                    return
                }
            }
            // Rect-limited on purpose: the canvas is already clipped to [rect],
            // but a whole-store draw would still issue one blit per tile of the
            // layer, which makes the cost scale with the layer's tile count
            // instead of with the dirty area.
            drawStoreInto(c, layer.tiles, rect, layerPaint)
            return
        }
        val buf = ensureLayerBuffer(rect)
        val bc = Canvas(buf)
        val cp = bc.save()
        bufferOrigin(bc, rect)
        bc.clipRect(rect)
        bc.drawColor(0, BlendMode.CLEAR)
        if (clipped) {
            // Confine the layer's colour to the alpha of the layer below: lay
            // down the base, then paint the layer with SRC_ATOP so only pixels
            // the base covers survive.
            clipBase!!.drawInto(bc, null)
            maskPaint.reset()
            maskPaint.isFilterBitmap = true
            maskPaint.blendMode = BlendMode.SRC_ATOP
            drawStoreInto(bc, layer.tiles, rect, maskPaint)
        } else {
            drawStoreInto(bc, layer.tiles, rect, null)
        }
        if (hasMask) {
            val maskStore = layer.maskTiles
            if (maskStore != null) {
                maskPaint.reset()
                maskPaint.isFilterBitmap = true
                maskPaint.blendMode = BlendMode.DST_IN
                drawStoreInto(bc, maskStore, rect, maskPaint)
            }
        }
        bc.restoreToCount(cp)
        layerPaint.reset()
        layerPaint.isFilterBitmap = true
        layerPaint.alpha = (layer.opacity * 255f).toInt().coerceIn(0, 255)
        layerPaint.blendMode = layer.blend.mode
        if (previewed && pv!!.filter != null) layerPaint.colorFilter = pv.filter
        drawBuffer(c, buf, rect, layerPaint)
    }

    private fun ensureGroupBuffer(rect: Rect): Bitmap {
        if (!sparseMode) {
            groupBuffer?.let { return it }
            return AllocTracker.bitmap("engine.groupBuffer", width, height)
                .also { groupBuffer = it }
        }
        return groupPool.get(rect.width(), rect.height())
    }

    private fun ensureLayerBuffer(rect: Rect): Bitmap {
        if (!sparseMode) {
            layerBuffer?.let { return it }
            return AllocTracker.bitmap("engine.layerBuffer", width, height)
                .also { layerBuffer = it }
        }
        return layerPool.get(rect.width(), rect.height())
    }

    fun setSoloLayer(id: Long?) {
        soloLayerId = if (id == null) null else layers.firstOrNull { it.id == id }?.id
        markDirtyAll()
    }

    /**
     * Pre-allocates the scratch surfaces a first edit would otherwise allocate
     * mid-gesture: the tile-sized group and clipping buffers and the tile
     * scratch used by one-shot operations.
     */
    fun reserveScratch() {
        if (!sparseMode) return
        val tile = Rect(0, 0, min(width, TileStoreConfig.tileSize), min(height, TileStoreConfig.tileSize))
        if (tile.isEmpty) return
        ensureGroupBuffer(tile)
        ensureLayerBuffer(tile)
        TileOps.scratch(tile.width(), tile.height())
    }

    fun applyPreview(p: LayerPreview?) {
        preview = p
        markDirtyAll()
    }

    /** Flattened copy including background, for export. */
    fun flatten(): Bitmap {
        val st = compositeTiles
        if (st != null) {
            compositeAll()
            // The single canvas-sized assembly a sparse document may build: the
            // surface handed to the PNG encoder (tracked under the encode site).
            val out = AllocTracker.encodeCanvas(width, height)
            st.readRectInto(Rect(0, 0, width, height), out)
            return out
        }
        val out = AllocTracker.bitmap("engine.flatten", width, height)
        renderInto(Canvas(out), Rect(0, 0, width, height))
        return out
    }

    /**
     * Snapshot of a layer before a stroke starts so the stroke can be rolled
     * back (long-press eyedropper) or turned into an undo patch.
     */
    fun captureStrokeBase(layer: Layer, capture: Boolean = true) {
        if (!capture) return
        val c = Canvas(strokeBase)
        val p = Paint()
        p.blendMode = BlendMode.SRC
        layer.paintStore.drawInto(c, p)
    }

    fun makePatch(
        layer: Layer,
        rect: Rect,
        label: String,
        kind: EditKind,
        detail: String = "",
        swatch: Int = 0
    ): PixelAction? {
        val r = Rect(rect)
        if (!r.intersect(0, 0, width, height)) return null
        if (r.isEmpty) return null
        return PixelAction(
            layerId = layer.id,
            rect = r,
            before = strokeBase.extractRegion(r),
            after = layer.paintStore.readRect(r),
            label = label,
            kind = kind,
            detail = detail,
            swatch = swatch,
            mask = layer.editMask && layer.hasMask
        )
    }

    fun blitRegion(layer: Layer, rect: Rect, patch: Bitmap, mask: Boolean = false) {
        val target = if (mask) layer.maskTiles ?: return else layer.tiles
        target.writeRect(rect, patch)
        layer.touch()
        markDirty(rect)
    }

    /** Puts the pre-stroke snapshot back into [rect] of the paint target. */
    fun restoreFromBase(layer: Layer, rect: Rect) {
        val r = Rect(rect)
        if (!r.intersect(0, 0, width, height)) return
        if (r.isEmpty) return
        layer.paintStore.writeRect(r, strokeBase, r)
        layer.touch()
        markDirty(r)
    }

    // ------------------------------------------------------ sparse history

    /**
     * Turns the per-tile capture taken during a stroke into a tile-granular
     * history action. Tiles that ended up unchanged are dropped, and the
     * capture is released (its changed tiles move into the action).
     */
    fun makeTilePatch(
        layer: Layer,
        capture: TileCapture,
        rect: Rect,
        label: String,
        kind: EditKind,
        detail: String = "",
        swatch: Int = 0
    ): HistoryAction? {
        val store = capture.store
        val r = Rect(rect)
        if (!r.intersect(0, 0, width, height) || r.isEmpty) {
            capture.release()
            return null
        }
        val tileSize = store.tileSize
        val def = store.defaultPixel
        val changes = ArrayList<TileChange>()
        val box = Rect()
        val ty1 = (r.bottom - 1) / tileSize
        val tx1 = (r.right - 1) / tileSize
        for (ty in r.top / tileSize..ty1) {
            for (tx in r.left / tileSize..tx1) {
                val tx0 = tx * tileSize
                val ty0 = ty * tileSize
                val tw = min(tileSize, store.width - tx0)
                val th = min(tileSize, store.height - ty0)
                box.set(
                    max(r.left, tx0), max(r.top, ty0),
                    min(r.right, tx0 + tw), min(r.bottom, ty0 + th)
                )
                if (box.isEmpty) continue
                val k = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
                // Tiles the stroke never wrote are still part of the action's
                // rectangle: the dense path restores them too (so replaying a
                // later step brings back anything a deleted step painted there).
                val beforeFull = if (capture.has(tx, ty)) {
                    capture.beforeTile(k)
                } else {
                    store.getTile(tx, ty)
                }
                val afterFull = store.getTile(tx, ty)
                val beforeCrop = cropTile(beforeFull, tx0, ty0, box, def)
                val afterCrop = cropTile(afterFull, tx0, ty0, box, def)
                if (beforeCrop == null && afterCrop == null) continue
                changes.add(TileChange(tx, ty, Rect(box), beforeCrop, afterCrop))
            }
        }
        // The crops are copies, so the copy-on-touch snapshot is no longer needed.
        capture.release()
        // Stroke / one-shot op end: tiles that ended up one flat colour become
        // shared solids. Bulk scan per touched tile, never per dab.
        store.compactSolids(r)
        if (changes.isEmpty()) return null
        return TilePixelAction(
            layerId = layer.id,
            bounds = Rect(r),
            changes = changes,
            label = label,
            kind = kind,
            detail = detail,
            swatch = swatch,
            mask = layer.editMask && layer.hasMask
        )
    }

    private fun cropTile(
        tile: Bitmap?,
        tx0: Int,
        ty0: Int,
        clip: Rect,
        def: Int
    ): Bitmap? {
        if (tile == null) return null
        val w = clip.width()
        val h = clip.height()
        // A whole shared solid is kept by reference: immutable, never
        // recycled or spilled by the history, and costs no memory.
        if (w == tile.width && h == tile.height && clip.left == tx0 && clip.top == ty0 && SolidTiles.isShared(tile)) {
            return tile
        }
        val slice = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(slice).drawBitmap(
            tile,
            Rect(clip.left - tx0, clip.top - ty0, clip.right - tx0, clip.bottom - ty0),
            Rect(0, 0, w, h),
            cropPaint
        )
        if (isDefaultPixels(slice, def)) {
            slice.recycle()
            return null
        }
        return slice
    }

    private fun isDefaultPixels(bmp: Bitmap, def: Int): Boolean {
        val w = bmp.width
        val row = if (cropRow.size < w) IntArray(w).also { cropRow = it } else cropRow
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (row[x] != def) return false
            }
        }
        return true
    }

    private var cropRow = IntArray(256)
    private val cropPaint = Paint().apply {
        blendMode = BlendMode.SRC
        isFilterBitmap = false
    }

    /** Applies the before (undo) or after (redo) tiles of a sparse edit. */
    fun applyTileChanges(
        layerId: Long,
        changes: List<TileChange>,
        bounds: Rect,
        before: Boolean,
        mask: Boolean
    ) {
        val layer = layerById(layerId) ?: return
        val store = if (mask) layer.maskTiles ?: return else layer.tiles
        for (c in changes) {
            val tile = if (before) c.before else c.after
            store.putTileRegion(c.tx, c.ty, c.clip, tile)
        }
        store.compactSolids(bounds)
        layer.touch()
        markDirty(bounds)
    }

    /**
     * Converts a tile action to the legacy rectangle patch written to disk.
     * Returns null when the rectangle would exceed [budgetBytes], so the caller
     * can end the persisted span instead of writing an oversized patch.
     */
    fun tileActionToRect(action: TilePixelAction, budgetBytes: Long): PixelAction? {
        val layer = layerById(action.layerId) ?: return null
        val store = if (action.mask) layer.maskTiles ?: return null else layer.tiles
        val rect = Rect(action.bounds)
        if (!rect.intersect(0, 0, width, height)) return null
        if (rect.isEmpty) return null
        val area = rect.width().toLong() * rect.height().toLong()
        if (area * 8L > budgetBytes) return null
        val after = store.readRect(rect)
        val before = copyPixels(after)
        val def = store.defaultPixel
        for (c in action.changes) {
            putCrop(before, rect, c.clip, c.before, def)
            putCrop(after, rect, c.clip, c.after, def)
        }
        return PixelAction(
            layerId = layer.id,
            rect = rect,
            before = before,
            after = after,
            label = action.label,
            kind = action.kind,
            detail = action.detail,
            swatch = action.swatch,
            mask = action.mask
        )
    }

    private fun putCrop(
        dest: Bitmap,
        destOrigin: Rect,
        clip: Rect,
        source: Bitmap?,
        default: Int
    ) {
        val c = Canvas(dest)
        val dx = clip.left - destOrigin.left
        val dy = clip.top - destOrigin.top
        if (source == null) {
            val p = Paint()
            p.blendMode = BlendMode.SRC
            p.color = default
            c.drawRect(
                RectF(
                    dx.toFloat(), dy.toFloat(),
                    (dx + clip.width()).toFloat(), (dy + clip.height()).toFloat()
                ),
                p
            )
        } else {
            val p = Paint()
            p.blendMode = BlendMode.SRC
            p.isFilterBitmap = false
            c.drawBitmap(
                source,
                null,
                Rect(dx, dy, dx + clip.width(), dy + clip.height()),
                p
            )
        }
    }

    // ------------------------------------------------------------ structural

    fun snapshotState(): DocumentState = DocumentState(
        layers = layers.map { LayerState(it, it.props()) },
        groups = groups.map { it.snapshot() },
        activeIndex = activeIndex,
        activeGroupId = activeGroupId
    )

    fun restoreState(state: DocumentState) {
        layers.clear()
        groups.clear()
        for (s in state.layers) {
            s.layer.applyProps(s.props)
            layers.add(s.layer)
        }
        for (g in state.groups) {
            g.applyTo()
            groups.add(g.group)
        }
        activeIndex = state.activeIndex.coerceIn(0, max(0, layers.size - 1))
        activeGroupId = if (groups.any { it.id == state.activeGroupId }) state.activeGroupId else 0L
        markDirtyAll()
    }

    // ------------------------------------------------------------- selection

    fun setSelection(region: Region?) {
        selection = if (region == null || region.isEmpty) null else Region(region)
        selectionClip = selection?.let { SelectionUtil.regionFillPath(it) }
        selectionOutline = selection?.let { SelectionUtil.boundaryPath(it) }
    }

    fun applySelection(region: Region?, op: SelectionOp) {
        if (region == null || region.isEmpty) {
            if (op == SelectionOp.Replace) setSelection(null)
            return
        }
        val current = selection
        val next = when (op) {
            SelectionOp.Replace -> Region(region)
            SelectionOp.Add -> {
                val r = if (current == null) Region() else Region(current)
                r.op(region, Region.Op.UNION)
                r
            }
            SelectionOp.Subtract -> {
                if (current == null) Region()
                else Region(current).apply { op(region, Region.Op.DIFFERENCE) }
            }
        }
        setSelection(next)
    }

    fun selectAll() {
        setSelection(Region(0, 0, width, height))
    }

    fun clearSelection() {
        setSelection(null)
    }

    fun invertSelection() {
        val all = Region(0, 0, width, height)
        val current = selection ?: return
        all.op(current, Region.Op.DIFFERENCE)
        setSelection(all)
    }

    // ------------------------------------------------------------- memory

    /**
     * Resident bytes by category, for the harness memory report: layer tiles,
     * composite, pre-stroke snapshot, group/clipping scratch and mip tiles.
     */
    fun memoryReport(): String {
        var layerTotal = 0L
        for (layer in layers) layerTotal += layer.residentBytes()
        val compositeBytes = compositeTiles?.residentBytes()
            ?: (denseComposite?.byteCount?.toLong() ?: 0L)
        val snapshot = strokeBaseBitmap?.byteCount?.toLong() ?: 0L
        var scratch = (groupBuffer?.byteCount?.toLong() ?: 0L) +
            (layerBuffer?.byteCount?.toLong() ?: 0L)
        scratch += groupPool.bytes + layerPool.bytes + stagePool.bytes
        val mip = mipTiles?.residentBytes() ?: 0L
        fun mb(v: Long): Long = v / (1024L * 1024L)
        return "layers=${mb(layerTotal)}MB composite=${mb(compositeBytes)}MB " +
            "strokeBase=${mb(snapshot)}MB scratch=${mb(scratch)}MB " +
            "mip=${mb(mip)}MB"
    }

    /**
     * Debug: re-derives every per-tile opacity cache from pixels and reports
     * stale entries. Any mismatch means a write path bypassed invalidation, so
     * the compositor could have culled a layer it should have drawn.
     */
    fun verifyOpaqueFlags(all: Boolean = true): Int {
        var bad = 0
        for (layer in layers) {
            bad += layer.tiles.verifyOpaqueFlags(all)
            layer.maskTiles?.let { bad += it.verifyOpaqueFlags(all) }
        }
        compositeTiles?.let { bad += it.verifyOpaqueFlags(all) }
        mipTiles?.let { bad += it.verifyOpaqueFlags(all) }
        belowTiles?.let { bad += it.verifyOpaqueFlags(all) }
        aboveTiles?.let { bad += it.verifyOpaqueFlags(all) }
        return bad
    }

    /**
     * Full-canvas bitmaps a composite of the current visible layers needs:
     * the composite and stroke snapshot, one per visible layer and per visible
     * mask, plus any scratch surfaces already allocated.
     */
    fun visibleBitmapNeed(): Int {
        if (sparseMode) return 0
        var n = 2
        for (layer in layers) {
            if (!layer.visible) continue
            n++
            if (layer.maskEnabled && layer.hasMask) n++
        }
        if (groupBuffer != null) n++
        if (layerBuffer != null) n++
        return n
    }

    /** Full-canvas bitmaps a composite of [state] would need. */
    fun stateBitmapNeed(state: DocumentState): Int {
        var n = 2
        for (entry in state.layers) {
            val layer = entry.layer
            if (!layer.visible) continue
            n++
            if (layer.maskEnabled && layer.hasMask) n++
        }
        return n
    }

    /** False when decoding [extra] more full-canvas surfaces would exceed the budget. */
    fun canAfford(extra: Int): Boolean {
        if (!sparseMode) return MemoryBudget.fitsBitmaps(width, height, visibleBitmapNeed() + extra)
        // Stage 3c: with the tile cache, resident memory is held to the cache
        // budget by eviction, so what can run out is storage for the spill.
        if (TileCache.active != null) {
            return MemoryBudget.freeDisk() >= MemoryBudget.layerBytes(width, height) / 8 * extra +
                MemoryBudget.DISK_HEADROOM
        }
        // Sparse documents only pay for the tiles they hold: painted layers are
        // counted from resident bytes, and a layer that is not decoded yet is
        // budgeted at an eighth of a canvas.
        var resident = 0L
        for (layer in layers) resident += layer.residentBytes()
        resident += compositeTiles?.residentBytes() ?: 0L
        resident += mipTiles?.residentBytes() ?: 0L
        resident += MemoryBudget.layerBytes(width, height) / 8 * extra
        return resident <= MemoryBudget.canvasBudget
    }

    /** False when restoring [state] would exceed the bitmap budget. */
    fun canAffordState(state: DocumentState): Boolean =
        sparseMode || MemoryBudget.fitsBitmaps(width, height, stateBitmapNeed(state))

    /** Extra bitmaps [mergeDown] needs at its peak (sources + merged copy). */
    fun mergeCost(id: Long): Int {
        val i = indexOf(id)
        if (i <= 0) return 0
        var n = 1
        if (!layers[i].isResident) n++
        if (!layers[i - 1].isResident) n++
        return n
    }

    /**
     * Releases hidden layers to disk until the resident layer pixels fit
     * [budget]. Hidden layers are never composited or painted, so their pixels
     * are pure overhead; they reload transparently if they become visible.
     *
     * Returns the number of layers that were swapped out.
     */
    fun releaseHiddenLayers(budget: Long, write: (Layer) -> java.io.File?): Int {
        var total = 0L
        for (layer in layers) total += layer.tiles.residentBytes()
        if (total <= budget) return 0
        val activeId = activeLayer?.id
        var released = 0
        for (layer in layers) {
            if (total <= budget) break
            val bytes = layer.tiles.residentBytes()
            if (bytes <= 0L) continue
            if (layer.visible || layer.id == activeId) continue
            val file = write(layer) ?: continue
            total -= bytes
            layer.tiles.releaseToDisk(file)
            released++
        }
        return released
    }

    // ------------------------------------------------------------ thumbnails

    fun thumb(layer: Layer, maxPx: Int): Bitmap {
        val s = min(maxPx.toFloat() / width, maxPx.toFloat() / height)
        val w = max(1, (width * s).toInt())
        val h = max(1, (height * s).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF32323A.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.alpha = (layer.opacity * 255f).toInt().coerceIn(0, 255)
        val save = c.save()
        c.scale(w.toFloat() / width, h.toFloat() / height)
        layer.tiles.drawInto(c, p)
        c.restoreToCount(save)
        return bmp
    }

    /** Builds a layer thumbnail from an already-decoded (possibly sampled) bitmap. */
    fun thumbOf(source: Bitmap, maxPx: Int, opacity: Float): Bitmap {
        val s = min(maxPx.toFloat() / width, maxPx.toFloat() / height)
        val w = max(1, (width * s).toInt())
        val h = max(1, (height * s).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF32323A.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        c.drawBitmap(
            source,
            null,
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            p
        )
        return bmp
    }

    /** Preview of a layer mask: white where the layer is visible. */
    fun maskThumb(layer: Layer, maxPx: Int): Bitmap? {
        val store = layer.maskTiles ?: return null
        val s = min(maxPx.toFloat() / width, maxPx.toFloat() / height)
        val w = max(1, (width * s).toInt())
        val h = max(1, (height * s).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF32323A.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (store.isSparse) p.blendMode = BlendMode.SRC
        val save = c.save()
        c.scale(w.toFloat() / width, h.toFloat() / height)
        store.drawInto(c, p)
        c.restoreToCount(save)
        return bmp
    }

    /** Mask thumbnail from an already-decoded (possibly sampled) mask bitmap. */
    fun maskThumbOf(mask: Bitmap, maxPx: Int): Bitmap {
        val s = min(maxPx.toFloat() / width, maxPx.toFloat() / height)
        val w = max(1, (width * s).toInt())
        val h = max(1, (height * s).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF32323A.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(mask, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
        return bmp
    }
}
