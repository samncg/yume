package com.yume.app.ui

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.ui.layout.positionInRoot

import androidx.compose.ui.layout.layout

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.togetherWith
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yume.app.engine.CanvasView
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.EditorUiState
import com.yume.app.engine.Tool
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TOP_BAR_HEIGHT = 54

/** Panels that belong to a tool: they open on a second tap of that tool. */
private val TOOL_PANELS = setOf(
    PanelId.Brush, PanelId.Fill, PanelId.Gradient, PanelId.Selection, PanelId.Shape, PanelId.Liquify
)

@Composable
fun EditorScreen(
    controller: EditorController,
    onExit: () -> Unit,
    onOpenProject: (String) -> Unit = {},
    /** False while the opening fade runs: the canvas surface would ignore its alpha. */
    showCanvas: Boolean = true
) {
    val sessionState by controller.session.collectAsStateWithLifecycle()
    val session = sessionState
    val message by controller.message.collectAsStateWithLifecycle()

    if (session == null || !showCanvas) {
        // The project curtain covers this; keep it plain.
        Box(Modifier.fillMaxSize().background(YumeColors.bg))
        return
    }

    val state by session.uiState.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf<PanelId?>(null) }
    var renaming by remember { mutableStateOf(false) }
    // Window x of the button that opened the current panel: panels grow out of
    // their own button and return to it.
    var panelAnchorX by remember { mutableStateOf<Float?>(null) }
    var rootWidth by remember { mutableStateOf(1f) }
    // Vertical geometry for the collapse point: the toolbar's centre line,
    // relative to the panel area below it.
    var barCenterY by remember { mutableStateOf(0f) }
    var barBottom by remember { mutableStateOf(0f) }
    var areaTop by remember { mutableStateOf(0f) }
    var areaHeight by remember { mutableStateOf(1f) }
    // Each panel remembers the button it came from, so it closes back into that
    // button even when another button was tapped since.
    val panelAnchors = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<PanelId, Float>() }
    fun showPanel(id: PanelId?) {
        if (id != null && id != panel) panelAnchors[id] = panelAnchorX ?: rootWidth
        panel = id
    }
    // Guide handles are draggable only while the Guides panel is open.
    LaunchedEffect(panel == PanelId.Guides) { session.setGuideEditing(panel == PanelId.Guides) }
    // Squeeze menu position (window px), or null when closed.
    var penMenuAt by remember { mutableStateOf<Offset?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) controller.importImage(uri) }
    val referenceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) controller.setReference(uri) }
    val reference by controller.reference.collectAsStateWithLifecycle()
    var exportOpen by remember { mutableStateOf(false) }
    val shareContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(controller) {
        controller.shareRequests.collect { (uri, mime) ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { shareContext.startActivity(android.content.Intent.createChooser(send, "Share")) }
        }
    }

    // Periodic autosave keeps work safe without ever blocking the render thread.
    LaunchedEffect(session) {
        while (true) {
            delay(25_000)
            controller.autosave()
        }
    }

    // Debounced autosave: every edit lands on disk shortly after it happens.
    LaunchedEffect(state.documentVersion) {
        if (state.documentVersion == 0) return@LaunchedEffect
        delay(1200)
        controller.autosave()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.save()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Leaving the transform tool by picking another tool commits the transform.
    BackHandler { onExit() }

    // Where the canvas sits in the window: ColorDrop maps through it, and the
    // glass backdrop aligns the canvas proxy with it.
    val canvasOriginState = remember { mutableStateOf(Offset.Zero) }
    // ColorDrop in progress: the floating colour dot and the live threshold.
    var dropDot by remember { mutableStateOf<Offset?>(null) }
    var dropTolerance by remember { mutableStateOf<Int?>(null) }
    var canvasOrigin by canvasOriginState
    val glassVersion = com.yume.app.engine.CanvasGlass.version.collectAsStateWithLifecycle()
    val glassBackdrop = remember { CanvasProxyBackdrop(glassVersion, canvasOriginState) }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop) {
    Box(
        Modifier
            .fillMaxSize()
            .background(YumeColors.bg)
            .onGloballyPositioned { rootWidth = it.size.width.toFloat() }
    ) {
        AndroidView(
            factory = { context -> CanvasView(context, session) },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { canvasOrigin = it.positionInWindow() }
        )

        // Under the toolbar and panels, so a panel (History) opens over it.
        if (state.chromeVisible) {
            SideRail(state, session, topInset = barBottom)
        }
        val ref = reference
        if (ref != null && state.chromeVisible) {
            ReferenceWindow(
                bitmap = ref,
                onPickColor = { session.chooseColor(it) },
                onClose = { controller.clearReference() },
                topInset = barBottom
            )
        }

        Column(Modifier.fillMaxSize()) {
            if (state.chromeVisible) {
                Box(Modifier.onGloballyPositioned {
                    barCenterY = it.positionInRoot().y + it.size.height / 2f
                    barBottom = it.positionInRoot().y + it.size.height
                }) {
                EditorTopBar(
                    state = state,
                    activePanel = panel,
                    onExit = onExit,
                    onRename = { renaming = true },
                    onTool = { tool, panelId ->
                        // First tap selects the tool; a tap on the tool that is
                        // already selected opens (or closes) its settings.
                        val sameTool = state.tool == tool
                        session.chooseTool(tool)
                        showPanel(
                            when {
                                sameTool && panelId != null -> if (panel == panelId) null else panelId
                                panel in TOOL_PANELS -> null
                                else -> panel
                            }
                        )
                    },
                    onPanel = { id -> showPanel(if (panel == id) null else id) },
                    onAnchor = { x -> panelAnchorX = x },
                    onColorDrop = object : ColorDropHandler {
                        override fun dragging(window: Offset?, tolerance: Int?) {
                            dropDot = window
                            dropTolerance = tolerance
                        }
                        override fun drop(window: Offset) =
                            session.colorDropAt(window.x - canvasOrigin.x, window.y - canvasOrigin.y)
                        override fun begin(window: Offset) =
                            session.colorDropBegin(window.x - canvasOrigin.x, window.y - canvasOrigin.y)
                        override fun startTolerance(): Int = state.fillTolerance
                        override fun adjust(tolerance: Int) = session.colorDropAdjust(tolerance)
                        override fun end() = session.colorDropEnd()
                    }
                )
                }
            }
            // weight(1f), not fillMaxSize(): a non-weighted child of a Column is
            // measured against the full column height, which let a tall panel card
            // overflow below the screen so its scroll range stayed at zero.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        areaTop = it.positionInRoot().y
                        areaHeight = it.size.height.toFloat().coerceAtLeast(1f)
                    }
            ) {
                // Panels grow out of the button that opened them and return to
                // it, on Apple's sheet spring (damping 0.8, response 0.3): a
                // small overshoot, interruptible at any moment.
                androidx.compose.animation.AnimatedContent(
                    targetState = if (state.chromeVisible) panel else null,
                    transitionSpec = {
                        // The button's centre: x from the tapped button, y on the
                        // toolbar line above the panel area (a negative fraction).
                        fun originOf(id: PanelId?) = androidx.compose.ui.graphics.TransformOrigin(
                            ((id?.let { panelAnchors[it] } ?: rootWidth) / rootWidth.coerceAtLeast(1f))
                                .coerceIn(0f, 1f),
                            ((barCenterY - areaTop) / areaHeight).coerceIn(-0.5f, 0f)
                        )
                        // No size animation: by default the container shrinks toward
                        // the empty state and clips the closing panel to that box,
                        // which dragged every panel off to the side as it closed.
                        (
                            androidx.compose.animation.scaleIn(AppleSpring.sheet(), 0.12f, originOf(targetState)) +
                                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120))
                            ) togetherWith (
                            // Collapses all the way into the button, fading only
                            // at the end so the motion stays readable.
                            androidx.compose.animation.scaleOut(AppleSpring.fade(), 0.08f, originOf(initialState)) +
                                androidx.compose.animation.fadeOut(
                                    androidx.compose.animation.core.tween(140, delayMillis = 110)
                                )
                            ) using androidx.compose.animation.SizeTransform(clip = false)
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "panel"
                ) { shown ->
                    if (shown == null) {
                        // Same size as a panel page, so nothing resizes on close.
                        Box(Modifier.fillMaxSize())
                    } else {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                            val margin = with(LocalDensity.current) { 12.dp.toPx() }
                            val areaW = constraints.maxWidth
                            val anchor = panelAnchors[shown] ?: areaW.toFloat()
                            Box(
                                Modifier
                                    .layout { measurable, c ->
                                        val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
                                        // Centred under its button, kept on screen.
                                        val x = (anchor - p.width / 2f)
                                            .coerceIn(margin, (areaW - p.width - margin).coerceAtLeast(margin))
                                        layout(c.maxWidth, p.height) { p.place(x.roundToInt(), 0) }
                                    }
                                    // Clear of the gesture bar, so a tall
                                    // panel's last row (Apply / Cancel) can
                                    // always be scrolled into view.
                                    .navigationBarsPadding()
                                    .padding(top = 8.dp, bottom = 12.dp)
                                    .claimCanvasTouchesInside()
                            ) {
                                PanelHost(
                                    panel = shown,
                                    state = state,
                                    session = session,
                                    controller = controller,
                                    onClose = { panel = null },
                                    onOpenProject = onOpenProject,
                                    onImport = { importLauncher.launch("image/*") },
                                    onReference = { referenceLauncher.launch("image/*") },
                                    onExportDialog = {
                                        exportOpen = true
                                        panel = null
                                    },
                                    hasReference = reference != null
                                )
                            }
                        }
                    }
                }
            }
        }

        val penState by androidx.compose.runtime.rememberUpdatedState(state)
        var penPrevTool by remember { mutableStateOf(Tool.Brush) }
        DisposableEffect(session) {
            com.yume.app.input.PenGestures.listener = { _, action ->
                when (action) {
                    com.yume.app.input.PenGestures.Action.Menu -> {
                        penMenuAt = if (penMenuAt != null) null else {
                            val px = com.yume.app.input.PenGestures.lastX
                            val py = com.yume.app.input.PenGestures.lastY
                            if (px < 0f) Offset(rootWidth / 2f, 600f)
                            else Offset(px + canvasOrigin.x, py + canvasOrigin.y)
                        }
                    }
                    com.yume.app.input.PenGestures.Action.Eraser -> {
                        // Like Mi Canvas: toggle the eraser, then back to the tool before it.
                        if (penState.tool == Tool.Eraser) {
                            session.chooseTool(penPrevTool)
                        } else {
                            penPrevTool = penState.tool
                            session.chooseTool(Tool.Eraser)
                        }
                    }
                    com.yume.app.input.PenGestures.Action.Undo -> session.undo()
                    com.yume.app.input.PenGestures.Action.Off -> Unit
                }
            }
            com.yume.app.input.PenGestures.onMenuAt = { x, y ->
                penMenuAt = Offset(x + canvasOrigin.x, y + canvasOrigin.y)
            }
            // Gestures, keyboard shortcuts and the pen button all run here.
            com.yume.app.data.Customization.dispatcher = { action ->
                fun openPanel(id: PanelId) {
                    panelAnchorX = null
                    showPanel(if (panel == id) null else id)
                }
                fun brushSize(f: Float) {
                    penState.presets.firstOrNull { it.id == penState.activePresetId }?.let {
                        session.setBrushSize((it.size * f).coerceIn(1f, 300f))
                    }
                }
                fun brushOpacity(d: Float) {
                    penState.presets.firstOrNull { it.id == penState.activePresetId }?.let {
                        session.setBrushOpacity((it.opacity + d).coerceIn(0.02f, 1f))
                    }
                }
                when (action) {
                    com.yume.app.data.Customization.Action.None -> Unit
                    com.yume.app.data.Customization.Action.Undo -> session.undo()
                    com.yume.app.data.Customization.Action.Redo -> session.redo()
                    com.yume.app.data.Customization.Action.ToggleUi -> session.toggleChrome()
                    com.yume.app.data.Customization.Action.ShortcutMenu -> {
                        penMenuAt = if (penMenuAt != null) null else {
                            val px = com.yume.app.input.PenGestures.lastX
                            val py = com.yume.app.input.PenGestures.lastY
                            if (px < 0f) Offset(rootWidth / 2f, 600f)
                            else Offset(px + canvasOrigin.x, py + canvasOrigin.y)
                        }
                    }
                    com.yume.app.data.Customization.Action.Brush -> session.chooseTool(Tool.Brush)
                    com.yume.app.data.Customization.Action.Eraser -> session.chooseTool(Tool.Eraser)
                    com.yume.app.data.Customization.Action.Smudge -> session.chooseTool(Tool.Smudge)
                    com.yume.app.data.Customization.Action.ToggleEraser -> {
                        if (penState.tool == Tool.Eraser) {
                            session.chooseTool(penPrevTool)
                        } else {
                            penPrevTool = penState.tool
                            session.chooseTool(Tool.Eraser)
                        }
                    }
                    com.yume.app.data.Customization.Action.Fill -> session.chooseTool(Tool.Fill)
                    com.yume.app.data.Customization.Action.Selection -> session.chooseTool(Tool.Selection)
                    com.yume.app.data.Customization.Action.Transform -> session.chooseTool(Tool.Transform)
                    com.yume.app.data.Customization.Action.Eyedropper -> {
                        // Picks the colour under the pen (where it last hovered).
                        val px = com.yume.app.input.PenGestures.lastX
                        val py = com.yume.app.input.PenGestures.lastY
                        if (px >= 0f) {
                            session.pushInput(com.yume.app.engine.InputEvent.PickStart(px, py))
                            session.pushInput(com.yume.app.engine.InputEvent.PickEnd)
                        }
                    }
                    com.yume.app.data.Customization.Action.BrushStudio -> openPanel(PanelId.Brush)
                    com.yume.app.data.Customization.Action.Layers -> openPanel(PanelId.Layers)
                    com.yume.app.data.Customization.Action.Colours -> openPanel(PanelId.Color)
                    com.yume.app.data.Customization.Action.Adjustments -> openPanel(PanelId.Adjust)
                    com.yume.app.data.Customization.Action.NewLayer -> session.addLayer()
                    com.yume.app.data.Customization.Action.ClearLayer -> session.clearLayer()
                    com.yume.app.data.Customization.Action.SelectAll -> session.selectAll()
                    com.yume.app.data.Customization.Action.Deselect -> session.clearSelection()
                    com.yume.app.data.Customization.Action.FitToScreen -> session.fitToScreen()
                    com.yume.app.data.Customization.Action.ActualSize -> session.zoomActualSize()
                    com.yume.app.data.Customization.Action.FlipView -> session.toggleMirrorView()
                    com.yume.app.data.Customization.Action.SizeUp -> brushSize(1.15f)
                    com.yume.app.data.Customization.Action.SizeDown -> brushSize(1f / 1.15f)
                    com.yume.app.data.Customization.Action.OpacityUp -> brushOpacity(0.1f)
                    com.yume.app.data.Customization.Action.OpacityDown -> brushOpacity(-0.1f)
                }
            }
            onDispose {
                com.yume.app.input.PenGestures.listener = null
                com.yume.app.input.PenGestures.onMenuAt = null
                com.yume.app.data.Customization.dispatcher = null
            }
        }
        penMenuAt?.let { at ->
            PenShortcutMenu(
                at = at,
                state = state,
                session = session,
                onColor = {
                    panelAnchorX = at.x
                    showPanel(PanelId.Color)
                },
                onDismiss = { penMenuAt = null }
            )
        }

        // ColorDrop: the dragged colour follows the finger (lifted above it so
        // it stays visible), with the live threshold while adjusting.
        var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.positionInWindow() }
        ) {
            dropDot?.let { p ->
                Column(
                    Modifier.offset {
                        IntOffset(
                            (p.x - overlayOrigin.x - 60.dp.toPx()).roundToInt(),
                            (p.y - overlayOrigin.y - 104.dp.toPx()).roundToInt()
                        )
                    }.width(120.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val t = dropTolerance
                    Text(
                        text = if (t != null) "Threshold ${com.yume.app.engine.tolerancePercent(t)}%" else " ",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (t != null) Color(0xB3000000) else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(state.color))
                            .border(2.5.dp, Color.White, CircleShape)
                    )
                }
            }
        }

        // Long operations (a big undo, an adjustment on an 8K layer…) show a
        // small working card after a moment, so the app never looks frozen.
        // Any command or tool action on the render thread that runs past a
        // quarter second (fill, auto-select, shapes, undo, adjustments…).
        var showBusy by remember { mutableStateOf(false) }
        var canCancel by remember { mutableStateOf(false) }
        var cancelling by remember { mutableStateOf(false) }
        LaunchedEffect(session) {
            while (true) {
                val b = session.workingMillis() >= 250L
                if (b != showBusy) showBusy = b
                val c = com.yume.app.engine.OpCancel.armed
                if (c != canCancel) canCancel = c
                if (!b) cancelling = false
                delay(if (b) 50L else 90L)
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showBusy,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .claimCanvasTouches(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .glass(corner = 22f, weight = GlassWeight.Heavy)
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (cancelling) "Cancelling…" else "Working…",
                        style = MaterialTheme.typography.titleSmall,
                        color = YumeColors.text
                    )
                    Spacer(Modifier.height(12.dp))
                    ProgressLine()
                    if (canCancel && !cancelling) {
                        Spacer(Modifier.height(14.dp))
                        // Stops the operation; everything it changed is put back.
                        SmallButton("Cancel") {
                            cancelling = true
                            com.yume.app.engine.OpCancel.requested = true
                        }
                    }
                }
            }
        }

        if (exportOpen) {
            ExportDialog(controller, state.width, state.height, onDismiss = { exportOpen = false })
        }

        val textAt by session.textRequest.collectAsStateWithLifecycle()
        textAt?.let { at ->
            TextDialog(
                onDismiss = { session.textRequest.value = null },
                onAdd = { text, size, font, bold, italic, align ->
                    session.addTextLayer(text, at[0], at[1], size, font, bold, italic, align, state.color)
                    session.textRequest.value = null
                }
            )
        }

        if (renaming) {
            RenameDialog(
                current = state.title,
                onDismiss = { renaming = false },
                onRename = { name ->
                    renaming = false
                    controller.rename(name)
                }
            )
        }

        if (state.transforming) {
            TransformBar(
                onCommit = { session.commitTransform() },
                onCancel = { session.cancelTransform() },
                onFlipH = { session.flipTransform(true) },
                onFlipV = { session.flipTransform(false) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 26.dp)
            )
        }

        if (message != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp)
                    .glass(corner = 20f, weight = GlassWeight.Heavy)
                    .padding(horizontal = 18.dp, vertical = 11.dp)
            ) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = YumeColors.text
                )
            }
            LaunchedEffect(message) {
                delay(2600)
                controller.clearMessage()
            }
        }
    }
    }
}

