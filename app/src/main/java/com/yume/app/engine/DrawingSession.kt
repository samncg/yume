package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AdjustmentKind(val label: String) {
    Hue("Hue"),
    Saturation("Saturation"),
    Brightness("Brightness"),
    Blur("Blur"),
    Noise("Noise")
}

data class LayerSnapshotData(
    val name: String,
    /** Pixels when resident; null for a layer that is still on disk. */
    val bitmap: Bitmap?,
    val opacity: Float,
    val blend: LayerBlend,
    val visible: Boolean,
    val alphaLock: Boolean,
    val id: Long = 0L,
    val revision: Int = 0,
    val locked: Boolean = false,
    val clipping: Boolean = false,
    val mask: Bitmap? = null,
    val maskFile: File? = null,
    val maskEnabled: Boolean = true,
    val groupId: Long = 0L,
    /** Source file for a non-resident layer, so it can be saved without decoding. */
    val file: File? = null,
    /** Resident sparse store that still needs assembling for a PNG write. */
    val sparse: TileStore? = null,
    /** Resident sparse mask that still needs assembling for a PNG write. */
    val maskSparse: TileStore? = null
)

data class GroupSnapshotData(
    val id: Long,
    val name: String,
    val opacity: Float,
    val blend: LayerBlend,
    val visible: Boolean,
    val locked: Boolean,
    val expanded: Boolean
)

data class DocumentSnapshot(
    val title: String,
    val width: Int,
    val height: Int,
    val background: Int,
    val activeIndex: Int,
    val layers: List<LayerSnapshotData>,
    val groups: List<GroupSnapshotData> = emptyList(),
    val symmetryX: Boolean = false,
    val symmetryY: Boolean = false,
    val symmetryXPos: Float = -1f,
    val symmetryYPos: Float = -1f,
    /**
     * Runs a block that reads sparse layer tiles on the thread that owns the
     * engine (the render thread while it runs), so a save never mutates tile
     * maps (evicted-tile loads) concurrently with painting. Returns false when
     * the owner did not run it in time; the caller then keeps the old file.
     */
    val onOwner: (() -> Unit) -> Boolean = { it(); true },
    /** Journal slot of each layer's store / mask store (-1 = none), by index. */
    val slots: IntArray = IntArray(0),
    val maskSlots: IntArray = IntArray(0)
)

/** One autosave's worth of journal data, captured on the owner thread. */
class JournalBatch(
    val snapshot: DocumentSnapshot,
    val changes: List<TileJournal.Change>
)

/** One persisted history step (pixel edits only). */
data class PersistedHistoryEntry(
    val actionId: Long,
    val label: String,
    val kind: EditKind,
    val detail: String,
    val swatch: Int,
    val layerIndex: Int,
    val rect: Rect,
    val before: Bitmap,
    val after: Bitmap,
    val mask: Boolean = false
)

data class PersistedHistory(
    val appliedCount: Int,
    val entries: List<PersistedHistoryEntry>
)

/**
 * Bridges the UI (main thread) and the drawing engine (render thread).
 *
 * UI gestures go into [inputs], UI commands into [commands]; the render loop
 * drains both, mutates the document and publishes an immutable
 * [EditorUiState] snapshot back to Compose.
 */
