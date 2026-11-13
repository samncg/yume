package com.yume.app.ui

import android.net.Uri
import com.yume.app.data.PresetStore
import com.yume.app.data.ProjectStore
import com.yume.app.engine.BrushLibrary
import com.yume.app.engine.BrushPreset
import com.yume.app.engine.DrawingEngine
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.History
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads a project into a [DrawingSession] and takes care of persistence.
 *
 * Saving always happens off the main thread; the session hands back bitmap
 * references so compression never blocks the render thread for long.
 */
class EditorController(
    private val store: ProjectStore,
    private val presetStore: PresetStore,
    private val projectId: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var presetSaveJob: Job? = null

    private val _session = MutableStateFlow<DrawingSession?>(null)
    val session: StateFlow<DrawingSession?> = _session.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var loaded = false
    private var disposed = false
    @Volatile private var finalSaveDone = false
    /** An exit save was already launched, so dispose must not start a second one. */
    @Volatile private var exitSaveStarted = false

    fun load() {
        if (loaded) return
        loaded = true
        scope.launch {
            val project = store.loadProject(projectId)
                ?: store.createProject("Untitled", 2048, 2048, 0xFFFFFFFF.toInt())

            // Sparse documents get a tile cache: layer tiles beyond the budget
            // are evicted to a spill file in the cache directory.
            tileCache = if (com.yume.app.engine.TileStoreConfig.useSparseLayers) {
                com.yume.app.engine.TileCache.open(
                    store.cacheRoot(), project.id,
                    com.yume.app.engine.TileCache.defaultBudget(store.activityManager())
                )
            } else {
                null
            }
            val engine = DrawingEngine(project.width, project.height)
            engine.backgroundColor = project.background
            engine.symmetryX = project.symmetryX
            engine.symmetryY = project.symmetryY
            if (project.symmetryXPos > 0f) engine.symmetryXPos = project.symmetryXPos
            if (project.symmetryYPos > 0f) engine.symmetryYPos = project.symmetryYPos
            for (data in project.layers) {
                // Hidden layers may arrive without pixels; they reload on demand.
                val layer = engine.newLayer(
                    name = data.name,
                    bitmap = data.bitmap,
                    backingFile = data.file
                )
                layer.opacity = data.opacity
                layer.blend = data.blend
                layer.visible = data.visible
                layer.alphaLock = data.alphaLock
                layer.locked = data.locked
                layer.clipping = data.clipping
                layer.adoptMask(data.mask, data.maskFile)
                layer.maskEnabled = data.maskEnabled
            }
            // Group ids are reassigned on load, so rebuild them and remap the
            // membership recorded in the manifest.
            val groupIdMap = HashMap<Long, Long>()
            for (saved in project.groups) {
                val group = engine.newGroup(saved.name)
                group.opacity = saved.opacity
                group.blend = saved.blend
                group.visible = saved.visible
                group.locked = saved.locked
                group.expanded = saved.expanded
                groupIdMap[saved.id] = group.id
            }
            project.layers.forEachIndexed { index, data ->
                val layer = engine.layers[index]
                layer.groupId = groupIdMap[data.groupId] ?: 0L
                // Group children take their opacity from the group.
                if (layer.groupId != 0L) layer.opacity = 1f
            }
            if (engine.layers.isNotEmpty()) {
                val index = project.activeIndex.coerceIn(0, engine.layers.size - 1)
                engine.selectLayer(engine.layers[index].id)
            }
            engine.compositeAll()

            _session.value = DrawingSession(
                documentId = project.id,
                title = project.name,
                engine = engine,
                history = History()
            ).also { session ->
                val presets = runCatching { presetStore.load() }
                    .getOrDefault(BrushLibrary.defaults)
                session.presets = presets
                session.activePresetId = presets.firstOrNull { it.id == "studio-pen" }?.id
                    ?: presets.first().id
                session.onPresetsChanged = { list -> schedulePresetSave(list) }
                session.onMessage = { text -> _message.value = text }
            }

            // Re-attach the previous session's reversible edits.
            runCatching { store.loadHistory(projectId) }
                .getOrNull()
                ?.takeIf { it.entries.isNotEmpty() }
                ?.let { _session.value?.restoreHistory(it) }
            // Allocate the dab scratch before the first gesture so the first
            // stroke does not pay for the allocation inside its first frame.
            _session.value?.warmup()
        }
    }

    /** This document's tile cache (sparse documents only). */
    private var tileCache: com.yume.app.engine.TileCache? = null
    private var exitSave: kotlinx.coroutines.Deferred<Boolean>? = null

    fun save() {
        val session = _session.value ?: return
        scope.launch { saveNow(session) }
    }

    /**
     * Leaves the editor only after the document has been written.
     *
     * The render thread owns the engine state, so the snapshot must be taken
     * while it is still alive. Disposing the editor first (which shuts the
     * renderer down) used to make the final save time out and silently drop the
     * last property change (for example a visibility toggle).
     *
     * The save is bounded: if it has not finished after [EXIT_SAVE_TIMEOUT_MS],
     * the editor leaves anyway and [onWarning] is told that the last changes may
     * not have been saved. The write itself is left running on [SaveScope].
     */
    fun saveThen(onWarning: (String) -> Unit = {}, action: () -> Unit) {
        val session = _session.value
        if (session == null) {
            action()
            return
        }
        exitSaveStarted = true
        scope.launch {
            val save = SaveScope.async {
                runCatching { saveNow(session) }.isSuccess
            }
            exitSave = save
            val completed = withTimeoutOrNull(EXIT_SAVE_TIMEOUT_MS) { save.await() } ?: false
            finalSaveDone = completed
            withContext(Dispatchers.Main) {
                if (!completed) onWarning("Last changes may not have been saved")
                action()
            }
        }
    }

    private suspend fun saveNow(session: DrawingSession) {
        val snapshot = session.snapshotBlocking() ?: return
        runCatching { store.saveProject(projectId, snapshot) }
        store.saveWarning?.let {
            store.saveWarning = null
            _message.value = it
        }
        tileCache?.lastError?.let {
            tileCache?.lastError = null
            _message.value = it
        }
        session.historyForDiskBlocking()?.let { history ->
            runCatching { store.saveHistory(projectId, history) }
        }
        // Keep memory bounded on big documents: flush hidden layers to disk once
        // the document is safely written. They reload if they become visible.
        val budget = Runtime.getRuntime().maxMemory() / 5
        runCatching {
            session.sweepMemoryBlocking(budget) { layer ->
                val pixels = layer.tiles.sharedBitmap() ?: return@sweepMemoryBlocking null
                runCatching {
                    store.writeLayerCache(projectId, layer.id, pixels)
                }.getOrNull()
            }
        }
    }

    private fun schedulePresetSave(presets: List<BrushPreset>) {
        presetSaveJob?.cancel()
        presetSaveJob = scope.launch {
            delay(700)
            runCatching { presetStore.save(presets) }
        }
    }

    fun export() {
        val session = _session.value ?: return
        scope.launch {
            if (session.engine.compositeIsSparse) {
                // Stage 3c: no canvas-sized bitmap; rows stream from the tiles.
                val uri = runCatching {
                    store.exportPngStream(session.title) { out -> session.exportBandedTo(out) }
                }.getOrNull()
                _message.value = if (uri != null) "Exported to Pictures/Yume" else "Export failed"
                return@launch
            }
            val flat = session.flattenBlocking()
            if (flat == null) {
                _message.value = "Export failed"
                return@launch
            }
            val uri = runCatching { store.exportPng(flat, session.title) }.getOrNull()
            flat.recycle()
            _message.value = if (uri != null) {
                "Exported to Pictures/Yume"
            } else {
                "Export failed"
            }
        }
    }

    fun importImage(uri: Uri) {
        val session = _session.value ?: return
        scope.launch {
            val bitmap = store.decodeUri(uri)
            if (bitmap == null) {
                _message.value = "Could not read that image"
                return@launch
            }
            session.addImageLayer(bitmap, "Image")
            _message.value = "Image added as a new layer"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val session = _session.value
        val cache = tileCache
        tileCache = null
        if (session != null && !finalSaveDone && !exitSaveStarted) {
            // Detached from this controller's scope so the final save survives.
            // The tile cache is only deleted once that save has read every tile.
            SaveScope.launch {
                runCatching { saveNow(session) }
                cache?.close()
            }
        } else if (cache != null) {
            val pending = exitSave
            SaveScope.launch {
                runCatching { pending?.await() }
                cache.close()
            }
        }
        scope.cancel()
    }

    private companion object {
        /** Bound on the save performed while leaving, so exit cannot hang. */
        const val EXIT_SAVE_TIMEOUT_MS = 3_000L
        val SaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