@Composable
private fun PanelHost(
    panel: PanelId?,
    state: EditorUiState,
    session: DrawingSession,
    controller: EditorController,
    onClose: () -> Unit,
    onImport: () -> Unit,
    onOpenProject: (String) -> Unit = {},
    onReference: () -> Unit = {},
    hasReference: Boolean = false,
    onExportDialog: () -> Unit = {}
) {
    when (panel) {
        PanelId.Layers -> LayersPanel(state, session, onClose)
        PanelId.Brush -> BrushPanel(state, session, onClose)
        PanelId.Color -> ColorPanel(state, session, onClose)
        PanelId.Adjust -> AdjustPanel(
            state = state,
            session = session,
            // Closing the panel keeps whatever the user was previewing.
            onClose = {
                session.commitAdjustment()
                onClose()
            }
        )
        PanelId.Selection -> SelectionPanel(state, session, onClose)
        PanelId.Fill -> FillPanel(state, session, onClose)
        PanelId.Gradient -> GradientPanel(state, session, onClose)
        PanelId.Shape -> ShapePanel(state, session, onClose)
        PanelId.Guides -> GuidesPanel(state, session, onClose)
        PanelId.Liquify -> LiquifyPanel(state, session, onClose)
        PanelId.Actions -> ActionsPanel(
            state = state,
            session = session,
            onClose = onClose,
            onImport = onImport,
            onReference = onReference,
            onRemoveReference = if (hasReference) ({ controller.clearReference() }) else null,
            onSettings = { SettingsHost.open = true },
            onExport = onExportDialog,
            onSave = { controller.save() },
            onCanvasCopy = { w, h, scale, center ->
                controller.canvasCopy(w, h, scale, center) { id ->
                    if (id != null) onOpenProject(id)
                }
            }
        )
        PanelId.History -> HistoryPanel(state, session, onClose)
        null -> Unit
    }
}

