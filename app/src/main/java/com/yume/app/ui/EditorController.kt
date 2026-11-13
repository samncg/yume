package com.yume.app.ui

import android.net.Uri
import com.yume.app.data.PresetStore
import com.yume.app.data.ProjectStore
import com.yume.app.engine.BrushLibrary
import kotlinx.coroutines.sync.withLock
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
            // Bases of the journal: the PNG each layer was opened from.
            store.adoptBases(project.id, engine.layers.map { it.id }, project.layers)
            // Crash recovery: replay the journal written by the last autosave.
            var recovered = false
            project.recovery?.let { r ->
                recovered = true
                val ok = runCatching { r.apply(engine) }
                _message.value = if (ok.isSuccess) {
                    "Recovered your work from the last autosave"
                } else {
                    "Recovered your work partially: the autosave journal was damaged"
                }
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
                project.recovery?.let { session.nextJournalSlot = it.maxSlot + 1 }
                // Older undo steps spill to the document's cache directory.
                tileCache?.let { c ->
                    session.history.spill = com.yume.app.engine.HistorySpill(java.io.File(c.dir, "history.spill"))
                }
            }

            // Re-attach the previous session's reversible edits. After a journal
            // recovery the persisted history describes the last FULL save, not
            // the recovered pixels, so it is not restored.
            if (!recovered) {
                runCatching { store.loadHistory(projectId) }
                    .getOrNull()
                    ?.takeIf { it.entries.isNotEmpty() }
                    ?.let { _session.value?.restoreHistory(it) }
            }
            // Allocate the dab scratch before the first gesture so the first
            // stroke does not pay for the allocation inside its first frame.
            _session.value?.warmup()
        }
    }

    /** This document's tile cache (sparse documents only). */
    private var tileCache: com.yume.app.engine.TileCache? = null
    private var exitSave: kotlinx.coroutines.Deferred<Boolean>? = null

    /** Serialises saves and journal writes (they share the project folder). */
    private val saveMutex = kotlinx.coroutines.sync.Mutex()
    /** A journal write failed: the next autosave writes PNGs instead. */
    @Volatile private var journalBroken = false

    fun save() {
        val session = _session.value ?: return
        scope.launch { saveNow(session) }
    }

    /**
     * Writes a cropped / resized copy of the document as a new project and
     * hands its id to [onDone] (null on failure). The original is not changed.
     */
    fun canvasCopy(newW: Int, newH: Int, scale: Boolean, anchorCenter: Boolean, onDone: (String?) -> Unit) {
        val session = _session.value ?: return
        scope.launch {
            var snap: com.yume.app.engine.DocumentSnapshot? = null
            if (!session.runOnOwner { snap = session.snapshotNow() }) {
                withContext(Dispatchers.Main) { onDone(null) }
                return@launch
            }
            val s = snap!!
            val ox = if (scale || !anchorCenter) 0 else (s.width - newW) / 2
            val oy = if (scale || !anchorCenter) 0 else (s.height - newH) / 2
            val label = if (scale) "resized" else "cropped"
            val id = runCatching {
                store.createCanvasCopy(s, "${s.title} ($label)", newW, newH, scale, ox, oy)
            }.getOrNull()
            withContext(Dispatchers.Main) { onDone(id) }
        }
    }

    /**
     * Periodic / idle autosave. Sparse documents append only the changed tiles
     * to the crash-recovery journal (Stage 3c); dense documents, or a journal
     * that failed, get a full PNG save as before.
     */
    fun autosave() {
        val session = _session.value ?: return
        if (!session.engine.compositeIsSparse || journalBroken) {
            save()
            return
        }
        scope.launch {
            saveMutex.withLock {
                var batch: com.yume.app.engine.JournalBatch? = null
                var bigger = false
                val ran = session.runOnOwner {
                    batch = session.collectJournal(
                        isNew = { layerId, mask -> store.baseOf(projectId, layerId, mask) == null },
                        maxBytes = JOURNAL_MAX_BYTES
                    )
                    if (batch == null) bigger = true
                }
                val b = batch
                if (!ran || b == null) {
                    if (bigger) journalBroken = true
                    return@withLock
                }
                runCatching { store.writeJournal(projectId, b) }.onFailure {
                    // The changes left the stores' journal state: only a full
                    // save can persist them now.
                    journalBroken = true
                    _message.value = "Autosave journal failed (${it.message}); saving in full"
                }
            }
            if (journalBroken) saveNow(session)
        }
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

    private suspend fun saveNow(session: DrawingSession) = saveMutex.withLock {
        saveNowLocked(session)
    }

    private fun saveNowLocked(session: DrawingSession) {
        // Take the pending journal changes in the same owner turn as the
        // snapshot: a change made after it stays pending for the next autosave.
        var snapshot: com.yume.app.engine.DocumentSnapshot? = null
        var taken: List<Pair<com.yume.app.engine.SparseTileStore, Pair<Set<Long>, Boolean>>> = emptyList()
        if (!session.runOnOwner {
                taken = session.journalTakeAll()
                snapshot = session.snapshotNow()
            }
        ) return
        val snap = snapshot ?: return
        val saved = runCatching { store.saveProject(projectId, snap) }.isSuccess
        if (saved && store.lastSaveComplete) {
            journalBroken = false
        } else {
            // Some pixels did not reach a PNG: keep them pending.
            session.runOnOwner { for ((st, t) in taken) st.journalRestore(t) }
        }
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
        /** Larger journal batches (a big import) fall back to a full save. */
        const val JOURNAL_MAX_BYTES = 96L * 1024L * 1024L
        val SaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