class DrawingSession(
    val documentId: String,
    var title: String,
    val engine: DrawingEngine,
    val history: History
) {
    val view = ViewTransform()

    /** Largest legacy rectangle we are willing to materialise for one history
     * entry when persisting a sparse edit (before + after). */
    private val PERSIST_PATCH_BUDGET = 64L * 1024L * 1024L

    private val _uiState = MutableStateFlow(EditorUiState(loaded = true, title = title))
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    internal val commands = ConcurrentLinkedQueue<() -> Unit>()
    internal val inputs = ConcurrentLinkedQueue<InputEvent>()

    @Volatile
    var wakeListener: (() -> Unit)? = null

    // ---- state written on the UI thread, read on the render thread ----
    @Volatile var tool: Tool = Tool.Brush
    @Volatile var category: BrushCategory = BrushCategory.Brush
    @Volatile var presets: List<BrushPreset> = BrushLibrary.defaults
    @Volatile var activePresetId: String = "studio-pen"
    @Volatile var color: Int = Color.BLACK
    @Volatile var fingerPaint: Boolean = true
    @Volatile var selectionShape: SelectionShape = SelectionShape.Lasso
    @Volatile var selectionOp: SelectionOp = SelectionOp.Replace
    @Volatile var chromeVisible: Boolean = true
    @Volatile var assist: Float = 0.3f
    @Volatile var quickShapeEnabled: Boolean = true
    @Volatile var colorMode: ColorMode = ColorMode.Square

    /** Sample the active layer instead of the flattened composite. */
    @Volatile var eyedropperLayerOnly: Boolean = false

    /** Colour tolerance for the automatic (magic wand) selection, 1..100. */
    @Volatile var wandTolerance: Int = 30

    // Paint bucket / ColorDrop (Stage 4.1).
    @Volatile var fillTolerance: Int = 30
    @Volatile var fillLayerOnly: Boolean = false
    @Volatile var fillSelection: Boolean = true
    @Volatile var fillExpand: Int = 1

    // Gradient tool (Stage 4.1).
    @Volatile var gradientKind: Gradients.Kind = Gradients.Kind.Linear
    /** End colour. Defaults to transparent, i.e. "fade out the current colour". */
    @Volatile var gradientSecond: Int = 0x00FFFFFF
    /** Drag guide shown while placing a gradient: x0, y0, x1, y1 in document px. */
    @Volatile var gradientGuide: FloatArray? = null

    /** Notified whenever the brush library changes so it can be persisted. */
    var onPresetsChanged: ((List<BrushPreset>) -> Unit)? = null
    /** Surfaces a message (such as a memory guard) to the editor UI. */
    @Volatile var onMessage: ((String) -> Unit)? = null
    /** Screen pixels taken by the toolbar, used when fitting the canvas. */
    @Volatile var chromeTopInset: Float = 0f
    @Volatile private var stateDirty: Boolean = true

    val recentColors = ArrayList<Int>()
    val palette = ArrayList(EditorUiState.defaultPalette)

    // ---- render-thread only ----
    private val painter = StrokePainter(engine)
    private var transform: TransformSession? = null
    private var strokeLayer: Layer? = null
    private var strokePreset: BrushPreset? = null
    private var quickShaping = false
    private var strokeStartX = 0f
    private var strokeStartY = 0f
    private var strokeLastX = 0f
    private var strokeLastY = 0f

    private var selecting = false
    private var selectStartX = 0f
    private var selectStartY = 0f
    private var selectLastX = 0f
    private var selectLastY = 0f
    private var selectingPath: Path? = null

    private var movingSelection = false
    private var selectionMoveBase: Region? = null
    private var selectionMoveStartX = 0f
    private var selectionMoveStartY = 0f

    /** Auto (magic wand) selection: armed on down, run on tap-up only. */
    private var autoSelectPending = false
    private var autoSelectX = 0f
    private var autoSelectY = 0f
    /** True when the tap landed inside the selection, so a drag may move it. */
    private var autoSelectCanMove = false
    /** Where the finger landed, in screen pixels (zoom independent). */
    private var wandDownX = 0f
    private var wandDownY = 0f

    /** Open property edit (opacity drag) waiting to be committed to history. */
    private var propEditBefore: DocumentState? = null
    private var propEditTarget = 0L
    private var propEditLastMs = 0L
    private var propEditLabel = ""

    /**
     * Bumped whenever a persisted property changes without pushing history
     * (visibility, opacity, blend mode, order-free flags, symmetry, ...). It is
     * folded into [EditorUiState.documentVersion] so the debounced autosave
     * still lands these changes on disk.
     */
    private var propertyVersion = 0

    private fun touchProperties() {
        propertyVersion++
    }

    /** 0 = none, 1 = vertical axis, 2 = horizontal axis. */
    private var draggingGuide = 0

    private var draggingTransform = false
    private var dragLastDocX = 0f
    private var dragLastDocY = 0f

    private var picking = false

    private var adjustSnapshot: Bitmap? = null
    /** Sparse documents snapshot the tiles instead of the whole canvas. */
    private var adjustCapture: TileCapture? = null
    private var adjustBounds: Rect = Rect()
    /** True while an adjustment is being previewed, dense or sparse. */
    private val adjusting: Boolean get() = adjustSnapshot != null || adjustCapture != null
    private var adjustKind: AdjustmentKind? = null
    private var adjustAmount = 0f
    private var adjustProxy: Bitmap? = null
    /** Tiles the bake writes (a blur spreads 3 * radius past the content). */
    private var adjustWork: Rect = Rect()

    @Volatile private var pendingPreviewKind: AdjustmentKind? = null
    @Volatile private var pendingPreviewAmount = 0f
    @Volatile private var hasPendingPreview = false

    private var historyCacheVersion = -1
    private var historyCache: List<HistoryEntry> = emptyList()

    private val thumbs = HashMap<Long, Pair<Int, Bitmap>>()
    private val maskThumbs = HashMap<Long, Pair<Int, Bitmap>>()

    /** Preview path for an in-progress selection drag, in document space. */
    var selectionPreview: Path? = null
        private set

    var hoverX = 0f
        private set
    var hoverY = 0f
        private set
    var hoverVisible = false
        private set

    init {
        view.setDocument(engine.width, engine.height)
        engine.ensureLayer()
        history.onChanged = { requestState() }
        _uiState.value = buildState()
    }

    // ------------------------------------------------------------- plumbing

    fun post(block: () -> Unit) {
        commands.add(block)
        wakeListener?.invoke()
    }

    /** Next free journal slot (recovery sets it past the recovered ones). */
    var nextJournalSlot: Int = 0

    /**
     * Captures every sparse store's changes since the last autosave as journal
     * records, plus a snapshot of the document structure (owner thread).
     * [isNew] tells whether a store has no base PNG yet (then it is dumped in
     * full). Returns null when the document cannot be journaled (a dense layer)
     * or the batch would be larger than [maxBytes]; the caller then does a full
     * save instead.
     */
    fun collectJournal(isNew: (layerId: Long, mask: Boolean) -> Boolean, maxBytes: Long): JournalBatch? {
        val changes = ArrayList<TileJournal.Change>()
        var bytes = 0L
        for (l in engine.layers) {
            for (mask in listOf(false, true)) {
                val st = (if (mask) l.maskTiles else l.tiles) ?: continue
                val sp = st as? SparseTileStore ?: return null
                var full = false
                if (sp.journalSlot < 0) {
                    sp.journalSlot = nextJournalSlot++
                    full = isNew(l.id, mask)
                } else if (isNew(l.id, mask) && sp.journalHasChanges()) {
                    full = false
                }
                if (!full && !sp.journalHasChanges()) continue
                if (!full && !sp.isResident) continue
                bytes += sp.journalCollect(sp.journalSlot, full, changes)
                if (bytes > maxBytes) return null
            }
        }
        val snap = snapshotNow()
        return JournalBatch(snap, changes)
    }

    /** Takes the pending journal state of every sparse store (before a full save). */
    fun journalTakeAll(): List<Pair<SparseTileStore, Pair<Set<Long>, Boolean>>> {
        val out = ArrayList<Pair<SparseTileStore, Pair<Set<Long>, Boolean>>>()
        for (l in engine.layers) {
            for (st in listOf(l.tiles, l.maskTiles)) {
                val sp = st as? SparseTileStore ?: continue
                out.add(sp to sp.journalTake())
            }
        }
        return out
    }

    /** The render thread while a renderer drives this session, else null. */
    @Volatile var ownerThread: Thread? = null

    /**
     * Runs [block] on the owner thread and waits (at most [timeoutMs]). Inline
     * when called on the owner or when no renderer is attached (harness, or
     * after the renderer stopped).
     */
    fun runOnOwner(timeoutMs: Long = 30_000L, block: () -> Unit): Boolean {
        val owner = ownerThread
        if (owner == null || owner === Thread.currentThread()) {
            block()
            return true
        }
        val latch = CountDownLatch(1)
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        var error: Throwable? = null
        post {
            if (claimed.compareAndSet(false, true)) {
                try {
                    block()
                } catch (t: Throwable) {
                    error = t
                } finally {
                    latch.countDown()
                }
            }
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!latch.await(50L, TimeUnit.MILLISECONDS)) {
            // The renderer stopped before it ran the block: run it here. The
            // claim makes sure it runs exactly once either way.
            if (ownerThread == null && claimed.compareAndSet(false, true)) {
                return runCatching(block).isSuccess
            }
            if (System.nanoTime() > deadline) return false
        }
        return error == null
    }

    fun pushInput(event: InputEvent) {
        inputs.add(event)
        wakeListener?.invoke()
    }

    fun requestState() {
        stateDirty = true
        wakeListener?.invoke()
    }

    private val activePreset: BrushPreset
        get() = presets.firstOrNull { it.id == activePresetId } ?: presets.first()

    /** Brush diameter in document pixels, used for the hover cursor. */
    val brushSize: Float get() = activePreset.size

    val transformActive: Boolean get() = transform != null

    private fun presetsOf(category: BrushCategory): List<BrushPreset> {
        val filtered = presets.filter { it.category == category }
        return filtered.ifEmpty { presets }
    }

    // ------------------------------------------------------- render loop API

    fun runCommands() {
        while (true) {
            val cmd = commands.poll() ?: break
            try {
                cmd()
            } catch (t: Throwable) {
                android.util.Log.e("Yume", "command failed", t)
            }
        }
    }

    fun runInput(): Boolean {
        var changed = false
        while (true) {
            val event = inputs.poll() ?: break
            changed = try {
                handle(event) || changed
            } catch (t: Throwable) {
                android.util.Log.e("Yume", "input failed: ${event.javaClass.simpleName}", t)
                changed
            }
        }
        return changed
    }

    fun publishIfNeeded(): Boolean {
        if (!stateDirty) return false
        stateDirty = false
        _uiState.value = buildState()
        return true
    }

    fun rebuildTransformIfNeeded() {
        val t = transform ?: return
        if (t.needsRebuild) t.rebuild()
    }

    // --------------------------------------------------------------- input

    private fun handle(event: InputEvent): Boolean {
        val doc = FloatArray(2)
        return when (event) {
            is InputEvent.Down -> {
                view.toDocument(event.x, event.y, doc)
                // The pen has landed: hide the hover ring immediately.
                hoverVisible = false
                wandDownX = event.x
                wandDownY = event.y
                when {
                    tryGuideDrag(doc[0], doc[1]) -> Unit
                    tool == Tool.Selection -> beginSelectionOrMove(doc[0], doc[1])
                    tool == Tool.Transform -> beginTransformDrag(doc[0], doc[1])
                    tool == Tool.Fill -> fillAtInternal(doc[0], doc[1], color, false)
                    tool == Tool.Gradient -> gradientGuide = floatArrayOf(doc[0], doc[1], doc[0], doc[1])
                    else -> beginStroke(doc[0], doc[1], event.pressure, event.tilt, event.eraserButton)
                }
                true
            }

            is InputEvent.Move -> {
                var changed = false
                for (i in event.xs.indices) {
                    view.toDocument(event.xs[i], event.ys[i], doc)
                    when {
                        draggingGuide != 0 -> {
                            moveGuide(doc[0], doc[1])
                            changed = true
                        }
                        picking -> sampleColor(doc[0], doc[1])
                        autoSelectPending -> {
                            // A drag is not a tap. Measure in screen pixels so the
                            // test behaves the same at any zoom level: zoomed in,
                            // a short finger drag covers very few document px.
                            if (hypot(event.xs[i] - wandDownX, event.ys[i] - wandDownY) > 30f) {
                                autoSelectPending = false
                                // Started inside the selection: dragging it moves
                                // the outline rather than selecting again.
                                if (autoSelectCanMove) {
                                    autoSelectCanMove = false
                                    movingSelection = true
                                }
                            }
                        }
                        movingSelection -> {
                            moveSelection(doc[0], doc[1])
                            changed = true
                        }
                        selecting -> {
                            updateSelection(doc[0], doc[1])
                            changed = true
                        }
                        tool == Tool.Gradient && gradientGuide != null -> {
                            val g = gradientGuide!!
                            gradientGuide = floatArrayOf(g[0], g[1], doc[0], doc[1])
                            changed = true
                        }
                        draggingTransform -> {
                            transform?.translate(doc[0] - dragLastDocX, doc[1] - dragLastDocY)
                            dragLastDocX = doc[0]
                            dragLastDocY = doc[1]
                            changed = true
                        }
                        painter.isActive -> {
                            strokeLastX = doc[0]
                            strokeLastY = doc[1]
                            if (quickShaping) {
                                redrawQuickStroke()
                            } else {
                                painter.move(doc[0], doc[1], event.pressures[i], event.tilt)
                            }
                            changed = true
                        }
                    }
                }
                changed
            }

            is InputEvent.Up -> {
                val guide = gradientGuide
                if (tool == Tool.Gradient && guide != null) {
                    gradientGuide = null
                    // A tap (no drag) does nothing: a gradient needs a direction.
                    if (hypot(guide[2] - guide[0], guide[3] - guide[1]) * view.scale > 6f) {
                        applyGradientInternal(guide[0], guide[1], guide[2], guide[3])
                    }
                }
                when {
                    draggingGuide != 0 -> draggingGuide = 0
                    painter.isActive -> endStroke()
                    movingSelection -> {
                        movingSelection = false
                        selectionMoveBase = null
                    }
                    autoSelectPending -> runAutoSelect()
                    selecting -> endSelection()
                    draggingTransform -> draggingTransform = false
                }
                requestState()
                true
            }

            is InputEvent.Gesture -> {
                val t = transform
                if (t != null) {
                    view.toDocument(event.focusX, event.focusY, doc)
                    t.scaleRotate(doc[0], doc[1], event.scaleFactor, event.angleDelta)
                } else {
                    view.applyGesture(
                        event.focusX, event.focusY,
                        event.scaleFactor, event.angleDelta,
                        event.panX, event.panY
                    )
                    requestState()
                }
                true
            }

            is InputEvent.Tap -> {
                when (event.fingers) {
                    2 -> doUndo()
                    3 -> doRedo()
                    4 -> toggleChrome()
                }
                true
            }

            is InputEvent.Hover -> {
                view.toDocument(event.x, event.y, doc)
                hoverX = doc[0]
                hoverY = doc[1]
                hoverVisible = event.visible && !painter.isActive
                true
            }

            is InputEvent.PickStart -> {
                view.toDocument(event.x, event.y, doc)
                picking = true
                sampleColor(doc[0], doc[1])
                requestState()
                true
            }

            is InputEvent.PickMove -> {
                view.toDocument(event.x, event.y, doc)
                sampleColor(doc[0], doc[1])
                true
            }

            InputEvent.PickEnd -> {
                picking = false
                requestState()
                true
            }

            InputEvent.CancelStroke -> {
                if (painter.isActive) {
                    painter.cancel()
                    painter.releaseTileCapture()
                    FrameLogger.strokeActive = false
                    engine.endStrokeCache()
                    strokeLayer = null
                    strokePreset = null
                }
                // The gesture turned into a multi-touch pan/zoom: drop the
                // pending magic wand instead of selecting under the fingers.
                autoSelectPending = false
                autoSelectCanMove = false
                selecting = false
                selectingPath = null
                selectionPreview = null
                draggingTransform = false
                quickShaping = false
                true
            }

            InputEvent.QuickShape -> {
                val length = hypot(strokeLastX - strokeStartX, strokeLastY - strokeStartY)
                if (quickShapeEnabled && length > 30f && painter.isActive &&
                    strokePreset != null && !quickShaping
                ) {
                    quickShaping = true
                    redrawQuickStroke()
                }
                true
            }
        }
    }

    // ------------------------------------------------------- symmetry guides

    private fun tryGuideDrag(x: Float, y: Float): Boolean {
        if (engine.symmetryLocked) return false
        if (!engine.symmetryX && !engine.symmetryY) return false
        if (tool == Tool.Selection || tool == Tool.Transform || tool == Tool.Fill ||
            tool == Tool.Gradient
        ) {
            return false
        }
        val threshold = 30f / max(view.scale, 0.0001f)
        if (engine.symmetryX && abs(x - engine.symmetryXPos) <= threshold) {
            draggingGuide = 1
            return true
        }
        if (engine.symmetryY && abs(y - engine.symmetryYPos) <= threshold) {
            draggingGuide = 2
            return true
        }
        return false
    }

    private fun moveGuide(x: Float, y: Float) {
        if (draggingGuide == 1) {
            engine.symmetryXPos = x.coerceIn(0f, engine.width.toFloat())
        } else if (draggingGuide == 2) {
            engine.symmetryYPos = y.coerceIn(0f, engine.height.toFloat())
        }
        touchProperties()
        requestState()
    }

    // --------------------------------------------------------------- strokes

    private fun beginStroke(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        eraserButton: Boolean
    ) {
        val layer = engine.activeLayer ?: return
        if (!layer.visible) return
        if (!editable(layer)) return
        if (adjusting) commitAdjustmentInternal()

        var brush = activePreset
        if (eraserButton && brush.category != BrushCategory.Eraser) {
            brush = presets.firstOrNull { it.category == BrushCategory.Eraser } ?: brush
        }
        // Smudging a coverage mask has no sensible meaning; treat it as a no-op.
        if (layer.editMask && layer.hasMask && brush.category == BrushCategory.Smudge) return
        strokeLayer = layer
        strokePreset = brush
        strokeStartX = x
        strokeStartY = y
        strokeLastX = x
        strokeLastY = y
        quickShaping = false
        painter.begin(layer, brush, color, x, y, pressure, tilt, assistStrength = assist)
        FrameLogger.strokeActive = true
        // Cache the layers below for the tiles this stroke touches, so a frame
        // composites the stroke instead of the whole stack.
        engine.beginStrokeCache()
    }

    /**
     * Replaces the freehand stroke with a straight line from its origin to the
     * current point. The pre-stroke snapshot is kept, so this is cheap enough
     * to run on every pointer move while the user adjusts the line.
     */
    private fun redrawQuickStroke() {
        val layer = strokeLayer ?: return
        val brush = strokePreset ?: return
        painter.cancel()
        // Smoothing would lag the single move call, so the snapped line must
        // use a non-streamlined version of the brush.
        painter.begin(
            layer, brush.copy(smoothing = 0f), color,
            strokeStartX, strokeStartY, 1f, 0f,
            captureBase = false, assistStrength = 0f
        )
        painter.move(strokeLastX, strokeLastY, 1f, 0f)
    }

    private fun endStroke() {
        val layer = strokeLayer
        val brush = strokePreset
        val tileCapture = painter.tileCapture
        painter.end()
        painter.clearTileCapture()
        FrameLogger.strokeActive = false
        engine.endStrokeCache()
        val touched = Rect(painter.dirty)
        painter.dirty.setEmpty()
        if (layer != null && !touched.isEmpty) {
            val (label, kind) = when (brush?.category) {
                BrushCategory.Eraser -> "Erase stroke" to EditKind.Erase
                BrushCategory.Smudge -> "Smudge stroke" to EditKind.Stroke
                else -> "Brush stroke" to EditKind.Stroke
            }
            val patchText = if (quickShaping) "QuickShape line" else label
            val sizePx = (brush?.size ?: 0f).roundToInt()
            val detail = if (brush != null) "${brush.name} · $sizePx px" else ""
            val swatch = if (brush?.category == BrushCategory.Eraser) 0 else color
            if (tileCapture != null) {
                engine.makeTilePatch(layer, tileCapture, touched, patchText, kind, detail, swatch)?.let {
                    history.push(it)
                }
            } else {
                engine.makePatch(layer, touched, patchText, kind, detail, swatch)?.let {
                    history.push(it)
                }
            }
            layer.touch()
            // The colour only becomes "recent" once it has been used.
            if (brush?.category == BrushCategory.Brush) addRecent(swatch)
        } else {
            tileCapture?.release()
        }
        strokeLayer = null
        strokePreset = null
        quickShaping = false
        TileCache.active?.let { if (it.evictEveryOp) it.evictTo() }
        requestState()
    }

    // ------------------------------------------------------------- selection

    private fun beginSelectionOrMove(x: Float, y: Float) {
        val region = engine.selection
        val inside = region != null && region.contains(x.toInt(), y.toInt())
        if (selectionShape == SelectionShape.Automatic) {
            // A tap always re-runs the wand, even inside an existing selection;
            // if the finger then drags, the gesture turns into moving the
            // outline instead (see the move handler).
            autoSelectPending = true
            autoSelectX = x
            autoSelectY = y
            autoSelectCanMove = inside
            if (inside) {
                selectionMoveBase = Region(region!!)
                selectionMoveStartX = x
                selectionMoveStartY = y
            }
            return
        }
        if (inside) {
            movingSelection = true
            selectionMoveBase = Region(region!!)
            selectionMoveStartX = x
            selectionMoveStartY = y
            return
        }
        beginSelection(x, y)
    }

    private fun moveSelection(x: Float, y: Float) {
        val base = selectionMoveBase ?: return
        val dx = (x - selectionMoveStartX).roundToInt()
        val dy = (y - selectionMoveStartY).roundToInt()
        val moved = Region(base)
        moved.translate(dx, dy)
        engine.setSelection(moved)
    }

    private fun beginSelection(x: Float, y: Float) {
        if (selectionShape == SelectionShape.Automatic) {
            // Defer the flood fill until the finger lifts. A two-finger pan
            // cancels the gesture, so panning never re-runs the wand.
            autoSelectPending = true
            autoSelectX = x
            autoSelectY = y
            return
        }
        selecting = true
        selectStartX = x
        selectStartY = y
        selectLastX = x
        selectLastY = y
        val path = Path()
        if (selectionShape == SelectionShape.Lasso) path.moveTo(x, y)
        selectingPath = path
        selectionPreview = path
    }

    private fun updateSelection(x: Float, y: Float) {
        val path = selectingPath ?: return
        when (selectionShape) {
            SelectionShape.Rectangle -> {
                path.reset()
                path.addRect(
                    min(selectStartX, x), min(selectStartY, y),
                    max(selectStartX, x), max(selectStartY, y),
                    Path.Direction.CW
                )
            }
            SelectionShape.Ellipse -> {
                path.reset()
                path.addOval(
                    RectF(
                        min(selectStartX, x), min(selectStartY, y),
                        max(selectStartX, x), max(selectStartY, y)
                    ),
                    Path.Direction.CW
                )
            }
            SelectionShape.Lasso -> {
                if (hypot(x - selectLastX, y - selectLastY) > 1.5f) {
                    path.lineTo(x, y)
                    selectLastX = x
                    selectLastY = y
                }
            }
            SelectionShape.Automatic -> Unit
        }
        selectionPreview = path
    }

    private fun endSelection() {
        val path = selectingPath
        selecting = false
        selectingPath = null
        selectionPreview = null
        if (path == null) return
        if (selectionShape == SelectionShape.Lasso) path.close()
        val region = SelectionUtil.pathToRegion(path, engine.width, engine.height)
        engine.applySelection(region, selectionOp)
        // Stay in the selection tool so the outline can be dragged around;
        // picking a brush clips painting to the selection.
        requestState()
    }

    /** Runs the deferred magic wand for a completed tap. */
    private fun runAutoSelect() {
        if (!autoSelectPending) return
        autoSelectPending = false
        autoSelectCanMove = false
        selectionMoveBase = null
        val x = autoSelectX.toInt().coerceIn(0, engine.width - 1)
        val y = autoSelectY.toInt().coerceIn(0, engine.height - 1)
        val step = if (engine.width.toLong() * engine.height > 1_000_000L) 2 else 1
        val grid = engine.compositeGrid(step)
        val region = try {
            SelectionUtil.magicWandGrid(grid, x, y, wandTolerance)
        } finally {
            grid.release()
        }
        engine.applySelection(region, selectionOp)
        requestState()
    }

    // ----------------------------------------------------------- transform

    private fun beginTransformDrag(x: Float, y: Float) {
        if (transform == null) return
        draggingTransform = true
        dragLastDocX = x
        dragLastDocY = y
    }

    private fun startTransform() {
        val layer = engine.activeLayer ?: return
        if (!editable(layer)) return
        if (transform != null) return
        cancelAdjustmentInternal()
        transform = TransformSession(engine, layer, engine.selection)
        engine.markDirtyAll()
        requestState()
    }

    private fun commitTransformInternal() {
        val current = transform ?: return
        val action = current.commit()
        transform = null
        if (action != null) history.push(action)
        engine.markDirtyAll()
        requestState()
    }

    private fun cancelTransformInternal() {
        transform?.cancel()
        transform = null
        engine.markDirtyAll()
        requestState()
    }

    fun commitTransform() = post { commitTransformInternal() }

    fun cancelTransform() = post { cancelTransformInternal() }

    fun flipTransform(horizontal: Boolean) {
        post {
            val t = transform ?: return@post
            if (horizontal) t.flipHorizontal() else t.flipVertical()
            requestState()
        }
    }

    // -------------------------------------------------------------- tools

    fun chooseTool(newTool: Tool) {
        if (newTool == tool) return
        // Leaving the transform tool commits what the user arranged.
        if (tool == Tool.Transform && transform != null) {
            post { commitTransformInternal() }
        }
        if (adjusting) {
            post { commitAdjustmentInternal() }
        }
        tool = newTool
        when (newTool) {
            Tool.Brush -> category = BrushCategory.Brush
            Tool.Eraser -> category = BrushCategory.Eraser
            Tool.Smudge -> category = BrushCategory.Smudge
            Tool.Selection, Tool.Transform, Tool.Fill, Tool.Gradient -> Unit
        }
        if (newTool == Tool.Brush || newTool == Tool.Eraser || newTool == Tool.Smudge) {
            val list = presetsOf(category)
            if (list.none { it.id == activePresetId }) {
                activePresetId = list.first().id
            }
        }
        if (newTool == Tool.Transform) {
            post { startTransform() }
        }
        requestState()
    }

    fun selectPreset(id: String) {
        activePresetId = id
        presets.firstOrNull { it.id == id }?.let { p ->
            when (p.category) {
                BrushCategory.Brush -> tool = Tool.Brush
                BrushCategory.Eraser -> tool = Tool.Eraser
                BrushCategory.Smudge -> tool = Tool.Smudge
            }
            category = p.category
        }
        requestState()
    }

    fun updatePreset(block: (BrushPreset) -> BrushPreset) {
        val id = activePresetId
        presets = presets.map { if (it.id == id) block(it) else it }
        onPresetsChanged?.invoke(presets)
        requestState()
    }

    fun chooseColorMode(mode: ColorMode) {
        colorMode = mode
        requestState()
    }

    /** Restores a built-in brush to its factory settings. */
    fun resetActivePreset() {
        val current = presets.firstOrNull { it.id == activePresetId } ?: return
        if (!current.builtIn) return
        val factory = BrushLibrary.defaults.firstOrNull { it.id == current.id } ?: return
        presets = presets.map { if (it.id == current.id) factory else it }
        onPresetsChanged?.invoke(presets)
        requestState()
    }

    /** Saves the current settings as a new preset in the active family. */
    fun savePresetAs(name: String) {
        val source = activePreset
        val id = "user-${System.currentTimeMillis()}"
        val created = source.copy(
            id = id,
            name = name.ifBlank { "Custom" },
            builtIn = false
        )
        val index = presets.indexOfFirst { it.id == source.id }
        val list = presets.toMutableList()
        list.add(if (index >= 0) index + 1 else list.size, created)
        presets = list
        activePresetId = id
        onPresetsChanged?.invoke(presets)
        requestState()
    }

    /** Removes a user preset (built-ins cannot be deleted). */
    fun deleteActivePreset() {
        val current = presets.firstOrNull { it.id == activePresetId } ?: return
        if (current.builtIn) return
        val remaining = presets.filter { it.id != current.id }
        if (remaining.isEmpty()) return
        presets = remaining
        val sameFamily = remaining.filter { it.category == current.category }
        activePresetId = (sameFamily.firstOrNull() ?: remaining.first()).id
        onPresetsChanged?.invoke(presets)
        requestState()
    }

    fun setBrushSize(value: Float) =
        updatePreset { it.copy(size = value.coerceIn(1f, 900f)) }

    fun setBrushOpacity(value: Float) =
        updatePreset { it.copy(opacity = value.coerceIn(0.02f, 1f)) }

    fun updateAssist(value: Float) {
        assist = value.coerceIn(0f, 1f)
        requestState()
    }

    fun setQuickShape(value: Boolean) {
        quickShapeEnabled = value
        requestState()
    }

    fun chooseColor(value: Int) {
        if (Color.alpha(value) == 0) return
        // Picking a colour does not make it recent; only using it on the canvas
        // does (see endStroke / fillLayer).
        color = value
        requestState()
    }

    private fun addRecent(value: Int) {
        if (recentColors.firstOrNull() == value) return
        recentColors.remove(value)
        recentColors.add(0, value)
        while (recentColors.size > 12) recentColors.removeAt(recentColors.size - 1)
    }

    private fun sampleColor(x: Float, y: Float) {
        val px = x.toInt().coerceIn(0, engine.width - 1)
        val py = y.toInt().coerceIn(0, engine.height - 1)
        val sampled = if (eyedropperLayerOnly) {
            engine.activeLayer?.tiles?.getPixel(px, py) ?: return
        } else {
            engine.compositePixel(px, py)
        }
        if (Color.alpha(sampled) == 0) return
        color = sampled
        requestState()
    }

    fun chooseEyedropperSource(layerOnly: Boolean) {
        eyedropperLayerOnly = layerOnly
        requestState()
    }

    fun chooseFillTolerance(value: Int) {
        fillTolerance = value.coerceIn(1, 100)
        requestState()
    }

    fun chooseFillLayerOnly(value: Boolean) {
        fillLayerOnly = value
        requestState()
    }

    fun chooseFillSelection(value: Boolean) {
        fillSelection = value
        requestState()
    }

    fun chooseFillExpand(value: Int) {
        fillExpand = value.coerceIn(0, 4)
        requestState()
    }

    /** Paint bucket at a document point (harness / programmatic use). */
    fun fillAt(x: Float, y: Float, color: Int = this.color) = post { fillAtInternal(x, y, color, false) }

    /**
     * ColorDrop: the colour chip was dragged onto the canvas and released at
     * view coordinates ([vx], [vy]). Fills the region under it with the current
     * colour, sampled from every visible layer as Procreate does.
     */
    fun colorDropAt(vx: Float, vy: Float) = post {
        val doc = FloatArray(2)
        view.toDocument(vx, vy, doc)
        fillAtInternal(doc[0], doc[1], color, true)
    }

    /**
     * Flood-fills the region around (x, y) on the active layer (or its mask).
     *
     * The region comes from the streaming wand grid at full resolution, over
     * the composite or the current layer only, so a fill never materialises a
     * canvas-sized bitmap. It is grown by [fillExpand] px so anti-aliased line
     * edges do not leave a halo, clipped to the selection, and painted per tile
     * through the store's write canvas with an integer-rect clip path, which is
     * exact and identical on the dense and the sparse store.
     */
    private fun fillAtInternal(fx: Float, fy: Float, fillColor: Int, compositeOnly: Boolean) {
        val layer = engine.activeLayer ?: return
        if (!editable(layer)) return
        if (adjusting) commitAdjustmentInternal()
        val x = fx.toInt()
        val y = fy.toInt()
        if (x !in 0 until engine.width || y !in 0 until engine.height) return
        val store = layer.paintStore
        val mask = layer.editMask && layer.hasMask
        val sel = engine.selection
        val region: android.graphics.Region
        if (fillSelection && sel != null && sel.contains(x, y)) {
            region = android.graphics.Region(sel)
        } else {
            val grid = if (fillLayerOnly && !compositeOnly) {
                WandGrid(
                    engine.width, engine.height, 1,
                    store as? SparseTileStore,
                    if (store.isSparse) null else store.sharedBitmap(),
                    if (store.isSparse) store.defaultPixel else 0
                )
            } else {
                engine.compositeGrid(1)
            }
            region = try {
                SelectionUtil.magicWandGrid(grid, x, y, fillTolerance)
            } finally {
                grid.release()
            }
            if (fillExpand > 0 && !region.isEmpty) {
                val grown = android.graphics.Region(region)
                val shifted = android.graphics.Region()
                for (d in 1..fillExpand) {
                    for ((dx, dy) in listOf(d to 0, -d to 0, 0 to d, 0 to -d)) {
                        shifted.set(region)
                        shifted.translate(dx, dy)
                        grown.op(shifted, android.graphics.Region.Op.UNION)
                    }
                }
                region.set(grown)
            }
            region.op(0, 0, engine.width, engine.height, android.graphics.Region.Op.INTERSECT)
            if (sel != null) region.op(sel, android.graphics.Region.Op.INTERSECT)
        }
        if (region.isEmpty) return
        val bounds = Rect(region.bounds)
        if (!bounds.intersect(0, 0, engine.width, engine.height)) return
        val capture = TileOps.capture(store, bounds)
        val path = SelectionUtil.regionFillPath(region)
        val paint = android.graphics.Paint().apply {
            // Masks are alpha coverage (composited with DST_IN): a mask fill
            // hides, exactly like painting a mask (DST_OUT) does.
            color = if (mask) 0xFF000000.toInt() else fillColor
            isAntiAlias = false
            blendMode = when {
                mask -> android.graphics.BlendMode.DST_OUT
                layer.alphaLock -> android.graphics.BlendMode.SRC_ATOP
                else -> android.graphics.BlendMode.SRC_OVER
            }
        }
        store.withWriteCanvas(bounds) { c ->
            val save = c.save()
            c.clipPath(path)
            c.drawPaint(paint)
            c.restoreToCount(save)
        }
        engine.makeTilePatch(
            layer, capture, bounds, "Fill", EditKind.LayerFill, "Tolerance $fillTolerance%", fillColor
        )?.let { history.push(it) }
        layer.touch()
        engine.markDirty(bounds)
        if (!mask) addRecent(fillColor)
        requestState()
    }

    fun chooseGradientKind(kind: Gradients.Kind) {
        gradientKind = kind
        requestState()
    }

    fun chooseGradientSecond(value: Int) {
        gradientSecond = value
        requestState()
    }

    /** Swaps the current colour and the gradient's end colour. */
    fun swapGradientColors() {
        val second = gradientSecond
        // A transparent end colour swaps to an opaque start: keep its RGB.
        gradientSecond = color
        color = if ((second ushr 24) == 0) second or 0xFF000000.toInt() else second
        requestState()
    }

    /** Applies a gradient from (x0, y0) to (x1, y1) in document px (harness / API). */
    fun applyGradient(x0: Float, y0: Float, x1: Float, y1: Float) = post {
        applyGradientInternal(x0, y0, x1, y1)
    }

    /**
     * Paints the gradient over the whole active layer (or mask), clipped to the
     * selection. The pixels are generated 256 px at a time and blitted 1:1, so
     * neither store needs a canvas-sized bitmap and both produce the same bytes.
     */
    private fun applyGradientInternal(x0: Float, y0: Float, x1: Float, y1: Float) {
        val layer = engine.activeLayer ?: return
        if (!editable(layer)) return
        if (adjusting) commitAdjustmentInternal()
        val store = layer.paintStore
        val mask = layer.editMask && layer.hasMask
        val sel = engine.selection
        val bounds = Rect(0, 0, engine.width, engine.height)
        if (sel != null && !bounds.intersect(sel.bounds)) return
        val capture = TileOps.capture(store, bounds)
        val selPath = sel?.let { SelectionUtil.regionFillPath(it) }
        val c1 = color
        val c2 = gradientSecond
        val kind = gradientKind
        val paint = android.graphics.Paint().apply {
            isFilterBitmap = false
            blendMode = when {
                mask -> android.graphics.BlendMode.DST_OUT
                layer.alphaLock -> android.graphics.BlendMode.SRC_ATOP
                else -> android.graphics.BlendMode.SRC_OVER
            }
        }
        val clip = Rect()
        val chunk = 256
        val px = IntArray(chunk * chunk)
        store.withWriteCanvas(bounds) { c ->
            if (!c.getClipBounds(clip)) return@withWriteCanvas
            val save = c.save()
            if (selPath != null) c.clipPath(selPath)
            var cy = clip.top
            while (cy < clip.bottom) {
                val ch = minOf(chunk, clip.bottom - cy)
                var cx = clip.left
                while (cx < clip.right) {
                    val cw = minOf(chunk, clip.right - cx)
                    Gradients.render(kind, x0, y0, x1, y1, c1, c2, cx, cy, cw, ch, px)
                    val bmp = TileOps.scratch(cw, ch)
                    bmp.setPixels(px, 0, cw, 0, 0, cw, ch)
                    c.drawBitmap(bmp, cx.toFloat(), cy.toFloat(), paint)
                    cx += cw
                }
                cy += ch
            }
            c.restoreToCount(save)
        }
        engine.makeTilePatch(
            layer, capture, bounds, "Gradient", EditKind.LayerFill, kind.label, c1
        )?.let { history.push(it) }
        layer.touch()
        engine.markDirty(bounds)
        if (!mask) addRecent(c1)
        requestState()
    }

    fun chooseWandTolerance(value: Int) {
        wandTolerance = value.coerceIn(1, 100)
        requestState()
    }

    fun toggleChrome() {
        chromeVisible = !chromeVisible
        requestState()
    }

    // ------------------------------------------------------------- layers

    private fun pushStructural(before: DocumentState, label: String, kind: EditKind) {
        val after = engine.snapshotState()
        history.push(LayersAction(before, after, label, kind))
        engine.markDirtyAll()
        requestState()
    }

    /** Locked layers, and layers inside a hidden or locked group, refuse edits. */
    private fun editable(layer: Layer): Boolean {
        if (layer.locked) return false
        if (!layer.visible) return false
        val group = engine.groupById(layer.groupId)
        return group == null || (group.visible && !group.locked)
    }

    fun addLayer() = post {
        if (!MemoryBudget.fitsDocument(engine.width, engine.height, engine.layers.size + 1)) {
            onMessage?.invoke(
                MemoryBudget.documentRefusal(engine.width, engine.height, engine.layers.size + 1)
            )
            return@post
        }
        val before = engine.snapshotState()
        engine.newLayer(at = engine.activeIndex + 1)
        pushStructural(before, "Add layer", EditKind.LayerAdd)
    }

    /** Adds an imported bitmap as a new layer, scaled to fit the canvas. */
    fun addImageLayer(source: Bitmap, name: String) = post {
        if (!MemoryBudget.fitsDocument(engine.width, engine.height, engine.layers.size + 1)) {
            onMessage?.invoke(
                MemoryBudget.documentRefusal(engine.width, engine.height, engine.layers.size + 1)
            )
            return@post
        }
        val before = engine.snapshotState()
        val scaled = fitToCanvas(source, engine.width, engine.height)
        engine.newLayer(name = name, bitmap = scaled, at = engine.activeIndex + 1)
        pushStructural(before, "Import image", EditKind.Import)
    }

    private fun fitToCanvas(source: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(source, null, RectF(left, top, left + w, top + h), p)
        return out
    }

    fun duplicateLayer() = post {
        val id = engine.activeLayer?.id ?: return@post
        val src = engine.layerById(id) ?: return@post
        // The copy is a new full-canvas surface; decoding a swapped-out source
        // adds one more. Refuse rather than risk an OOM.
        val extra = 1 + if (src.isResident) 0 else 1
        if (!engine.canAfford(extra)) {
            onMessage?.invoke(MemoryBudget.decodeRefusal(engine.width, engine.height, extra))
            return@post
        }
        val before = engine.snapshotState()
        engine.duplicateLayer(id)
        pushStructural(before, "Duplicate layer", EditKind.LayerDuplicate)
    }

    fun mergeDown() = post {
        val id = engine.activeLayer?.id ?: return@post
        val extra = engine.mergeCost(id)
        if (extra > 0 && !engine.canAfford(extra)) {
            onMessage?.invoke(MemoryBudget.decodeRefusal(engine.width, engine.height, extra))
            return@post
        }
        val before = engine.snapshotState()
        if (engine.mergeDown(id)) {
            // Replayable: redo recomputes the merge from the restored layers.
            history.push(MergeAction(before, upperId = id, label = "Merge down"))
            engine.markDirtyAll()
            requestState()
        }
    }

    fun deleteLayer() = post {
        val id = engine.activeLayer?.id ?: return@post
        val before = engine.snapshotState()
        if (engine.deleteLayer(id)) {
            pushStructural(before, "Delete layer", EditKind.LayerDelete)
        }
    }

    fun clearLayer() = post {
        val layer = engine.activeLayer ?: return@post
        if (!editable(layer)) return@post
        val mask = layer.editMask && layer.hasMask
        val store = layer.paintStore
        val bounds = editBounds(store)
        if (bounds.isEmpty) {
            requestState()
            return@post
        }
        val capture = TileOps.capture(store, bounds)
        engine.clearLayer(layer.id)
        engine.makeTilePatch(
            layer, capture, bounds,
            if (mask) "Clear mask" else "Clear layer", EditKind.LayerClear
        )?.let { history.push(it) }
        requestState()
    }

    fun fillLayer(color: Int) = post {
        val layer = engine.activeLayer ?: return@post
        if (!editable(layer)) return@post
        val mask = layer.editMask && layer.hasMask
        val store = layer.paintStore
        val bounds = Rect(0, 0, engine.width, engine.height)
        val capture = TileOps.capture(store, bounds)
        engine.fillLayer(layer.id, color)
        engine.makeTilePatch(
            layer, capture, bounds, "Fill layer", EditKind.LayerFill, "", color
        )?.let { history.push(it) }
        if (!mask) addRecent(color)
        requestState()
    }

    fun selectLayer(id: Long) = post {
        if (adjusting) commitAdjustmentInternal()
        engine.selectLayer(id)
        requestState()
    }

    fun selectGroup(id: Long) = post {
        if (adjusting) commitAdjustmentInternal()
        engine.selectGroup(id)
        requestState()
    }

    fun setLayerOpacity(id: Long, value: Float) = post {
        val layer = engine.layerById(id) ?: return@post
        // Inside a group the group's opacity governs the layer, so the slider is
        // read-only for its children.
        if (layer.groupId != 0L) return@post
        beginPropertyEdit(id, "Layer opacity")
        layer.opacity = value.coerceIn(0f, 1f)
        touchProperties()
        engine.markDirtyAll()
        requestState()
    }

    /** Captures the state before a slider drag so it becomes one undo step. */
    private fun beginPropertyEdit(id: Long, label: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (propEditBefore == null || propEditTarget != id || now - propEditLastMs > 1500L) {
            propEditBefore = engine.snapshotState()
            propEditTarget = id
            propEditLabel = label
        }
        propEditLastMs = now
    }

    /** Called when the slider is released: records the change in history. */
    fun commitPropertyEdit() = post {
        val before = propEditBefore ?: return@post
        propEditBefore = null
        history.push(
            LayersAction(before, engine.snapshotState(), propEditLabel, EditKind.Opacity)
        )
        requestState()
    }

    fun setLayerBlend(id: Long, blend: LayerBlend) = post {
        engine.layerById(id)?.let {
            it.blend = blend
            touchProperties()
            engine.markDirtyAll()
            requestState()
        }
    }

    fun toggleLayerVisibility(id: Long) = post {
        val layer = engine.layerById(id) ?: return@post
        if (!layer.visible) {
            // Showing a swapped-out layer decodes its pixels (and mask).
            var extra = 1
            if (layer.maskEnabled && layer.hasMask && !layer.maskResident) extra++
            if (!engine.canAfford(extra)) {
                onMessage?.invoke(MemoryBudget.decodeRefusal(engine.width, engine.height, extra))
                return@post
            }
        }
        layer.visible = !layer.visible
        touchProperties()
        engine.markDirtyAll()
        requestState()
    }

    fun toggleAlphaLock(id: Long) = post {
        engine.layerById(id)?.let {
            it.alphaLock = !it.alphaLock
            touchProperties()
            requestState()
        }
    }

    fun toggleLayerLock(id: Long) = post {
        engine.layerById(id)?.let {
            it.locked = !it.locked
            touchProperties()
            requestState()
        }
    }

    fun toggleClipping(id: Long) = post {
        engine.layerById(id)?.let {
            it.clipping = !it.clipping
            touchProperties()
            engine.markDirtyAll()
            requestState()
        }
    }

    // ----------------------------------------------------------- masks

    fun addLayerMask() = post {
        val layer = engine.activeLayer ?: return@post
        val before = engine.snapshotState()
        if (engine.addMask(layer)) {
            pushStructural(before, "Add layer mask", EditKind.LayerMask)
        }
    }

    fun removeLayerMask() = post {
        val layer = engine.activeLayer ?: return@post
        if (!layer.hasMask) return@post
        val before = engine.snapshotState()
        engine.removeMask(layer)
        pushStructural(before, "Remove layer mask", EditKind.LayerMask)
    }

    fun toggleEditMask() = post {
        val layer = engine.activeLayer ?: return@post
        if (!layer.hasMask) return@post
        layer.editMask = !layer.editMask
        layer.touch()
        requestState()
    }

    fun toggleMaskEnabled() = post {
        val layer = engine.activeLayer ?: return@post
        if (!layer.hasMask) return@post
        layer.maskEnabled = !layer.maskEnabled
        layer.touch()
        touchProperties()
        engine.markDirtyAll()
        requestState()
    }

    // ---------------------------------------------------------- groups

    /** Wraps the active layer in a group, or focuses the group it is in. */
    fun groupActiveLayer() = post {
        val id = engine.activeLayer?.id ?: return@post
        val before = engine.snapshotState()
        if (engine.groupLayer(id) != null) {
            pushStructural(before, "Group layer", EditKind.LayerOrder)
        }
    }

    fun ungroupActive() = post {
        val gid = engine.activeGroupId.takeIf { it != 0L }
            ?: engine.activeLayer?.groupId ?: 0L
        if (gid == 0L) return@post
        val before = engine.snapshotState()
        if (engine.ungroup(gid)) pushStructural(before, "Ungroup", EditKind.LayerOrder)
    }

    fun setGroupOpacity(id: Long, value: Float) = post {
        engine.groupById(id)?.let {
            beginPropertyEdit(id, "Group opacity")
            it.opacity = value.coerceIn(0f, 1f)
            touchProperties()
            engine.markDirtyAll()
            requestState()
        }
    }

    fun setGroupBlend(id: Long, blend: LayerBlend) = post {
        engine.groupById(id)?.let {
            it.blend = blend
            touchProperties()
            engine.markDirtyAll()
            requestState()
        }
    }

    fun toggleGroupVisibility(id: Long) = post {
        engine.groupById(id)?.let {
            it.visible = !it.visible
            touchProperties()
            engine.markDirtyAll()
            requestState()
        }
    }

    fun toggleGroupLock(id: Long) = post {
        engine.groupById(id)?.let {
            it.locked = !it.locked
            touchProperties()
            requestState()
        }
    }

    fun toggleGroupExpanded(id: Long) = post {
        engine.groupById(id)?.let {
            it.expanded = !it.expanded
            touchProperties()
            requestState()
        }
    }

    fun renameGroup(id: Long, name: String) = post {
        engine.groupById(id)?.let {
            it.name = name.ifBlank { "Group" }
            touchProperties()
            requestState()
        }
    }

    /** Shows only the active layer, or everything if it is already isolated. */
    fun toggleSoloLayer() = post {
        val id = engine.activeLayer?.id ?: return@post
        engine.setSoloLayer(if (engine.soloLayerId == id) null else id)
        requestState()
    }

    fun renameLayer(id: Long, name: String) = post {
        engine.layerById(id)?.let {
            it.name = name.ifBlank { "Layer" }
            touchProperties()
            requestState()
        }
    }

    /** Moves a layer to an absolute position in the stack (engine order). */
    fun moveLayerTo(id: Long, targetIndex: Int) = post {
        val from = engine.indexOf(id)
        if (from < 0) return@post
        val to = targetIndex.coerceIn(0, engine.layers.size - 1)
        if (from == to) return@post
        val before = engine.snapshotState()
        engine.moveLayer(from, to)
        pushStructural(before, "Reorder layers", EditKind.LayerOrder)
    }

    /** Moves a layer one slot up (delta > 0) or down in the stack. */
    fun nudgeLayer(id: Long, delta: Int) = post {
        val from = engine.indexOf(id)
        if (from < 0) return@post
        val to = (from + delta).coerceIn(0, engine.layers.size - 1)
        if (from == to) return@post
        val before = engine.snapshotState()
        engine.moveLayer(from, to)
        pushStructural(before, "Reorder layers", EditKind.LayerOrder)
    }

    fun setBackgroundColor(value: Int) = post {
        engine.backgroundColor = value
        touchProperties()
        engine.markDirtyAll()
        requestState()
    }

    fun setSymmetry(x: Boolean, y: Boolean) = post {
        engine.symmetryX = x
        engine.symmetryY = y
        touchProperties()
        requestState()
    }

    fun setSymmetryLocked(value: Boolean) = post {
        engine.symmetryLocked = value
        touchProperties()
        requestState()
    }

    fun centerSymmetry() = post {
        engine.symmetryXPos = engine.width / 2f
        engine.symmetryYPos = engine.height / 2f
        touchProperties()
        requestState()
    }

    fun setFingerPainting(value: Boolean) {
        fingerPaint = value
        requestState()
    }

    /**
     * Allocates the big scratch surfaces up front so the first stroke or the
     * first adjustment does not allocate them inside a gesture. Called when a
     * document opens and when a painting tool is picked.
     */
    fun warmup() = post {
        engine.reserveScratch()
        painter.preallocate()
    }

    /**
     * Debug switch (debuggable builds only): documents created or opened from
     * now on store their pixels as sparse tiles. The open document keeps the
     * storage it was loaded with, because the engine captures the mode when the
     * store is created.
     */
    fun setSparseLayers(value: Boolean) = post {
        TileStoreConfig.useSparseLayers = value
        onMessage?.invoke(
            if (value) "Sparse layers on for documents opened from now on"
            else "Sparse layers off for documents opened from now on"
        )
        requestState()
    }

    fun chooseSelectionShape(shape: SelectionShape) {
        selectionShape = shape
        requestState()
    }

    fun chooseSelectionOp(op: SelectionOp) {
        selectionOp = op
        requestState()
    }

    fun selectAll() = post {
        engine.selectAll()
        requestState()
    }

    fun clearSelection() = post {
        engine.clearSelection()
        requestState()
    }

    fun invertSelection() = post {
        engine.invertSelection()
        requestState()
    }

    fun undo() = post { doUndo() }

    fun redo() = post { doRedo() }

    private fun doUndo() {
        if (transform != null) cancelTransformInternal()
        cancelAdjustmentInternal()
        val action = history.peekUndo() ?: return
        if (!ensureHistoryBudget(action, undo = true)) return
        if (history.undo(engine)) {
            engine.markDirtyAll()
            requestState()
        }
    }

    private fun doRedo() {
        val action = history.peekRedo() ?: return
        if (!ensureHistoryBudget(action, undo = false)) return
        if (history.redo(engine)) {
            engine.markDirtyAll()
            requestState()
        }
    }

    /**
     * Refuses a history step that would have to decode more layers than the
     * budget allows, instead of letting the render thread run out of memory.
     */
    private fun ensureHistoryBudget(action: HistoryAction, undo: Boolean): Boolean {
        val target = if (undo) action.undoState() else action.redoState()
        if (target != null) {
            if (engine.canAffordState(target)) return true
            val extra = (engine.stateBitmapNeed(target) - engine.visibleBitmapNeed())
                .coerceAtLeast(1)
            onMessage?.invoke(MemoryBudget.decodeRefusal(engine.width, engine.height, extra))
            return false
        }
        val extra = when (action) {
            is PixelAction -> {
                val layer = engine.layerById(action.layerId)
                var n = 0
                if (layer != null && !layer.isResident) n++
                if (action.mask && layer != null && !layer.maskResident) n++
                n
            }
            is TilePixelAction -> {
                val layer = engine.layerById(action.layerId)
                var n = 0
                if (layer != null && !layer.isResident) n++
                if (action.mask && layer != null && !layer.maskResident) n++
                n
            }
            is MergeAction -> engine.mergeCost(action.upperId)
            else -> 0
        }
        if (extra == 0 || engine.canAfford(extra)) return true
        onMessage?.invoke(MemoryBudget.decodeRefusal(engine.width, engine.height, extra))
        return false
    }

    /** Conservative check for jump/delete, which may visit many states. */
    private fun historyFits(): Boolean {
        // Sparse states hold tiles, not full-canvas bitmaps.
        if (engine.compositeIsSparse) return true
        val need = history.maxStateBitmapNeed(engine)
        if (MemoryBudget.fitsBitmaps(engine.width, engine.height, need)) return true
        val extra = (need - engine.visibleBitmapNeed()).coerceAtLeast(1)
        onMessage?.invoke(MemoryBudget.decodeRefusal(engine.width, engine.height, extra))
        return false
    }

    // ------------------------------------------------------------- history

    fun revertHistory(index: Int) = post {
        if (transform != null) cancelTransformInternal()
        cancelAdjustmentInternal()
        if (!historyFits()) return@post
        history.revertTo(engine, index + 1)
        engine.markDirtyAll()
        requestState()
    }

    fun deleteHistoryAt(index: Int) = post {
        if (transform != null) transform = null
        cancelAdjustmentInternal()
        if (!historyFits()) return@post
        history.deleteAt(engine, index)
        engine.markDirtyAll()
        requestState()
    }

    fun clearHistory() = post {
        history.clear()
        requestState()
    }

    // -------------------------------------------------------- adjustments

    /**
     * Document region an adjustment can change. A transparent store only has
     * content inside its non-empty bounds; a mask (default opaque) covers the
     * whole canvas, because every untouched pixel is opaque white.
     */
    private fun editBounds(store: TileStore): Rect {
        val full = Rect(0, 0, engine.width, engine.height)
        if (!store.isSparse || store.defaultPixel != 0) return full
        val b = store.nonEmptyBounds()
        if (!b.intersect(0, 0, engine.width, engine.height)) return Rect()
        return b
    }

    /** Largest blur halo a preview or a bake can reach (3 passes x 24 px). */
    private val blurHalo = 3 * 24 + 2

    private fun growBounds(r: Rect, by: Int): Rect {
        val out = Rect(r)
        out.left = max(0, out.left - by)
        out.top = max(0, out.top - by)
        out.right = min(engine.width, out.right + by)
        out.bottom = min(engine.height, out.bottom + by)
        return out
    }

    /** Applies [block] per tile over the store's content and records it. */
    private fun pixelEdit(layer: Layer, label: String, block: (Bitmap) -> Unit) {
        val store = layer.paintStore
        val bounds = editBounds(store)
        if (bounds.isEmpty) {
            requestState()
            return
        }
        val capture = TileOps.capture(store, bounds)
        TileOps.forEachTile(store, bounds, 0) { patch, pad, tile ->
            block(patch)
            TileOps.writePart(store, patch, pad, tile)
        }
        engine.makeTilePatch(layer, capture, bounds, label, EditKind.Adjust)?.let { history.push(it) }
        layer.touch()
        engine.markDirtyAll()
        requestState()
    }

    /**
     * Live preview requests are coalesced: the UI only records the latest
     * value and the render loop picks it up once per frame. Without this a
     * slider drag floods the queue with expensive proxy rebuilds.
     */
    fun previewAdjustment(kind: AdjustmentKind, amount: Float) {
        pendingPreviewKind = kind
        pendingPreviewAmount = amount
        hasPendingPreview = true
        wakeListener?.invoke()
    }

    /** Called on the render thread once per frame. */
    fun consumePendingPreview(): Boolean {
        if (!hasPendingPreview) return false
        hasPendingPreview = false
        val kind = pendingPreviewKind ?: return false
        applyPreviewInternal(kind, pendingPreviewAmount)
        return true
    }

    private fun applyPreviewInternal(kind: AdjustmentKind, amount: Float) {
        val layer = engine.activeLayer ?: return
        if (!editable(layer)) return
        val store = layer.paintStore
        if (!adjusting) {
            // A blur writes up to 3 * radius past the content and reads another
            // 3 * radius beyond that, so the snapshot has to cover the widest
            // halo any amount can produce, while the bake only writes the inner
            // region. Outside the snapshot a tile reads as the store default,
            // which is correct there because the content really is empty.
            val base = editBounds(store)
            if (base.isEmpty) return
            adjustWork = if (kind == AdjustmentKind.Blur) growBounds(base, blurHalo) else base
            adjustBounds = if (kind == AdjustmentKind.Blur) {
                growBounds(base, blurHalo * 2)
            } else {
                base
            }
            if (adjustCapture == null && store.isSparse) {
                adjustCapture = TileOps.capture(store, adjustBounds)
            } else if (adjustSnapshot == null && !store.isSparse) {
                adjustSnapshot = store.readRect(Rect(0, 0, engine.width, engine.height))
            }
        }
        val snapshot = adjustSnapshot
        if (store.isSparse && adjustCapture == null) return
        if (!store.isSparse && snapshot == null) return
        adjustKind = kind
        adjustAmount = amount

        val preview = when (kind) {
            AdjustmentKind.Hue -> LayerPreview(
                layer.id,
                ColorMatrixColorFilter(Adjustments.hsvMatrix(amount, 1f, 1f)),
                null
            )
            AdjustmentKind.Saturation -> LayerPreview(
                layer.id,
                ColorMatrixColorFilter(Adjustments.hsvMatrix(0f, 1f + amount, 1f)),
                null
            )
            AdjustmentKind.Brightness -> LayerPreview(
                layer.id,
                ColorMatrixColorFilter(Adjustments.hsvMatrix(0f, 1f, 1f + amount)),
                null
            )
            AdjustmentKind.Blur -> {
                // Sparse layers build the proxy from their tiles, so no
                // canvas-sized snapshot is needed for the live preview.
                val proxy = if (store.isSparse) blurProxyFromStore(store, amount)
                else Adjustments.blurProxyInto(snapshot!!, amount, adjustProxy)
                adjustProxy = proxy
                LayerPreview(layer.id, null, proxy)
            }
            AdjustmentKind.Noise -> null
        }
        engine.applyPreview(preview)
        requestState()
    }

    /**
     * Quarter-resolution blurred copy of a tile store, used as the live blur
     * preview. Built from the layer's own tiles so the document is never
     * materialised. The compositor stretches it over the whole canvas.
     */
    private fun blurProxyFromStore(store: TileStore, amount: Float): Bitmap {
        val w = max(1, engine.width / 4)
        val h = max(1, engine.height / 4)
        val reuse = adjustProxy
        val small = if (reuse != null && !reuse.isRecycled &&
            reuse.width == w && reuse.height == h
        ) {
            reuse
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val c = Canvas(small)
        c.drawColor(0, BlendMode.CLEAR)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val ts = store.tileSize
        store.forEachTileIn(Rect(0, 0, engine.width, engine.height)) { tx, ty, tile ->
            val x0 = tx * ts * 0.25f
            val y0 = ty * ts * 0.25f
            c.drawBitmap(
                tile, null,
                RectF(x0, y0, x0 + tile.width * 0.25f, y0 + tile.height * 0.25f),
                p
            )
        }
        Adjustments.blur(small, (amount * 24f * 0.25f).toInt().coerceIn(1, 10))
        return small
    }

    private fun commitAdjustmentInternal() {
        val layer = engine.activeLayer
        val dense = adjustSnapshot
        val capture = adjustCapture
        val bounds = Rect(adjustBounds)
        val kind = adjustKind
        val amount = adjustAmount
        engine.applyPreview(null)
        adjustProxy?.recycle()
        adjustProxy = null
        adjustSnapshot = null
        adjustCapture = null
        adjustKind = null
        adjustWork.setEmpty()
        if (layer == null || kind == null) {
            requestState()
            return
        }
        if (capture != null) {
            // Sparse: bake tile by tile from the pre-edit snapshot.
            val store = layer.paintStore
            val work = if (adjustWork.isEmpty) bounds else Rect(adjustWork)
            when (kind) {
                AdjustmentKind.Hue -> TileOps.applyFilter(
                    store, work, ColorMatrixColorFilter(Adjustments.hsvMatrix(amount, 1f, 1f))
                )
                AdjustmentKind.Saturation -> TileOps.applyFilter(
                    store, work, ColorMatrixColorFilter(Adjustments.hsvMatrix(0f, 1f + amount, 1f))
                )
                AdjustmentKind.Brightness -> TileOps.applyFilter(
                    store, work, ColorMatrixColorFilter(Adjustments.hsvMatrix(0f, 1f, 1f + amount))
                )
                AdjustmentKind.Blur ->
                    TileOps.blur(store, capture, work, (amount * 24f).toInt().coerceIn(1, 24))
                AdjustmentKind.Noise -> TileOps.noise(store, work, amount.coerceIn(0f, 1f))
            }
            // The action covers the whole snapshot region: a blurred tile past
            // the work rect is unchanged, and copying it costs nothing extra.
            engine.makeTilePatch(
                layer, capture, bounds, "Adjust ${kind.label}", EditKind.Adjust
            )?.let { history.push(it) }
        } else {
            val snapshot = dense
            if (snapshot == null) {
                requestState()
                return
            }
            // Bake from the untouched snapshot; both bitmaps become the entry.
            val work = copyPixels(snapshot)
            when (kind) {
                AdjustmentKind.Hue -> Adjustments.hsb(work, amount, 1f, 1f)
                AdjustmentKind.Saturation -> Adjustments.hsb(work, 0f, 1f + amount, 1f)
                AdjustmentKind.Brightness -> Adjustments.hsb(work, 0f, 1f, 1f + amount)
                AdjustmentKind.Blur ->
                    Adjustments.blur(work, (amount * 24f).toInt().coerceIn(1, 24))
                AdjustmentKind.Noise -> Adjustments.noise(work, amount.coerceIn(0f, 1f))
            }
            val full = Rect(0, 0, engine.width, engine.height)
            layer.tiles.writeRect(full, work)
            history.push(
                PixelAction(
                    layer.id, full,
                    snapshot, work, "Adjust ${kind.label}", EditKind.Adjust
                )
            )
        }
        layer.touch()
        engine.markDirtyAll()
        requestState()
    }

    private fun cancelAdjustmentInternal() {
        engine.applyPreview(null)
        adjustProxy?.recycle()
        adjustProxy = null
        val layer = engine.activeLayer
        val capture = adjustCapture
        val snapshot = adjustSnapshot
        if (layer != null && capture != null) {
            capture.restore()
            capture.release()
            layer.touch()
            engine.markDirtyAll()
        } else if (layer != null && snapshot != null) {
            blitFull(layer, snapshot)
            layer.touch()
            engine.markDirtyAll()
        }
        adjustCapture = null
        adjustSnapshot = null
        adjustKind = null
        adjustWork.setEmpty()
        requestState()
    }

    fun commitAdjustment() = post { commitAdjustmentInternal() }

    fun cancelAdjustment() = post { cancelAdjustmentInternal() }

    private fun blitFull(layer: Layer, source: Bitmap) {
        layer.tiles.writeRect(Rect(0, 0, engine.width, engine.height), source)
    }

    fun invertColors() = post {
        val layer = engine.activeLayer ?: return@post
        pixelEdit(layer, "Invert") { Adjustments.invert(it) }
    }

    fun grayscale() = post {
        val layer = engine.activeLayer ?: return@post
        pixelEdit(layer, "Grayscale") { Adjustments.grayscale(it) }
    }

    // ------------------------------------------------------------ viewport

    fun fitToScreen() = post {
        view.fit()
        requestState()
    }

    fun resetRotation() = post {
        view.setRotation(0f)
        requestState()
    }

    fun zoomBy(factor: Float) = post {
        view.applyGesture(
            view.viewWidth / 2f, view.viewHeight / 2f,
            factor, 0f, 0f, 0f
        )
        requestState()
    }

    // ------------------------------------------------------------ snapshot

    fun snapshotBlocking(): DocumentSnapshot? {
        val latch = CountDownLatch(1)
        var snapshot: DocumentSnapshot? = null
        post {
            snapshot = snapshotNow()
            latch.countDown()
        }
        if (!latch.await(6, TimeUnit.SECONDS)) return null
        return snapshot
    }

    /**
     * Builds the document snapshot synchronously. Call this from the render
     * thread (or a harness with no render loop); [snapshotBlocking] is the UI
     * thread wrapper.
     */
    fun snapshotNow(): DocumentSnapshot = DocumentSnapshot(
        title = title,
        width = engine.width,
        height = engine.height,
        background = engine.backgroundColor,
        activeIndex = engine.activeIndex,
        layers = engine.layers.map {
            LayerSnapshotData(
                name = it.name,
                bitmap = it.tiles.sharedBitmap(),
                opacity = it.opacity,
                blend = it.blend,
                visible = it.visible,
                alphaLock = it.alphaLock,
                id = it.id,
                revision = it.revision,
                locked = it.locked,
                clipping = it.clipping,
                mask = it.maskTiles?.sharedBitmap(),
                maskFile = it.maskTiles?.backingFile(),
                maskEnabled = it.maskEnabled,
                groupId = it.groupId,
                file = it.tiles.backingFile(),
                sparse = if (it.tiles.isSparse) it.tiles else null,
                maskSparse = it.maskTiles?.takeIf { m -> m.isSparse }
            )
        },
        groups = engine.groups.map {
            GroupSnapshotData(
                id = it.id,
                name = it.name,
                opacity = it.opacity,
                blend = it.blend,
                visible = it.visible,
                locked = it.locked,
                expanded = it.expanded
            )
        },
        onOwner = { block -> runOnOwner(block = block) },
        slots = IntArray(engine.layers.size) { (engine.layers[it].tiles as? SparseTileStore)?.journalSlot ?: -1 },
        maskSlots = IntArray(engine.layers.size) {
            (engine.layers[it].maskTiles as? SparseTileStore)?.journalSlot ?: -1
        },
        symmetryX = engine.symmetryX,
        symmetryY = engine.symmetryY,
        symmetryXPos = engine.symmetryXPos,
        symmetryYPos = engine.symmetryYPos
    )

    /**
     * Streams the flattened document as a PNG into [out], band by band from the
     * sparse composite (read on the owner thread). Returns false on failure, or
     * when the composite changed while it was being encoded (the caller then
     * discards the partial export).
     */
    fun exportBandedTo(out: java.io.OutputStream): Boolean {
        if (!runOnOwner { engine.compositeAll() }) return false
        val st = engine.compositeTiles ?: return false
        val w = engine.width
        val h = engine.height
        val bandRows = st.tileSize
        val band = Bitmap.createBitmap(w, minOf(bandRows, h), Bitmap.Config.ARGB_8888)
        val rect = android.graphics.Rect()
        return try {
            val before = st.modCount
            BandedPng.encode(out, w, h, bandRows) { y0, rows, px ->
                rect.set(0, y0, w, y0 + rows)
                if (!runOnOwner { st.readRectInto(rect, band) }) {
                    throw java.io.IOException("timed out")
                }
                band.getPixels(px, 0, w, 0, 0, w, rows)
            }
            out.flush()
            st.modCount == before
        } catch (e: java.io.IOException) {
            false
        } finally {
            band.recycle()
        }
    }

    fun flattenBlocking(): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        post {
            result = engine.flatten()
            latch.countDown()
        }
        if (!latch.await(6, TimeUnit.SECONDS)) return null
        return result
    }

    /** Swaps hidden layers to disk so memory stays bounded after a save. */
    fun sweepMemoryBlocking(budget: Long, write: (Layer) -> java.io.File?) {
        val latch = CountDownLatch(1)
        post {
            try {
                engine.releaseHiddenLayers(budget, write)
            } finally {
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
    }

    /** Serialisable view of the newest replayable run of edits. */
    fun historyForDiskBlocking(): PersistedHistory? {
        val latch = CountDownLatch(1)
        var out: PersistedHistory? = null
        post {
            val (run, applied) = history.persistableRun()
            val entries = ArrayList<PersistedHistoryEntry>(run.size)
            // Walk newest to oldest, stopping at an edit whose layer is gone or
            // whose legacy rectangle would exceed the persist budget.
            for (action in run.asReversed()) {
                val index = engine.indexOf(action.layerId)
                if (index < 0) break
                val entry = when (action) {
                    is PixelAction -> PersistedHistoryEntry(
                        actionId = action.id,
                        label = action.label,
                        kind = action.kind,
                        detail = action.detail,
                        swatch = action.swatch,
                        layerIndex = index,
                        rect = Rect(action.rect),
                        before = action.before,
                        after = action.after,
                        mask = action.mask
                    )
                    is TilePixelAction -> {
                        // A spilled (disk-tier) step is read back for the write.
                        if (!history.ensureLoaded(action)) break
                        val legacy = engine.tileActionToRect(action, PERSIST_PATCH_BUDGET)
                            ?: break
                        PersistedHistoryEntry(
                            actionId = action.id,
                            label = action.label,
                            kind = action.kind,
                            detail = action.detail,
                            swatch = action.swatch,
                            layerIndex = index,
                            rect = Rect(legacy.rect),
                            before = legacy.before,
                            after = legacy.after,
                            mask = legacy.mask
                        )
                    }
                    else -> break
                }
                entries.add(entry)
            }
            entries.reverse()
            val dropped = run.size - entries.size
            out = PersistedHistory((applied - dropped).coerceAtLeast(0), entries)
            latch.countDown()
        }
        if (!latch.await(6, TimeUnit.SECONDS)) return null
        return out
    }

    /** Re-attaches edits restored from disk to the freshly loaded layers. */
    fun restoreHistory(persisted: PersistedHistory) {
        post {
            val actions = ArrayList<HistoryAction>(persisted.entries.size)
            for (entry in persisted.entries) {
                val layer = engine.layers.getOrNull(entry.layerIndex) ?: continue
                val action = PixelAction(
                    layerId = layer.id,
                    rect = Rect(entry.rect),
                    before = entry.before,
                    after = entry.after,
                    label = entry.label,
                    kind = entry.kind,
                    detail = entry.detail,
                    swatch = entry.swatch,
                    mask = entry.mask
                )
                action.id = entry.actionId
                HistoryAction.bumpCounterTo(entry.actionId)
                actions.add(action)
            }
            history.restoreFromDisk(actions, persisted.appliedCount.coerceIn(0, actions.size))
            engine.markDirtyAll()
            requestState()
        }
    }

    // --------------------------------------------------------- ui snapshot

    private fun buildState(): EditorUiState {
        val activeNodeId = if (engine.activeGroupId != 0L) engine.activeGroupId
            else (engine.activeLayer?.id ?: -1L)
        val rows = ArrayList<LayerUi>(engine.layers.size + engine.groups.size)
        val emittedGroups = HashSet<Long>()
        var i = engine.layers.size - 1
        while (i >= 0) {
            val layer = engine.layers[i]
            val gid = layer.groupId
            if (gid != 0L) {
                val group = engine.groupById(gid)
                if (group != null && emittedGroups.add(gid)) {
                    rows.add(
                        LayerUi(
                            id = group.id,
                            name = group.name,
                            visible = group.visible,
                            opacity = group.opacity,
                            blend = group.blend,
                            alphaLock = false,
                            thumb = null,
                            isGroup = true,
                            locked = group.locked,
                            expanded = group.expanded
                        )
                    )
                }
                if (group == null || group.expanded) rows.add(layerUiOf(layer, 1))
                i--
            } else {
                rows.add(layerUiOf(layer, 0))
                i--
            }
        }
        if (historyCacheVersion != history.version) {
            historyCache = history.entries()
            historyCacheVersion = history.version
        }
        val preset = activePreset
        return EditorUiState(
            loaded = true,
            title = title,
            width = engine.width,
            height = engine.height,
            layers = rows,
            activeLayerId = activeNodeId,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            tool = tool,
            category = category,
            presets = presetsOf(category),
            activePresetId = activePresetId,
            brushSize = preset.size,
            brushOpacity = preset.opacity,
            color = color,
            recentColors = recentColors.toList(),
            palette = palette.toList(),
            selectionShape = selectionShape,
            selectionOp = selectionOp,
            hasSelection = engine.selection != null,
            transforming = transform != null,
            symmetryX = engine.symmetryX,
            symmetryY = engine.symmetryY,
            symmetryXPos = (engine.symmetryXPos / engine.width).coerceIn(0f, 1f),
            symmetryYPos = (engine.symmetryYPos / engine.height).coerceIn(0f, 1f),
            symmetryLocked = engine.symmetryLocked,
            soloLayerId = engine.soloLayerId ?: -1L,
            assist = assist,
            quickShape = quickShapeEnabled,
            history = historyCache,
            adjusting = adjusting,
            colorMode = colorMode,
            eyedropperLayerOnly = eyedropperLayerOnly,
            wandTolerance = wandTolerance,
            fillTolerance = fillTolerance,
            fillLayerOnly = fillLayerOnly,
            fillSelection = fillSelection,
            fillExpand = fillExpand,
            gradientKind = gradientKind,
            gradientSecond = gradientSecond,
            sparseLayers = TileStoreConfig.useSparseLayers,
            documentVersion = history.version + propertyVersion,
            backgroundColor = engine.backgroundColor,
            zoom = view.zoomFactor(),
            rotation = view.rotationDeg,
            fingerPaint = fingerPaint,
            chromeVisible = chromeVisible,
            eyedropperActive = picking
        )
    }

    private fun layerUiOf(layer: Layer, depth: Int): LayerUi {
        val cached = thumbs[layer.id]
        val thumb = if (cached != null && cached.first == layer.revision) {
            cached.second
        } else {
            val fresh = thumbOf(layer, 128)
            if (fresh != null) thumbs[layer.id] = layer.revision to fresh
            fresh
        }
        val hasMask = layer.hasMask
        val maskThumb = if (!hasMask) null else {
            val cachedMask = maskThumbs[layer.id]
            if (cachedMask != null && cachedMask.first == layer.revision) {
                cachedMask.second
            } else {
                maskThumbOf(layer, 128)?.also {
                    maskThumbs[layer.id] = layer.revision to it
                }
            }
        }
        return LayerUi(
            id = layer.id,
            name = layer.name,
            visible = layer.visible,
            opacity = layer.opacity,
            blend = layer.blend,
            alphaLock = layer.alphaLock,
            thumb = thumb,
            locked = layer.locked,
            clipping = layer.clipping,
            hasMask = hasMask,
            maskEnabled = layer.maskEnabled,
            editingMask = layer.editMask && hasMask,
            maskThumb = maskThumb,
            depth = depth
        )
    }

    /**
     * Thumbnail for a layer that may be swapped out. Resident layers use the
     * full bitmap; swapped-out ones are decoded at thumbnail size so a hidden
     * layer never has to be pulled back into memory just to draw its row.
     */
    private fun thumbOf(layer: Layer, maxPx: Int): Bitmap? {
        if (layer.tiles.isResident) return engine.thumb(layer, maxPx)
        val file = layer.tiles.backingFile() ?: return null
        val sampled = Layer.decodeSampled(file, maxPx) ?: return null
        return engine.thumbOf(sampled, maxPx, layer.opacity).also {
            if (sampled !== it) sampled.recycle()
        }
    }

    /** Mask thumbnail, sampling the mask file when it is swapped out. */
    private fun maskThumbOf(layer: Layer, maxPx: Int): Bitmap? {
        val store = layer.maskTiles ?: return null
        if (store.isResident) return engine.maskThumb(layer, maxPx)
        val file = store.backingFile() ?: return null
        val sampled = Layer.decodeSampled(file, maxPx) ?: return null
        return engine.maskThumbOf(sampled, maxPx).also {
            if (sampled !== it) sampled.recycle()
        }
    }
}
