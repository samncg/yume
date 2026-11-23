package com.yume.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.EditorUiState
import com.yume.app.engine.Tool
import com.yume.app.input.PenGestures
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** How an action looks on the shortcut ring: an icon, a short text, or (both null) the colour swatch. */
internal fun ringFace(a: com.yume.app.data.Customization.Action): Pair<Glyph?, String?> = when (a.name) {
    "Undo" -> Glyph.Undo to null
    "Redo" -> Glyph.Redo to null
    "ToggleUi" -> Glyph.Eye to null
    "Brush" -> Glyph.Brush to null
    "Eraser" -> Glyph.Eraser to null
    "Smudge" -> Glyph.Smudge to null
    "ToggleEraser" -> null to "B⇄E"
    "Fill" -> Glyph.Bucket to null
    "Selection" -> Glyph.Select to null
    "Transform" -> Glyph.Transform to null
    "Eyedropper" -> Glyph.Droplet to null
    "SwapColour" -> Glyph.Palette to null
    "BrushStudio" -> Glyph.Sparkle to null
    "Layers" -> Glyph.Layers to null
    "Colours" -> null to null
    "Adjustments" -> Glyph.Sliders to null
    "NewLayer" -> Glyph.Plus to null
    "ClearLayer" -> Glyph.Clear to null
    "SelectAll" -> null to "All"
    "Deselect" -> null to "None"
    "CopyMerged" -> null to "Copy+"
    "FitToScreen" -> Glyph.Fit to null
    "ActualSize" -> null to "1:1"
    "FlipView" -> Glyph.FlipH to null
    "SizeUp" -> null to "Size+"
    "SizeDown" -> null to "Size−"
    "OpacityUp" -> null to "Opac+"
    "OpacityDown" -> null to "Opac−"
    else -> null to a.label.take(5)
}

/**
 * The shortcut ring around the pen tip (or between two held fingers), like
 * Mi Canvas. Its items are the user's (Settings, Gestures). Touch one to
 * choose it: a new finger chooses on contact; a finger that opened the ring
 * slides onto an item and chooses it when lifted. Tap anywhere else to close.
 */
