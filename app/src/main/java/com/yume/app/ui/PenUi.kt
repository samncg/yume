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

/**
 * The squeeze menu: a ring of tools around the pen tip, like Mi Canvas. Tap
 * one to switch; tap anywhere else (or squeeze again) to close.
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
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .claimCanvasTouches()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        val density = LocalDensity.current
        val radius = with(density) { 70.dp.toPx() }
        val button = with(density) { 50.dp.toPx() }
        val reach = radius + button / 2f + with(density) { 8.dp.toPx() }
        // Keep the whole ring on screen.
        val cx = at.x.coerceIn(reach, (constraints.maxWidth - reach).coerceAtLeast(reach))
        val cy = at.y.coerceIn(reach, (constraints.maxHeight - reach).coerceAtLeast(reach))
        data class Item(val glyph: Glyph?, val label: String, val selected: Boolean, val run: () -> Unit)
        val items = listOf(
            Item(Glyph.Brush, "Paint", state.tool == Tool.Brush) { session.chooseTool(Tool.Brush) },
            Item(Glyph.Eraser, "Erase", state.tool == Tool.Eraser) { session.chooseTool(Tool.Eraser) },
            Item(Glyph.Smudge, "Smudge", state.tool == Tool.Smudge) { session.chooseTool(Tool.Smudge) },
            Item(null, "Colour", false) { onColor() },
            Item(Glyph.Redo, "Redo", false) { session.redo() },
            Item(Glyph.Undo, "Undo", false) { session.undo() }
        )
        items.forEachIndexed { i, item ->
            // Start at the top and go clockwise.
            val angle = Math.toRadians(-90.0 + i * 360.0 / items.size)
            val t = open.value
            val px = cx + (radius * t * cos(angle)).toFloat() - button / 2f
            val py = cy + (radius * t * sin(angle)).toFloat() - button / 2f
            Box(
                Modifier
                    .offset { IntOffset(px.roundToInt(), py.roundToInt()) }
                    .size(50.dp)
                    .graphicsLayer {
                        val s = 0.5f + 0.5f * t
                        scaleX = s
                        scaleY = s
                        alpha = t.coerceIn(0f, 1f)
                    }
                    .glass(corner = 25f, weight = GlassWeight.Heavy),
                contentAlignment = Alignment.Center
            ) {
                if (item.glyph != null) {
                    YumeIconButton(
                        item.glyph,
                        {
                            item.run()
                            onDismiss()
                        },
                        selected = item.selected,
                        size = 50.dp,
                        iconSize = 22.dp,
                        label = item.label
                    )
                } else {
                    Box(
                        Modifier
                            .size(26.dp)
                            .background(Color(state.color), CircleShape)
                            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    item.run()
                                    onDismiss()
                                }
                            }
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
