package com.yume.app.data

import kotlinx.coroutines.flow.first

import kotlinx.coroutines.flow.update

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yume.app.engine.AllocTracker
import com.yume.app.engine.DocumentSnapshot
import com.yume.app.engine.EditKind
import com.yume.app.engine.Layer
import com.yume.app.engine.LayerBlend
import com.yume.app.engine.LayerSnapshotData
import com.yume.app.engine.MemoryBudget
import com.yume.app.engine.PersistedHistory
import com.yume.app.engine.PersistedHistoryEntry
import com.yume.app.engine.TileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ProjectMeta(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val updatedAt: Long,
    val layerCount: Int,
    val thumbFile: File?,
    val strokeCount: Long = 0L,
    /** Time spent on the project (ms). */
    val timeMs: Long = 0L
)

data class LoadedGroup(
    val id: Long,
    val name: String,
    val opacity: Float,
    val blend: LayerBlend,
    val visible: Boolean,
    val locked: Boolean,
    val expanded: Boolean
)

data class LoadedProject(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val background: Int,
    val activeIndex: Int,
    val layers: List<LayerSnapshotData>,
    val groups: List<LoadedGroup> = emptyList(),
    val symmetryX: Boolean = false,
    val symmetryY: Boolean = false,
    val symmetryXPos: Float = -1f,
    val symmetryYPos: Float = -1f,
    val extras: String = "",
    /** Set when the document was restored from its crash-recovery journal. */
    val recovery: JournalRecovery? = null
)

/**
 * A valid crash-recovery journal found while opening (Stage 3c): the layers in
 * [LoadedProject.layers] are the journal's base PNGs; [apply] replays the tiles
 * on top once the engine has built its stores.
 */
class JournalRecovery(
    private val log: File,
    private val length: Long,
    /** Journal slot of each layer store / mask store, by layer index (-1 = none). */
    val slots: IntArray,
    val maskSlots: IntArray,
    /** Layers whose mask exists only in the journal (no mask PNG yet). */
    val emptyMasks: Set<Int>
) {
    val maxSlot: Int get() = maxOf(slots.maxOrNull() ?: -1, maskSlots.maxOrNull() ?: -1)

    /**
     * Replays the journal into [engine]'s layer stores. Returns the number of
     * records applied; throws IOException when the journal is damaged inside
     * its committed length (the document is then left at its base PNGs plus
     * the records before the damage).
     */
    fun apply(engine: com.yume.app.engine.DrawingEngine): Int {
        val bySlot = HashMap<Int, com.yume.app.engine.TileStore>()
        engine.layers.forEachIndexed { i, l ->
            if (i in emptyMasks && l.maskTiles == null) {
                l.maskTiles = com.yume.app.engine.TileStoreFactory.maskEmpty(engine.width, engine.height)
            }
            slots.getOrNull(i)?.takeIf { it >= 0 }?.let { s ->
                bySlot[s] = l.tiles
                (l.tiles as? com.yume.app.engine.SparseTileStore)?.journalSlot = s
            }
            maskSlots.getOrNull(i)?.takeIf { it >= 0 }?.let { s ->
                val m = l.maskTiles ?: return@let
                bySlot[s] = m
                (m as? com.yume.app.engine.SparseTileStore)?.journalSlot = s
            }
        }
        val touched = HashSet<com.yume.app.engine.TileStore>()
        try {
            return com.yume.app.engine.TileJournal.replay(log, length) { slot, tx, ty, kind, tile ->
                val st = bySlot[slot]
                if (st == null) {
                    if (tile != null) com.yume.app.engine.TilePool.release(tile)
                    return@replay
                }
                if (touched.add(st)) st.ensureResident()
                when (kind) {
                    com.yume.app.engine.TileJournal.RESET -> st.clear()
                    com.yume.app.engine.TileJournal.DEFAULT -> st.clearTile(tx, ty)
                    else -> if (tile != null) {
                        st.putTile(tx, ty, tile)
                        com.yume.app.engine.TilePool.release(tile)
                    }
                }
            }
        } finally {
            // The replayed changes are already in the journal: start clean.
            for (st in touched) (st as? com.yume.app.engine.SparseTileStore)?.journalTake()
            engine.layers.forEach { l -> l.touch() }
            engine.markDirtyAll()
        }
    }
}

/**
 * Flat-file document store: one directory per project containing a JSON
 * manifest plus one PNG per layer. Simple, crash tolerant and easy to inspect.
 */
class ProjectStore(private val context: Context) {

    /**
     * Which PNG holds each layer's pixels as of the last full save (or open):
     * the journal's base. Key "<project>/<layerId>/L|M". Absent = no base yet
     * (a layer created since), so the journal dumps that store in full.
     */
    private val bases = HashMap<String, String>()

    /** True when the last saveProject wrote every layer and mask it had to. */
    @Volatile var lastSaveComplete: Boolean = true

    private fun baseKey(id: String, layerId: Long, mask: Boolean) =
        "$id/$layerId/${if (mask) "M" else "L"}"

    fun baseOf(id: String, layerId: Long, mask: Boolean): String? = bases[baseKey(id, layerId, mask)]

    /** Records the base PNGs of freshly opened layers (by index, after load). */
    fun adoptBases(id: String, layerIds: List<Long>, loaded: List<LayerSnapshotData>) {
        bases.keys.removeAll { it.startsWith("$id/") }
        for ((i, lid) in layerIds.withIndex()) {
            val d = loaded.getOrNull(i) ?: continue
            d.file?.let { bases[baseKey(id, lid, false)] = it.name }
            d.maskFile?.let { bases[baseKey(id, lid, true)] = it.name }
        }
    }

    private fun journalDir(id: String) = File(projectDir(id), "journal")

    private fun readSaveSeq(dir: File): Int = runCatching {
        JSONObject(File(dir, "manifest.json").readText()).optInt("saveSeq", 0)
    }.getOrDefault(0)

    /**
     * Names the pixel state on disk: the last full save ("s<seq>"), plus the
     * journal on top of it when there is one ("s<seq>/j<length>"). The undo
     * history is stamped with it when written and restored only when opening
     * finds the same state, so it always matches the pixels it undoes.
     */
    fun historyStamp(id: String): String {
        val dir = projectDir(id)
        val seq = readSaveSeq(dir)
        val st = runCatching { JSONObject(File(journalDir(id), "state.json").readText()) }.getOrNull()
        val len = st?.takeIf { it.optInt("saveSeq", -1) == seq }?.optLong("logLength", -1L) ?: -1L
        return if (len >= 0L) "s$seq/j$len" else "s$seq"
    }

    /** Deletes the journal (after a full save made it obsolete). */
    fun resetJournal(id: String) {
        journalDir(id).deleteRecursively()
    }

