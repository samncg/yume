package com.yume.app.ui

import androidx.compose.foundation.gestures.waitForUpOrCancellation

import androidx.compose.ui.layout.layout

import androidx.compose.ui.layout.positionInRoot

import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.EditorUiState
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Apple-style springs (Designing Fluid Interfaces), in Compose terms:
 * stiffness = (2 pi / response)^2 with unit mass.
 */
object AppleSpring {
    private fun stiffness(response: Float) = ((2.0 * PI / response) * (2.0 * PI / response)).toFloat()

    /** Drawer / sheet: damping 0.8, response 0.3. Used for panels. */
    fun <T> sheet() = spring<T>(dampingRatio = 0.8f, stiffness = stiffness(0.3f))

    /** Fades that accompany a sheet: critically damped, same response. */
    fun <T> fade() = spring<T>(dampingRatio = 1f, stiffness = stiffness(0.3f))

    /** Moving an object that carried momentum: damping 0.8, response 0.4. */
    fun <T> move() = spring<T>(dampingRatio = 0.8f, stiffness = stiffness(0.4f))

    /** Apple's momentum projection (decelerationRate 0.998): px/s -> px. */
    fun project(velocity: Float, rate: Float = 0.998f): Float = (velocity / 1000f) * rate / (1f - rate)
}

/** One button of a [GlassToolCapsule]. */
class CapsuleItem(
    val glyph: Glyph,
    val label: String,
    /** The tool this button selects is the current one: the glass bubble sits on it. */
    val selected: Boolean = false,
    /** Its panel is open: a quiet tint, not the bubble. */
    val active: Boolean = false,
    val onClick: () -> Unit
)

private val BUTTON = 44.dp
private val GAP = 2.dp
private val PAD = 4.dp

/**
 * A glass pill of toolbar buttons. The selected tool is covered by a
 * completely clear liquid-glass bubble that refracts the button under it
 * (lens with chromatic aberration, no tint). The bubble slides to a newly
 * selected tool on an Apple spring and squishes along its direction of travel
 * in proportion to its speed, like a drop of liquid.
 */
@Composable
fun GlassToolCapsule(
    items: List<CapsuleItem>,
    modifier: Modifier = Modifier,
    /** Window x of the centre of a button the user just tapped (panel origin). */
    onAnchor: (Float) -> Unit = {},
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val centers = remember(items.size) { FloatArray(items.size) }
    val layer = rememberLayerBackdrop()
    val density = LocalDensity.current
    val step = with(density) { (BUTTON + GAP).toPx() }
    val bw = with(density) { BUTTON.toPx() }
    val selected = items.indexOfFirst { it.selected }
    // The bubble's two edges spring separately: the leading edge races ahead on
    // a snappy spring and the trailing edge follows on a softer one, so the
    // glass stretches out across the buttons it passes and snaps back to a
    // circle when it lands.
    val start = if (selected >= 0) selected * step else 0f
    val left = remember { Animatable(start) }
    val right = remember { Animatable(start + bw) }
    val shown = remember { Animatable(if (selected >= 0) 1f else 0f) }
    LaunchedEffect(selected) {
        if (selected >= 0) {
            val tl = selected * step
            val tr = tl + bw
            if (shown.value < 0.05f) {
                left.snapTo(tl)
                right.snapTo(tr)
            }
            launch { shown.animateTo(1f, AppleSpring.sheet()) }
            val movingRight = tl > left.value
            val lead = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.72f, stiffness = 900f)
            val trail = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.86f, stiffness = 170f)
            launch { left.animateTo(tl, if (movingRight) trail else lead) }
            right.animateTo(tr, if (movingRight) lead else trail)
        } else {
            shown.animateTo(0f, AppleSpring.fade())
        }
    }
    Box(modifier.blockCanvasTouches()) {
        Row(
            Modifier
                .layerBackdrop(layer)
                .glass(corner = 26f)
                .padding(PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GAP)
        ) {
            items.forEachIndexed { index, item ->
                YumeIconButton(
                    item.glyph,
                    {
                        onAnchor(centers[index])
                        item.onClick()
                    },
                    modifier = Modifier.onGloballyPositioned { c ->
                        centers[index] = c.positionInRoot().x + c.size.width / 2f
                    },
                    size = BUTTON,
                    selected = item.active,
                    selectedTint = YumeColors.text,
                    selectedBg = Color(0x26FFFFFF),
                    label = item.label
                )
            }
            trailing?.invoke(this)
        }
        if (shown.value > 0.01f) {
            Box(
                Modifier
                    .padding(PAD)
                    .offset { IntOffset(minOf(left.value, right.value - bw * 0.6f).roundToInt(), 0) }
                    .layout { measurable, _ ->
                        // Width follows the two edges; read in layout, so a
                        // moving bubble relayouts without recomposing.
                        val w = (right.value - left.value).coerceAtLeast(bw * 0.6f).roundToInt()
                        val h = bw.roundToInt()
                        val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                        layout(w, h) { p.place(0, 0) }
                    }
                    .graphicsLayer {
                        val s = shown.value
                        // A stretched bubble thins a little, like a drop pulled along.
                        val stretch = ((right.value - left.value - bw) / step).coerceIn(0f, 1.6f)
                        scaleX = s
                        scaleY = s * (1f - 0.16f * stretch.coerceAtMost(1f))
                    }
                    .drawBackdrop(
                        backdrop = layer,
                        shape = { androidx.compose.foundation.shape.RoundedCornerShape(50) },
                        effects = {
                            lens(
                                16.dp.toPx(), 32.dp.toPx(),
                                depthEffect = true, chromaticAberration = true
                            )
                        },
                        onDrawSurface = { }
                    )
            )
        }
    }
}