@Composable
private fun EditorTopBar(
    state: EditorUiState,
    activePanel: PanelId?,
    onExit: () -> Unit,
    onRename: () -> Unit,
    onTool: (Tool, PanelId?) -> Unit,
    onPanel: (PanelId) -> Unit,
    onAnchor: (Float) -> Unit = {},
    onColorDrop: ColorDropHandler? = null
) {
    // Floating glass capsules, grouped by intent: the document, then tools
    // that change what is there, then tools that put paint down, then layers
    // and colour. The painting stays visible between and through them.
    // Floating glass capsules. One row when the screen is wide enough; on a
    // narrow (portrait) screen the tools pill moves to a second row of its
    // own instead of being squeezed between the others.
    val titleCapsule: @Composable () -> Unit = {
            Capsule {
                YumeIconButton(Glyph.Gallery, onExit, size = 44.dp, iconSize = 20.dp, label = "Gallery")
                // Tap the name to rename the document.
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = YumeColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onRename)
                        .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                )
            }
    }
    val leftPill: @Composable () -> Unit = {
            GlassToolCapsule(
                listOf(
                    CapsuleItem(Glyph.History, "History", active = activePanel == PanelId.History) {
                        onPanel(PanelId.History)
                    },
                    CapsuleItem(Glyph.Wrench, "Actions", active = activePanel == PanelId.Actions) {
                        onPanel(PanelId.Actions)
                    },
                    CapsuleItem(Glyph.Guides, "Guides", active = activePanel == PanelId.Guides) {
                        onPanel(PanelId.Guides)
                    }
                ),
                onAnchor = onAnchor
            )
    }
    val toolsPill: @Composable (Modifier) -> Unit = { modifier ->
            // The order and the hidden tools are the user's (Settings, Layout).
            GlassToolCapsule(
                com.yume.app.data.Customization.visibleTools.map { t ->
                    val (glyph, label, toolPanel) = toolChrome(t)
                    CapsuleItem(glyph, label, selected = state.tool == t) { onTool(t, toolPanel) }
                },
                onAnchor = onAnchor,
                modifier = modifier
            )
    }
    val rightPill: @Composable () -> Unit = {
            GlassToolCapsule(
                listOf(
                    CapsuleItem(Glyph.Sliders, "Adjustments", active = activePanel == PanelId.Adjust) {
                        onPanel(PanelId.Adjust)
                    },
                    CapsuleItem(Glyph.Layers, "Layers", active = activePanel == PanelId.Layers) {
                        onPanel(PanelId.Layers)
                    }
                ),
                onAnchor = onAnchor,
                trailing = {
                    var chipX by remember { mutableStateOf(0f) }
                    Box(Modifier.onGloballyPositioned { chipX = it.positionInRoot().x + it.size.width / 2f }) {
                        ColorChip(
                            color = state.color,
                            selected = activePanel == PanelId.Color,
                            onClick = {
                                onAnchor(chipX)
                                onPanel(PanelId.Color)
                            },
                            dropHandler = onColorDrop
                        )
                    }
                }
            )
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (maxWidth >= TOOLBAR_ONE_ROW_MIN) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                titleCapsule()
                leftPill()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    toolsPill(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))
                }
                rightPill()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.weight(1f, fill = false)) { titleCapsule() }
                    leftPill()
                    Spacer(Modifier.weight(1f))
                    rightPill()
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    toolsPill(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))
                }
            }
        }
    }
}