    /**
     * Appends one autosave's tile changes and commits the matching state
     * (layer list, bases, slots, committed log length). Throws on IO failure.
     */
    fun writeJournal(id: String, batch: com.yume.app.engine.JournalBatch) {
        val dir = projectDir(id)
        val jdir = journalDir(id).apply { mkdirs() }
        val log = File(jdir, "tiles.log")
        val stateFile = File(jdir, "state.json")
        val seq = readSaveSeq(dir)
        val prevSeq = runCatching { JSONObject(stateFile.readText()).optInt("saveSeq", -1) }
            .getOrDefault(-1)
        if (prevSeq != seq) {
            // A journal from before the last full save: start over.
            log.delete()
            stateFile.delete()
        }
        val length = com.yume.app.engine.TileJournal.append(log, batch.changes)
        val snap = batch.snapshot
        val layersJson = JSONArray()
        snap.layers.forEachIndexed { i, layer ->
            val o = layerJson(layer, i)
            o.put("file", baseOf(id, layer.id, false) ?: NO_FILE)
            o.put("slot", snap.slots.getOrElse(i) { -1 })
            o.put("maskSlot", snap.maskSlots.getOrElse(i) { -1 })
            val hasMask = layer.mask != null || layer.maskFile != null || layer.maskSparse != null
            o.put("hasMask", hasMask)
            if (hasMask) o.put("mask", baseOf(id, layer.id, true) ?: "") else o.remove("mask")
            layersJson.put(o)
        }
        val state = JSONObject().apply {
            put("saveSeq", seq)
            put("logLength", length)
            put("name", snap.title)
            put("width", snap.width)
            put("height", snap.height)
            put("background", snap.background)
            put("active", snap.activeIndex)
            put("symmetryX", snap.symmetryX)
            put("symmetryY", snap.symmetryY)
            put("symmetryXPos", snap.symmetryXPos.toDouble())
            put("symmetryYPos", snap.symmetryYPos.toDouble())
            if (snap.extras.isNotEmpty()) put("extras", JSONObject(snap.extras))
            put("layers", layersJson)
            put("groups", JSONArray().apply {
                snap.groups.forEach { g ->
                    put(JSONObject().apply {
                        put("id", g.id); put("name", g.name); put("opacity", g.opacity.toDouble())
                        put("blend", g.blend.name); put("visible", g.visible); put("locked", g.locked)
                        put("expanded", g.expanded)
                    })
                }
            })
        }
        com.yume.app.engine.TileJournal.writeAtomically(stateFile, state.toString())
    }

    /**
     * Canvas crop / resize (Stage 4.1), as a NEW document: the source document
     * and its undo history are left untouched. Every layer and mask is written
     * band by band from its store (read on the engine's owner thread), so even
     * an 8192^2 document never builds a canvas-sized bitmap.
     *
     * [scale] false = crop / extend: the new canvas shows the source shifted by
     * ([offsetX], [offsetY]); uncovered area is transparent (white for masks).
     * [scale] true = resize: the whole source is resampled (bilinear) to the
     * new size. Returns the new project id, or null on failure.
     */
    fun createCanvasCopy(
        snapshot: DocumentSnapshot,
        name: String,
        newW: Int,
        newH: Int,
        scale: Boolean,
        offsetX: Int,
        offsetY: Int
    ): String? {
        val id = UUID.randomUUID().toString()
        val dir = projectDir(id).apply { mkdirs() }
        val srcW = snapshot.width
        val srcH = snapshot.height
        fun store(layer: LayerSnapshotData, mask: Boolean): TileStore? =
            if (mask) layer.maskStore else layer.store
        fun writeStore(file: File, st: TileStore, mask: Boolean): Boolean = runCatching {
            // Uncovered area: transparent for a layer, white (visible) for a
            // mask. Not st.defaultPixel: a dense mask store reports 0.
            val def = if (mask) 0xFFFFFFFF.toInt() else 0
            // Scaled bands read a source band (plus a 1 px halo) per few rows.
            val sy = srcH.toFloat() / newH
            val sx = srcW.toFloat() / newW
            val bandRows = if (scale) maxOf(1, (256 / maxOf(1f, sy)).toInt()) else 256
            val band = Bitmap.createBitmap(newW, minOf(bandRows, newH), Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(band)
            val filter = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                blendMode = android.graphics.BlendMode.SRC
            }
            val copy = android.graphics.Paint().apply { blendMode = android.graphics.BlendMode.SRC }
            try {
                java.io.BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
                    com.yume.app.engine.BandedPng.encode(out, newW, newH, bandRows) { y0, rows, px ->
                        band.eraseColor(def)
                        if (!scale) {
                            val src = Rect(offsetX, offsetY + y0, offsetX + newW, offsetY + y0 + rows)
                            val clip = Rect(src)
                            if (clip.intersect(0, 0, srcW, srcH)) {
                                var patch: Bitmap? = null
                                if (!snapshot.onOwner { patch = st.readRect(clip) }) {
                                    throw java.io.IOException("timed out")
                                }
                                patch?.let {
                                    canvas.drawBitmap(
                                        it, (clip.left - src.left).toFloat(),
                                        (clip.top - src.top).toFloat(), copy
                                    )
                                    it.recycle()
                                }
                            }
                        } else {
                            val top = maxOf(0, (y0 * sy).toInt() - 1)
                            val bottom = minOf(srcH, ((y0 + rows) * sy).toInt() + 2)
                            val src = Rect(0, top, srcW, bottom)
                            var patch: Bitmap? = null
                            if (!snapshot.onOwner { patch = st.readRect(src) }) {
                                throw java.io.IOException("timed out")
                            }
                            patch?.let {
                                val m = android.graphics.Matrix()
                                m.setTranslate(0f, top.toFloat())
                                m.postScale(1f / sx, 1f / sy)
                                m.postTranslate(0f, -y0.toFloat())
                                val save = canvas.save()
                                canvas.clipRect(0, 0, newW, rows)
                                canvas.drawBitmap(it, m, filter)
                                canvas.restoreToCount(save)
                                it.recycle()
                            }
                        }
                        band.getPixels(px, 0, newW, 0, 0, newW, rows)
                    }
                }
            } finally {
                band.recycle()
            }
            true
        }.getOrDefault(false)

        val layersJson = JSONArray()
        for ((i, layer) in snapshot.layers.withIndex()) {
            val st = store(layer, false) ?: return null.also { dir.deleteRecursively() }
            if (!writeStore(File(dir, "layer_$i.png"), st, false)) {
                dir.deleteRecursively()
                return null
            }
            val m = store(layer, true)
            if (m != null && !writeStore(File(dir, "mask_$i.png"), m, true)) {
                dir.deleteRecursively()
                return null
            }
            layersJson.put(layerJson(layer, i))
        }
        val scaleT = 512f / maxOf(newW, newH)
        val thumb = Bitmap.createBitmap(
            (newW * scaleT).toInt().coerceAtLeast(1), (newH * scaleT).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        android.graphics.Canvas(thumb).drawColor(snapshot.background)
        writeThumb(dir, thumb)
        thumb.recycle()
        val manifest = JSONObject().apply {
            put("name", name)
            put("width", newW)
            put("height", newH)
            put("background", snapshot.background)
            put("active", snapshot.activeIndex)
            put("updatedAt", System.currentTimeMillis())
            put("layers", layersJson)
            put("groups", JSONArray().apply {
                snapshot.groups.forEach { g ->
                    put(JSONObject().apply {
                        put("id", g.id); put("name", g.name); put("opacity", g.opacity.toDouble())
                        put("blend", g.blend.name); put("visible", g.visible); put("locked", g.locked)
                        put("expanded", g.expanded)
                    })
                }
            })
        }
        File(dir, "manifest.json").writeText(manifest.toString())
        return id
    }

    /** Root for per-document tile caches (scratch, deleted on close). */
    fun cacheRoot(): File = context.cacheDir

