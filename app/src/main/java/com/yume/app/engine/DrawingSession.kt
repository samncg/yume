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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AdjustmentKind(val label: String) {
    Hue("Hue"),
    Saturation("Saturation"),
    Brightness("Brightness"),
    Blur("Blur"),
    Noise("Noise"),
    Sharpen("Sharpen"),
    MotionBlur("Motion blur"),
    Curves("Curves"),
    Levels("Levels"),
    ColorBalance("Colour balance"),
    GradientMap("Gradient map"),
    Posterize("Posterize"),
    Threshold("Threshold");

    /** Driven by a lookup table built from the panel's editor, not the amount. */
    val isTone: Boolean get() = this == Curves || this == Levels || this == ColorBalance || this == GradientMap ||
        this == Posterize || this == Threshold
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
    val maskSparse: TileStore? = null,
    /** The layer's live store (dense or sparse), for whole-document copies. */
    val store: TileStore? = null,
    val maskStore: TileStore? = null
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
    /** [DrawingEngine.extrasJson]: guides, radial symmetry and later settings. */
    val extras: String = "",
    /**
     * Runs a block that reads sparse layer tiles on the thread that owns the
     * engine (the render thread while it runs), so a save never mutates tile
     * maps (evicted-tile loads) concurrently with painting. Returns false when
     * the owner did not run it in time; the caller then keeps the old file.
     */
    val onOwner: (() -> Unit) -> Boolean = { it(); true },
    /**
     * Draws the document as it looks (groups, clipping, masks, blend modes)
     * scaled to fit [maxPx], from the live composite. Owner thread only; null
     * when the composite is not the plain document (solo view, live preview).
     */
    val composeThumb: ((maxPx: Int) -> Bitmap?)? = null,
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
    /** Rectangle form (dense edits): the region before and after. */
    val before: Bitmap?,
    val after: Bitmap?,
    val mask: Boolean = false,
    /**
     * Tile form (sparse edits of any size, 8K included): only the changed
     * tiles' crops, as the in-memory step holds them. Null for rectangle form.
     */
    val tiles: List<PersistedTile>? = null,
    val tileSize: Int = 0,
    /**
     * A layer-structure step (add, order, properties, groups): its before
     * and after states as JSON (layers and groups by id). Null for pixels.
     */
    val structure: String? = null,
    /** The layer's id (pixel steps), so a restored step finds its layer even if the order changed. */
    val layerId: Long = -1L,
    /** The step's files are already on disk from an earlier save: only its record is rewritten. */
    val reused: Boolean = false
)