/** A glass pill holding a group of toolbar buttons. Blocks canvas touches. */
@Composable
private fun Capsule(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .glass(corner = 26f)
            .blockCanvasTouches()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

@Composable
private fun CapsuleDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(22.dp)
            .background(YumeColors.hairline)
    )
}

/** What the colour chip tells the editor while it is dragged (ColorDrop). */
interface ColorDropHandler {
    /** The chip is being dragged to [window] (null: drag over); [tolerance] while adjusting. */
    fun dragging(window: Offset?, tolerance: Int?)
    /** Released without holding: a plain fill at [window]. */
    fun drop(window: Offset)
    /** Held still at [window]: fill now, then follow [adjust] until [end]. */
    fun begin(window: Offset)
    fun startTolerance(): Int
    fun adjust(tolerance: Int)
    fun end()
}

@Composable
private fun ColorChip(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    dropHandler: ColorDropHandler? = null
) {
    // ColorDrop: drag the chip onto the canvas and release to fill the region
    // under the finger. Hold still before releasing to fill right away, then
    // slide sideways to change the threshold live; lift to keep it. A tap
    // still opens the colour panel.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val handler = androidx.compose.runtime.rememberUpdatedState(dropHandler)
    val tap = androidx.compose.runtime.rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(13.dp))
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = origin + down.position
                    var pos = start
                    var dragging = false
                    var holding = false
                    var stillAt = start
                    var stillSince = down.uptimeMillis
                    var holdX = 0f
                    var tol0 = 0
                    var tol = 0
                    val slop = viewConfiguration.touchSlop
                    val stillRadius = 10.dp.toPx()
                    val perFull = 260.dp.toPx()
                    while (true) {
                        val ev = withTimeoutOrNull(60L) { awaitPointerEvent() }
                        if (ev == null) {
                            // Resting: after a moment the drop happens here.
                            val h = handler.value
                            if (dragging && !holding && h != null &&
                                android.os.SystemClock.uptimeMillis() - stillSince > COLOR_DROP_HOLD_MS
                            ) {
                                holding = true
                                holdX = pos.x
                                tol0 = h.startTolerance()
                                tol = tol0
                                h.begin(pos)
                                h.dragging(pos, tol)
                            }
                            continue
                        }
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                        if (!ch.pressed) break
                        val p = origin + ch.position
                        if (!dragging && (p - start).getDistance() > slop) dragging = true
                        if (dragging) {
                            ch.consume()
                            pos = p
                            if (holding) {
                                tol = (tol0 + (p.x - holdX) / perFull * 255f).roundToInt().coerceIn(1, 255)
                                handler.value?.adjust(tol)
                            } else if ((p - stillAt).getDistance() > stillRadius) {
                                stillAt = p
                                stillSince = ch.uptimeMillis
                            }
                            handler.value?.dragging(pos, if (holding) tol else null)
                        }
                    }
                    val h = handler.value
                    when {
                        !dragging -> tap.value()
                        holding -> h?.end()
                        else -> h?.drop(pos)
                    }
                    h?.dragging(null, null)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(
                    width = if (selected) 2.dp else 1.5.dp,
                    color = if (selected) YumeColors.accent else Color(0x66FFFFFF),
                    shape = CircleShape
                )
        )
    }
}