    /** A content:// URI other apps can read, for a file under cache/exports. */
    fun shareUri(file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun openOutput(uri: Uri): java.io.OutputStream? = context.contentResolver.openOutputStream(uri)
    fun openInput(uri: Uri): java.io.InputStream? = context.contentResolver.openInputStream(uri)

    /** Zips a project folder (it must not be open in the editor). */
    fun backupProject(id: String, out: java.io.OutputStream): Boolean = Exporter.backup(projectDir(id), out)

    /** Restores a backup as a new project; returns its id, or null for a bad file. */
    fun restoreProject(input: java.io.InputStream): String? {
        val id = UUID.randomUUID().toString()
        val dir = projectDir(id)
        if (!Exporter.restore(input, dir)) return null
        runCatching {
            val m = File(dir, "manifest.json")
            val json = JSONObject(m.readText())
            json.put("name", json.optString("name", "Untitled") + " (restored)")
            json.put("updatedAt", System.currentTimeMillis())
            m.writeText(json.toString())
        }
        return id
    }

    fun activityManager(): android.app.ActivityManager? =
        context.getSystemService(android.app.ActivityManager::class.java)

    private val root: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    /**
     * Remembers which layer files are already up to date, so autosaves only
     * re-encode layers that actually changed. Key: project/layer id.
     */
    private val savedLayers = HashMap<String, Pair<Int, Int>>()

    /**
     * Layers just loaded from disk are already saved as they are: the next
     * save skips re-encoding them until they change (an 8K layer takes
     * seconds to encode). Not used after a journal recovery, whose pixels
     * still have to reach the PNGs.
     */
    fun markLoaded(id: String, layers: List<Pair<Long, Int>>) {
        layers.forEachIndexed { index, (layerId, revision) -> savedLayers["$id/$layerId"] = index to revision }
    }

    fun projectDir(id: String): File = File(root, id)

    fun listProjects(): List<ProjectMeta> {
        val dirs = root.listFiles() ?: return emptyList()
        val out = ArrayList<ProjectMeta>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val manifest = File(dir, "manifest.json")
            if (!manifest.exists()) {
                // Leftover of a deleted project (undo history written after the
                // delete): no document and no layer files, not being created.
                val files = dir.listFiles().orEmpty()
                val orphan = files.none { it.name.startsWith("layer_") || it.name.startsWith(TMP_PREFIX) } &&
                    System.currentTimeMillis() - dir.lastModified() > 60_000L
                if (orphan) runCatching { dir.deleteRecursively() }
                continue
            }
            val meta = runCatching { parseMeta(dir, manifest) }.getOrNull() ?: continue
            out.add(meta)
        }
        out.sortByDescending { it.updatedAt }
        return out
    }

    private fun parseMeta(dir: File, manifest: File): ProjectMeta {
        val json = JSONObject(manifest.readText())
        val thumb = File(dir, "thumb.png")
        return ProjectMeta(
            id = dir.name,
            name = json.optString("name", "Untitled"),
            width = json.optInt("width", 2048),
            height = json.optInt("height", 2048),
            updatedAt = json.optLong("updatedAt", manifest.lastModified()),
            layerCount = json.optJSONArray("layers")?.length() ?: 1,
            strokeCount = json.optJSONObject("extras")?.optLong("strokes", 0L) ?: 0L,
            timeMs = json.optJSONObject("extras")?.optLong("timeMs", 0L) ?: 0L,
            thumbFile = if (thumb.exists()) thumb else null
        )
    }

    fun loadThumb(meta: ProjectMeta, maxPx: Int): Bitmap? {
        val file = meta.thumbFile ?: return null
        return decodeScaled(file.absolutePath, maxPx)
    }

