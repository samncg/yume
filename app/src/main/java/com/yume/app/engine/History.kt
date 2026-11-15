package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Category of an edit, used to pick an icon in the history panel. */
enum class EditKind {
    Stroke, Erase, LayerAdd, LayerDelete, LayerMerge, LayerDuplicate,
    LayerClear, LayerOrder, LayerFill, LayerMask, Opacity, Transform, Adjust, Import
}


/**
 * Undo/redo entries.
 *
 * Pixel edits only store the dirty sub-rectangle before and after the stroke,
 * which keeps history small even for tall documents. Structural edits snapshot
 * the layer list (bitmaps are shared by reference, they are never mutated in
 * place by structural operations).
 */
sealed class HistoryAction {
    /** Stable id used as the patch filename when history is persisted. */
    var id: Long = nextId++
        internal set
    abstract val bytes: Long
    abstract val label: String
    abstract val kind: EditKind
    /** Layer this edit belongs to, or -1 for structural edits. */
    open val layerId: Long get() = -1L
    abstract fun undo(engine: DrawingEngine)
    abstract fun redo(engine: DrawingEngine)

    /** Structural state [undo] restores, or null for pixel edits. */
    open fun undoState(): DocumentState? = null
    /** Structural state [redo] restores, or null when redo recomputes in place. */
    open fun redoState(): DocumentState? = null

    companion object {
        private var nextId = 1L

        /** Keeps generated ids above any id restored from disk. */
        fun bumpCounterTo(id: Long) {
            if (id >= nextId) nextId = id + 1
        }
    }
}

/** Several edits applied together that undo and redo as one step. */
class GroupAction(
    val actions: List<HistoryAction>,
    override val label: String,
    override val kind: EditKind
) : HistoryAction() {
    override val bytes: Long get() = actions.sumOf { it.bytes }
    override fun undo(engine: DrawingEngine) {
        for (i in actions.indices.reversed()) actions[i].undo(engine)
    }
    override fun redo(engine: DrawingEngine) {
        for (a in actions) a.redo(engine)
    }
}

class PixelAction(
    override val layerId: Long,
    val rect: Rect,
    internal val before: Bitmap,
    internal val after: Bitmap,
    override val label: String,
    override val kind: EditKind,
    /** Human readable brush/size summary shown in the history panel. */
    val detail: String = "",
    /** Colour dot shown next to the entry (0 = no dot). */
    val swatch: Int = 0,
    /** True when the patch belongs to the layer mask instead of its pixels. */
    val mask: Boolean = false
) : HistoryAction() {

    override val bytes: Long
        get() = (before.byteCount + after.byteCount).toLong()

    override fun undo(engine: DrawingEngine) {
        val layer = engine.layerById(layerId) ?: return
        engine.blitRegion(layer, rect, before, mask)
    }

    override fun redo(engine: DrawingEngine) {
        val layer = engine.layerById(layerId) ?: return
        engine.blitRegion(layer, rect, after, mask)
    }
}

class LayersAction(
    internal val before: DocumentState,
    internal val after: DocumentState,
    override val label: String,
    override val kind: EditKind
) : HistoryAction() {

    override val bytes: Long
        get() = 16L * (before.layers.size + after.layers.size)

    override fun undo(engine: DrawingEngine) = engine.restoreState(before)
    override fun redo(engine: DrawingEngine) = engine.restoreState(after)
    override fun undoState(): DocumentState = before
    override fun redoState(): DocumentState = after
}

data class LayerState(val layer: Layer, val props: LayerProps)

/** Everything structural that undo needs: the layer list and its groups. */
data class DocumentState(
    val layers: List<LayerState>,
    val groups: List<GroupState>,
    val activeIndex: Int,
    val activeGroupId: Long = 0L
)

/**
 * A merge that can be replayed instead of a baked result.
 *
 * Undo restores the two layers from the snapshot. Redo re-runs the merge over
 * whatever those layers contain *now*, so edits replayed in between (for
 * example deleting one of the strokes from the history panel) end up inside
 * the merged result instead of being resurrected from a pre-baked bitmap.
 */