@Composable
fun PenShortcutMenu(
    at: Offset,
    state: EditorUiState,
    session: DrawingSession,
    onColor: () -> Unit,
    onDismiss: () -> Unit
) {
    val open = remember { Animatable(0f) }
    LaunchedEffect(at) {
        open.snapTo(0f)
        open.animateTo(1f, AppleSpring.sheet())
    }
    val actions = com.yume.app.data.Customization.ringItems
    fun run(a: com.yume.app.data.Customization.Action) {
        if (a == com.yume.app.data.Customization.Action.Colours) onColor()
        else com.yume.app.data.Customization.dispatch(a)
        onDismiss()
    }
    val n = actions.size
    // Item centres in the ring's coordinates, filled in during layout below.
    val centres = remember { HashMap<Int, Offset>() }
    var hitRadius by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val fingers = PenGestures.ringFingers
    val origin = PenGestures.ringOrigin
    val aimed: Int? = fingers.firstNotNullOfOrNull { f ->
        val p = f + origin
        centres.entries.filter { it.key < n }.minByOrNull { (it.value - p).getDistance() }
            ?.takeIf { (it.value - p).getDistance() <= hitRadius }?.key
    }
    val currentActions = androidx.compose.runtime.rememberUpdatedState(actions)
    val currentAimed = androidx.compose.runtime.rememberUpdatedState(aimed)
    val currentDismiss = androidx.compose.runtime.rememberUpdatedState(onDismiss)
    val currentColor = androidx.compose.runtime.rememberUpdatedState(onColor)
    androidx.compose.runtime.DisposableEffect(Unit) {
        PenGestures.onRingRelease = {
            val i = currentAimed.value
            val list = currentActions.value
            if (i != null && i in list.indices) {
                val action = list[i]
                if (action == com.yume.app.data.Customization.Action.Colours) currentColor.value()
                else com.yume.app.data.Customization.dispatch(action)
                currentDismiss.value()
            }
        }
        onDispose {
            PenGestures.onRingRelease = null
            PenGestures.ringFingers = emptyList()
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .claimCanvasTouches()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        val density = LocalDensity.current
        // More items, a wider ring, so the buttons never overlap.
        val radiusDp = maxOf(70f, n * 60f / (2f * Math.PI.toFloat()))
        val radius = with(density) { radiusDp.dp.toPx() }
        val button = with(density) { 50.dp.toPx() }
        val reach = radius + button / 2f + with(density) { 8.dp.toPx() }
        // A finger anywhere on a button (and a little around it) is on it.
        hitRadius = button * 0.75f
        // Keep the whole ring on screen.
        val cx = at.x.coerceIn(reach, (constraints.maxWidth - reach).coerceAtLeast(reach))
        val cy = at.y.coerceIn(reach, (constraints.maxHeight - reach).coerceAtLeast(reach))
        // The aimed item's name in the middle.
        if (aimed != null) {
            val halfW = with(density) { 60.dp.toPx() }
            val halfH = with(density) { 14.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((cx - halfW).roundToInt(), (cy - halfH).roundToInt()) }
                    .width(120.dp)
                    .height(28.dp)
                    .glass(corner = 14f, weight = GlassWeight.Heavy),
                contentAlignment = Alignment.Center
            ) {
                Text(actions[aimed].label, style = MaterialTheme.typography.labelMedium, color = YumeColors.text, maxLines = 1)
            }
        }
        actions.forEachIndexed { i, action ->
            // Start at the top and go clockwise.
            val angle = Math.toRadians(-90.0 + i * 360.0 / n)
            val t = open.value
            val hot = aimed == i
            val px = cx + (radius * t * cos(angle)).toFloat() - button / 2f
            val py = cy + (radius * t * sin(angle)).toFloat() - button / 2f
            centres[i] = Offset(cx + (radius * cos(angle)).toFloat(), cy + (radius * sin(angle)).toFloat())
            val selected = when (action.name) {
                "Brush" -> state.tool == Tool.Brush
                "Eraser" -> state.tool == Tool.Eraser
                "Smudge" -> state.tool == Tool.Smudge
                "Fill" -> state.tool == Tool.Fill
                "Selection" -> state.tool == Tool.Selection
                "Transform" -> state.tool == Tool.Transform
                else -> false
            }
            val (glyph, text) = ringFace(action)
            Box(
                Modifier
                    .offset { IntOffset(px.roundToInt(), py.roundToInt()) }
                    .size(50.dp)
                    .graphicsLayer {
                        val s = (0.5f + 0.5f * t) * (if (hot) 1.22f else 1f)
                        scaleX = s
                        scaleY = s
                        alpha = t.coerceIn(0f, 1f)
                    }
                    .glass(corner = 25f, weight = GlassWeight.Heavy)
                    .then(if (hot) Modifier.border(2.dp, YumeColors.accent, CircleShape) else Modifier)
                    // A finger landing on the item chooses it at once.
                    .pointerInput(action) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                            )
                            down.consume()
                            run(action)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    glyph != null -> YumeIconButton(
                        glyph, { run(action) },
                        selected = selected || hot,
                        size = 50.dp,
                        iconSize = 22.dp,
                        label = action.label
                    )
                    text != null -> Box(
                        Modifier
                            .size(50.dp)
                            .pointerInput(action) { detectTapGestures { run(action) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text, style = MaterialTheme.typography.labelMedium, color = if (hot) YumeColors.accent else YumeColors.text)
                    }
                    else -> Box(
                        Modifier
                            .size(26.dp)
                            .background(Color(state.color), CircleShape)
                            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .pointerInput(action) { detectTapGestures { run(action) } }
                    )
                }
            }
        }
    }
}

/** Pen gesture settings: what squeeze and double-tap do, and teaching them. */
@Composable
fun PenGestureSettings() {
    SectionLabel("Pen gestures")
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Hold two fingers still on the canvas to open the shortcut ring. " +
            "For pens with pinch or double-press, like the Xiaomi Focus Pen Pro. " +
            "On Xiaomi tablets, HyperOS keeps these gestures for its own shortcuts in apps " +
            "it hasn't approved, so they may not reach Yume yet. " +
            "For other pens, tap Teach and then do the gesture once.",
        style = MaterialTheme.typography.bodySmall,
        color = YumeColors.textDim
    )
    for (g in PenGestures.Gesture.entries) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.label, style = MaterialTheme.typography.bodyMedium, color = YumeColors.text)
                Text(
                    text = if (PenGestures.learning == g) "Do the gesture on your pen now…"
                    else "Listens for ${PenGestures.keyLabel(PenGestures.keyFor(g))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (PenGestures.learning == g) YumeColors.accent else YumeColors.textDim
                )
            }
            SmallButton(if (PenGestures.learning == g) "Cancel" else "Teach") {
                PenGestures.learning = if (PenGestures.learning == g) null else g
            }
        }
        Spacer(Modifier.height(6.dp))
        SegmentedRow(
            options = PenGestures.Action.entries.map { it.label },
            selectedIndex = PenGestures.actionFor(g).ordinal,
            onSelect = { PenGestures.setAction(g, PenGestures.Action.entries[it]) }
        )
    }
    val unknown = PenGestures.lastUnknownKey
    if (unknown != PenGestures.KEY_UNSET) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your pen sent ${PenGestures.keyLabel(unknown)}, which isn't assigned yet. Tap Teach to use it.",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
    }
    if (PenGestures.squeezeKey != PenGestures.KEY_UNSET || PenGestures.doubleTapKey != PenGestures.KEY_UNSET) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Forget taught keys") { PenGestures.resetKeys() }
        }
    }
    Spacer(Modifier.width(0.dp))
}
