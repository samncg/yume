package com.yume.app.data

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
    val thumbFile: File?
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
    val symmetryYPos: Float = -1f
)

/**
 * Flat-file document store: one directory per project containing a JSON
 * manifest plus one PNG per layer. Simple, crash tolerant and easy to inspect.
 */
class ProjectStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    /**
     * Remembers which layer files are already up to date, so autosaves only
     * re-encode layers that actually changed. Key: project/layer id.
     */
    private val savedLayers = HashMap<String, Pair<Int, Int>>()

    fun projectDir(id: String): File = File(root, id)

    fun listProjects(): List<ProjectMeta> {
        val dirs = root.listFiles() ?: return emptyList()
        val out = ArrayList<ProjectMeta>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val manifest = File(dir, "manifest.json")
            if (!manifest.exists()) continue
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
        val blank = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val layer = LayerSnapshotData("Layer 1", blank, 1f, LayerBlend.Normal, true, false)

        writeLayer(dir, 0, blank)
        val thumb = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
            put("layers", JSONArray().put(layerJson(layer, 0)))
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
            val json = JSONObject(manifestFile.readText())
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
                val bitmap: Bitmap? = when {
                    visible && exists -> decodeLayerFile(file)
                    !visible && exists -> null
                    else -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                val maskName = o.optString("mask", "")
                val maskFile = if (maskName.isEmpty()) {
                    null
                } else {
                    File(dir, maskName).takeIf { it.exists() }
                }
                val mask = if (visible && maskFile != null) decodeLayerFile(maskFile) else null
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
                        file = if (exists) file else null
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
                symmetryYPos = json.optDouble("symmetryYPos", -1.0).toFloat()
            )
        }.getOrNull()
    }

    fun saveProject(id: String, snapshot: DocumentSnapshot) {
        val dir = projectDir(id).apply { mkdirs() }
        // Drop leftovers from a save that was interrupted before its renames.
        dir.listFiles()?.forEach { if (it.name.startsWith(TMP_PREFIX)) it.delete() }

        val seen = HashSet<String>()
        val seenMask = HashSet<String>()
        // temp -> final, applied only after every temp has been written.
        val pending = ArrayList<Pair<File, File>>()

        for ((index, layer) in snapshot.layers.withIndex()) {
            val name = "layer_$index.png"
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
                    if (encodePng(tmp, bmp)) pending.add(tmp to target)
                } else if (layer.sparse != null && layer.sparse.isResident) {
                    // Stage 2: assemble a full bitmap for the PNG encoder. One
                    // layer at a time, so peak extra memory is one canvas.
                    val tmp = tempFile(dir, name)
                    if (encodeStorePng(tmp, layer.sparse)) pending.add(tmp to target)
                } else {
                    val src = layer.file ?: layer.sparse?.backingFile()
                    if (src != null && src.exists() && src.absolutePath != target.absolutePath) {
                        val tmp = tempFile(dir, name)
                        if (copyBytes(src, tmp)) pending.add(tmp to target)
                    }
                    // When src == target the file already holds these pixels.
                }
                savedLayers[key] = index to layer.revision
            }
            seen.add(name)

            val maskName = "mask_$index.png"
            val maskTarget = File(dir, maskName)
            val mask = layer.mask
            if (mask != null) {
                if (!unchanged || !maskTarget.exists()) {
                    val tmp = tempFile(dir, maskName)
                    if (encodePng(tmp, mask)) pending.add(tmp to maskTarget)
                }
                seenMask.add(maskName)
            } else if (layer.maskSparse != null && layer.maskSparse.isResident) {
                if (!unchanged || !maskTarget.exists()) {
                    val tmp = tempFile(dir, maskName)
                    if (encodeStorePng(tmp, layer.maskSparse)) pending.add(tmp to maskTarget)
                }
                seenMask.add(maskName)
            } else if (layer.maskFile != null) {
                if (!unchanged || !maskTarget.exists()) {
                    val src = layer.maskFile
                    if (src.exists() && src.absolutePath != maskTarget.absolutePath) {
                        val tmp = tempFile(dir, maskName)
                        if (copyBytes(src, tmp)) pending.add(tmp to maskTarget)
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
            layersJson.put(layerJson(layer, index))
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
            put("updatedAt", System.currentTimeMillis())
            put("layers", layersJson)
            put("groups", groupsJson)
        }
        File(dir, "manifest.json").writeText(manifest.toString())
    }

    private fun layerJson(layer: LayerSnapshotData, index: Int): JSONObject = JSONObject().apply {
        put("file", "layer_$index.png")
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
            put("mask", "mask_$index.png")
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
    private fun encodePng(file: File, bitmap: Bitmap): Boolean = runCatching {
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        true
    }.getOrDefault(false)

    /** Assembles a sparse store into one bitmap only for the length of a write. */
    private fun encodeStorePng(file: File, store: TileStore): Boolean {
        // The one canvas-sized assembly a sparse document is allowed to build:
        // the surface handed to the PNG encoder. Allocated through the shared
        // encode site so the debug allocation counter can prove it is the only
        // one, and capped by the memory guard.
        if (!MemoryBudget.fitsBitmaps(store.width, store.height, 1)) return false
        val full = runCatching {
            val b = AllocTracker.encodeCanvas(store.width, store.height)
            store.readRectInto(Rect(0, 0, store.width, store.height), b)
            b
        }.getOrNull() ?: return false
        return try {
            encodePng(file, full)
        } finally {
            full.recycle()
        }
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
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
        )
    }.getOrNull()

    private fun writeThumb(dir: File, thumb: Bitmap) {
        runCatching {
            FileOutputStream(File(dir, "thumb.png")).use { out ->
                thumb.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
        }
    }

    private fun makeThumb(snapshot: DocumentSnapshot): Bitmap? {
        val scale = 512f / maxOf(snapshot.width, snapshot.height)
        val w = (snapshot.width * scale).toInt().coerceAtLeast(1)
        val h = (snapshot.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(snapshot.background)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        for (layer in snapshot.layers) {
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
                layer.sparse.drawInto(canvas, paint)
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
    fun saveHistory(id: String, history: PersistedHistory) {
        val dir = projectDir(id).apply { mkdirs() }
        val hdir = File(dir, "history").apply { mkdirs() }
        val keep = HashSet<String>()
        val entriesJson = JSONArray()

        for (entry in history.entries) {
            val beforeName = "h${entry.actionId}_b.png"
            val afterName = "h${entry.actionId}_a.png"
            val beforeFile = File(hdir, beforeName)
            val afterFile = File(hdir, afterName)
            if (!beforeFile.exists() || !afterFile.exists()) {
                writePng(beforeFile, entry.before)
                writePng(afterFile, entry.after)
            }
            keep.add(beforeName)
            keep.add(afterName)
            entriesJson.put(
                JSONObject().apply {
                    put("id", entry.actionId)
                    put("label", entry.label)
                    put("kind", entry.kind.name)
                    put("detail", entry.detail)
                    put("swatch", entry.swatch)
                    put("layer", entry.layerIndex)
                    put(
                        "rect",
                        JSONArray().put(entry.rect.left).put(entry.rect.top)
                            .put(entry.rect.right).put(entry.rect.bottom)
                    )
                    put("before", beforeName)
                    put("after", afterName)
                }
            )
        }

        hdir.listFiles()?.forEach { file -> if (file.name !in keep) file.delete() }

        val manifest = JSONObject().apply {
            put("version", 1)
            put("applied", history.appliedCount)
            put("entries", entriesJson)
        }
        runCatching {
            File(dir, "history.json").writeText(manifest.toString())
        }
    }

    fun loadHistory(id: String): PersistedHistory? = runCatching {
        val dir = projectDir(id)
        val manifest = File(dir, "history.json")
        if (!manifest.exists()) return@runCatching null
        val json = JSONObject(manifest.readText())
        val hdir = File(dir, "history")
        val entriesJson = json.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<PersistedHistoryEntry>()
        for (i in 0 until entriesJson.length()) {
            val o = entriesJson.getJSONObject(i)
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
                    after = after
                )
            )
        }
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
        /** Prefix for in-progress writes; must not look like a layer file. */
        const val TMP_PREFIX = ".tmp-"
    }
}