/** How long the dragged colour must rest before the drop happens in place. */
private const val COLOR_DROP_HOLD_MS = 380L

@Composable
private fun TransformBar(
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .glass(corner = 26f, weight = GlassWeight.Heavy)
            .blockCanvasTouches()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        YumeIconButton(Glyph.Close, onCancel, size = 40.dp)
        YumeIconButton(Glyph.FlipH, onFlipH, size = 40.dp)
        YumeIconButton(Glyph.FlipV, onFlipV, size = 40.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Drag to move · pinch to scale & rotate",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
        Spacer(Modifier.width(6.dp))
        YumeIconButton(Glyph.Check, onCommit, size = 40.dp, tint = YumeColors.accent)
    }
}

/** Narrower than this, the toolbar wraps the tools pill onto its own row. */
private val TOOLBAR_ONE_ROW_MIN = 1000.dp

/** Icon, name and settings panel of a tool, for the toolbar and Settings. */
internal fun toolChrome(t: Tool): Triple<Glyph, String, PanelId?> = when (t) {
    Tool.Selection -> Triple(Glyph.Select, "Select", PanelId.Selection)
    Tool.Transform -> Triple(Glyph.Transform, "Transform", null)
    Tool.Brush -> Triple(Glyph.Brush, "Paint", PanelId.Brush)
    Tool.Smudge -> Triple(Glyph.Smudge, "Smudge", PanelId.Brush)
    Tool.Eraser -> Triple(Glyph.Eraser, "Erase", PanelId.Brush)
    Tool.Fill -> Triple(Glyph.Bucket, "Fill", PanelId.Fill)
    Tool.Gradient -> Triple(Glyph.Gradient, "Gradient", PanelId.Gradient)
    Tool.Shape -> Triple(Glyph.Shape, "Shape", PanelId.Shape)
    Tool.Liquify -> Triple(Glyph.Liquify, "Liquify", PanelId.Liquify)
    Tool.Text -> Triple(Glyph.Text, "Text", null)
}