    fun decodeScaled(path: String, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    fun createProject(
        name: String,
        width: Int,
        height: Int,
        background: Int
    ): LoadedProject {
        val id = UUID.randomUUID().toString()
        val dir = projectDir(id).apply { mkdirs() }
        // Stage 3c: no canvas-sized bitmap at creation. The blank layer PNG is
        // band-encoded from zeros; a sparse document opens it lazily, a dense
        // one still gets its full surface (the dense engine needs it anyway).
        val sparse = com.yume.app.engine.TileStoreConfig.useSparseLayers
        val file0: File
        if (sparse) {
            // An empty tile file: a few bytes per tile row, at any size.
            file0 = File(dir, "layer_0.${com.yume.app.engine.TileFile.EXT}")
            val ts = com.yume.app.engine.TileStoreConfig.tileSize
            val cols = (width + ts - 1) / ts
            com.yume.app.engine.TileFile.write(file0, width, height, ts, 0) { _ ->
                Array(cols) { com.yume.app.engine.TileFile.TileData(com.yume.app.engine.TileFile.KIND_DEFAULT, null) }
            }
        } else {
            file0 = File(dir, "layer_0.png")
            java.io.BufferedOutputStream(FileOutputStream(file0), 1 shl 16).use { out ->
                com.yume.app.engine.BandedPng.encode(out, width, height, ENCODE_BAND) { _, rows, px ->
                    java.util.Arrays.fill(px, 0, rows * width, 0)
                }
            }
        }
        val blank = if (sparse) null else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val layer = LayerSnapshotData(
            "Layer 1", blank, 1f, LayerBlend.Normal, true, false,
            file = if (sparse) file0 else null
        )
        val scale = 512f / maxOf(width, height)
        val thumb = Bitmap.createBitmap(
            (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val c = android.graphics.Canvas(thumb)
        c.drawColor(background)
        writeThumb(dir, thumb)
        thumb.recycle()

        val manifest = JSONObject().apply {
            put("name", name)
            put("width", width)
            put("height", height)
            put("background", background)
            put("active", 0)
            put("updatedAt", System.currentTimeMillis())
            put("layers", JSONArray().put(layerJson(layer, 0, file0.name)))
        }
        File(dir, "manifest.json").writeText(manifest.toString())

        return LoadedProject(id, name, width, height, background, 0, listOf(layer))
    }

    fun loadProject(id: String): LoadedProject? {
        // Layer ids are only unique within a session, so drop cached file state
        // for this project: the first save after loading rewrites every layer.
        savedLayers.keys.removeAll { it.startsWith("$id/") }
        val dir = projectDir(id)
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return null
        return runCatching {
            val manifestJson = JSONObject(manifestFile.readText())
            // Crash recovery: a journal written against this manifest's saveSeq
            // restores the document to its last autosave.
            val seq = manifestJson.optInt("saveSeq", 0)
            val stateFile = File(journalDir(id), "state.json")
            val state = runCatching { JSONObject(stateFile.readText()) }.getOrNull()
                ?.takeIf { it.optInt("saveSeq", -1) == seq && it.optLong("logLength", -1L) >= 0L }
            val json = state ?: manifestJson
            val width = json.optInt("width", 2048)
            val height = json.optInt("height", 2048)
            val layersJson = json.optJSONArray("layers") ?: JSONArray()
            val layers = ArrayList<LayerSnapshotData>()
            for (i in 0 until layersJson.length()) {
                val o = layersJson.getJSONObject(i)
                val file = File(dir, o.optString("file", "layer_$i.png"))
                val visible = o.optBoolean("visible", true)
                val exists = file.exists()
                // Lazy open: hidden layers stay on disk until something needs
                // their pixels. A missing file still gets a blank surface so the
                // layer has a valid size when it is painted on.
                // Sparse documents open every layer lazily: the store decodes its
                // file on first use and splits it into (evictable) tiles, so
                // opening never holds all layers' full bitmaps at once.
                val lazyAll = com.yume.app.engine.TileStoreConfig.useSparseLayers
                val bitmap: Bitmap? = when {
                    visible && exists && lazyAll -> null
                    visible && exists -> decodeLayerFile(file)
                    !visible && exists -> null
                    // A sparse blank layer needs no canvas-sized surface.
                    lazyAll -> null
                    else -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                val maskName = o.optString("mask", "")
                val maskFile = if (maskName.isEmpty()) {
                    null
                } else {
                    File(dir, maskName).takeIf { it.exists() }
                }
                val mask = if (visible && maskFile != null && !lazyAll) decodeLayerFile(maskFile) else null
                layers.add(
                    LayerSnapshotData(
                        name = o.optString("name", "Layer ${i + 1}"),
                        bitmap = bitmap,
                        opacity = o.optDouble("opacity", 1.0).toFloat(),
                        blend = LayerBlend.fromName(o.optString("blend", "Normal")),
                        visible = visible,
                        alphaLock = o.optBoolean("alphaLock", false),
                        locked = o.optBoolean("locked", false),
                        clipping = o.optBoolean("clipping", false),
                        mask = mask,
                        maskFile = maskFile,
                        maskEnabled = o.optBoolean("maskEnabled", true),
                        groupId = o.optLong("group", 0L),
                        file = if (exists) file else null,
                        id = o.optLong("id", 0L)
                    )
                )
            }
            if (layers.isEmpty()) {
                layers.add(
                    LayerSnapshotData(
                        "Layer 1",
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
                        1f, LayerBlend.Normal, true, false
                    )
                )
            }
            val groupsJson = json.optJSONArray("groups") ?: JSONArray()
            val groups = ArrayList<LoadedGroup>()
            for (i in 0 until groupsJson.length()) {
                val o = groupsJson.getJSONObject(i)
                groups.add(
                    LoadedGroup(
                        id = o.optLong("id", 0L),
                        name = o.optString("name", "Group"),
                        opacity = o.optDouble("opacity", 1.0).toFloat(),
                        blend = LayerBlend.fromName(o.optString("blend", "Normal")),
                        visible = o.optBoolean("visible", true),
                        locked = o.optBoolean("locked", false),
                        expanded = o.optBoolean("expanded", true)
                    )
                )
            }
            LoadedProject(
                id = id,
                name = json.optString("name", "Untitled"),
                width = width,
                height = height,
                background = json.optInt("background", android.graphics.Color.WHITE),
                activeIndex = json.optInt("active", layers.size - 1)
                    .coerceIn(0, layers.size - 1),
                layers = layers,
                groups = groups,
                symmetryX = json.optBoolean("symmetryX", false),
                symmetryY = json.optBoolean("symmetryY", false),
                symmetryXPos = json.optDouble("symmetryXPos", -1.0).toFloat(),
                symmetryYPos = json.optDouble("symmetryYPos", -1.0).toFloat(),
                extras = json.optJSONObject("extras")?.toString() ?: "",
                recovery = state?.let { st ->
                    val n = layersJson.length()
                    JournalRecovery(
                        log = File(journalDir(id), "tiles.log"),
                        length = st.optLong("logLength", 0L),
                        slots = IntArray(n) { layersJson.getJSONObject(it).optInt("slot", -1) },
                        maskSlots = IntArray(n) { layersJson.getJSONObject(it).optInt("maskSlot", -1) },
                        emptyMasks = (0 until n).filter {
                            val o = layersJson.getJSONObject(it)
                            o.optBoolean("hasMask", false) && o.optString("mask", "").isEmpty()
                        }.toSet()
                    )
                }
            )
        }.getOrNull()
    }

    private val _saving = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    /** Projects with a full save in progress (the gallery shows "Saving…"). */
    val saving: kotlinx.coroutines.flow.StateFlow<Set<String>> = _saving

    private val _saved = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** A project finished a full save (the gallery refreshes its card). */
    val saved: kotlinx.coroutines.flow.SharedFlow<String> = _saved

    fun saveProject(id: String, snapshot: DocumentSnapshot) {
        _saving.update { it + id }
        try {
            saveProjectInner(id, snapshot)
        } finally {
            _saving.update { it - id }
            _saved.tryEmit(id)
        }
    }

    /** Suspends until no full save of [id] is running (before reopening it). */
    suspend fun awaitSaved(id: String) {
        _saving.first { id !in it }
    }

    private fun saveProjectInner(id: String, snapshot: DocumentSnapshot) {
        val dir = projectDir(id).apply { mkdirs() }
        // Stage 3c: every full save bumps saveSeq, which invalidates any journal
        // written against the previous PNG set.
        val saveSeq = readSaveSeq(dir) + 1
        var complete = true
        val failedLayers = HashSet<Long>()
        // Drop leftovers from a save that was interrupted before its renames.
        dir.listFiles()?.forEach { if (it.name.startsWith(TMP_PREFIX)) it.delete() }

        val seen = HashSet<String>()
        val seenMask = HashSet<String>()
        // temp -> final, applied only after every temp has been written.
        val pending = ArrayList<Pair<File, File>>()

        val layerNames = HashMap<Int, String>()
        val maskNames = HashMap<Int, String>()
        for ((index, layer) in snapshot.layers.withIndex()) {
            // Sparse layers are written as tile files; a layer still on disk
            // keeps its file's format (copied as is); dense ones stay PNG.
            val srcFile = layer.file ?: layer.sparse?.backingFile()
            val tiled = layer.bitmap == null && (
                (layer.sparse != null && layer.sparse.isResident) ||
                    (srcFile != null && com.yume.app.engine.TileFile.isTileFile(srcFile))
                )
            val name = "layer_$index.${if (tiled) com.yume.app.engine.TileFile.EXT else "png"}"
            layerNames[index] = name
            val target = File(dir, name)
            val key = "$id/${layer.id}"
            val unchanged = savedLayers[key] == (index to layer.revision) && target.exists()
            if (!unchanged) {
                // Write to a temp file first: a swapped-out layer's source may be
                // another layer's destination, so no final name may be touched
                // until every source has been read.
                val bmp = layer.bitmap
                if (bmp != null) {
                    val tmp = tempFile(dir, name)
                    if (encodePng(tmp, bmp)) pending.add(tmp to target) else { complete = false; failedLayers.add(layer.id) }
                } else if (layer.sparse != null && layer.sparse.isResident) {
                    // Stage 2: assemble a full bitmap for the PNG encoder. One
                    // layer at a time, so peak extra memory is one canvas.
                    val tmp = tempFile(dir, name)
                    if (encodeStorePng(tmp, layer.sparse, snapshot.onOwner)) pending.add(tmp to target) else { complete = false; failedLayers.add(layer.id) }
                } else {
                    val src = layer.file ?: layer.sparse?.backingFile()
                    if (src != null && src.exists() && src.absolutePath != target.absolutePath) {
                        val tmp = tempFile(dir, name)
                        if (copyBytes(src, tmp)) pending.add(tmp to target) else { complete = false; failedLayers.add(layer.id) }
                    }
                    // When src == target the file already holds these pixels.
                }
                savedLayers[key] = index to layer.revision
            }
            seen.add(name)

            val maskTiled = layer.mask == null && (
                (layer.maskSparse != null && layer.maskSparse.isResident) ||
                    (layer.maskFile != null && com.yume.app.engine.TileFile.isTileFile(layer.maskFile))
                )
            val maskName = "mask_$index.${if (maskTiled) com.yume.app.engine.TileFile.EXT else "png"}"
            maskNames[index] = maskName
            val maskTarget = File(dir, maskName)
            val mask = layer.mask
            if (mask != null) {
                if (!unchanged || !maskTarget.exists()) {
                    val tmp = tempFile(dir, maskName)
                    if (encodePng(tmp, mask)) pending.add(tmp to maskTarget) else { complete = false; failedLayers.add(layer.id) }
                }
                seenMask.add(maskName)
            } else if (layer.maskSparse != null && layer.maskSparse.isResident) {
                if (!unchanged || !maskTarget.exists()) {
                    val tmp = tempFile(dir, maskName)
                    if (encodeStorePng(tmp, layer.maskSparse, snapshot.onOwner)) {
                        pending.add(tmp to maskTarget)
                    } else {
                        complete = false
                        failedLayers.add(layer.id)
                    }
                }
                seenMask.add(maskName)
            } else if (layer.maskFile != null) {
                if (!unchanged || !maskTarget.exists()) {
                    val src = layer.maskFile
                    if (src.exists() && src.absolutePath != maskTarget.absolutePath) {
                        val tmp = tempFile(dir, maskName)
                        if (copyBytes(src, tmp)) pending.add(tmp to maskTarget) else { complete = false; failedLayers.add(layer.id) }
                    }
                }
                seenMask.add(maskName)
            }
        }

        // Every source has been copied into a temp, so stale outputs are safe
        // to delete now (a stale file may have been the source of another layer).
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith("layer_") && file.name !in seen) file.delete()
            if (file.name.startsWith("mask_") && file.name !in seenMask) file.delete()
        }

        // Commit: rename every temp over its final name at the end.
        for ((tmp, target) in pending) moveReplacing(tmp, target)

        // Layer pixels that were swapped out of memory live in cache/, keyed by
        // layer id. Drop entries for layers that no longer exist.
        val cacheDir = File(dir, "cache")
        if (cacheDir.isDirectory) {
            val keepCache = snapshot.layers.mapTo(HashSet()) { "l${it.id}.png" }
            cacheDir.listFiles()?.forEach { file ->
                if (file.name !in keepCache) file.delete()
            }
        }

        val thumb = makeThumb(snapshot)
        if (thumb != null) {
            writeThumb(dir, thumb)
            thumb.recycle()
        }

        val layersJson = JSONArray()
        snapshot.layers.forEachIndexed { index, layer ->
            layersJson.put(layerJson(layer, index, layerNames[index] ?: "layer_$index.png", maskNames[index] ?: "mask_$index.png"))
        }
        val groupsJson = JSONArray()
        snapshot.groups.forEach { group ->
            groupsJson.put(
                JSONObject().apply {
                    put("id", group.id)
                    put("name", group.name)
                    put("opacity", group.opacity.toDouble())
                    put("blend", group.blend.name)
                    put("visible", group.visible)
                    put("locked", group.locked)
                    put("expanded", group.expanded)
                }
            )
        }
        val manifest = JSONObject().apply {
            put("name", snapshot.title)
            put("width", snapshot.width)
            put("height", snapshot.height)
            put("background", snapshot.background)
            put("active", snapshot.activeIndex)
            put("symmetryX", snapshot.symmetryX)
            put("symmetryY", snapshot.symmetryY)
            put("symmetryXPos", snapshot.symmetryXPos.toDouble())
            put("symmetryYPos", snapshot.symmetryYPos.toDouble())
            if (snapshot.extras.isNotEmpty()) put("extras", JSONObject(snapshot.extras))
            put("updatedAt", System.currentTimeMillis())
            put("layers", layersJson)
            put("groups", groupsJson)
            put("saveSeq", saveSeq)
        }
        File(dir, "manifest.json").writeText(manifest.toString())
        // The journal described the previous PNG set: it is obsolete now.
        resetJournal(id)
        bases.keys.removeAll { it.startsWith("$id/") }
        snapshot.layers.forEachIndexed { index, layer ->
            if (layer.id in failedLayers) return@forEachIndexed
            bases[baseKey(id, layer.id, false)] = layerNames[index] ?: "layer_$index.png"
            val hasMask = layer.mask != null || layer.maskFile != null || layer.maskSparse != null
            if (hasMask) bases[baseKey(id, layer.id, true)] = maskNames[index] ?: "mask_$index.png"
        }
        lastSaveComplete = complete
    }

    private fun layerJson(
        layer: LayerSnapshotData,
        index: Int,
        file: String = "layer_$index.png",
        maskFile: String = "mask_$index.png"
    ): JSONObject = JSONObject().apply {
        put("file", file)
        // The id the undo history refers to this layer by.
        if (layer.id != 0L) put("id", layer.id)
        put("name", layer.name)
        put("opacity", layer.opacity.toDouble())
        put("blend", layer.blend.name)
        put("visible", layer.visible)
        put("alphaLock", layer.alphaLock)
        put("locked", layer.locked)
        put("clipping", layer.clipping)
        put("maskEnabled", layer.maskEnabled)
        put("group", layer.groupId)
        if (layer.mask != null || layer.maskFile != null || layer.maskSparse != null) {
            put("mask", maskFile)
        }
    }

    /** Writes a hidden layer to the swap cache and returns the backing file. */
    fun writeLayerCache(id: String, layerId: Long, bitmap: Bitmap): File? = runCatching {
        val dir = File(projectDir(id), "cache").apply { mkdirs() }
        val file = File(dir, "l$layerId.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        file
    }.getOrNull()

    private fun writeLayer(dir: File, index: Int, bitmap: Bitmap) {
        val file = File(dir, "layer_$index.png")
        runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private fun tempFile(dir: File, name: String): File = File(dir, "$TMP_PREFIX$name")

    /** Encodes [bitmap] to [file]; returns false on failure. */
    /**
     * Layer / mask PNG through the banded encoder (Stage 3c), so a dense layer
     * and a sparse layer with the same pixels produce the same bytes.
     */
    private fun encodePng(file: File, bitmap: Bitmap): Boolean = runCatching {
        val w = bitmap.width
        java.io.BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            com.yume.app.engine.BandedPng.encode(out, w, bitmap.height, ENCODE_BAND) { y0, rows, px ->
                bitmap.getPixels(px, 0, w, 0, y0, w, rows)
            }
        }
        true
    }.getOrDefault(false)

    /** Assembles a sparse store into one bitmap only for the length of a write. */
    /** Last save problem worth telling the user about (cleared when read). */
    @Volatile var saveWarning: String? = null

    private fun encodeStorePng(
        file: File,
        store: TileStore,
        onOwner: (() -> Unit) -> Boolean = { it(); true }
    ): Boolean {
        // A cached tile that could not be read back (CRC / IO error) must not be
        // saved as blank: keep the previous file and say so.
        val sparse = store as? com.yume.app.engine.SparseTileStore
        if (sparse != null && sparse.lostTiles > 0) {
            saveWarning = "A layer could not be saved: ${sparse.lostTiles} cached tile(s) " +
                "were unreadable. The previous saved version was kept."
            return false
        }
        // Stage 3c: rows go from tiles straight into the encoder, one tile row
        // (band) at a time, read on the engine's owner thread. No canvas-sized
        // bitmap exists at any point. A layer that changes while it is being
        // encoded is encoded again (a band could otherwise mix two states).
        for (attempt in 0 until 3) {
            val before = sparse?.modCount ?: 0L
            val error = encodeStoreBands(file, store, onOwner)
            if (error != null) {
                saveWarning = error
                return false
            }
            if (sparse != null && sparse.lostTiles > 0) {
                saveWarning = "A layer could not be saved: cached tiles were unreadable. " +
                    "The previous saved version was kept."
                return false
            }
            if (sparse == null || sparse.modCount == before) return true
        }
        // Still changing after three tries: keep the previous file; the next
        // save retries.
        return false
    }

    /** Encodes [store] band by band into [file]; returns an error message or null. */
    private fun encodeStoreBands(
        file: File,
        store: TileStore,
        onOwner: (() -> Unit) -> Boolean
    ): String? {
        val w = store.width
        val h = store.height
        val sparseStore = store as? com.yume.app.engine.SparseTileStore
        if (sparseStore != null && com.yume.app.engine.TileFile.EXT == file.name.substringAfterLast('.')) {
            return try {
                val ok = com.yume.app.engine.TileFile.write(file, w, h, sparseStore.tileSize, sparseStore.defaultPixel) { ty ->
                    var row: Array<com.yume.app.engine.TileFile.TileData>? = null
                    if (onOwner { row = sparseStore.tileRowForSave(ty) }) row else null
                }
                if (ok) null else "Saving failed (timed out waiting for the canvas); kept the previous version"
            } catch (e: java.io.IOException) {
                "Saving failed (${e.message}); kept the previous version"
            }
        }
        val bandRows = if (store.isSparse) store.tileSize else ENCODE_BAND
        val band = Bitmap.createBitmap(w, minOf(bandRows, h), Bitmap.Config.ARGB_8888)
        val rect = Rect()
        return try {
            java.io.BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
                com.yume.app.engine.BandedPng.encode(out, w, h, bandRows) { y0, rows, px ->
                    rect.set(0, y0, w, y0 + rows)
                    if (!onOwner { store.readRectInto(rect, band) }) {
                        throw java.io.IOException("timed out waiting for the canvas")
                    }
                    band.getPixels(px, 0, w, 0, 0, w, rows)
                }
            }
            null
        } catch (e: java.io.IOException) {
            "Saving failed (${e.message}); kept the previous version"
        } finally {
            band.recycle()
        }
    }

    /**
     * Exports through [writer], which streams PNG bytes (banded for sparse
     * documents) into a new Pictures/Yume entry. The entry is removed again if
     * the writer fails.
     */
    fun exportPngStream(name: String, writer: (java.io.OutputStream) -> Boolean): Uri? {
        val safeName = name.ifBlank { "Yume" }.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
            .ifBlank { "Yume" }
        val fileName = "$safeName-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Yume")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { raw ->
                val out = java.io.BufferedOutputStream(raw, 1 shl 16)
                val r = writer(out)
                out.flush()
                r
            } ?: false
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri
    }

    /** Copies bytes from [source] to [target]; returns false on failure. */
    private fun copyBytes(source: File, target: File): Boolean = runCatching {
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        true
    }.getOrDefault(false)

    /** Atomic replace where the filesystem supports it, with safe fallbacks. */
    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        }.recoverCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            runCatching {
                if (target.exists()) target.delete()
                source.renameTo(target)
            }
        }
    }

    /** Decodes a layer or mask file at full resolution, mutable. */
    private fun decodeLayerFile(file: File): Bitmap? = runCatching {
        if (com.yume.app.engine.TileFile.isTileFile(file)) return com.yume.app.engine.TileFile.decodeFull(file)
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
        )
    }.getOrNull()

    private fun writeThumb(dir: File, thumb: Bitmap) {
        // Written aside and renamed into place: the gallery and the project
        // curtain read thumbnails while a save may be running, and must never
        // decode a half-written file.
        runCatching {
            val tmp = File(dir, "thumb.png.tmp")
            FileOutputStream(tmp).use { out ->
                thumb.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
            if (!tmp.renameTo(File(dir, "thumb.png"))) tmp.delete()
        }
    }

    private fun makeThumb(snapshot: DocumentSnapshot): Bitmap? {
        // Preferred: the engine's own composite, scaled. It is what the canvas
        // shows, groups, clipping, masks and all.
        val compose = snapshot.composeThumb
        if (compose != null) {
            var composed: Bitmap? = null
            snapshot.onOwner { composed = compose(512) }
            composed?.let { return it }
        }
        return makeThumbFromLayers(snapshot)
    }

    /**
     * Layer-by-layer fallback (solo view or a live preview on screen, or no
     * engine): layer opacity and blend, plus group visibility, opacity and
     * blend. Clipping and masks are not applied here.
     */
    private fun makeThumbFromLayers(snapshot: DocumentSnapshot): Bitmap? {
        val scale = 512f / maxOf(snapshot.width, snapshot.height)
        val w = (snapshot.width * scale).toInt().coerceAtLeast(1)
        val h = (snapshot.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(snapshot.background)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val groups = snapshot.groups.associateBy { it.id }
        var openGroup = 0L
        var groupSave = -1
        for (layer in snapshot.layers) {
            if (layer.groupId != openGroup) {
                // Leaving a group: blend its isolated layer down.
                if (groupSave >= 0) canvas.restoreToCount(groupSave)
                groupSave = -1
                openGroup = layer.groupId
                val g = groups[openGroup]
                if (g != null && g.visible) {
                    val gp = android.graphics.Paint().apply {
                        alpha = (g.opacity * 255f).toInt().coerceIn(0, 255)
                        blendMode = g.blend.mode
                    }
                    groupSave = canvas.saveLayer(null, gp)
                }
            }
            val group = groups[layer.groupId]
            if (group != null && (!group.visible || group.opacity <= 0.002f)) continue
            if (!layer.visible || layer.opacity <= 0.002f) continue
            // A visible layer can still be swapped out (for example right after
            // it is shown and before the next composite). Sample its file at
            // thumbnail resolution instead of leaving the preview blank.
            val resident = layer.bitmap
            if (resident == null && layer.sparse != null && layer.sparse.isResident) {
                paint.alpha = (layer.opacity * 255f).toInt().coerceIn(0, 255)
                paint.blendMode = layer.blend.mode
                val save = canvas.save()
                canvas.scale(
                    w.toFloat() / snapshot.width, h.toFloat() / snapshot.height
                )
                snapshot.onOwner { layer.sparse.drawInto(canvas, paint) }
                canvas.restoreToCount(save)
                continue
            }
            val sampled = if (resident == null && layer.file != null) {
                Layer.decodeSampled(layer.file, maxOf(w, h))
            } else {
                null
            }
            val pixels = resident ?: sampled ?: continue
            paint.alpha = (layer.opacity * 255f).toInt().coerceIn(0, 255)
            paint.blendMode = layer.blend.mode
            canvas.drawBitmap(
                pixels,
                null,
                android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()),
                paint
            )
            if (sampled != null && sampled !== resident) sampled.recycle()
        }
        if (groupSave >= 0) canvas.restoreToCount(groupSave)
        return out
    }

    fun deleteProject(id: String) {
        savedLayers.keys.removeAll { it.startsWith("$id/") }
        projectDir(id).deleteRecursively()
    }

    // ------------------------------------------------------------- history

    /**
     * Writes the replayable history run. Patches are immutable, so a file that
     * already exists for an action id is left alone (autosave stays cheap).
     */
    /** Each project's last written history records, by step id (for [PersistedHistoryEntry.reused]). */
    private val historyRecords = java.util.concurrent.ConcurrentHashMap<String, Map<Long, JSONObject>>()

    /** Step ids whose files are on disk from the last history write of [id]. */
    fun savedHistoryIds(id: String): Set<Long> = historyRecords[id]?.keys ?: emptySet()

    private fun recordFiles(o: JSONObject, into: MutableSet<String>) {
        o.optString("before", "").takeIf { it.isNotEmpty() }?.let { into.add(it) }
        o.optString("after", "").takeIf { it.isNotEmpty() }?.let { into.add(it) }
        val tiles = o.optJSONArray("tiles") ?: return
        for (k in 0 until tiles.length()) {
            val t = tiles.getJSONObject(k)
            for (key in arrayOf("b", "a")) if (!t.isNull(key) && t.has(key)) into.add(t.optString(key))
        }
    }

    @Synchronized
    fun saveHistory(id: String, history: PersistedHistory, stamp: String? = null, protect: Set<Long> = emptySet()) {
        // A project deleted while its last save was still running must not
        // come back as a folder of undo history with no document.
        if (!File(projectDir(id), "manifest.json").exists()) return
        val dir = projectDir(id)
        val hdir = File(dir, "history").apply { mkdirs() }
        val keep = HashSet<String>()
        var entriesJson = JSONArray()
        val previous = historyRecords[id] ?: emptyMap()
        val records = HashMap<Long, JSONObject>()
        var applied = history.appliedCount
        var written = 0

        for (entry in history.entries) {
            // Layer structure: just its states.
            val structure = entry.structure
            if (structure != null) {
                val o = JSONObject().apply {
                    put("id", entry.actionId)
                    put("label", entry.label)
                    put("kind", entry.kind.name)
                    put("structure", JSONObject(structure))
                }
                entriesJson.put(o)
                written++
                continue
            }
            // Written by an earlier save: the same record, at today's layer index.
            if (entry.reused) {
                val prev = previous[entry.actionId]
                if (prev == null) {
                    // Should not happen: drop it and everything after it.
                    applied = applied.coerceAtMost(written)
                    break
                }
                val o = JSONObject(prev.toString()).apply {
                    put("layer", entry.layerIndex)
                    put("layerId", entry.layerId)
                }
                recordFiles(o, keep)
                entriesJson.put(o)
                records[entry.actionId] = o
                written++
                continue
            }
            val tiles = entry.tiles
            if (tiles != null) {
                // Tile form: one small PNG per changed tile crop.
                val tilesJson = JSONArray()
                val stepFiles = ArrayList<String>()
                val readable = try {
                    for (t in tiles) {
                        fun crop(given: Bitmap?, before: Boolean, tag: String): Any {
                            val name = "h${entry.actionId}_${t.tx}_${t.ty}_$tag.png"
                            val f = File(hdir, name)
                            val fetch = t.fetch
                            if (fetch != null) {
                                // A big step read crop by crop (one tile in memory).
                                if (f.exists()) { stepFiles.add(name); return name }
                                val b = fetch(before) ?: return JSONObject.NULL
                                try { writePng(f, b) } finally { b.recycle() }
                                stepFiles.add(name)
                                return name
                            }
                            if (given == null) return JSONObject.NULL
                            if (!f.exists()) writePng(f, given)
                            stepFiles.add(name)
                            return name
                        }
                        tilesJson.put(JSONObject().apply {
                            put("tx", t.tx)
                            put("ty", t.ty)
                            put("clip", JSONArray().put(t.clip.left).put(t.clip.top).put(t.clip.right).put(t.clip.bottom))
                            if (t.beforeSolid >= 0L) put("bSolid", t.beforeSolid) else put("b", crop(t.before, true, "b"))
                            if (t.afterSolid >= 0L) put("aSolid", t.afterSolid) else put("a", crop(t.after, false, "a"))
                        })
                    }
                    true
                } catch (e: Exception) {
                    android.util.Log.w("YumeHistory", "step ${entry.actionId} could not be saved", e)
                    false
                }
                if (!readable) {
                    // This step and everything older cannot be kept (the
                    // saved history must lead up to the current pixels): only
                    // the newer steps are written.
                    applied = (applied - written - 1).coerceAtLeast(0)
                    entriesJson = JSONArray()
                    keep.clear()
                    records.clear()
                    written = 0
                    continue
                }
                keep.addAll(stepFiles)
                val o = JSONObject().apply {
                    put("id", entry.actionId)
                    put("label", entry.label)
                    put("kind", entry.kind.name)
                    put("detail", entry.detail)
                    put("swatch", entry.swatch)
                    put("layer", entry.layerIndex)
                    put("layerId", entry.layerId)
                    put("rect", JSONArray().put(entry.rect.left).put(entry.rect.top).put(entry.rect.right).put(entry.rect.bottom))
                    put("mask", entry.mask)
                    put("tileSize", entry.tileSize)
                    put("tiles", tilesJson)
                }
                entriesJson.put(o)
                records[entry.actionId] = o
                written++
                continue
            }
            val beforeBmp = entry.before ?: continue
            val afterBmp = entry.after ?: continue
            val beforeName = "h${entry.actionId}_b.png"
            val afterName = "h${entry.actionId}_a.png"
            val beforeFile = File(hdir, beforeName)
            val afterFile = File(hdir, afterName)
            if (!beforeFile.exists() || !afterFile.exists()) {
                writePng(beforeFile, beforeBmp)
                writePng(afterFile, afterBmp)
            }
            keep.add(beforeName)
            keep.add(afterName)
            val o = JSONObject().apply {
                put("id", entry.actionId)
                put("label", entry.label)
                put("kind", entry.kind.name)
                put("detail", entry.detail)
                put("swatch", entry.swatch)
                put("layer", entry.layerIndex)
                put("layerId", entry.layerId)
                put("mask", entry.mask)
                put(
                    "rect",
                    JSONArray().put(entry.rect.left).put(entry.rect.top)
                        .put(entry.rect.right).put(entry.rect.bottom)
                )
                put("before", beforeName)
                put("after", afterName)
            }
            entriesJson.put(o)
            records[entry.actionId] = o
            written++
        }

        // The manifest first (written whole, then renamed), so a crash never
        // leaves it pointing at deleted patches; then unused patches go.
        val manifest = JSONObject().apply {
            put("version", 2)
            if (stamp != null) put("stamp", stamp)
            put("applied", applied.coerceIn(0, written))
            put("entries", entriesJson)
        }
        val ok = runCatching {
            val tmp = File(dir, "history.json.tmp")
            tmp.writeText(manifest.toString())
            if (!tmp.renameTo(File(dir, "history.json"))) {
                File(dir, "history.json").writeText(manifest.toString())
                tmp.delete()
            }
        }.isSuccess
        if (ok) {
            // Steps still in the session that read their crops from these
            // files keep them (and their records), even out of the run.
            for (pid in protect) {
                if (pid in records) continue
                val prev = previous[pid] ?: continue
                recordFiles(prev, keep)
                records[pid] = prev
            }
            historyRecords[id] = records
            hdir.listFiles()?.forEach { file -> if (file.name !in keep) file.delete() }
        }
    }

    fun loadHistory(id: String, expected: String? = null): PersistedHistory? = runCatching {
        val dir = projectDir(id)
        val manifest = File(dir, "history.json")
        if (!manifest.exists()) return@runCatching null
        val json = JSONObject(manifest.readText())
        // A history written for other pixels than those being opened would
        // undo into the wrong picture.
        if (expected != null) {
            val stamp = json.optString("stamp", "")
            if (stamp.isNotEmpty() && stamp != expected) return@runCatching null
            if (stamp.isEmpty() && expected.contains("/j")) return@runCatching null
        }
        val hdir = File(dir, "history")
        val entriesJson = json.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<PersistedHistoryEntry>()
        val records = HashMap<Long, JSONObject>()
        for (i in 0 until entriesJson.length()) {
            val o = entriesJson.getJSONObject(i)
            val structure = o.optJSONObject("structure")
            if (structure != null) {
                entries.add(
                    PersistedHistoryEntry(
                        actionId = o.optLong("id", i + 1L),
                        label = o.optString("label", "Edit"),
                        kind = runCatching { EditKind.valueOf(o.optString("kind", "LayerOrder")) }.getOrDefault(EditKind.LayerOrder),
                        detail = "", swatch = 0, layerIndex = -1, rect = android.graphics.Rect(),
                        before = null, after = null, structure = structure.toString()
                    )
                )
                continue
            }
            records[o.optLong("id", i + 1L)] = o
            val r0 = o.optJSONArray("rect")
            val rect0 = android.graphics.Rect(
                r0?.optInt(0) ?: 0, r0?.optInt(1) ?: 0, r0?.optInt(2) ?: 0, r0?.optInt(3) ?: 0
            )
            val tilesJson = o.optJSONArray("tiles")
            if (tilesJson != null) {
                val tiles = ArrayList<com.yume.app.engine.PersistedTile>(tilesJson.length())
                var ok = true
                for (k in 0 until tilesJson.length()) {
                    val t = tilesJson.getJSONObject(k)
                    val c = t.optJSONArray("clip")
                    // Not decoded here: a big canvas's history is far more than
                    // memory. The step reads its PNGs when undone or redone.
                    fun crop(key: String): Pair<Boolean, File?> {
                        if (t.isNull(key) || !t.has(key)) return true to null
                        val f = File(hdir, t.optString(key))
                        return f.exists() to f
                    }
                    val bSolid = t.optLong("bSolid", -1L)
                    val aSolid = t.optLong("aSolid", -1L)
                    val (okB, bf) = if (bSolid >= 0L) true to null else crop("b")
                    val (okA, af) = if (aSolid >= 0L) true to null else crop("a")
                    if (!okB || !okA) { ok = false; break }
                    tiles.add(com.yume.app.engine.PersistedTile(
                        t.optInt("tx"), t.optInt("ty"),
                        android.graphics.Rect(c?.optInt(0) ?: 0, c?.optInt(1) ?: 0, c?.optInt(2) ?: 0, c?.optInt(3) ?: 0),
                        null, null, bSolid, aSolid, beforeFile = bf, afterFile = af
                    ))
                }
                // A step with a missing crop cannot be undone exactly: stop
                // there (older steps would replay on top of a hole).
                if (!ok) break
                entries.add(
                    PersistedHistoryEntry(
                        actionId = o.optLong("id", i + 1L),
                        label = o.optString("label", "Edit"),
                        kind = runCatching { EditKind.valueOf(o.optString("kind", "Stroke")) }.getOrDefault(EditKind.Stroke),
                        detail = o.optString("detail", ""),
                        swatch = o.optInt("swatch", 0),
                        layerIndex = o.optInt("layer", -1),
                        rect = rect0,
                        before = null,
                        after = null,
                        mask = o.optBoolean("mask", false),
                        tiles = tiles,
                        tileSize = o.optInt("tileSize", 0),
                        layerId = o.optLong("layerId", -1L)
                    )
                )
                continue
            }
            val before = decodePatch(File(hdir, o.optString("before")))
            val after = decodePatch(File(hdir, o.optString("after")))
            if (before == null || after == null) continue
            val r = o.optJSONArray("rect")
            val rect = android.graphics.Rect(
                r?.optInt(0) ?: 0, r?.optInt(1) ?: 0,
                r?.optInt(2) ?: 0, r?.optInt(3) ?: 0
            )
            entries.add(
                PersistedHistoryEntry(
                    actionId = o.optLong("id", i + 1L),
                    label = o.optString("label", "Edit"),
                    kind = runCatching { EditKind.valueOf(o.optString("kind", "Stroke")) }
                        .getOrDefault(EditKind.Stroke),
                    detail = o.optString("detail", ""),
                    swatch = o.optInt("swatch", 0),
                    layerIndex = o.optInt("layer", -1),
                    rect = rect,
                    before = before,
                    after = after,
                    mask = o.optBoolean("mask", false),
                    layerId = o.optLong("layerId", -1L)
                )
            )
        }
        historyRecords[id] = records
        PersistedHistory(json.optInt("applied", entries.size), entries)
    }.getOrNull()

    private fun decodePatch(file: File): Bitmap? {
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        )
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }
    }

    fun renameProject(id: String, name: String) {
        val dir = projectDir(id)
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return
        runCatching {
            val json = JSONObject(manifestFile.readText())
            json.put("name", name)
            json.put("updatedAt", System.currentTimeMillis())
            manifestFile.writeText(json.toString())
        }
    }

    fun duplicateProject(id: String): String? {
        val source = projectDir(id)
        if (!source.exists()) return null
        val newId = UUID.randomUUID().toString()
        val target = projectDir(newId).apply { mkdirs() }
        source.listFiles()?.forEach { file ->
            if (file.isFile) File(target, file.name).writeBytes(file.readBytes())
        }
        val manifestFile = File(target, "manifest.json")
        runCatching {
            val json = JSONObject(manifestFile.readText())
            json.put("name", json.optString("name", "Untitled") + " copy")
            json.put("updatedAt", System.currentTimeMillis())
            manifestFile.writeText(json.toString())
        }
        return newId
    }

    /** Writes a flattened image into the device Pictures folder. */
    fun exportPng(bitmap: Bitmap, name: String): Uri? {
        val safeName = name.ifBlank { "Yume" }.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
            .ifBlank { "Yume" }
        val fileName = "$safeName-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Yume"
            )
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            uri
        }.getOrNull()
    }

    fun decodeUri(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input, null,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            )
        }
    }.getOrNull()

    private companion object {
        /** Rows per encoder band for dense surfaces (Stage 3c banded PNG). */
        const val ENCODE_BAND = 256
        /** state.json file name for a layer that has no base PNG yet. */
        const val NO_FILE = "__none__.png"
        /** Prefix for in-progress writes; must not look like a layer file. */
        const val TMP_PREFIX = ".tmp-"
    }
}
