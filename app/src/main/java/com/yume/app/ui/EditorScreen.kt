package com.yume.app.ui

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

@Composable
fun EditorScreen(
    controller: EditorController,
    onExit: () -> Unit,
    onOpenProject: (String) -> Unit = {}
) {
    val sessionState by controller.session.collectAsStateWithLifecycle()
    val session = sessionState
    val message by controller.message.collectAsStateWithLifecycle()

    if (session == null) {
        Box(
            Modifier.fillMaxSize().background(YumeColors.bg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Opening canvas…",
                style = MaterialTheme.typography.titleMedium,
                color = YumeColors.textDim
            )
        }
        return
    }

    val state by session.uiState.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf<PanelId?>(null) }
    var quickMenuOpen by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) controller.importImage(uri) }

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
    var canvasOrigin by canvasOriginState
    val glassVersion = com.yume.app.engine.CanvasGlass.version.collectAsStateWithLifecycle()
    val glassBackdrop = remember { CanvasProxyBackdrop(glassVersion, canvasOriginState) }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop) {
    Box(Modifier.fillMaxSize().background(YumeColors.bg)) {
        AndroidView(
            factory = { context -> CanvasView(context, session) },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { canvasOrigin = it.positionInWindow() }
        )

        Column(Modifier.fillMaxSize()) {
            if (state.chromeVisible) {
                EditorTopBar(
                    state = state,
                    activePanel = panel,
                    onExit = onExit,
                    onUndo = { session.undo() },
                    onRedo = { session.redo() },
                    onTool = { tool, panelId ->
                        val sameTool = state.tool == tool
                        session.chooseTool(tool)
                        panel = when {
                            panelId == null -> null
                            sameTool && panel == panelId -> null
                            else -> panelId
                        }
                    },
                    onPanel = { id -> panel = if (panel == id) null else id },
                    onColorDrop = { window ->
                        session.colorDropAt(window.x - canvasOrigin.x, window.y - canvasOrigin.y)
                    }
                )
            }
            // weight(1f), not fillMaxSize(): a non-weighted child of a Column is
            // measured against the full column height, which let a tall panel card
            // overflow below the screen so its scroll range stayed at zero.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.chromeVisible && panel != null) {
                    val onLeft = panel == PanelId.History
                    Box(
                        Modifier
                            .align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                            .padding(top = 8.dp)
                            .padding(
                                start = if (onLeft) 12.dp else 0.dp,
                                end = if (onLeft) 0.dp else 12.dp
                            )
                            .claimCanvasTouches()
                    ) {
                        PanelHost(
                            panel = panel,
                            state = state,
                            session = session,
                            controller = controller,
                            onClose = { panel = null },
                            onOpenProject = onOpenProject,
                            onImport = { importLauncher.launch("image/*") }
                        )
                    }
                }
            }
        }

        if (state.chromeVisible) {
            // Side rail: brush size and opacity where the hand rests, with undo
            // and redo right under them (as in Procreate), on one glass pill.
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .glass(corner = 28f)
                    .blockCanvasTouches()
                    .padding(horizontal = 6.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    Modifier.height(260.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    YumeVerticalSlider(
                        value = state.brushSize,
                        onChange = { session.setBrushSize(it) },
                        label = "Size",
                        valueLabel = state.brushSize.roundToInt().toString(),
                        min = 1f,
                        max = 300f
                    )
                    YumeVerticalSlider(
                        value = state.brushOpacity,
                        onChange = { session.setBrushOpacity(it) },
                        label = "Opacity",
                        valueLabel = formatPercent(state.brushOpacity),
                        min = 0.02f
                    )
                }
                Spacer(Modifier.height(10.dp))
                YumeIconButton(Glyph.Undo, { session.undo() }, enabled = state.canUndo, size = 44.dp, label = "Undo")
                YumeIconButton(Glyph.Redo, { session.redo() }, enabled = state.canRedo, size = 44.dp, label = "Redo")
            }
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
    onOpenProject: (String) -> Unit = {}
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
        PanelId.Actions -> ActionsPanel(
            state = state,
            session = session,
            onClose = onClose,
            onImport = onImport,
            onExport = { controller.export() },
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
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTool: (Tool, PanelId?) -> Unit,
    onPanel: (PanelId) -> Unit,
    onColorDrop: (Offset) -> Unit = {}
) {
    // Floating glass capsules instead of a solid bar: the painting stays
    // visible between and through them. Grouped the way people think about
    // them: document + "change what is there" on the left, "put paint down"
    // on the right, then layers and colour.
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Capsule {
            YumeIconButton(Glyph.Gallery, onExit, size = 44.dp, iconSize = 20.dp, label = "Gallery")
            Text(
                text = state.title,
                style = MaterialTheme.typography.labelLarge,
                color = YumeColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPanel(PanelId.History) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            CapsuleDivider()
            YumeIconButton(
                Glyph.Wrench, { onPanel(PanelId.Actions) },
                selected = activePanel == PanelId.Actions, size = 44.dp, label = "Actions"
            )
            YumeIconButton(
                Glyph.Sliders, { onPanel(PanelId.Adjust) },
                selected = activePanel == PanelId.Adjust, size = 44.dp, label = "Adjustments"
            )
            YumeIconButton(
                Glyph.Select, { onTool(Tool.Selection, PanelId.Selection) },
                selected = state.tool == Tool.Selection, size = 44.dp, label = "Select"
            )
            YumeIconButton(
                Glyph.Transform, { onTool(Tool.Transform, null) },
                selected = state.tool == Tool.Transform, size = 44.dp, label = "Transform"
            )
        }
        Spacer(Modifier.weight(1f))
        Capsule {
            YumeIconButton(
                Glyph.Brush, { onTool(Tool.Brush, PanelId.Brush) },
                selected = state.tool == Tool.Brush, size = 44.dp, label = "Paint"
            )
            YumeIconButton(
                Glyph.Smudge, { onTool(Tool.Smudge, PanelId.Brush) },
                selected = state.tool == Tool.Smudge, size = 44.dp, label = "Smudge"
            )
            YumeIconButton(
                Glyph.Eraser, { onTool(Tool.Eraser, PanelId.Brush) },
                selected = state.tool == Tool.Eraser, size = 44.dp, label = "Erase"
            )
            YumeIconButton(
                Glyph.Bucket, { onTool(Tool.Fill, PanelId.Fill) },
                selected = state.tool == Tool.Fill, size = 44.dp, label = "Fill"
            )
            YumeIconButton(
                Glyph.Gradient, { onTool(Tool.Gradient, PanelId.Gradient) },
                selected = state.tool == Tool.Gradient, size = 44.dp, label = "Gradient"
            )
        }
        Spacer(Modifier.width(10.dp))
        Capsule {
            YumeIconButton(
                Glyph.Layers, { onPanel(PanelId.Layers) },
                selected = activePanel == PanelId.Layers, size = 44.dp, label = "Layers"
            )
            ColorChip(
                color = state.color,
                selected = activePanel == PanelId.Color,
                onClick = { onPanel(PanelId.Color) },
                onDrop = onColorDrop
            )
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

@Composable
private fun ColorChip(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onDrop: (Offset) -> Unit = {}
) {
    // ColorDrop: drag the chip onto the canvas and release to fill the region
    // under the finger. A tap still opens the colour panel.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragAt by remember { mutableStateOf<Offset?>(null) }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(13.dp))
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragAt = origin + it },
                    onDrag = { change, amount ->
                        change.consume()
                        dragAt = (dragAt ?: origin) + amount
                    },
                    onDragEnd = {
                        dragAt?.let(onDrop)
                        dragAt = null
                    },
                    onDragCancel = { dragAt = null }
                )
            }
            .clickable(onClick = onClick),
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