/**
 * Brush size, opacity, undo and redo on one glass rail. Drag it by the grip
 * (1:1, then a momentum-projected spring to the nearest side), lock it in
 * place, or minimise it to a small pill. Position and state persist.
 */
@Composable
fun SideRail(state: EditorUiState, session: DrawingSession, topInset: Float = 0f) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("yume_ui", 0) }
    var locked by remember { mutableStateOf(prefs.getBoolean("rail_locked", false)) }
    var minimized by remember { mutableStateOf(prefs.getBoolean("rail_min", false)) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Read through State: rotation does not recreate the activity, and the
        // drag handler must see the current size, not the one it started with.
        val maxWState = androidx.compose.runtime.rememberUpdatedState(constraints.maxWidth.toFloat())
        val maxHState = androidx.compose.runtime.rememberUpdatedState(constraints.maxHeight.toFloat())
        val maxW = maxWState.value
        val maxH = maxHState.value
        var railSize by remember { mutableStateOf(IntSize.Zero) }
        val ox = remember { Animatable(margin) }
        val oy = remember { Animatable(Float.NaN) }
        // The rail never slides under the toolbar (two rows tall when narrow).
        val topState = androidx.compose.runtime.rememberUpdatedState(topInset)
        fun minY(): Float = margin + topState.value
        fun bounds(): Pair<Float, Float> {
            val maxX = (maxWState.value - railSize.width - margin).coerceAtLeast(margin)
            val maxY = (maxHState.value - railSize.height - margin).coerceAtLeast(minY())
            return maxX to maxY
        }
        // Restore the saved side / height once the rail has been measured.
        LaunchedEffect(railSize, maxW, maxH, topInset) {
            if (railSize == IntSize.Zero) return@LaunchedEffect
            val (maxX, maxY) = bounds()
            if (oy.value.isNaN()) {
                ox.snapTo(if (prefs.getBoolean("rail_right", false)) maxX else margin)
                oy.snapTo(minY() + (maxY - minY()) * prefs.getFloat("rail_y", 0.5f))
            } else {
                ox.snapTo(ox.value.coerceIn(margin, maxX))
                oy.snapTo(oy.value.coerceIn(minY(), maxY))
            }
        }
        fun save() {
            val (maxX, maxY) = bounds()
            prefs.edit()
                .putBoolean("rail_right", ox.value > (margin + maxX) / 2f)
                .putFloat("rail_y", ((oy.value - minY()) / (maxY - minY()).coerceAtLeast(1f)).coerceIn(0f, 1f))
                .apply()
        }
        Column(
            Modifier
                .offset { IntOffset(ox.value.roundToInt(), if (oy.value.isNaN()) 0 else oy.value.roundToInt()) }
                .graphicsLayer { alpha = if (oy.value.isNaN()) 0f else 1f }
                .onSizeChanged { railSize = it }
                .glass(corner = 26f)
                .blockCanvasTouches()
                .animateContentSize(AppleSpring.sheet())
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Grip: the only place a drag moves the rail (sliders keep theirs).
            Box(
                Modifier
                    .width(44.dp)
                    .height(22.dp)
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val tracker = VelocityTracker()
                            scope.launch { ox.stop(); oy.stop() }
                            tracker.addPosition(down.uptimeMillis, down.position)
                            drag(down.id) { change ->
                                val d = change.position - change.previousPosition
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                scope.launch {
                                    val (maxX, maxY) = bounds()
                                    ox.snapTo(rubber(ox.value + d.x, margin, maxX))
                                    oy.snapTo(rubber(oy.value + d.y, minY(), maxY))
                                }
                            }
                            val v = tracker.calculateVelocity()
                            val (maxX, maxY) = bounds()
                            // Throw: choose the side and height from where the
                            // flick is going, then hand the velocity to the spring.
                            val px = ox.value + AppleSpring.project(v.x)
                            val tx = if (px + railSize.width / 2f < maxWState.value / 2f) margin else maxX
                            val ty = (oy.value + AppleSpring.project(v.y)).coerceIn(minY(), maxY)
                            scope.launch { ox.animateTo(tx, AppleSpring.move(), v.x) }
                            scope.launch {
                                oy.animateTo(ty, AppleSpring.move(), v.y)
                                save()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(22.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (locked) Color(0x33FFFFFF) else Color(0x80FFFFFF))
                )
            }
            if (minimized) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable {
                            minimized = false
                            prefs.edit().putBoolean("rail_min", false).apply()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // A dot whose size follows the brush: the minimised rail
                    // still tells you what you are about to paint with.
                    val d = (6f + state.brushSize / 300f * 22f).dp
                    Box(Modifier.size(d).clip(CircleShape).background(YumeColors.text.copy(alpha = state.brushOpacity.coerceIn(0.25f, 1f))))
                }
            } else {
                // Procreate's order: size, the square button, opacity.
                val onRight = ox.value + railSize.width / 2f < maxW / 2f
                // Size on a curve (1..900 px): small sizes get most of the
                // track, where fine control matters.
                RailSlider(
                    fraction = kotlin.math.sqrt(((state.brushSize - 1f) / 899f).coerceIn(0f, 1f)),
                    onFraction = { f -> session.setBrushSize(1f + 899f * f * f) },
                    label = "Size",
                    valueText = "${state.brushSize.roundToInt()} px",
                    bubbleOnRight = onRight,
                    modifier = Modifier.height(150.dp)
                )
                Spacer(Modifier.height(8.dp))
                // The square button: eyedropper.
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x40000000))
                        .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(6.dp))
                        .clickable {
                            com.yume.app.data.Customization.dispatcher?.invoke(com.yume.app.data.Customization.Action.Eyedropper)
                        }
                )
                Spacer(Modifier.height(8.dp))
                RailSlider(
                    fraction = ((state.brushOpacity - 0.02f) / 0.98f).coerceIn(0f, 1f),
                    onFraction = { f -> session.setBrushOpacity(0.02f + 0.98f * f) },
                    label = "Opacity",
                    valueText = formatPercent(state.brushOpacity),
                    bubbleOnRight = onRight,
                    modifier = Modifier.height(150.dp)
                )
                Spacer(Modifier.height(8.dp))
                RepeatButton(Glyph.Undo, "Undo", state.canUndo, { session.undo() }, { session.redo() }) { session.undo() }
                RepeatButton(Glyph.Redo, "Redo", state.canRedo, { session.undo() }, { session.redo() }) { session.redo() }
                Spacer(Modifier.height(4.dp))
                Row {
                    YumeIconButton(
                        if (locked) Glyph.Lock else Glyph.Unlock,
                        {
                            locked = !locked
                            prefs.edit().putBoolean("rail_locked", locked).apply()
                        },
                        size = 32.dp, iconSize = 15.dp,
                        selected = locked,
                        label = if (locked) "Unlock position" else "Lock position"
                    )
                    YumeIconButton(
                        Glyph.ChevronDown,
                        {
                            minimized = true
                            prefs.edit().putBoolean("rail_min", true).apply()
                        },
                        size = 32.dp, iconSize = 15.dp,
                        label = "Minimise"
                    )
                }
            }
        }
    }
}