class MergeAction(
    private val before: DocumentState,
    internal val upperId: Long,
    override val label: String,
    override val kind: EditKind = EditKind.LayerMerge
) : HistoryAction() {

    override val bytes: Long
        get() = (before.layers.lastOrNull()?.layer?.tiles?.residentBytes() ?: 0L) +
            16L * before.layers.size

    override fun undo(engine: DrawingEngine) = engine.restoreState(before)
    override fun undoState(): DocumentState = before

    override fun redo(engine: DrawingEngine) {
        engine.mergeDown(upperId)
    }
}

/** One row in the history panel. [applied] entries are currently in effect. */
data class HistoryEntry(
    val index: Int,
    val label: String,
    val kind: EditKind,
    val applied: Boolean,
    val detail: String = "",
    val swatch: Int = 0
)

class History(
    /** In-memory cap; lowered to [SPILL_MEM_CAP] once a disk tier is attached. */
    var maxBytes: Long = 320L * 1024L * 1024L
) {
    private val undoStack = ArrayDeque<HistoryAction>()
    private val redoStack = ArrayDeque<HistoryAction>()
    private var bytes = 0L

    /**
     * Disk tier (Stage 3c). When set, the oldest tile edits are spilled here
     * once the in-memory history passes [maxBytes] instead of being dropped;
     * history depth is then bounded by [diskCap].
     */
    var spill: HistorySpill? = null
        set(value) {
            field = value
            // With a disk tier, older steps cost storage, not memory.
            if (value != null) maxBytes = minOf(maxBytes, SPILL_MEM_CAP)
        }
    var diskCap: Long = 1024L * 1024L * 1024L

    companion object {
        const val SPILL_MEM_CAP = 64L * 1024L * 1024L
        /** A step past this many bytes of crops is kept on disk and streamed. */
        const val STREAM_BYTES = 96L * 1024L * 1024L
    }
    var spilledActions: Long = 0L; private set
    var loadedActions: Long = 0L; private set
    /** Set when a spilled step could not be read back (that step is skipped). */
    var lastError: String? = null

    /** In-memory bytes of the undo stack (spilled crops count as 0). */
    private fun residentUndoBytes(): Long {
        var t = 0L
        for (a in undoStack) t += a.bytes
        return t
    }

    /**
     * A step bigger than [STREAM_BYTES] (or one whose crops were already put
     * on disk while it was made) goes to disk at once and is streamed on
     * undo / redo, instead of sitting in memory as the newest step: one blur
     * of a painted 16K layer is 2 GB of crops.
     */
    private fun streamIfLarge(action: HistoryAction) {
        if (action is GroupAction) {
            action.actions.forEach { streamIfLarge(it) }
            return
        }
        val t = action as? TilePixelAction ?: return
        val s = spill ?: return
        if (t.isResident && t.bytes <= STREAM_BYTES) return
        t.spillTo(s)
        t.stream = s
    }

    /** Brings [action]'s spilled crops back into memory. False if unreadable. */
    fun ensureLoaded(action: HistoryAction): Boolean {
        if (action is GroupAction) return action.actions.all { ensureLoaded(it) }
        val t = action as? TilePixelAction ?: return true
        if (t.isResident) return true
        val s = spill ?: return false
        if (t.stream === s) {
            if (t.canStream(s)) return true
            lastError = "An older undo step could not be read back"
            return false
        }
        return try {
            t.loadFrom(s)
            loadedActions++
            true
        } catch (e: java.io.IOException) {
            lastError = "An older undo step could not be read back (${e.message})"
            false
        }
    }

    /** Bumped on every change so the UI can cache its derived list. */
    var version: Int = 0
        private set

    var onChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val appliedCount: Int get() = undoStack.size
    val totalCount: Int get() = undoStack.size + redoStack.size

    /** The action [undo] would apply, without removing it. */
    fun peekUndo(): HistoryAction? = undoStack.lastOrNull()

    /** The action [redo] would apply, without removing it. */
    fun peekRedo(): HistoryAction? = redoStack.lastOrNull()

    /**
     * Largest bitmap need across every structural state still reachable.
     * Jump-to-step and delete-and-replay may visit many states, so they are
     * checked against the worst case before the first mutation.
     */
    fun maxStateBitmapNeed(engine: DrawingEngine): Int {
        var max = engine.visibleBitmapNeed()
        fun consider(state: DocumentState?) {
            if (state != null) max = maxOf(max, engine.stateBitmapNeed(state))
        }
        for (action in undoStack) {
            consider(action.undoState())
            consider(action.redoState())
        }
        for (action in redoStack) {
            consider(action.undoState())
            consider(action.redoState())
        }
        return max
    }

    fun entries(): List<HistoryEntry> {
        val applied = undoStack.size
        val out = ArrayList<HistoryEntry>(applied + redoStack.size)
        undoStack.forEachIndexed { index, action ->
            out.add(entryOf(index, action, true))
        }
        for (i in redoStack.indices.reversed()) {
            val action = redoStack[i]
            val index = applied + (redoStack.size - 1 - i)
            out.add(entryOf(index, action, false))
        }
        return out
    }

    private fun entryOf(index: Int, action: HistoryAction, applied: Boolean): HistoryEntry {
        val pixel = action as? PixelAction
        return HistoryEntry(
            index = index,
            label = action.label,
            kind = action.kind,
            applied = applied,
            detail = pixel?.detail ?: "",
            swatch = pixel?.swatch ?: 0
        )
    }

    /**
     * The newest span of edits that can be serialised and replayed after a
     * restart.
     *
     * Pixel edits are replayable on their own. Trailing structural actions that
     * neither remove a layer nor rewrite its pixels (adding, duplicating or
     * reordering) are skipped rather than treated as a barrier — otherwise the
     * common "paint, then add a layer" sequence would persist an empty history.
     * Destructive structure (delete, merge, clear) still ends the span, because
     * the patches before it may no longer apply meaningfully.
     */
    fun persistableRun(): Pair<List<HistoryAction>, Int> = persistableRun { action ->
        action is PixelAction || action is TilePixelAction
    }

    /**
     * The longest stretch of history around the current state that
     * [canPersist] accepts: back through the undo stack to the first step it
     * refuses, and forward through the redo stack likewise (later redo steps
     * are dropped, the undo side is kept). Returns the run (oldest first) and
     * how many of its steps are applied.
     */
    fun persistableRun(canPersist: (HistoryAction) -> Boolean): Pair<List<HistoryAction>, Int> {
        val undo = undoStack.toList()
        var start = undo.size
        while (start > 0 && canPersist(undo[start - 1])) start--
        val run = ArrayList<HistoryAction>(undo.size - start + redoStack.size)
        for (i in start until undo.size) run.add(undo[i])
        val applied = run.size
        val redo = redoStack.toList()
        for (i in redo.indices.reversed()) {
            if (!canPersist(redo[i])) break
            run.add(redo[i])
        }
        return run to applied
    }

    /** Rebuilds the stacks from persisted edits (oldest first). */
    fun restoreFromDisk(actions: List<HistoryAction>, appliedCount: Int) {
        undoStack.forEach { it.releaseBitmaps() }
        redoStack.forEach { it.releaseBitmaps() }
        undoStack.clear()
        redoStack.clear()
        bytes = 0
        actions.forEachIndexed { index, action ->
            if (index < appliedCount) undoStack.addLast(action) else redoStack.addLast(action)
            bytes += action.bytes
        }
        changed()
    }

    fun push(action: HistoryAction) {
        streamIfLarge(action)
        undoStack.addLast(action)
        bytes += action.bytes
        redoStack.forEach { it.releaseBitmaps() }
        redoStack.clear()
        trim()
        changed()
    }

    fun undo(engine: DrawingEngine): Boolean {
        val action = undoStack.lastOrNull() ?: return false
        if (!ensureLoaded(action)) return false
        undoStack.removeLast()
        action.undo(engine)
        redoStack.addLast(action)
        bytes = residentUndoBytes()
        changed()
        return true
    }

    fun redo(engine: DrawingEngine): Boolean {
        val action = redoStack.lastOrNull() ?: return false
        if (!ensureLoaded(action)) return false
        redoStack.removeLast()
        action.redo(engine)
        undoStack.addLast(action)
        bytes = residentUndoBytes()
        trim()
        changed()
        return true
    }

    /** Moves to the state right after [appliedCount] edits. */
    fun revertTo(engine: DrawingEngine, appliedCount: Int) {
        val target = appliedCount.coerceIn(0, totalCount)
        var guard = 0
        while (undoStack.size > target && guard++ < 512) undo(engine)
        while (undoStack.size < target && guard++ < 512) redo(engine)
    }

    /**
     * Removes a single step and its effect.
     *
     * Rewinds to the state before the step, drops it, then replays everything
     * that came after, so the document keeps every other edit. Undoing *later*
     * steps afterwards can resurrect pixels of a deleted step when their
     * rectangles overlap; that trade-off keeps deletion cheap and predictable.
     */
    fun deleteAt(engine: DrawingEngine, index: Int) {
        if (index !in 0 until totalCount) return
        revertTo(engine, index)
        val target = redoStack.removeLastOrNull() ?: return
        target.releaseBitmaps()
        val remaining = redoStack.size
        repeat(remaining) { redo(engine) }
        changed()
    }

    fun clear() {
        undoStack.forEach { it.releaseBitmaps() }
        redoStack.forEach { it.releaseBitmaps() }
        undoStack.clear()
        redoStack.clear()
        bytes = 0
        changed()
    }

    private fun trim() {
        bytes = residentUndoBytes()
        val s = spill
        while (bytes > maxBytes && undoStack.size > 1) {
            // Prefer spilling the oldest in-memory tile edit to disk; drop
            // (as before) only when there is no disk tier, it is full, or the
            // oldest steps are not tile edits.
            if (s != null && s.diskBytes < diskCap) {
                var freed = 0L
                for (a in undoStack) {
                    if (a === undoStack.last()) break
                    val t = a as? TilePixelAction ?: continue
                    if (t.bytes <= 0L) continue
                    freed = t.spillTo(s)
                    if (freed > 0L) spilledActions++
                    break
                }
                if (freed > 0L) {
                    bytes -= freed
                    continue
                }
                // Nothing left to spill. Dropping the oldest step only helps if it
                // still holds memory (a structural snapshot or a dense patch);
                // a spilled step frees nothing, so stop instead of losing it.
                // The newest step stays in memory even when it alone exceeds
                // the cap.
                if (undoStack.first().bytes <= 0L) break
            }
            val dropped = undoStack.removeFirst()
            bytes -= dropped.bytes
            dropped.releaseBitmaps()
        }
        while (s != null && s.diskBytes > diskCap && undoStack.size > 1) {
            undoStack.removeFirst().releaseBitmaps()
        }
        s?.compactIfNeeded()
    }

    private fun changed() {
        version++
        onChanged?.invoke()
    }

    /** Frees an action that was never pushed (a cancelled edit). */
    fun discard(action: HistoryAction) = action.releaseBitmaps()

    private fun HistoryAction.releaseBitmaps() {
        when (this) {
            is PixelAction -> {
                before.recycle()
                after.recycle()
            }
            is TilePixelAction -> releaseTiles(spill)
            is GroupAction -> actions.forEach { it.releaseBitmaps() }
            else -> Unit
        }
    }
}

/** Utility used by the transform tool and adjustments to snapshot a bitmap. */
internal fun copyPixels(source: Bitmap): Bitmap {
    val copy = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(copy)
    val p = Paint()
    p.blendMode = BlendMode.SRC
    c.drawBitmap(source, 0f, 0f, p)
    return copy
}

internal fun Bitmap.extractRegion(rect: Rect): Bitmap {
    val out = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val p = Paint()
    p.blendMode = BlendMode.SRC
    c.drawBitmap(this, rect, RectF(0f, 0f, rect.width().toFloat(), rect.height().toFloat()), p)
    return out
}