/** One changed tile of a persisted step; a null crop means the tile was empty. */
class PersistedTile(
    val tx: Int,
    val ty: Int,
    val clip: Rect,
    val before: Bitmap?,
    val after: Bitmap?,
    /**
     * A crop that is a whole flat tile is kept as its raw pixel value
     * (-1 when not): no 256 KB copy and no file, and it comes back as the
     * shared solid it was. A whole-canvas fill at 16K is 4096 such tiles.
     */
    val beforeSolid: Long = -1L,
    val afterSolid: Long = -1L
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
    @Volatile var colorMode: ColorMode = ColorMode.Wheel

    /** Sample the active layer instead of the flattened composite. */
    @Volatile var eyedropperLayerOnly: Boolean = false

    /**
     * Colour tolerance for the automatic (magic wand) selection: the largest
     * per-channel difference still selected, 1..[TOLERANCE_MAX] (255 takes
     * everything, so soft stroke edges can be included).
     */
    @Volatile var wandTolerance: Int = 30

    // Paint bucket / ColorDrop (Stage 4.1).
    @Volatile var fillTolerance: Int = 30
    @Volatile var fillLayerOnly: Boolean = false
    @Volatile var fillSelection: Boolean = true
    @Volatile var fillExpand: Int = 1

    /** Direction of the motion blur adjustment, in degrees. */
    @Volatile var motionAngle: Float = 0f

    fun chooseMotionAngle(deg: Float) {
        motionAngle = deg
        requestState()
    }

    /** Pixels an adjustment reads beyond what it writes (0 = per pixel). */
    private fun haloOf(kind: AdjustmentKind): Int = when (kind) {
        AdjustmentKind.Blur -> blurHalo
        AdjustmentKind.Sharpen -> 2
        AdjustmentKind.MotionBlur -> MOTION_MAX_RADIUS + 1
        else -> 0
    }

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

    private val _busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** A long operation (undo/redo, adjustment, transform, merge…) is running. */
    val busy: kotlinx.coroutines.flow.StateFlow<Boolean> = _busy

    private inline fun <T> working(block: () -> T): T {
        _busy.value = true
        try {
            return block()
        } finally {
            _busy.value = false
        }
    }

    /**
     * Runs [block] so the user can stop it from the working card. On a cancel
     * [onCancel] puts back whatever was already changed. False when cancelled.
     */
    private inline fun cancellable(onCancel: () -> Unit, block: () -> Unit): Boolean {
        OpCancel.arm()
        try {
            block()
            return true
        } catch (e: OperationCancelled) {
            runCatching(onCancel)
            onMessage?.invoke("Cancelled")
            requestState()
            return false
        } finally {
            OpCancel.disarm()
        }
    }

    /**
     * Where the pen is predicted to be a few ms ahead (view px), drawn as the
     * stroke's leading tip until the real samples arrive. Set by the view.
     */
    @Volatile var predictedTip: FloatArray? = null

    /** The stroke painter, for harness timings. */
    internal fun painterForDebug(): StrokePainter = painter

    /** True while a brush / eraser / smudge stroke is being painted. */
    val strokeInProgress: Boolean get() = painter.isActive

    /**
     * The brush cursor while a stroke is live: x, y and dab diameter in
     * document px, following pressure, tilt and the start taper. Null when no
     * stroke is in progress.
     */
    fun liveCursor(): FloatArray? {
        if (!painter.isActive || painter.liveSize <= 0f) return null
        return floatArrayOf(painter.liveX, painter.liveY, painter.liveSize)
    }

    /** Adjustments apply to every visible, unlocked layer instead of the active one. */
    @Volatile var adjustAllLayers: Boolean = false

    fun chooseAdjustAllLayers(all: Boolean) {
        if (all == adjustAllLayers) return
        // The live preview belongs to one scope: start over in the new one.
        post { cancelAdjustmentInternal() }
        adjustAllLayers = all
        requestState()
    }

    /** Renames the document (shown in the editor; written with the next save). */
    fun renameDocument(name: String) = post {
        title = name
        requestState()
    }
    private var transform: TransformSession? = null
    private var strokeLayer: Layer? = null
    private var strokePreset: BrushPreset? = null
    private var quickShaping = false
    // The freehand stroke's points, for QuickShape recognition (doc px).
    private var qsXs = FloatArray(512)
    private var qsYs = FloatArray(512)
    private var qsCount = 0
    private var qsPressureSum = 0f
    private var qsPressureCount = 0
    /** The recognised shape, and where the pen was when it snapped. */
    private var qsShape: ShapePath? = null
    private var qsAnchorX = 0f
    private var qsAnchorY = 0f
    private var qsCenterX = 0f
    private var qsCenterY = 0f
    private var qsPressure = 1f

    /** Liquify tool settings and engine. */
    @Volatile var liquifyMode: LiquifyMode = LiquifyMode.Push
        private set
    @Volatile var liquifySize: Float = 160f
        private set
    @Volatile var liquifyStrength: Float = 0.5f
        private set
    private val liquify by lazy { Liquify(engine) }
    private var liquifyLastX = 0f
    private var liquifyLastY = 0f

    /** Shape tool settings. */
    @Volatile var shapeKind: ShapeKind = ShapeKind.Ellipse
        private set
    @Volatile var shapePerfect: Boolean = false
        private set
    @Volatile var shapeSides: Int = 5
        private set
    private var shapeDragging = false
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
    /** Where a dragged guide / handle was, to put it back on a cancel. */
    private var dragRestoreX = 0f
    private var dragRestoreY = 0f
    private var transformDragStart: android.graphics.Matrix? = null
    /** Fill / text: the touch that lands, acted on when it lifts. */
    private var tapPending = false
    private var tapX = 0f
    private var tapY = 0f

    private enum class GestureKind { Undecided, View, Content }
    /** How the current two-finger gesture is being read (transform tool). */
    private var gestureKind = GestureKind.Undecided
    private var gesturePan = 0f
    private var gestureScale = 1f
    private var gestureTurn = 0f

    private var draggingTransform = false
    private var dragLastDocX = 0f
    private var dragLastDocY = 0f

    private var picking = false

    private var adjustSnapshot: Bitmap? = null
    /** Sparse documents snapshot the tiles instead of the whole canvas. */
    private var adjustCapture: TileCapture? = null
    private var adjustBounds: Rect = Rect()
    /** True while an adjustment is being previewed, dense or sparse. */
    private val adjusting: Boolean get() = adjustSnapshot != null || adjustCapture != null || adjustKind != null

    /** Pixels the adjustment being previewed will rewrite (all layers counted). */
    @Volatile private var adjustPixels = 0L

    /** Expected duration of the operation running now, in ms (0 = unknown). */
    @Volatile var workEtaMs = 0L
        private set

    /**
     * About how long Apply will take for [kind], in seconds (0 when nothing
     * is being previewed). Learned from earlier applies on this device.
     */
    fun adjustEstimateSeconds(kind: AdjustmentKind): Float {
        val mp = adjustPixels / 1_000_000f
        if (mp <= 0f) return 0f
        var rate = synchronized(adjustRates) { adjustRates[kind] } ?: defaultRate(kind)
        if (kind == AdjustmentKind.MotionBlur) rate *= (0.25f + adjustAmount.coerceIn(0f, 1f) * 1.5f)
        return mp * rate
    }

    private fun defaultRate(kind: AdjustmentKind): Float = when (kind) {
        AdjustmentKind.Hue, AdjustmentKind.Saturation, AdjustmentKind.Brightness -> 0.02f
        AdjustmentKind.Blur -> 0.15f
        AdjustmentKind.Noise -> 0.04f
        AdjustmentKind.Sharpen -> 0.12f
        AdjustmentKind.MotionBlur -> 0.2f
        else -> 0.03f
    }
    private var adjustKind: AdjustmentKind? = null
    private var adjustAmount = 0f
    private var adjustProxy: Bitmap? = null
    /** Unfiltered small copy of the layer the GPU previews filter (built once per adjustment). */
    private var adjustBase: Bitmap? = null
    private var adjustBaseKind: AdjustmentKind? = null
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
        engine.patchSpill = { history.spill }
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
                val got = sp.journalCollect(sp.journalSlot, full, changes, maxBytes - bytes)
                // Over the budget: no journal this time, a full save instead
                // (the store keeps its pending changes for it).
                if (got < 0L) return null
                bytes += got
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

    private var lastInputAt = 0L

    fun pushInput(event: InputEvent) {
        // Time spent: the gaps between inputs, except long pauses.
        val now = android.os.SystemClock.uptimeMillis()
        val gap = now - lastInputAt
        if (lastInputAt > 0L && gap in 0L until ACTIVE_GAP_MS) engine.activeMs += gap
        lastInputAt = now
        inputs.add(event)
        wakeListener?.invoke()
    }

    fun requestState() {
        stateDirty = true
        wakeListener?.invoke()
    }

    private val activePreset: BrushPreset
        get() = presets.firstOrNull { it.id == activePresetId } ?: presets.first()

    /**
     * The preset the hover cursor previews: the painting tools only (a
     * selection or fill cursor stays a plain ring).
     */
    val cursorPreset: BrushPreset?
        get() = if (tool == Tool.Brush || tool == Tool.Eraser || tool == Tool.Smudge) activePreset else null

    @Volatile var hoverTilt = 0f
        private set
    @Volatile var hoverOrientation = 0f
        private set
    @Volatile var hoverDirection = 0f
        private set
    private var hoverDirX = 0f
    private var hoverDirY = 0f

    /** The dab the next stroke lays down at the lowest pressure, where the pen hovers. */
    fun cursorDab(): StrokePainter.CursorDab? {
        val p = cursorPreset ?: return null
        return StrokePainter.cursorDab(p, hoverTilt, hoverOrientation, hoverDirection)
    }

    /** Brush diameter in document pixels, used for the hover cursor. */
    val brushSize: Float get() = activePreset.size
    val brushOpacity: Float get() = activePreset.opacity

    val transformActive: Boolean get() = transform != null

    /** The live transform's matrix values (harness checks), or null. */
    fun transformValues(): FloatArray? = transform?.let { t -> FloatArray(9).also { t.matrix.getValues(it) } }

    private fun presetsOf(category: BrushCategory): List<BrushPreset> {
        val filtered = presets.filter { it.category == category }
        return filtered.ifEmpty { presets }
    }

    // ------------------------------------------------------- render loop API

    fun runCommands() {
        while (true) {
            val cmd = commands.poll() ?: break
            workSince = android.os.SystemClock.uptimeMillis()
            try {
                cmd()
            } catch (t: Throwable) {
                android.util.Log.e("Yume", "command failed", t)
            } finally {
                workSince = 0L
            }
        }
    }

    /**
     * When the command or input event the render thread is running started
     * (0 when idle). Moves while drawing are left out: they are the stroke
     * itself, never a wait. The editor shows its working card from this, so
     * every slow tool (fill, auto-select, shapes, transform…) gets it.
     */
    @Volatile private var workSince = 0L

    /** How long the current piece of work has been running, in ms. */
    fun workingMillis(now: Long = android.os.SystemClock.uptimeMillis()): Long {
        val s = workSince
        return if (s == 0L) 0L else now - s
    }

    fun runInput(budgetNs: Long = Long.MAX_VALUE): Boolean {
        var changed = false
        // A time budget: a big brush can take longer to paint than the
        // finger takes to move, and draining the queue in one go would show
        // nothing until the stroke ends. Past the budget the frame is drawn
        // and the rest is painted in the next turns.
        val start = System.nanoTime()
        while (true) {
            if (changed && System.nanoTime() - start > budgetNs && inputs.isNotEmpty()) break
            val event = inputs.poll() ?: break
            val timed = event !is InputEvent.Move && event !is InputEvent.Hover &&
                event !is InputEvent.PickMove && event !is InputEvent.Gesture
            if (timed) workSince = android.os.SystemClock.uptimeMillis()
            changed = try {
                handle(event) || changed
            } catch (t: Throwable) {
                android.util.Log.e("Yume", "input failed: ${event.javaClass.simpleName}", t)
                changed
            } finally {
                if (timed) workSince = 0L
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
                    tryHandleDrag(doc[0], doc[1]) -> Unit
                    tryGuideDrag(doc[0], doc[1]) -> Unit
                    tool == Tool.Selection -> beginSelectionOrMove(doc[0], doc[1])
                    tool == Tool.Transform -> beginTransformDrag(doc[0], doc[1])
                    // Fill and text act when the touch lifts: a touch that
                    // turns into a two-finger pan never fills or places text.
                    tool == Tool.Fill || tool == Tool.Text -> {
                        tapPending = true
                        tapX = doc[0]
                        tapY = doc[1]
                    }
                    tool == Tool.Gradient -> gradientGuide = floatArrayOf(doc[0], doc[1], doc[0], doc[1])
                    tool == Tool.Shape -> beginShape(doc[0], doc[1], event.pressure)
                    tool == Tool.Liquify -> beginLiquify(doc[0], doc[1])
                    else -> beginStroke(doc[0], doc[1], event.pressure, event.tilt, event.eraserButton)
                }
                true
            }

            is InputEvent.Move -> {
                var changed = false
                for (i in event.xs.indices) {
                    view.toDocument(event.xs[i], event.ys[i], doc)
                    when {
                        draggingHandle != NO_HANDLE -> {
                            moveHandle(doc[0], doc[1])
                            changed = true
                        }
                        liquify.active -> {
                            moveLiquify(doc[0], doc[1], event.pressures[i])
                            changed = true
                        }
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
                        shapeDragging || quickShaping -> {
                            // Shapes follow the pen as a light preview; the brush
                            // stamps them once, when the pen lifts.
                            strokeLastX = doc[0]
                            strokeLastY = doc[1]
                            updateShapePreview()
                            changed = true
                        }
                        painter.isActive -> {
                            strokeLastX = doc[0]
                            strokeLastY = doc[1]
                            recordStrokePoint(doc[0], doc[1], event.pressures[i])
                            painter.orientation = event.orientation
                            if (assistActive()) {
                                assistedMove(doc[0], doc[1], event.pressures[i], event.tilt)
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
                if (tapPending) {
                    tapPending = false
                    if (tool == Tool.Fill) fillAtInternal(tapX, tapY, color, false)
                    else if (tool == Tool.Text) textRequest.value = floatArrayOf(tapX, tapY)
                }
                val guide = gradientGuide
                if (tool == Tool.Gradient && guide != null) {
                    gradientGuide = null
                    // A tap (no drag) does nothing: a gradient needs a direction.
                    if (hypot(guide[2] - guide[0], guide[3] - guide[1]) * view.scale > 6f) {
                        applyGradientInternal(guide[0], guide[1], guide[2], guide[3])
                    }
                }
                when {
                    draggingHandle != NO_HANDLE -> {
                        draggingHandle = NO_HANDLE
                        touchProperties()
                    }
                    draggingGuide != 0 -> draggingGuide = 0
                    liquify.active -> liquify.end()?.let { history.push(it) }
                    shapeDragging || quickShaping -> finishShape()
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
                if (event.first) {
                    gestureKind = GestureKind.Undecided
                    gesturePan = 0f
                    gestureScale = 1f
                    gestureTurn = 0f
                }
                if (t != null && gestureKind != GestureKind.View) {
                    // Transform: two fingers pan the view like everywhere else.
                    // Only a gesture that is clearly a pinch or a twist before
                    // it travels scales / rotates the content.
                    gesturePan += hypot(event.panX, event.panY)
                    gestureScale *= event.scaleFactor
                    gestureTurn += event.angleDelta
                    if (gestureKind == GestureKind.Undecided) {
                        if (abs(ln(gestureScale.coerceAtLeast(0.01f))) > TRANSFORM_PINCH_LOG || abs(gestureTurn) > TRANSFORM_TWIST_DEG) {
                            gestureKind = GestureKind.Content
                            view.toDocument(event.focusX, event.focusY, doc)
                            // Catch up on what the fingers did while undecided.
                            t.scaleRotate(doc[0], doc[1], gestureScale, gestureTurn)
                        } else if (gesturePan > TRANSFORM_PAN_PX) {
                            gestureKind = GestureKind.View
                        }
                        if (event.panX != 0f || event.panY != 0f) {
                            view.applyGesture(event.focusX, event.focusY, 1f, 0f, event.panX, event.panY)
                        }
                    } else {
                        view.toDocument(event.focusX, event.focusY, doc)
                        t.scaleRotate(doc[0], doc[1], event.scaleFactor, event.angleDelta)
                        if (event.panX != 0f || event.panY != 0f) {
                            view.applyGesture(event.focusX, event.focusY, 1f, 0f, event.panX, event.panY)
                        }
                    }
                    requestState()
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
                // Direction of travel while hovering (for brushes that turn
                // with the stroke), once the pen has moved a few pixels.
                val mdx = doc[0] - hoverDirX
                val mdy = doc[1] - hoverDirY
                if (mdx * mdx + mdy * mdy > (6f / view.scale).let { it * it }) {
                    hoverDirection = Math.toDegrees(kotlin.math.atan2(mdy, mdx).toDouble()).toFloat()
                    hoverDirX = doc[0]
                    hoverDirY = doc[1]
                }
                hoverX = doc[0]
                hoverY = doc[1]
                hoverTilt = event.tilt
                hoverOrientation = event.orientation
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
                } else if (quickShaping || shapeDragging) {
                    // A snapped QuickShape has already put the freehand pixels
                    // back; only its capture and the preview remain.
                    painter.releaseTileCapture()
                    FrameLogger.strokeActive = false
                    engine.endStrokeCache()
                    strokeLayer = null
                    strokePreset = null
                }
                shapeDragging = false
                shapePreview = null
                // A touch that became a two-finger gesture leaves no trace:
                // liquify, a moved selection or transform, dragged guides and
                // pending taps are all put back as they were.
                if (liquify.active) liquify.end()?.let { action ->
                    action.undo(engine)
                    history.discard(action)
                }
                tapPending = false
                gradientGuide = null
                if (movingSelection || autoSelectCanMove) {
                    selectionMoveBase?.let { engine.setSelection(Region(it)) }
                    movingSelection = false
                    selectionMoveBase = null
                }
                if (draggingTransform) {
                    transformDragStart?.let { transform?.restoreMatrix(it) }
                }
                transformDragStart = null
                if (draggingHandle != NO_HANDLE) {
                    if (draggingHandle == CENTER_HANDLE) {
                        engine.symmetryXPos = dragRestoreX
                        engine.symmetryYPos = dragRestoreY
                    } else {
                        engine.guides.moveHandle(draggingHandle, dragRestoreX, dragRestoreY)
                    }
                    draggingHandle = NO_HANDLE
                }
                if (draggingGuide != 0) {
                    engine.symmetryXPos = dragRestoreX
                    engine.symmetryYPos = dragRestoreY
                    draggingGuide = 0
                    touchProperties()
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
                // Long enough on screen (not in document px, which depends on zoom).
                if (quickShapeEnabled && length * view.scale > 24f && painter.isActive &&
                    strokePreset != null && !quickShaping && !shapeDragging
                ) {
                    // What did the user mean? A line, polyline, polygon or
                    // ellipse; nothing when the stroke fits none of them.
                    val shape = ShapeGeometry.recognize(qsXs, qsYs, qsCount)
                        ?: if (qsCount >= 2) ShapeGeometry.line(strokeStartX, strokeStartY, strokeLastX, strokeLastY) else null
                    if (shape != null) {
                        quickShaping = true
                        qsShape = shape
                        qsAnchorX = strokeLastX
                        qsAnchorY = strokeLastY
                        var cx = 0f
                        var cy = 0f
                        val n = if (shape.closed) shape.size - 1 else shape.size
                        for (k in 0 until n) { cx += shape.xs[k]; cy += shape.ys[k] }
                        qsCenterX = cx / n
                        qsCenterY = cy / n
                        qsPressure = if (qsPressureCount > 0) qsPressureSum / qsPressureCount else 1f
                        // The freehand stroke leaves the pixels (its pre-stroke
                        // capture is kept) and the preview morphs from it into
                        // the clean shape.
                        val (fx, fy) = resamplePath(qsXs, qsYs, qsCount, MORPH_POINTS)
                        qsFreeXs = fx
                        qsFreeYs = fy
                        painter.cancel()
                        qsMorphStart = android.os.SystemClock.uptimeMillis()
                        updateShapePreview()
                    }
                }
                true
            }
        }
    }

    // ------------------------------------------------------- drawing guides

    /** Guide handles (vanishing points, ruler ends) are draggable while the Guides panel is open. */
    @Volatile var guideEditing: Boolean = false
        private set
    private var draggingHandle = NO_HANDLE
    private var assistLocked = false
    private var assistDx = 0f
    private var assistDy = 0f
    private var assistOnRuler = false

    fun setGuideEditing(value: Boolean) = post {
        guideEditing = value
        requestState()
    }

    private fun tryHandleDrag(x: Float, y: Float): Boolean {
        if (!guideEditing) return false
        val reach = 34f / max(view.scale, 0.0001f)
        val g = engine.guides
        var best = NO_HANDLE
        var bestD = reach
        for (i in 0 until g.handleCount()) {
            val d = hypot(x - g.handleX(i), y - g.handleY(i))
            if (d <= bestD) { bestD = d; best = i }
        }
        // The radial symmetry centre is a handle too.
        if (engine.symmetryRadial >= 2) {
            val d = hypot(x - engine.symmetryXPos, y - engine.symmetryYPos)
            if (d <= bestD) { bestD = d; best = CENTER_HANDLE }
        }
        draggingHandle = best
        if (best == CENTER_HANDLE) {
            dragRestoreX = engine.symmetryXPos
            dragRestoreY = engine.symmetryYPos
        } else if (best != NO_HANDLE) {
            dragRestoreX = g.handleX(best)
            dragRestoreY = g.handleY(best)
        }
        return best != NO_HANDLE
    }

    private fun moveHandle(x: Float, y: Float) {
        if (draggingHandle == CENTER_HANDLE) {
            engine.symmetryXPos = x.coerceIn(0f, engine.width.toFloat())
            engine.symmetryYPos = y.coerceIn(0f, engine.height.toFloat())
        } else {
            engine.guides.moveHandle(draggingHandle, x, y)
        }
    }

    private fun assistActive(): Boolean =
        engine.guides.visible && engine.guides.assist && strokePreset?.category != BrushCategory.Smudge

    /**
     * Assisted drawing: the stroke waits until it has moved a little, picks the
     * guide direction closest to that movement, and from then on every point
     * is projected onto the line through the stroke's start along it. A
     * stroke that starts on the ruler is drawn on the ruler itself.
     */
    private fun assistedMove(x: Float, y: Float, pressure: Float, tilt: Float) {
        val g = engine.guides
        if (!assistLocked) {
            val moved = hypot(x - strokeStartX, y - strokeStartY)
            if (moved * view.scale < 14f) return
            val dirs = g.directionsFrom(strokeStartX, strokeStartY)
            if (dirs.isEmpty()) {
                painter.move(x, y, pressure, tilt)
                return
            }
            val mx = (x - strokeStartX) / moved
            val my = (y - strokeStartY) / moved
            var best = 0
            var bestDot = -1f
            for (k in 0 until dirs.size / 2) {
                val d = abs(mx * dirs[k * 2] + my * dirs[k * 2 + 1])
                if (d > bestDot) { bestDot = d; best = k }
            }
            assistDx = dirs[best * 2]
            assistDy = dirs[best * 2 + 1]
            assistOnRuler = g.mode == GuideMode.Ruler &&
                g.rulerDistance(strokeStartX, strokeStartY) * view.scale < 40f
            assistLocked = true
        }
        val ox: Float
        val oy: Float
        if (assistOnRuler) {
            ox = engine.guides.rulerX[0]
            oy = engine.guides.rulerY[0]
        } else {
            ox = strokeStartX
            oy = strokeStartY
        }
        val t = (x - ox) * assistDx + (y - oy) * assistDy
        painter.move(ox + assistDx * t, oy + assistDy * t, pressure, tilt)
    }

    fun setGuideMode(mode: GuideMode) = post {
        engine.guides.mode = mode
        touchProperties()
        requestState()
    }

    fun setGuideAssist(value: Boolean) = post {
        engine.guides.assist = value
        touchProperties()
        requestState()
    }

    fun setGridSize(value: Float) = post {
        engine.guides.gridSize = value.coerceIn(8f, 4096f)
        touchProperties()
        requestState()
    }

    fun setVanishingPoints(count: Int) = post {
        val before = engine.guides.vpCount
        engine.guides.vpCount = count.coerceIn(1, 3)
        // A point that is switched on comes in where it can be seen and
        // dragged (older documents kept the third one far below). Points
        // already in use stay where the user put them, on or off the canvas.
        val g = engine.guides
        for (i in before until g.vpCount) {
            if (g.vpX[i] !in 0f..engine.width.toFloat() || g.vpY[i] !in 0f..engine.height.toFloat()) {
                g.vpX[i] = g.vpX[i].coerceIn(engine.width * 0.05f, engine.width * 0.95f)
                g.vpY[i] = g.vpY[i].coerceIn(engine.height * 0.05f, engine.height * 0.95f)
            }
        }
        touchProperties()
        requestState()
    }

    fun setRadialSymmetry(segments: Int) = post {
        engine.symmetryRadial = if (segments < 2) 0 else segments.coerceAtMost(32)
        if (engine.symmetryRadial >= 2 && engine.symmetryXPos <= 0f) {
            engine.symmetryXPos = engine.width / 2f
            engine.symmetryYPos = engine.height / 2f
        }
        touchProperties()
        requestState()
    }

    fun setSymmetryMirror(value: Boolean) = post {
        engine.symmetryMirror = value
        touchProperties()
        requestState()
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
        dragRestoreX = engine.symmetryXPos
        dragRestoreY = engine.symmetryYPos
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
        qsShape = null
        qsCount = 0
        qsPressureSum = 0f
        qsPressureCount = 0
        recordStrokePoint(x, y, pressure)
        assistLocked = false
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
    private fun recordStrokePoint(x: Float, y: Float, pressure: Float) {
        qsPressureSum += pressure
        qsPressureCount++
        if (qsCount > 0 && hypot(x - qsXs[qsCount - 1], y - qsYs[qsCount - 1]) < 1.5f) return
        if (qsCount == qsXs.size) {
            if (qsCount >= 8192) return
            qsXs = qsXs.copyOf(qsCount * 2)
            qsYs = qsYs.copyOf(qsCount * 2)
        }
        qsXs[qsCount] = x
        qsYs[qsCount] = y
        qsCount++
    }

    /**
     * Redraws the snapped QuickShape as the pen keeps moving: a line's end
     * follows the pen; other shapes scale and turn about their centre, the way
     * the pen moves relative to where it was when the shape snapped. The
     * pre-stroke snapshot is kept, so this is cheap enough for every move.
     */
    /** The QuickShape as the pen has adjusted it since it snapped. */
    private fun adjustedQuickShape(): ShapePath? {
        val shape = qsShape ?: return null
        return when (shape.kind) {
            "line" -> ShapeGeometry.line(shape.xs[0], shape.ys[0], strokeLastX, strokeLastY)
            else -> {
                val d0 = hypot(qsAnchorX - qsCenterX, qsAnchorY - qsCenterY)
                val d1 = hypot(strokeLastX - qsCenterX, strokeLastY - qsCenterY)
                if (d0 < 4f) {
                    shape
                } else {
                    val scale = (d1 / d0).coerceIn(0.05f, 20f)
                    val a0 = kotlin.math.atan2(qsAnchorY - qsCenterY, qsAnchorX - qsCenterX)
                    val a1 = kotlin.math.atan2(strokeLastY - qsCenterY, strokeLastX - qsCenterX)
                    // Closed shapes only scale: a turning ellipse feels unsteady.
                    val angle = if (shape.closed) 0f else a1 - a0
                    shape.transformed(qsCenterX, qsCenterY, scale, angle)
                }
            }
        }
    }

    /** What the shape tool or QuickShape would stamp right now. */
    private fun currentShape(): ShapePath? = when {
        shapeDragging -> if (hypot(strokeLastX - shapeStartX, strokeLastY - shapeStartY) < 1f) null
        else ShapeGeometry.fromDrag(shapeKind, shapeStartX, shapeStartY, strokeLastX, strokeLastY, shapePerfect, shapeSides)
        quickShaping -> adjustedQuickShape()
        else -> null
    }

    /** A shape being placed, drawn by the renderer as a plain stroked path. */
    class ShapePreview(
        val xs: FloatArray, val ys: FloatArray, val closed: Boolean,
        val width: Float, val color: Int
    )

    /** Read by the renderer each frame (render thread, so no locking). */
    var shapePreview: ShapePreview? = null
        private set
    private var qsFreeXs = FloatArray(0)
    private var qsFreeYs = FloatArray(0)
    private var qsMorphStart = 0L

    private fun morphProgress(): Float {
        if (!quickShaping || qsFreeXs.isEmpty()) return 1f
        val t = ((android.os.SystemClock.uptimeMillis() - qsMorphStart) / MORPH_MS.toFloat()).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    private fun updateShapePreview() {
        val brush = strokePreset ?: return
        val shape = currentShape()
        if (shape == null) {
            shapePreview = null
            return
        }
        val pressure = if (quickShaping) qsPressure else shapePressure
        val width = max(1.5f, brush.size * (1f - brush.pressureSize.coerceIn(0f, 1f) * (1f - pressure)))
        val tint = if (brush.category == BrushCategory.Eraser) 0x99A6A6AE.toInt()
        else {
            val a = (brush.opacity * 255f).toInt().coerceIn(40, 255)
            (a shl 24) or (color and 0x00FFFFFF)
        }
        val e = morphProgress()
        if (e < 1f) {
            val (tx, ty) = resamplePath(shape.xs, shape.ys, shape.size, MORPH_POINTS)
            val n = MORPH_POINTS
            val xs = FloatArray(n) { qsFreeXs[it] + (tx[it] - qsFreeXs[it]) * e }
            val ys = FloatArray(n) { qsFreeYs[it] + (ty[it] - qsFreeYs[it]) * e }
            shapePreview = ShapePreview(xs, ys, closed = false, width = width, color = tint)
        } else {
            shapePreview = ShapePreview(shape.xs, shape.ys, shape.closed, width, tint)
        }
    }

    /** Stamps the placed shape with the brush and ends it as one stroke. */
    private fun finishShape() {
        val shape = currentShape()
        shapePreview = null
        if (shape == null) {
            if (quickShaping) {
                // Nothing to draw any more: the freehand pixels are already back.
                painter.clearTileCapture()
            }
            strokeLayer = null
            strokePreset = null
            shapeDragging = false
            quickShaping = false
            FrameLogger.strokeActive = false
            return
        }
        if (shapeDragging) engine.beginStrokeCache()
        paintShape(shape, if (quickShaping) qsPressure else shapePressure, fresh = shapeDragging)
        endStroke()
    }

    /**
     * Per frame (render thread): the QuickShape morph, and liquify modes that
     * keep working while the pen is held still. True while there is motion.
     */
    fun tickFrame(): Boolean {
        var work = false
        if (quickShaping && morphProgress() < 1f) {
            updateShapePreview()
            work = true
        } else if (quickShaping && shapePreview != null && qsMorphStart != 0L) {
            // The last morph frame lands exactly on the shape.
            qsMorphStart = 0L
            updateShapePreview()
            work = true
        }
        if (liquify.active && liquifyMode != LiquifyMode.Push) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - liquifyHoldLast >= LIQUIFY_HOLD_MS) {
                liquifyHoldLast = now
                liquify.dab(liquifyMode, liquifyLastX, liquifyLastY, liquifySize / 2f, 0f, 0f, liquifyStrength * 0.45f)
            }
            work = true
        }
        return work
    }
    private var liquifyHoldLast = 0L

    /** Paints [shape] with the stroke's brush from the pre-stroke pixels. */
    private fun paintShape(shape: ShapePath, pressure: Float, fresh: Boolean = false) {
        val layer = strokeLayer ?: return
        val brush = strokePreset ?: return
        painter.cancel()
        // Smoothing would lag the vertices, so shapes use a non-streamlined
        // version of the brush, at the stroke's average pressure. A QuickShape
        // reuses its freehand stroke's pre-stroke capture; a shape tool drag
        // takes a fresh one.
        painter.begin(
            layer, brush.copy(smoothing = 0f), color,
            shape.xs[0], shape.ys[0], pressure, 0f,
            captureBase = fresh, assistStrength = 0f, startTaper = !shape.closed
        )
        for (k in 1 until shape.size) painter.move(shape.xs[k], shape.ys[k], pressure, 0f)
    }

    // ------------------------------------------------------------- liquify

    private fun beginLiquify(x: Float, y: Float) {
        val layer = engine.activeLayer ?: return
        if (!layer.visible || !editable(layer)) return
        if (adjusting) commitAdjustmentInternal()
        liquify.begin(layer)
        liquifyLastX = x
        liquifyLastY = y
        liquifyHoldLast = android.os.SystemClock.uptimeMillis()
        // Twirl, pinch, expand and restore act on a press that does not move.
        if (liquifyMode != LiquifyMode.Push) liquify.dab(liquifyMode, x, y, liquifySize / 2f, 0f, 0f, liquifyStrength)
    }

    /** Dabs along the pen's path, a quarter of the radius apart. */
    private fun moveLiquify(x: Float, y: Float, pressure: Float) {
        val r = liquifySize / 2f
        val dist = hypot(x - liquifyLastX, y - liquifyLastY)
        val step = max(2f, r * 0.25f)
        if (dist < step) return
        val n = (dist / step).toInt().coerceIn(1, 64)
        val strength = liquifyStrength * (0.35f + 0.65f * pressure.coerceIn(0f, 1f))
        val dx = (x - liquifyLastX) / n
        val dy = (y - liquifyLastY) / n
        for (k in 1..n) {
            val t = k / n.toFloat()
            liquify.dab(
                liquifyMode,
                liquifyLastX + (x - liquifyLastX) * t, liquifyLastY + (y - liquifyLastY) * t,
                r, dx, dy, strength
            )
        }
        liquifyLastX = x
        liquifyLastY = y
    }

    fun setLiquifyMode(mode: LiquifyMode) {
        liquifyMode = mode
        requestState()
    }

    fun setLiquifySize(value: Float) {
        liquifySize = value.coerceIn(8f, 1200f)
        requestState()
    }

    fun setLiquifyStrength(value: Float) {
        liquifyStrength = value.coerceIn(0.02f, 1f)
        requestState()
    }

    // ------------------------------------------------------------ shape tool

    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private var shapePressure = 1f

    private fun beginShape(x: Float, y: Float, pressure: Float) {
        val layer = engine.activeLayer ?: return
        if (!layer.visible || !editable(layer)) return
        if (adjusting) commitAdjustmentInternal()
        val brush = presets.firstOrNull { it.id == activePresetId && it.category == BrushCategory.Brush }
            ?: presets.firstOrNull { it.category == BrushCategory.Brush } ?: return
        strokeLayer = layer
        strokePreset = brush
        strokeStartX = x
        strokeStartY = y
        strokeLastX = x
        strokeLastY = y
        shapeStartX = x
        shapeStartY = y
        // Shapes are drawn at an even weight; a light press still draws a line.
        shapePressure = 1f
        quickShaping = false
        shapeDragging = true
        shapePreview = null
        FrameLogger.strokeActive = true
    }

    /** Arc-length resampling of a path into [n] points (for the morph). */
    private fun resamplePath(xs: FloatArray, ys: FloatArray, count: Int, n: Int): Pair<FloatArray, FloatArray> {
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        if (count <= 0) return ox to oy
        if (count == 1) { ox.fill(xs[0]); oy.fill(ys[0]); return ox to oy }
        val cum = FloatArray(count)
        for (i in 1 until count) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = max(cum[count - 1], 1e-3f)
        var j = 0
        for (k in 0 until n) {
            val t = total * k / (n - 1)
            while (j < count - 2 && cum[j + 1] < t) j++
            val seg = max(cum[j + 1] - cum[j], 1e-6f)
            val f = ((t - cum[j]) / seg).coerceIn(0f, 1f)
            ox[k] = xs[j] + (xs[j + 1] - xs[j]) * f
            oy[k] = ys[j] + (ys[j + 1] - ys[j]) * f
        }
        return ox to oy
    }

    fun setShapeKind(kind: ShapeKind) {
        shapeKind = kind
        requestState()
    }

    fun setShapePerfect(value: Boolean) {
        shapePerfect = value
        requestState()
    }

    fun setShapeSides(value: Int) {
        shapeSides = value.coerceIn(3, 24)
        requestState()
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
            val patchText = when {
                shapeDragging -> shapeKind.label
                quickShaping -> "QuickShape " + (qsShape?.kind ?: "line")
                else -> label
            }
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
            engine.strokeCount++
            // The colour only becomes "recent" once it has been used.
            if (brush?.category == BrushCategory.Brush) addRecent(swatch)
        } else {
            tileCapture?.release()
        }
        strokeLayer = null
        strokePreset = null
        shapeDragging = false
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
        cancellable({}) { runAutoSelectWork() }
    }

    private fun runAutoSelectWork() {
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
        transformDragStart = android.graphics.Matrix(transform!!.matrix)
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

    private fun commitTransformInternal() = working { commitTransformInternalWork() }

    private fun commitTransformInternalWork() {
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

    /** Tool settings as JSON, for carrying them to the next project. */
    fun toolStateJson(): String = org.json.JSONObject().apply {
        put("tool", tool.name)
        put("activePresetId", activePresetId)
        put("color", color)
        put("assist", assist.toDouble())
        put("quickShape", quickShapeEnabled)
        put("fillTolerance", fillTolerance)
        put("fillLayerOnly", fillLayerOnly)
        put("fillSelection", fillSelection)
        put("fillExpand", fillExpand)
        put("wandTolerance", wandTolerance)
        put("eyedropperLayerOnly", eyedropperLayerOnly)
        put("selectionShape", selectionShape.name)
        put("colorMode", colorMode.name)
    }.toString()

    /** Restores [toolStateJson]; unknown or missing values keep the current ones. */
    fun applyToolState(json: String) {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        o.optString("activePresetId", "").takeIf { id -> presets.any { it.id == id } }?.let { selectPreset(it) }
        Tool.entries.firstOrNull { it.name == o.optString("tool") }?.let { t ->
            if (t != Tool.Transform) chooseTool(t)
        }
        if (o.has("color")) color = o.getInt("color")
        assist = o.optDouble("assist", assist.toDouble()).toFloat().coerceIn(0f, 1f)
        quickShapeEnabled = o.optBoolean("quickShape", quickShapeEnabled)
        fillTolerance = o.optInt("fillTolerance", fillTolerance)
        fillLayerOnly = o.optBoolean("fillLayerOnly", fillLayerOnly)
        fillSelection = o.optBoolean("fillSelection", fillSelection)
        fillExpand = o.optInt("fillExpand", fillExpand)
        wandTolerance = o.optInt("wandTolerance", wandTolerance)
        eyedropperLayerOnly = o.optBoolean("eyedropperLayerOnly", eyedropperLayerOnly)
        SelectionShape.entries.firstOrNull { it.name == o.optString("selectionShape") }?.let { selectionShape = it }
        ColorMode.entries.firstOrNull { it.name == o.optString("colorMode") }?.let { colorMode = it }
        requestState()
    }

    /** A fresh session's tool settings from the user's defaults (Settings). */
    fun applyDefaults(
        tool0: Tool, brushId: String, eraserId: String, smudgeId: String,
        color0: Int, assist0: Float, fillTolerance0: Int, quickShape0: Boolean
    ) {
        // The eraser and blender pick their default when first chosen.
        defaultPresetFor[BrushCategory.Brush] = brushId
        defaultPresetFor[BrushCategory.Eraser] = eraserId
        defaultPresetFor[BrushCategory.Smudge] = smudgeId
        presets.firstOrNull { it.id == brushId && it.category == BrushCategory.Brush }?.let { activePresetId = it.id }
        color = color0
        assist = assist0.coerceIn(0f, 1f)
        fillTolerance = fillTolerance0
        quickShapeEnabled = quickShape0
        applyToolOptionDefaults(com.yume.app.data.Customization.toolDefaults)
        if (tool0 != Tool.Brush && tool0 != Tool.Transform) chooseTool(tool0)
        requestState()
    }

    /** Each tool's starting options from Settings (JSON; missing keys keep the built-in ones). */
    fun applyToolOptionDefaults(json: String) {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        SelectionShape.entries.firstOrNull { it.name == o.optString("selectionShape") }?.let { selectionShape = it }
        wandTolerance = o.optInt("wandTolerance", wandTolerance).coerceIn(1, 100)
        fillLayerOnly = o.optBoolean("fillLayerOnly", fillLayerOnly)
        fillSelection = o.optBoolean("fillSelection", fillSelection)
        fillExpand = o.optInt("fillExpand", fillExpand).coerceIn(0, 8)
        Gradients.Kind.entries.firstOrNull { it.name == o.optString("gradientKind") }?.let { gradientKind = it }
        ShapeKind.entries.firstOrNull { it.name == o.optString("shapeKind") }?.let { shapeKind = it }
        shapePerfect = o.optBoolean("shapePerfect", shapePerfect)
        shapeSides = o.optInt("shapeSides", shapeSides).coerceIn(3, 12)
        LiquifyMode.entries.firstOrNull { it.name == o.optString("liquifyMode") }?.let { liquifyMode = it }
        liquifySize = o.optDouble("liquifySize", liquifySize.toDouble()).toFloat().coerceIn(10f, 600f)
        liquifyStrength = o.optDouble("liquifyStrength", liquifyStrength.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** Preset each brush family starts with when first chosen (id, empty = first). */
    private val defaultPresetFor = HashMap<BrushCategory, String>()

    /** The preset last used in each brush family, so switching back restores it. */
    private val lastPresetFor = HashMap<BrushCategory, String>()

    private fun rememberActivePreset() {
        presets.firstOrNull { it.id == activePresetId }?.let { lastPresetFor[it.category] = it.id }
    }

    fun chooseTool(newTool: Tool) {
        if (newTool == tool) return
        // Leaving the transform tool commits what the user arranged.
        if (tool == Tool.Transform && transform != null) {
            post { commitTransformInternal() }
        }
        if (adjusting) {
            post { commitAdjustmentInternal() }
        }
        if (tool == Tool.Liquify && newTool != Tool.Liquify) post { liquify.releaseBase() }
        rememberActivePreset()
        tool = newTool
        when (newTool) {
            Tool.Brush -> category = BrushCategory.Brush
            Tool.Eraser -> category = BrushCategory.Eraser
            Tool.Smudge -> category = BrushCategory.Smudge
            Tool.Selection, Tool.Transform, Tool.Fill, Tool.Gradient,
            Tool.Shape, Tool.Liquify, Tool.Text -> Unit
        }
        if (newTool == Tool.Brush || newTool == Tool.Eraser || newTool == Tool.Smudge) {
            val list = presetsOf(category)
            if (list.none { it.id == activePresetId }) {
                // The one used last in this family, else the user's default.
                val last = lastPresetFor[category]
                val wanted = defaultPresetFor[category]
                activePresetId = list.firstOrNull { it.id == last }?.id
                    ?: list.firstOrNull { it.id == wanted }?.id ?: list.first().id
            }
        }
        if (newTool == Tool.Transform) {
            post { startTransform() }
        }
        requestState()
    }

    fun selectPreset(id: String) {
        rememberActivePreset()
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
            builtIn = false,
            group = "Custom"
        )
        val index = presets.indexOfFirst { it.id == source.id }
        val list = presets.toMutableList()
        list.add(if (index >= 0) index + 1 else list.size, created)
        presets = list
        activePresetId = id
        onPresetsChanged?.invoke(presets)
        requestState()
    }

    /** Adds imported presets after the active one and selects the first. */
    fun importPresets(list: List<BrushPreset>) {
        if (list.isEmpty()) return
        val index = presets.indexOfFirst { it.id == activePresetId }
        val all = presets.toMutableList()
        all.addAll(if (index >= 0) index + 1 else all.size, list)
        presets = all
        val first = list.first()
        tool = when (first.category) {
            BrushCategory.Eraser -> Tool.Eraser
            BrushCategory.Smudge -> Tool.Smudge
            BrushCategory.Brush -> Tool.Brush
        }
        category = first.category
        activePresetId = first.id
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

    /** Back to the colour painted with before this one (and again to return). */
    fun swapColor() {
        val other = recentColors.firstOrNull { it != color } ?: return
        chooseColor(other)
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
            engine.currentCompositePixel(px, py)
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
        fillTolerance = value.coerceIn(1, TOLERANCE_MAX)
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

    /** Where the last paint bucket spent its time (harness timing). */
    @Volatile var lastFillProfile: String = ""

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

    // ColorDrop with a threshold: drop and hold, then slide to adjust.
    private var dropX = 0f
    private var dropY = 0f
    private var dropVersion = -1
    @Volatile private var dropPendingTolerance = -1

    /** The chip was held still over the canvas: fill there and remember it. */
    fun colorDropBegin(vx: Float, vy: Float) = post {
        val doc = FloatArray(2)
        view.toDocument(vx, vy, doc)
        dropX = doc[0]
        dropY = doc[1]
        val before = history.version
        fillAtInternal(dropX, dropY, color, true)
        dropVersion = if (history.version != before) history.version else -1
    }

    /**
     * The finger slides while held: refill the same spot at [tolerance]
     * (the previous preview fill is undone first). Calls coalesce: only the
     * latest value is applied.
     */
    fun colorDropAdjust(tolerance: Int) {
        val first = dropPendingTolerance < 0
        dropPendingTolerance = tolerance.coerceIn(1, TOLERANCE_MAX)
        if (!first) return
        post {
            val t = dropPendingTolerance
            dropPendingTolerance = -1
            if (t < 0 || t == fillTolerance && dropVersion == history.version) return@post
            if (dropVersion >= 0 && dropVersion == history.version) doUndo()
            fillTolerance = t
            val before = history.version
            fillAtInternal(dropX, dropY, color, true)
            dropVersion = if (history.version != before) history.version else -1
            requestState()
        }
    }

    /** The finger lifted: keep the last fill (and its tolerance for next time). */
    fun colorDropEnd() = post {
        dropVersion = -1
        requestState()
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
        var cap: TileCapture? = null
        cancellable({
            cap?.let {
                it.restore()
                it.release()
                engine.markDirtyAll()
            }
        }) { fillAtWork(fx, fy, fillColor, compositeOnly) { cap = it } }
    }

    /**
     * [paint] drawn over a flat tile ([before] = its shared solid, or null
     * for an absent tile reading as [defaultPixel]): the shared solid of the
     * result, or null when it is not flat. Drawn on a real tile-sized copy, so
     * the bytes are exactly those of drawing on the tile itself.
     */
    private fun flatFillResult(before: Bitmap?, defaultPixel: Int, w: Int, h: Int, paint: Paint): Bitmap? {
        val scratch = before?.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                if (defaultPixel != 0) {
                    val px = IntArray(w * h) { defaultPixel }
                    it.copyPixelsFromBuffer(java.nio.IntBuffer.wrap(px))
                }
            }
        try {
            if (scratch.width != w || scratch.height != h) return null
            Canvas(scratch).drawPaint(paint)
            val buf = java.nio.IntBuffer.allocate(w * h)
            scratch.copyPixelsToBuffer(buf)
            val px = buf.array()
            val raw = px[0]
            for (v in px) if (v != raw) return null
            return SolidTiles.obtain(scratch, raw, scratch.getPixel(0, 0))
        } finally {
            scratch.recycle()
        }
    }

    private fun fillAtWork(fx: Float, fy: Float, fillColor: Int, compositeOnly: Boolean, onCapture: (TileCapture) -> Unit) {
        val layer = engine.activeLayer ?: return
        if (!editable(layer)) return
        if (adjusting) commitAdjustmentInternal()
        val x = fx.toInt()
        val y = fy.toInt()
        if (x !in 0 until engine.width || y !in 0 until engine.height) return
        val t0 = System.nanoTime()
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
        val t1 = System.nanoTime()
        val capture = TileOps.capture(store, bounds)
        onCapture(capture)
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
        if (store.isSparse) {
            // Tile by tile, each clipped to its own part of the region (one
            // path for the whole region was gigabytes for a fragmented region,
            // a fill around noise or texture). Same bytes as drawing it all.
            // A tile the region covers completely: an opaque fill is just the
            // colour, and any fill over a flat tile is flat too, so both
            // become one shared solid each (no pixels to draw or keep).
            val sp = store as? SparseTileStore
            val opaqueNormal = !mask && !layer.alphaLock && Color.alpha(fillColor) == 255
            val flatResults = HashMap<Long, Bitmap?>()
            val ts = store.tileSize
            val tile = Rect()
            val part = android.graphics.Region()
            for (ty in bounds.top / ts..(bounds.bottom - 1) / ts) {
                for (tx in bounds.left / ts..(bounds.right - 1) / ts) {
                    OpCancel.check()
                    tile.set(tx * ts, ty * ts, min(engine.width, (tx + 1) * ts), min(engine.height, (ty + 1) * ts))
                    part.set(region)
                    if (!part.op(tile, android.graphics.Region.Op.INTERSECT) || part.isEmpty) continue
                    if (part.isRect && part.bounds == tile) {
                        if (opaqueNormal) {
                            store.fillRect(tile, fillColor)
                            continue
                        }
                        val flat = sp?.flatColourAt(tx, ty) ?: -1L
                        if (sp != null && flat >= 0L) {
                            // The fill drawn once on a copy of the flat tile.
                            val fk = (flat shl 24) xor (tile.width().toLong() shl 12) xor tile.height().toLong()
                            val result = flatResults.getOrPut(fk) {
                                flatFillResult(sp.sharedSolidAt(tx, ty), store.defaultPixel, tile.width(), tile.height(), paint)
                            }
                            if (result != null) {
                                store.putTileRegion(tx, ty, tile, result)
                                continue
                            }
                        }
                        store.withWriteCanvas(Rect(tile)) { c ->
                            val save = c.save()
                            c.clipRect(tile)
                            c.drawPaint(paint)
                            c.restoreToCount(save)
                        }
                    } else {
                        val partPath = SelectionUtil.regionFillPath(part)
                        store.withWriteCanvas(Rect(part.bounds)) { c ->
                            val save = c.save()
                            c.clipPath(partPath)
                            c.drawPaint(paint)
                            c.restoreToCount(save)
                        }
                    }
                }
            }
        } else {
            val path = SelectionUtil.regionFillPath(region)
            store.withWriteCanvas(bounds) { c ->
                val save = c.save()
                c.clipPath(path)
                c.drawPaint(paint)
                c.restoreToCount(save)
            }
        }
        val t2 = System.nanoTime()
        engine.makeTilePatch(
            layer, capture, bounds, "Fill", EditKind.LayerFill, "Tolerance ${tolerancePercent(fillTolerance)}%", fillColor
        )?.let { history.push(it) }
        val t3 = System.nanoTime()
        lastFillProfile = "[${SelectionUtil.lastProfile}] region=${(t1 - t0) / 1_000_000}ms paint=${(t2 - t1) / 1_000_000}ms history=${(t3 - t2) / 1_000_000}ms"
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
        wandTolerance = value.coerceIn(1, TOLERANCE_MAX)
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
        val canvasBytes = engine.width.toLong() * engine.height * 4L
        if (canvasBytes > LARGE_CANVAS_BYTES) {
            // Very large canvases: draw the picture straight into the new
            // layer's tiles instead of building a canvas-sized bitmap first.
            val layer = engine.newLayer(name = name, at = engine.activeIndex + 1)
            val scale = min(engine.width.toFloat() / source.width, engine.height.toFloat() / source.height)
            val w = source.width * scale
            val h = source.height * scale
            val left = (engine.width - w) / 2f
            val top = (engine.height - h) / 2f
            val dst = RectF(left, top, left + w, top + h)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            layer.tiles.withWriteCanvas(Rect(left.toInt(), top.toInt(), (left + w).toInt() + 1, (top + h).toInt() + 1)) { c ->
                c.drawBitmap(source, null, dst, p)
            }
            engine.markDirtyAll()
        } else {
            val scaled = fitToCanvas(source, engine.width, engine.height)
            engine.newLayer(name = name, bitmap = scaled, at = engine.activeIndex + 1)
        }
        pushStructural(before, "Import image", EditKind.Import)
    }

    /** Where the text tool was tapped (document px), waiting for the text dialog. */
    val textRequest = kotlinx.coroutines.flow.MutableStateFlow<FloatArray?>(null)

    /**
     * Adds a text layer at ([x], [y]) (the top-left of the first line for
     * left-aligned text; the alignment edge otherwise). The text is drawn
     * straight into the new layer's tiles, only over its own bounds.
     */
    fun addTextLayer(
        text: String, x: Float, y: Float, sizePx: Float, font: String,
        bold: Boolean, italic: Boolean, align: Int, color: Int
    ) = post {
        val lines = text.split('\n')
        if (text.isBlank()) return@post
        if (!MemoryBudget.fitsDocument(engine.width, engine.height, engine.layers.size + 1)) {
            onMessage?.invoke(MemoryBudget.documentRefusal(engine.width, engine.height, engine.layers.size + 1))
            return@post
        }
        val style = when {
            bold && italic -> android.graphics.Typeface.BOLD_ITALIC
            bold -> android.graphics.Typeface.BOLD
            italic -> android.graphics.Typeface.ITALIC
            else -> android.graphics.Typeface.NORMAL
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = android.graphics.Typeface.create(font, style)
            textSize = sizePx.coerceIn(4f, 2000f)
            this.color = color
            textAlign = when (align) {
                1 -> Paint.Align.CENTER
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
        }
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 1.15f
        val widest = lines.maxOf { paint.measureText(it) }
        val left = when (align) {
            1 -> x - widest / 2f
            2 -> x - widest
            else -> x
        }
        val bounds = Rect(
            (left - sizePx).toInt(), (y - sizePx * 0.2f).toInt(),
            (left + widest + sizePx).toInt(), (y + lineH * lines.size + sizePx).toInt()
        )
        val before = engine.snapshotState()
        val name = "Text: " + lines.first().take(24)
        val layer = engine.newLayer(name = name, at = engine.activeIndex + 1)
        layer.tiles.withWriteCanvas(bounds) { c ->
            lines.forEachIndexed { i, line -> c.drawText(line, x, y - fm.ascent + i * lineH, paint) }
        }
        engine.markDirtyAll()
        pushStructural(before, "Text", EditKind.LayerAdd)
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
        val gid = engine.activeGroupId
        if (gid != 0L) {
            val before = engine.snapshotState()
            if (engine.duplicateGroup(gid)) pushStructural(before, "Duplicate group", EditKind.LayerDuplicate)
            return@post
        }
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

    fun mergeDown() = post { working { mergeDownWork() } }

    private fun mergeDownWork() {
        val gid = engine.activeGroupId
        if (gid != 0L) {
            val before = engine.snapshotState()
            if (engine.mergeGroup(gid)) pushStructural(before, "Merge group", EditKind.LayerOrder)
            return
        }
        val id = engine.activeLayer?.id ?: return
        val extra = engine.mergeCost(id)
        if (extra > 0 && !engine.canAfford(extra)) {
            onMessage?.invoke(MemoryBudget.decodeRefusal(engine.width, engine.height, extra))
            return
        }
        val before = engine.snapshotState()
        var merged = false
        // The merged layer is built on the side: a cancel changes nothing.
        cancellable({ engine.markDirtyAll() }) { merged = engine.mergeDown(id) }
        if (merged) {
            // Replayable: redo recomputes the merge from the restored layers.
            history.push(MergeAction(before, upperId = id, label = "Merge down"))
            engine.markDirtyAll()
            requestState()
        }
    }

    fun deleteLayer() = post {
        val gid = engine.activeGroupId
        if (gid != 0L) {
            val before = engine.snapshotState()
            if (engine.deleteGroup(gid)) pushStructural(before, "Delete group", EditKind.LayerDelete)
            return@post
        }
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

    fun fillLayer(color: Int) = post { working { fillLayerWork(color) } }

    private fun fillLayerWork(color: Int) {
        val layer = engine.activeLayer ?: return
        if (!editable(layer)) return
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
        engine.markLayerDirty(layer)
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
            engine.markLayerDirty(it)
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
        engine.markLayerDirty(layer)
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
            engine.markLayerDirty(it)
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
        engine.markLayerDirty(layer)
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
            engine.markGroupDirty(id)
            requestState()
        }
    }

    fun setGroupBlend(id: Long, blend: LayerBlend) = post {
        engine.groupById(id)?.let {
            it.blend = blend
            touchProperties()
            engine.markGroupDirty(id)
            requestState()
        }
    }

    fun toggleGroupVisibility(id: Long) = post {
        engine.groupById(id)?.let {
            it.visible = !it.visible
            touchProperties()
            engine.markGroupDirty(id)
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
    fun moveLayerTo(id: Long, targetIndex: Int, groupId: Long? = null) = post {
        val from = engine.indexOf(id)
        if (from < 0) return@post
        val to = targetIndex.coerceIn(0, engine.layers.size - 1)
        val layer = engine.layers[from]
        if (from == to && (groupId == null || layer.groupId == groupId)) return@post
        val before = engine.snapshotState()
        val oldGroup = layer.groupId
        if (groupId != null) engine.moveLayerInto(from, to, groupId) else engine.moveLayer(from, to)
        val label = when {
            groupId == null || groupId == oldGroup -> "Reorder layers"
            groupId != 0L -> "Move into group"
            else -> "Move out of group"
        }
        pushStructural(before, label, EditKind.LayerOrder)
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

    private fun doUndo() = working { doUndoWork() }

    private fun doUndoWork() {
        if (transform != null) cancelTransformInternal()
        cancelAdjustmentInternal()
        val action = history.peekUndo() ?: return
        if (!ensureHistoryBudget(action, undo = true)) return
        if (history.undo(engine)) {
            // Pixel edits mark exactly the tiles they put back; only layer
            // structure changes need the whole canvas recomposited (at 16K
            // that is 4096 tiles, many of them read from disk).
            if (!marksOwnDamage(action)) engine.markDirtyAll()
            requestState()
        }
    }

    private fun doRedo() = working { doRedoWork() }

    private fun doRedoWork() {
        val action = history.peekRedo() ?: return
        if (!ensureHistoryBudget(action, undo = false)) return
        if (history.redo(engine)) {
            if (!marksOwnDamage(action)) engine.markDirtyAll()
            requestState()
        }
    }

    /** True for steps whose undo / redo marks exactly the area it changes. */
    private fun marksOwnDamage(action: HistoryAction): Boolean = when (action) {
        is TilePixelAction, is PixelAction -> true
        is GroupAction -> action.actions.all { marksOwnDamage(it) }
        else -> false
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
    private val MOTION_MAX_RADIUS = 48

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
        val done = cancellable({
            capture.restore()
            capture.release()
            engine.markDirtyAll()
        }) {
            TileOps.forEachTile(store, bounds, 0) { patch, pad, tile ->
                block(patch)
                TileOps.writePart(store, patch, pad, tile)
            }
        }
        if (!done) return
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
        // The first preview of a big layer builds its small copy: that shows
        // the working card like any other slow step.
        workSince = android.os.SystemClock.uptimeMillis()
        try {
            applyPreviewInternal(kind, pendingPreviewAmount)
        } finally {
            workSince = 0L
        }
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
            val halo = haloOf(kind)
            adjustWork = if (halo > 0) growBounds(base, halo) else base
            adjustBounds = if (halo > 0) growBounds(base, halo * 2) else base
            // Sparse layers are not copied here: a preview never touches the
            // layer, so the undo copy is taken when Apply bakes it. (Copying a
            // painted 16K layer first made every preview start with a wait.)
            var px = base.width().toLong() * base.height()
            if (adjustAllLayers) {
                for (other in engine.layers) {
                    if (other === layer || !other.visible || !editable(other)) continue
                    val ob = editBounds(other.paintStore)
                    px += ob.width().toLong() * ob.height()
                }
            }
            adjustPixels = px
            if (adjustSnapshot == null && !store.isSparse) {
                adjustSnapshot = store.readRect(Rect(0, 0, engine.width, engine.height))
            }
        }
        val snapshot = adjustSnapshot
        if (!store.isSparse && snapshot == null) return
        adjustKind = kind
        adjustAmount = amount

        val preview = when (kind) {
            AdjustmentKind.Hue -> LayerPreview(
                previewTarget(layer),
                ColorMatrixColorFilter(Adjustments.hsvMatrix(amount, 1f, 1f)),
                null
            )
            AdjustmentKind.Saturation -> LayerPreview(
                previewTarget(layer),
                ColorMatrixColorFilter(Adjustments.hsvMatrix(0f, 1f + amount, 1f)),
                null
            )
            AdjustmentKind.Brightness -> LayerPreview(
                previewTarget(layer),
                ColorMatrixColorFilter(Adjustments.hsvMatrix(0f, 1f, 1f + amount)),
                null
            )
            AdjustmentKind.Blur -> {
                // Sparse layers build the proxy from their tiles, so no
                // canvas-sized snapshot is needed for the live preview.
                val proxy = gpuPreview(store, snapshot, kind, amount)
                    ?: if (store.isSparse) blurProxyFromStore(store, amount)
                    else Adjustments.blurProxyInto(snapshot!!, amount, adjustProxy)
                adjustProxy = proxy
                LayerPreview(layer.id, null, proxy)
            }
            // Sharpen and motion blur preview on the GPU (a reduced copy:
            // close, while Apply bakes the exact CPU kernels). Without a GPU,
            // and for noise (grain at preview size would mislead), no preview.
            AdjustmentKind.Sharpen, AdjustmentKind.MotionBlur, AdjustmentKind.Noise ->
                gpuPreview(store, snapshot, kind, amount)?.let { proxy ->
                    adjustProxy = proxy
                    LayerPreview(layer.id, null, proxy)
                }
            AdjustmentKind.Curves, AdjustmentKind.Levels, AdjustmentKind.ColorBalance,
            AdjustmentKind.GradientMap, AdjustmentKind.Posterize, AdjustmentKind.Threshold -> {
                val lut = toneLut ?: return
                val proxy = toneProxy(store, snapshot, lut)
                adjustProxy = proxy
                LayerPreview(layer.id, null, proxy)
            }
        }
        engine.applyPreview(preview)
        requestState()
    }

    /** The lookup table the tone adjustments (curves, levels, ...) preview and bake. */
    @Volatile var toneLut: ToneLut? = null
        private set

    /** Live preview of a tone adjustment built by the panel's editor. */
    fun previewTone(kind: AdjustmentKind, lut: ToneLut) {
        toneLut = lut
        previewAdjustment(kind, 1f)
    }

    /**
     * Reduced-resolution copy of the layer with the tone table applied, for
     * the live preview: at most 2048 px on its long edge, so an 8K layer never
     * needs a full-size working copy while the user drags a curve.
     */
    private fun toneProxy(store: TileStore, snapshot: Bitmap?, lut: ToneLut): Bitmap {
        val scale = minOf(1f, 2048f / maxOf(engine.width, engine.height))
        val w = max(1, (engine.width * scale).toInt())
        val h = max(1, (engine.height * scale).toInt())
        val reuse = adjustProxy
        val small = if (reuse != null && !reuse.isRecycled && reuse.width == w && reuse.height == h) reuse
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        c.drawColor(0, BlendMode.CLEAR)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (snapshot != null) {
            c.drawBitmap(snapshot, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
        } else {
            val save = c.save()
            c.scale(w.toFloat() / engine.width, h.toFloat() / engine.height)
            store.drawInto(c, p)
            c.restoreToCount(save)
        }
        lut.apply(small)
        return small
    }

    /**
     * A filter preview made on the GPU: [kind] at [amount] applied to a small
     * copy of the layer (quarter size for blur, at most 2048 px otherwise).
     * The copy is made once per adjustment; each slider move is one GPU pass.
     * Null when the GPU is not usable (the caller falls back).
     */
    private fun gpuPreview(store: TileStore, snapshot: Bitmap?, kind: AdjustmentKind, amount: Float): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 33 || !GpuFilter.available) return null
        // One small copy serves every filter, so switching between them
        // does not rebuild it.
        val scale = minOf(1f, 2048f / maxOf(engine.width, engine.height))
        val w = max(1, (engine.width * scale).toInt())
        val h = max(1, (engine.height * scale).toInt())
        var base = adjustBase
        if (base == null || base.isRecycled || base.width != w || base.height != h) {
            base?.recycle()
            if (snapshot == null && !store.isSparse) return null
            base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(base)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            if (snapshot != null) {
                c.drawBitmap(snapshot, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
            } else {
                val save = c.save()
                c.scale(w.toFloat() / engine.width, h.toFloat() / engine.height)
                store.drawInto(c, p)
                c.restoreToCount(save)
            }
            adjustBase = base
            adjustBaseKind = kind
        }
        val px = w.toFloat() / engine.width
        // A shader that does not build on this GPU means no GPU preview.
        val effect = try { when (kind) {
            AdjustmentKind.Blur -> GpuFilter.blurEffect((amount * 24f).toInt().coerceIn(1, 24), px)
            AdjustmentKind.Sharpen -> GpuFilter.sharpenEffect(amount.coerceIn(0f, 1f) * 2f)
            AdjustmentKind.MotionBlur ->
                GpuFilter.motionEffect(amount.coerceIn(0f, 1f) * MOTION_MAX_RADIUS * px, motionAngle)
            AdjustmentKind.Noise ->
                GpuFilter.noiseEffect((amount.coerceIn(0f, 1f) * 90f).toInt().coerceIn(1, 120) / 255f)
            else -> return null
        } } catch (t: IllegalArgumentException) {
            android.util.Log.w("YumeGpu", "preview shader failed", t)
            return null
        }
        val out = GpuFilter.apply(base, effect) ?: return null
        val reuse = adjustProxy
        val proxy = if (reuse != null && !reuse.isRecycled && reuse.width == w && reuse.height == h) reuse
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(proxy).drawBitmap(out, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC })
        out.recycle()
        return proxy
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

    private var bakeWrites = 0

    /**
     * Keeps the tile cache near its budget while a big bake writes: written
     * tiles go to disk here, under the working card, instead of all at once
     * on the first frame after Apply (at 16K that was one frame of ~17 s).
     */
    private fun bakeSafePoint() {
        if (++bakeWrites % 32 == 0) TileCache.active?.safePoint()
    }

    /** Adjustments whose bake captures each tile just before writing it. */
    private fun lazyCapture(kind: AdjustmentKind): Boolean =
        kind == AdjustmentKind.Sharpen || kind == AdjustmentKind.MotionBlur

    private fun previewTarget(layer: Layer): Long =
        if (adjustAllLayers) LayerPreview.ALL_LAYERS else layer.id

    private fun commitAdjustmentInternal() = working { commitAdjustmentInternalWork() }

    private fun commitAdjustmentInternalWork() {
        val layer = engine.activeLayer
        val dense = adjustSnapshot
        val eager = adjustCapture
        val bounds = Rect(adjustBounds)
        val kind = adjustKind
        val amount = adjustAmount
        engine.applyPreview(null)
        adjustProxy?.recycle()
        adjustProxy = null
        adjustBase?.recycle()
        adjustBase = null
        adjustBaseKind = null
        adjustSnapshot = null
        adjustCapture = null
        adjustKind = null
        adjustWork.setEmpty()
        if (layer == null || kind == null) {
            requestState()
            return
        }
        val work = if (adjustWork.isEmpty) bounds else Rect(adjustWork)
        val pixels = adjustPixels
        adjustPixels = 0L
        adjustAmount = amount
        val startedAt = android.os.SystemClock.uptimeMillis()
        adjustPixels = pixels
        workEtaMs = (adjustEstimateSeconds(kind) * 1000f).toLong()
        adjustPixels = 0L
        // The undo copy of the layer, taken now that it is really needed. The
        // chunked kernels fill it as they write, so tiles they leave alone
        // (empty, flat) are never copied.
        val capture = eager ?: if (dense == null && layer.paintStore.isSparse) {
            if (lazyCapture(kind)) TileCapture(layer.paintStore) else TileOps.capture(layer.paintStore, bounds)
        } else null
        val actions = ArrayList<HistoryAction>()
        var inFlight: TileCapture? = capture
        val baked = cancellable({
            // The layer being baked goes back from its capture; finished
            // layers are undone.
            inFlight?.let {
                it.restore()
                it.release()
            }
            for (a in actions.asReversed()) {
                a.undo(engine)
                history.discard(a)
            }
            engine.markDirtyAll()
        }) {
        bakeAdjustment(layer, capture, dense, bounds, work, kind, amount)?.let { actions.add(it) }
        inFlight = null
        layer.touch()
        if (adjustAllLayers) {
            // Same adjustment on every other visible, unlocked layer, each from
            // its own pre-edit snapshot; all of it is one undo step.
            val full = Rect(0, 0, engine.width, engine.height)
            val halo = haloOf(kind)
            for (other in engine.layers) {
                if (other === layer || !other.visible || !editable(other)) continue
                val store = other.paintStore
                val base = editBounds(store)
                if (base.isEmpty) continue
                val b = if (halo > 0) growBounds(base, halo * 2) else base
                val w = if (halo > 0) growBounds(base, halo) else base
                val cap = if (!store.isSparse) null
                else if (lazyCapture(kind)) TileCapture(store) else TileOps.capture(store, b)
                val snap = if (store.isSparse) null else store.readRect(full)
                inFlight = cap
                bakeAdjustment(other, cap, snap, b, w, kind, amount)?.let { actions.add(it) }
                inFlight = null
                other.touch()
            }
        }
        }
        workEtaMs = 0L
        // Back within the cache budget before the screen catches up.
        OpCancel.progress = 1f
        TileCache.active?.safePoint()
        if (!baked) return
        // Remember how fast this device did it, for the next estimate.
        val mp = pixels / 1_000_000f
        if (mp > 2f) {
            var rate = (android.os.SystemClock.uptimeMillis() - startedAt) / 1000f / mp
            if (kind == AdjustmentKind.MotionBlur) rate /= (0.25f + amount.coerceIn(0f, 1f) * 1.5f)
            synchronized(adjustRates) {
                val old = adjustRates[kind]
                adjustRates[kind] = if (old == null) rate else old * 0.4f + rate * 0.6f
            }
        }
        when (actions.size) {
            0 -> Unit
            1 -> history.push(actions[0])
            else -> history.push(GroupAction(actions, "Adjust ${kind.label} · all layers", EditKind.Adjust))
        }
        layer.touch()
        engine.markDirtyAll()
        requestState()
    }

    /**
     * Bakes one adjustment into [layer] from its pre-edit state ([capture] for
     * sparse stores, [snapshot] for dense ones) and returns the undo entry.
     */
    private fun bakeAdjustment(
        layer: Layer,
        capture: TileCapture?,
        snapshot0: Bitmap?,
        bounds: Rect,
        work: Rect,
        kind: AdjustmentKind,
        amount: Float
    ): HistoryAction? {
        if (capture != null) {
            // Sparse: bake tile by tile from the pre-edit snapshot.
            val store = layer.paintStore
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
                AdjustmentKind.Sharpen -> Kernels.sharpen(
                    engine.width, engine.height, work, amount.coerceIn(0f, 1f) * 2f,
                    Kernels.captureSource(capture, lazy = true)
                ) { r, bmp ->
                    TileOps.captureRect(capture, r)
                    store.writeRect(r, bmp, Rect(0, 0, r.width(), r.height()))
                    bakeSafePoint()
                }
                AdjustmentKind.MotionBlur -> Kernels.motionBlur(
                    engine.width, engine.height, work,
                    (amount.coerceIn(0f, 1f) * MOTION_MAX_RADIUS).toInt(), motionAngle,
                    Kernels.captureSource(capture, lazy = true)
                ) { r, bmp ->
                    TileOps.captureRect(capture, r)
                    store.writeRect(r, bmp, Rect(0, 0, r.width(), r.height()))
                    bakeSafePoint()
                }
                AdjustmentKind.Curves, AdjustmentKind.Levels, AdjustmentKind.ColorBalance,
                AdjustmentKind.GradientMap, AdjustmentKind.Posterize, AdjustmentKind.Threshold ->
                    toneLut?.let { TileOps.tone(store, work, it) }
            }
            // The action covers the whole snapshot region: a blurred tile past
            // the work rect is unchanged, and copying it costs nothing extra.
            return engine.makeTilePatch(
                layer, capture, bounds, "Adjust ${kind.label}", EditKind.Adjust,
                capturedOnly = lazyCapture(kind)
            )
        } else {
            val snapshot = snapshot0
            if (snapshot == null) return null
            // Bake from the untouched snapshot; both bitmaps become the entry.
            val target = copyPixels(snapshot)
            when (kind) {
                AdjustmentKind.Hue -> Adjustments.hsb(target, amount, 1f, 1f)
                AdjustmentKind.Saturation -> Adjustments.hsb(target, 0f, 1f + amount, 1f)
                AdjustmentKind.Brightness -> Adjustments.hsb(target, 0f, 1f, 1f + amount)
                AdjustmentKind.Blur ->
                    Adjustments.blur(target, (amount * 24f).toInt().coerceIn(1, 24))
                AdjustmentKind.Noise -> Adjustments.noise(target, amount.coerceIn(0f, 1f))
                // Same chunked kernels as the sparse path, so both stores match.
                AdjustmentKind.Sharpen -> Kernels.sharpen(
                    engine.width, engine.height, Rect(0, 0, engine.width, engine.height),
                    amount.coerceIn(0f, 1f) * 2f, Kernels.bitmapSource(snapshot)
                ) { r, bmp ->
                    android.graphics.Canvas(target).drawBitmap(
                        bmp, r.left.toFloat(), r.top.toFloat(),
                        Paint().apply { blendMode = BlendMode.SRC }
                    )
                }
                AdjustmentKind.MotionBlur -> Kernels.motionBlur(
                    engine.width, engine.height, Rect(0, 0, engine.width, engine.height),
                    (amount.coerceIn(0f, 1f) * MOTION_MAX_RADIUS).toInt(), motionAngle,
                    Kernels.bitmapSource(snapshot)
                ) { r, bmp ->
                    android.graphics.Canvas(target).drawBitmap(
                        bmp, r.left.toFloat(), r.top.toFloat(),
                        Paint().apply { blendMode = BlendMode.SRC }
                    )
                }
                AdjustmentKind.Curves, AdjustmentKind.Levels, AdjustmentKind.ColorBalance,
                AdjustmentKind.GradientMap, AdjustmentKind.Posterize, AdjustmentKind.Threshold -> toneLut?.apply(target)
            }
            val full = Rect(0, 0, engine.width, engine.height)
            layer.tiles.writeRect(full, target)
            return PixelAction(
                layer.id, full,
                snapshot, target, "Adjust ${kind.label}", EditKind.Adjust
            )
        }
        return null
    }

    private fun cancelAdjustmentInternal() {
        engine.applyPreview(null)
        adjustProxy?.recycle()
        adjustProxy = null
        adjustBase?.recycle()
        adjustBase = null
        adjustBaseKind = null
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
        adjustPixels = 0L
        adjustWork.setEmpty()
        requestState()
    }

    fun commitAdjustment() = post { commitAdjustmentInternal() }

    fun cancelAdjustment() = post { cancelAdjustmentInternal() }

    private fun blitFull(layer: Layer, source: Bitmap) {
        layer.tiles.writeRect(Rect(0, 0, engine.width, engine.height), source)
    }

    fun invertColors() = post { working { invertColorsWork() } }

    private fun invertColorsWork() {
        val layer = engine.activeLayer ?: return
        pixelEdit(layer, "Invert") { Adjustments.invert(it) }
    }

    fun grayscale() = post { working { grayscaleWork() } }

    private fun grayscaleWork() {
        val layer = engine.activeLayer ?: return
        pixelEdit(layer, "Grayscale") { Adjustments.grayscale(it) }
    }

    // ------------------------------------------------------------ viewport

    fun fitToScreen() = post {
        view.fit()
        requestState()
    }

    /** Mirrors the view left-right, to check a drawing with fresh eyes. */
    fun toggleMirrorView() = post {
        // Keep the document point at the screen centre where it is.
        val c = FloatArray(2)
        view.toDocument(view.viewWidth / 2f, view.viewHeight / 2f, c)
        view.mirrored = !view.mirrored
        view.update()
        val m = FloatArray(2)
        view.toScreen(c[0], c[1], m)
        view.pan(view.viewWidth / 2f - m[0], view.viewHeight / 2f - m[1])
        requestState()
    }

    val viewMirrored: Boolean get() = view.mirrored

    /** Zoom to 100 % (one document pixel per screen pixel) around the screen centre. */
    fun zoomActualSize() = post {
        if (view.scale > 0f) {
            view.applyGesture(view.viewWidth / 2f, view.viewHeight / 2f, 1f / view.scale, 0f, 0f, 0f)
            requestState()
        }
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
                maskSparse = it.maskTiles?.takeIf { m -> m.isSparse },
                store = it.tiles,
                maskStore = it.maskTiles
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
        composeThumb = { maxPx -> engine.compositeThumb(maxPx) },
        slots = IntArray(engine.layers.size) { (engine.layers[it].tiles as? SparseTileStore)?.journalSlot ?: -1 },
        maskSlots = IntArray(engine.layers.size) {
            (engine.layers[it].maskTiles as? SparseTileStore)?.journalSlot ?: -1
        },
        symmetryX = engine.symmetryX,
        symmetryY = engine.symmetryY,
        symmetryXPos = engine.symmetryXPos,
        symmetryYPos = engine.symmetryYPos,
        extras = engine.extrasJson()
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
    fun historyForDiskBlocking(saved: Set<Long> = emptySet()): PersistedHistory? {
        val latch = CountDownLatch(1)
        var out: PersistedHistory? = null
        post {
            out = historyForDiskNow(saved)
            latch.countDown()
        }
        if (!latch.await(6, TimeUnit.SECONDS)) return null
        return out
    }

    /** Saved group id -> this session's group id (groups get new ids when a project opens). */
    private var restoreGroupIds: Map<Long, Long> = emptyMap()

    /** True when every layer and group [s] names is in the document now. */
    private fun stateResolvable(s: DocumentState): Boolean =
        s.layers.all { engine.layerById(it.layer.id) === it.layer } &&
            s.groups.all { engine.groupById(it.group.id) === it.group }

    private fun stateJson(s: DocumentState): org.json.JSONObject = org.json.JSONObject().apply {
        put("layers", org.json.JSONArray().apply {
            for (ls in s.layers) put(org.json.JSONObject().apply {
                val p = ls.props
                put("id", ls.layer.id); put("name", p.name); put("opacity", p.opacity.toDouble())
                put("blend", p.blend.name); put("visible", p.visible); put("alphaLock", p.alphaLock)
                put("locked", p.locked); put("clipping", p.clipping); put("maskEnabled", p.maskEnabled)
                put("groupId", p.groupId); put("editMask", p.editMask)
            })
        })
        put("groups", org.json.JSONArray().apply {
            for (g in s.groups) put(org.json.JSONObject().apply {
                put("id", g.group.id); put("name", g.name); put("opacity", g.opacity.toDouble())
                put("blend", g.blend.name); put("visible", g.visible); put("locked", g.locked)
                put("expanded", g.expanded)
            })
        })
        put("active", s.activeIndex)
        put("activeGroup", s.activeGroupId)
    }

    /** The state [o] describes, with today's layer and group objects; null if one is missing. */
    private fun stateFromJson(o: org.json.JSONObject): DocumentState? {
        val la = o.optJSONArray("layers") ?: return null
        val layers = ArrayList<LayerState>(la.length())
        for (i in 0 until la.length()) {
            val j = la.getJSONObject(i)
            val layer = engine.layerById(j.optLong("id")) ?: return null
            layers.add(LayerState(layer, LayerProps(
                name = j.optString("name", layer.name),
                opacity = j.optDouble("opacity", 1.0).toFloat(),
                blend = LayerBlend.fromName(j.optString("blend", "Normal")),
                visible = j.optBoolean("visible", true),
                alphaLock = j.optBoolean("alphaLock", false),
                locked = j.optBoolean("locked", false),
                clipping = j.optBoolean("clipping", false),
                maskEnabled = j.optBoolean("maskEnabled", true),
                groupId = j.optLong("groupId", 0L).let { g -> if (g == 0L) 0L else restoreGroupIds[g] ?: g },
                editMask = j.optBoolean("editMask", false)
            )))
        }
        val ga = o.optJSONArray("groups") ?: org.json.JSONArray()
        val groups = ArrayList<GroupState>(ga.length())
        for (i in 0 until ga.length()) {
            val j = ga.getJSONObject(i)
            val gid = j.optLong("id")
            val g = engine.groupById(restoreGroupIds[gid] ?: gid) ?: return null
            groups.add(GroupState(
                g, j.optString("name", g.name), j.optDouble("opacity", 1.0).toFloat(),
                LayerBlend.fromName(j.optString("blend", "Normal")), j.optBoolean("visible", true),
                j.optBoolean("locked", false), j.optBoolean("expanded", true)
            ))
        }
        return DocumentState(layers, groups, o.optInt("active", 0), o.optLong("activeGroup", 0L))
    }

    /**
     * [historyForDiskBlocking] on the owner thread. Steps whose files an
     * earlier save already wrote ([saved]) are described without copying
     * their pixels again.
     */
    fun historyForDiskNow(saved: Set<Long> = emptySet()): PersistedHistory {
        run {
            val (run, applied) = history.persistableRun { action ->
                when (action) {
                    is PixelAction, is TilePixelAction -> engine.indexOf(action.layerId) >= 0
                    // Layer structure persists while every layer and group it
                    // names is still in the document (a deleted layer's pixels
                    // live only in memory).
                    is LayersAction -> stateResolvable(action.before) && stateResolvable(action.after)
                    else -> false
                }
            }
            val entries = ArrayList<PersistedHistoryEntry>(run.size)
            // Walk newest to oldest, stopping at an edit whose layer is gone or
            // whose legacy rectangle would exceed the persist budget.
            for (action in run.asReversed()) {
                if (action is LayersAction) {
                    entries.add(PersistedHistoryEntry(
                        actionId = action.id, label = action.label, kind = action.kind,
                        detail = "", swatch = 0, layerIndex = -1, rect = Rect(),
                        before = null, after = null,
                        structure = org.json.JSONObject().apply {
                            put("before", stateJson(action.before))
                            put("after", stateJson(action.after))
                        }.toString()
                    ))
                    continue
                }
                val index = engine.indexOf(action.layerId)
                if (index < 0) break
                if (action.id in saved && (action is TilePixelAction || action is PixelAction)) {
                    entries.add(PersistedHistoryEntry(
                        actionId = action.id, label = action.label, kind = action.kind,
                        detail = "", swatch = 0, layerIndex = index, rect = Rect(),
                        before = null, after = null, layerId = action.layerId, reused = true
                    ))
                    continue
                }
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
                        mask = action.mask,
                        layerId = action.layerId
                    )
                    is TilePixelAction -> {
                        // A streamed step (too big to hold in memory) is not
                        // saved with the project: its copies alone would be
                        // gigabytes. The saved history ends after it.
                        if (action.stream != null && !action.isResident) break
                        // A spilled (disk-tier) step is read back for the write.
                        if (!history.ensureLoaded(action)) break
                        val layerNow = engine.layers[index]
                        val store = if (action.mask) layerNow.maskTiles ?: break else layerNow.tiles
                        // Copies: the save writes them off this thread while
                        // the history may trim or spill the originals.
                        PersistedHistoryEntry(
                            actionId = action.id,
                            label = action.label,
                            kind = action.kind,
                            detail = action.detail,
                            swatch = action.swatch,
                            layerIndex = index,
                            rect = Rect(action.bounds),
                            before = null,
                            after = null,
                            mask = action.mask,
                            tiles = action.changes.map { c ->
                                val bRaw = c.before?.let { SolidTiles.rawOf(it) }
                                val aRaw = c.after?.let { SolidTiles.rawOf(it) }
                                PersistedTile(
                                    c.tx, c.ty, Rect(c.clip),
                                    if (bRaw != null) null else c.before?.copy(Bitmap.Config.ARGB_8888, false),
                                    if (aRaw != null) null else c.after?.copy(Bitmap.Config.ARGB_8888, false),
                                    beforeSolid = bRaw?.let { it.toLong() and 0xFFFFFFFFL } ?: -1L,
                                    afterSolid = aRaw?.let { it.toLong() and 0xFFFFFFFFL } ?: -1L
                                )
                            },
                            tileSize = store.tileSize,
                            layerId = action.layerId
                        )
                    }
                    else -> break
                }
                entries.add(entry)
            }
            entries.reverse()
            val dropped = run.size - entries.size
            return PersistedHistory((applied - dropped).coerceAtLeast(0), entries)
        }
    }

    /** Re-attaches edits restored from disk to the freshly loaded layers. */
    fun restoreHistory(persisted: PersistedHistory, groupIds: Map<Long, Long> = emptyMap()) {
        restoreGroupIds = groupIds
        post {
            val actions = ArrayList<HistoryAction>(persisted.entries.size)
            for (entry in persisted.entries) {
                val structure = entry.structure
                if (structure != null) {
                    val o = runCatching { org.json.JSONObject(structure) }.getOrNull() ?: break
                    val b = o.optJSONObject("before")?.let { stateFromJson(it) } ?: break
                    val a = o.optJSONObject("after")?.let { stateFromJson(it) } ?: break
                    val action = LayersAction(b, a, entry.label, entry.kind)
                    action.id = entry.actionId
                    HistoryAction.bumpCounterTo(entry.actionId)
                    actions.add(action)
                    continue
                }
                val layer = (if (entry.layerId >= 0L) engine.layerById(entry.layerId) else null)
                    ?: engine.layers.getOrNull(entry.layerIndex) ?: continue
                val tiles = entry.tiles
                if (tiles != null) {
                    val store = if (entry.mask) layer.maskTiles else layer.tiles
                    // Tile steps only fit a store with the same tile grid.
                    if (store == null || !store.isSparse || store.tileSize != entry.tileSize) break
                    val action = TilePixelAction(
                        layerId = layer.id,
                        bounds = Rect(entry.rect),
                        changes = tiles.map {
                            fun solid(raw: Long) = if (raw < 0L) null
                            else SolidTiles.solid(raw.toInt(), it.clip.width(), it.clip.height())
                            TileChange(
                                it.tx, it.ty, Rect(it.clip),
                                it.before ?: solid(it.beforeSolid),
                                it.after ?: solid(it.afterSolid)
                            )
                        },
                        label = entry.label,
                        kind = entry.kind,
                        detail = entry.detail,
                        swatch = entry.swatch,
                        mask = entry.mask
                    )
                    action.id = entry.actionId
                    HistoryAction.bumpCounterTo(entry.actionId)
                    actions.add(action)
                    continue
                }
                val before = entry.before ?: continue
                val after = entry.after ?: continue
                val action = PixelAction(
                    layerId = layer.id,
                    rect = Rect(entry.rect),
                    before = before,
                    after = after,
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
            symmetryRadial = engine.symmetryRadial,
            symmetryMirror = engine.symmetryMirror,
            guideMode = engine.guides.mode,
            guideAssist = engine.guides.assist,
            gridSize = engine.guides.gridSize,
            vanishingPoints = engine.guides.vpCount,
            soloLayerId = engine.soloLayerId ?: -1L,
            assist = assist,
            quickShape = quickShapeEnabled,
            shapeKind = shapeKind,
            liquifyMode = liquifyMode,
            strokeCount = engine.strokeCount,
            liquifySize = liquifySize,
            liquifyStrength = liquifyStrength,
            shapePerfect = shapePerfect,
            shapeSides = shapeSides,
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
            motionAngle = motionAngle,
            adjustAllLayers = adjustAllLayers,
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

private const val NO_HANDLE = -1
private const val CENTER_HANDLE = 99
/** Above this many bytes per layer, imports avoid canvas-sized bitmaps. */
private const val LARGE_CANVAS_BYTES = 64L * 1024L * 1024L
/** QuickShape morph: points and duration. */
private const val MORPH_POINTS = 160
private const val MORPH_MS = 240L
/** Liquify modes repeat this often while the pen rests. */
private const val LIQUIFY_HOLD_MS = 33L

/**
 * Transform tool: a two-finger gesture scales / rotates the content only when
 * it pinches by ~7% or twists by 8 degrees before it has panned 48 px;
 * otherwise it moves the view.
 */
private const val TRANSFORM_PINCH_LOG = 0.07f
private const val TRANSFORM_TWIST_DEG = 8f
private const val TRANSFORM_PAN_PX = 48f

/** Largest wand / paint bucket tolerance: a full channel difference. */
const val TOLERANCE_MAX = 255

/** A tolerance as the percentage the sliders show (100% = everything). */
fun tolerancePercent(tolerance: Int): Int = (tolerance * 100 + TOLERANCE_MAX / 2) / TOLERANCE_MAX

/** Seconds per megapixel each adjustment took on this device (this run). */
private val adjustRates = HashMap<AdjustmentKind, Float>()

/** Pauses longer than this between inputs do not count as time spent. */
private const val ACTIVE_GAP_MS = 120_000L

/** "2h 13m", "13m", "under a minute" for a duration in milliseconds. */
fun formatTimeSpent(ms: Long): String {
    val m = ms / 60_000L
    return when {
        m < 1L -> "under a minute"
        m < 60L -> "${m}m"
        else -> "${m / 60}h ${m % 60}m"
    }
}