/** Soft boundary: past [lo]..[hi] the rail follows the finger less and less. */
private fun rubber(v: Float, lo: Float, hi: Float, dim: Float = 600f, c: Float = 0.55f): Float {
    fun band(over: Float) = (over * dim * c) / (dim + c * abs(over))
    return when {
        v < lo -> lo - band(lo - v)
        v > hi -> hi + band(v - hi)
        else -> v
    }
}

/** Rename the open document. */
@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        PanelCard(Modifier.width(340.dp)) {
            PanelHeader("Rename", onClose = onDismiss)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Cancel") { onDismiss() }
                SmallButton("Rename", enabled = text.isNotBlank()) { onRename(text) }
            }
        }
    }
}

/**
 * Undo / redo button. A tap steps once; held still it keeps stepping, faster
 * the longer it is held; slid up or down (along the rail) it scrubs through
 * history: every step down is one undo, back up redoes. On by default
 * (Settings → Gestures).
 */
@androidx.compose.runtime.Composable
internal fun RepeatButton(
    glyph: Glyph,
    label: String,
    enabled: Boolean,
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
    action: () -> Unit
) {
    val act = androidx.compose.runtime.rememberUpdatedState(action)
    val back = androidx.compose.runtime.rememberUpdatedState(onBack)
    val fwd = androidx.compose.runtime.rememberUpdatedState(onForward)
    val on = androidx.compose.runtime.rememberUpdatedState(enabled)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    if (!com.yume.app.data.AppSettings.holdToRepeatUndo) {
        YumeIconButton(glyph, action, enabled = enabled, size = 44.dp, label = label)
        return
    }
    androidx.compose.foundation.layout.Box(
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                val down = awaitFirstDown(pass = pass)
                down.consume()
                val startY = down.position.y
                val slop = 12.dp.toPx()
                val step = 26.dp.toPx()
                var scrubbing = false
                var steps = 0
                var fired = 0
                var job = if (on.value) scope.launch {
                    kotlinx.coroutines.delay(420L)
                    while (on.value) {
                        act.value()
                        fired++
                        kotlinx.coroutines.delay(maxOf(45L, 190L - fired * 14L))
                    }
                } else null
                var lifted = false
                while (true) {
                    val ev = awaitPointerEvent(pass)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    ch.consume()
                    if (!ch.pressed) {
                        lifted = true
                        break
                    }
                    val dy = ch.position.y - startY
                    if (!scrubbing && kotlin.math.abs(dy) > slop && fired == 0) {
                        scrubbing = true
                        job?.cancel()
                        job = null
                    }
                    if (scrubbing) {
                        val target = (dy / step).toInt()
                        while (steps < target) { back.value(); steps++; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
                        while (steps > target) { fwd.value(); steps--; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
                    }
                }
                job?.cancel()
                if (lifted && !scrubbing && fired == 0 && on.value) act.value()
            }
        }
    ) {
        YumeIconButton(glyph, {}, enabled = enabled, size = 44.dp, label = label)
    }
}
