package com.yume.app.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

/**
 * Toolbar button. [label] names it: shown in a small tooltip on long press and
 * read by accessibility services, so an icon is never the only explanation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YumeIconButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 42.dp,
    iconSize: Dp = 21.dp,
    tint: Color = YumeColors.text,
    selectedTint: Color = YumeColors.accent,
    label: String? = null,
    selectedBg: Color = YumeColors.accentSoft
) {
    val bg = when {
        selected -> selectedBg
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> tint.copy(alpha = 0.28f)
        selected -> selectedTint
        else -> tint
    }
    var hint by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .semantics { if (label != null) contentDescription = label }
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = if (label != null) ({ hint = true }) else null
            ),
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(glyph, fg, size = iconSize)
        if (hint && label != null) {
            Tooltip(label, offsetY = size)
            LaunchedEffect(Unit) {
                delay(1400)
                hint = false
            }
        }
    }
}

/** A small label under its anchor, used by long-press hints. */
@Composable
fun Tooltip(text: String, offsetY: Dp) {
    val dy = with(androidx.compose.ui.platform.LocalDensity.current) { (offsetY + 6.dp).roundToPx() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, dy)
    ) {
        Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = YumeColors.text,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xF0202026))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    // Heavy glass: the painting shows through, frosted and veiled enough
    // that text and controls on top stay readable.
    val base = modifier
        .glass(corner = 24f, weight = GlassWeight.Heavy)
        .clip(RoundedCornerShape(24.dp))
    Column(
        modifier = if (scrollable) {
            base.padding(14.dp).verticalScroll(rememberScrollState())
        } else {
            base.padding(14.dp)
        },
        content = content
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = YumeColors.textDim,
        modifier = modifier
    )
}

@Composable
fun PanelHeader(title: String, onClose: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = YumeColors.text
        )
        Spacer(Modifier.weight(1f))
        if (onClose != null) {
            YumeIconButton(Glyph.Close, onClick = onClose, size = 30.dp, iconSize = 15.dp)
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
fun YumeSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueLabel: String? = null,
    min: Float = 0f,
    max: Float = 1f,
    accent: Color = YumeColors.accent,
    /** Called once when the gesture ends, to commit a single undo step. */
    onChangeFinished: (() -> Unit)? = null
) {
    val finished = rememberUpdatedState(onChangeFinished)
    Column(modifier.fillMaxWidth()) {
        if (label != null || valueLabel != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label.orEmpty(),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = valueLabel.orEmpty(),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim
                )
            }
            Spacer(Modifier.height(2.dp))
        }
        val knobRadius = 8.dp
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(min, max) {
                    val slop = viewConfiguration.touchSlop
                    val pad = knobRadius.toPx()
                    fun update(px: Float) {
                        val track = (size.width - pad * 2f).coerceAtLeast(1f)
                        val frac = ((px - pad) / track).coerceIn(0f, 1f)
                        onChange(min + frac * (max - min))
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        var horizontal = false
                        var vertical = false
                        var edited = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // A plain tap sets the value; a completed
                                // horizontal drag already did.
                                if (!vertical) {
                                    update(change.position.x)
                                    edited = true
                                }
                                break
                            }
                            if (!horizontal && !vertical) {
                                val dx = kotlin.math.abs(change.position.x - startX)
                                val dy = kotlin.math.abs(change.position.y - startY)
                                if (dx > slop || dy > slop) {
                                    if (dx >= dy) horizontal = true else vertical = true
                                }
                            }
                            if (horizontal) {
                                update(change.position.x)
                                edited = true
                                change.consume()
                            } else if (vertical) {
                                // Leave the gesture to the scroll container.
                                break
                            }
                        }
                        if (edited) finished.value?.invoke()
                    }
                }
        ) {
            val cy = size.height / 2f
            val pad = knobRadius.toPx()
            val trackW = (size.width - pad * 2f).coerceAtLeast(1f)
            val trackH = 6.dp.toPx()
            val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)

            drawRoundRect(
                color = YumeColors.track,
                topLeft = Offset(pad, cy - trackH / 2f),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(pad, cy - trackH / 2f),
                size = Size(trackW * frac, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
            )
            val knobX = pad + trackW * frac
            drawCircle(Color(0x33000000), pad * 1.15f, Offset(knobX, cy + 1.5f))
            drawCircle(Color.White, pad, Offset(knobX, cy))
            drawCircle(accent, pad * 0.45f, Offset(knobX, cy))
        }
    }
}

/**
 * The canvas-edge slider (brush size, opacity), in the manner of Procreate's
 * sidebar: a dark rounded track with a small light handle, no fill.
 *
 * - Dragging is relative: grabbing anywhere moves the handle from where it
 *   is, so touching the track never makes the value jump. A tap (no drag)
 *   jumps to the tapped spot.
 * - Precision: the further the finger moves sideways away from the track
 *   while dragging, the slower the value follows (1 / (1 + distance / 70 dp)).
 * - While dragging, a bubble beside the rail (on the side facing the canvas)
 *   shows the name and value.
 *
 * [fraction] is 0..1 (bottom..top); [toValue] turns it into the label.
 */
@Composable
fun RailSlider(
    fraction: Float,
    onFraction: (Float) -> Unit,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    bubbleOnRight: Boolean = true,
    onDragEnd: () -> Unit = {}
) {
    val current = androidx.compose.runtime.rememberUpdatedState(fraction)
    val change = androidx.compose.runtime.rememberUpdatedState(onFraction)
    val end = androidx.compose.runtime.rememberUpdatedState(onDragEnd)
    var dragging by remember { mutableStateOf(false) }
    val thumbH = 15.dp
    Box(modifier.width(32.dp)) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val pad = thumbH.toPx() / 2f + 2.dp.toPx()
                    val precisionPx = 70.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val track = (size.height - pad * 2f).coerceAtLeast(1f)
                        var f = current.value
                        var moved = false
                        var lastY = down.position.y
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) {
                                if (!moved) {
                                    // A tap: jump there.
                                    change.value(((size.height - pad - down.position.y) / track).coerceIn(0f, 1f))
                                }
                                break
                            }
                            if (!moved && kotlin.math.abs(c.position.y - down.position.y) > slop / 2f) {
                                moved = true
                                dragging = true
                                lastY = c.position.y
                            }
                            if (moved) {
                                val dx = kotlin.math.abs(c.position.x - size.width / 2f) - size.width / 2f
                                val gain = 1f / (1f + dx.coerceAtLeast(0f) / precisionPx)
                                f = (f - (c.position.y - lastY) / track * gain).coerceIn(0f, 1f)
                                lastY = c.position.y
                                change.value(f)
                            }
                            c.consume()
                        }
                        dragging = false
                        end.value()
                    }
                }
        ) {
            val w = size.width
            val pad = thumbH.toPx() / 2f + 2.dp.toPx()
            val r = 9.dp.toPx()
            drawRoundRect(
                color = Color(0x40000000),
                size = size,
                cornerRadius = CornerRadius(r, r)
            )
            drawRoundRect(
                color = Color(0x1FFFFFFF),
                size = size,
                cornerRadius = CornerRadius(r, r),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
            )
            val track = (size.height - pad * 2f).coerceAtLeast(1f)
            val cy = size.height - pad - fraction.coerceIn(0f, 1f) * track
            val tw = w - 7.dp.toPx()
            val th = thumbH.toPx()
            drawRoundRect(
                color = Color(0x55000000),
                topLeft = Offset((w - tw) / 2f, cy - th / 2f + 1.dp.toPx()),
                size = Size(tw, th),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
            drawRoundRect(
                color = if (dragging) Color.White else Color(0xFFD9D9DE),
                topLeft = Offset((w - tw) / 2f, cy - th / 2f),
                size = Size(tw, th),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
        }
        if (dragging) {
            // The value, beside the rail on the canvas side.
            androidx.compose.ui.window.Popup(
                alignment = if (bubbleOnRight) Alignment.CenterStart else Alignment.CenterEnd,
                offset = with(androidx.compose.ui.platform.LocalDensity.current) {
                    IntOffset(if (bubbleOnRight) 52.dp.roundToPx() else (-52).dp.roundToPx(), 0)
                }
            ) {
                Column(
                    Modifier
                        .glass(corner = 16f, weight = GlassWeight.Heavy)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = YumeColors.textDim)
                    Text(valueText, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = YumeColors.text)
                }
            }
        }
    }
}

/** Tall slider used for brush size and opacity on the canvas edge. */
@Composable
fun YumeVerticalSlider(
    value: Float,
    onChange: (Float) -> Unit,
    label: String,
    valueLabel: String,
    modifier: Modifier = Modifier,
    min: Float = 0f,
    max: Float = 1f,
    accent: Color = YumeColors.accent
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = valueLabel,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = YumeColors.text
        )
        Spacer(Modifier.height(4.dp))
        val knobRadius = 8.dp
        Canvas(
            Modifier
                .width(30.dp)
                .weight(1f)
                .pointerInput(min, max) {
                    val pad = knobRadius.toPx()
                    fun update(py: Float) {
                        val track = (size.height - pad * 2f).coerceAtLeast(1f)
                        val frac = 1f - ((py - pad) / track).coerceIn(0f, 1f)
                        onChange(min + frac * (max - min))
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        update(down.position.y)
                        down.consume()
                        drag(down.id) { change ->
                            update(change.position.y)
                            change.consume()
                        }
                    }
                }
        ) {
            val cx = size.width / 2f
            val pad = knobRadius.toPx()
            val trackH = (size.height - pad * 2f).coerceAtLeast(1f)
            val trackW = 6.dp.toPx()
            val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)

            drawRoundRect(
                color = YumeColors.track,
                topLeft = Offset(cx - trackW / 2f, pad),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
            )
            val fillH = trackH * frac
            drawRoundRect(
                color = accent,
                topLeft = Offset(cx - trackW / 2f, pad + trackH - fillH),
                size = Size(trackW, fillH),
                cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
            )
            val knobY = pad + trackH - fillH
            drawCircle(Color(0x33000000), pad * 1.15f, Offset(cx + 1.5f, knobY))
            drawCircle(Color.White, pad, Offset(cx, knobY))
            drawCircle(accent, pad * 0.45f, Offset(cx, knobY))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = YumeColors.textDim
        )
    }
}

@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(YumeColors.panelAlt)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) YumeColors.accentSoft else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = if (selected) YumeColors.accent else YumeColors.textDim
                )
            }
        }
    }
}

@Composable
fun ColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) YumeColors.accent else Color(0x33FFFFFF),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
fun MenuRow(
    glyph: Glyph,
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> YumeColors.textDim.copy(alpha = 0.5f)
        danger -> YumeColors.danger
        else -> YumeColors.text
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(glyph, tint, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = tint
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
        }
    }
}

@Composable
fun ToggleRow(
    glyph: Glyph,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(glyph, YumeColors.text, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = YumeColors.text
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 38.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (checked) YumeColors.accent else YumeColors.track)
        ) {
            Box(
                Modifier
                    .padding(3.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (checked) Color(0xFF1A1206) else YumeColors.textDim)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
            )
        }
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(YumeColors.hairline)
    )
}

@Composable
fun ValueBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(YumeColors.panelAlt)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = YumeColors.text
        )
    }
}

fun formatPercent(value: Float): String = "${(value * 100f).roundToInt()}%"

/**
 * Swallows pointer input so floating chrome never lets a stroke fall through to
 * the canvas underneath. Children of the same subtree still receive events
 * first, so buttons keep working.
 */
fun Modifier.blockCanvasTouches(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/**
 * Claims pointer input for a floating panel so the canvas SurfaceView behind it
 * never receives the gesture, but deliberately consumes nothing: scroll
 * containers, sliders and buttons inside the panel keep working normally.
 *
 * [blockCanvasTouches] cannot be used on scrollable panels — consuming every
 * change cancels the scroll container's own drag detection.
 */
fun Modifier.claimCanvasTouches(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            // Consume in the Final pass: descendants (scroll containers,
            // sliders, buttons) have already handled the event in Main, but
            // the change is still marked handled, so the canvas interop view
            // behind the panel never receives it and cannot start a stroke.
            // Listening is enough: a pointer handler covering the panel makes
            // it the hit target, so the canvas below is never hit. Consuming
            // here (an ancestor's Final pass runs before its descendants')
            // made every scroll container inside cancel its own drag: it
            // checks the Final pass for a parent that took the gesture.
            awaitPointerEvent(PointerEventPass.Final)
        }
    }
}

/** Claims touches inside a panel so they never reach the canvas. */
fun Modifier.claimCanvasTouchesInside(): Modifier = claimCanvasTouches()

// ------------------------------------------------------------- list helpers

/**
 * A gradual blur at the top and bottom edges of a scrolling list: rows
 * passing out of view soften instead of being cut off. An edge blurs only
 * while there is more content past it. Android 13+ (GPU, AGSL mask over a
 * blurred copy of the layer); older versions are left sharp.
 */
fun Modifier.edgeBlur(state: androidx.compose.foundation.ScrollState, edge: androidx.compose.ui.unit.Dp = 28.dp): Modifier {
    if (android.os.Build.VERSION.SDK_INT < 33) return this
    return this.graphicsLayer {
        val e = edge.toPx()
        val top = if (state.value > 0) e else 0f
        val bottom = if (state.value < state.maxValue) e else 0f
        renderEffect = if (top == 0f && bottom == 0f) null else EdgeBlur.effect(top, bottom, size.height)
    }
}

private object EdgeBlur {
    private const val MASK = """
        uniform shader content;
        uniform float top;
        uniform float bottom;
        uniform float h;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            float t = 0.0;
            if (top > 0.0) t = max(t, 1.0 - smoothstep(0.0, top, p.y));
            if (bottom > 0.0) t = max(t, smoothstep(h - bottom, h, p.y));
            return c * t;
        }
    """
    private const val FADE = """
        uniform shader content;
        uniform float top;
        uniform float bottom;
        uniform float h;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            float a = 1.0;
            if (top > 0.0) a *= mix(0.25, 1.0, smoothstep(0.0, top * 0.7, p.y));
            if (bottom > 0.0) a *= mix(0.25, 1.0, 1.0 - smoothstep(h - bottom * 0.7, h, p.y));
            return c * a;
        }
    """
    private val cache = HashMap<Triple<Float, Float, Float>, androidx.compose.ui.graphics.RenderEffect>()

    @androidx.annotation.RequiresApi(33)
    fun effect(top: Float, bottom: Float, h: Float): androidx.compose.ui.graphics.RenderEffect {
        val key = Triple(top, bottom, h)
        cache[key]?.let { return it }
        if (cache.size > 16) cache.clear()
        fun shader(src: String) = android.graphics.RuntimeShader(src).apply {
            setFloatUniform("top", top)
            setFloatUniform("bottom", bottom)
            setFloatUniform("h", h)
        }
        val blur = android.graphics.RenderEffect.createBlurEffect(14f, 14f, android.graphics.Shader.TileMode.CLAMP)
        // The blurred copy, kept only near the edges, over the sharp layer.
        val maskedBlur = android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader(MASK), "content"), blur
        )
        val sharpFaded = android.graphics.RenderEffect.createRuntimeShaderEffect(shader(FADE), "content")
        val out = android.graphics.RenderEffect.createBlendModeEffect(sharpFaded, maskedBlur, android.graphics.BlendMode.SRC_OVER)
            .asComposeRenderEffect()
        cache[key] = out
        return out
    }
}

/**
 * A blank strip beside a scrolling column with its scrollbar: a vertical drag
 * here only scrolls (it can never move a slider), and the thumb shows where
 * you are. Dragging works like touching the content (pull up to go down).
 */
@Composable
fun ScrollGutter(state: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var viewport by remember { mutableStateOf(0) }
    Canvas(
        modifier
            .onSizeChanged { viewport = it.height }
            .pointerInput(state) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    scope.launch { state.scrollBy(-dy) }
                }
            }
    ) {
        if (state.maxValue <= 0 || viewport <= 0) return@Canvas
        val content = viewport + state.maxValue
        val trackTop = 6.dp.toPx()
        val track = size.height - trackTop * 2f
        val thumbH = (track * viewport / content).coerceAtLeast(28.dp.toPx())
        val y = trackTop + (track - thumbH) * state.value / state.maxValue
        val w = 4.dp.toPx()
        val x = size.width / 2f - w / 2f
        drawRoundRect(Color(0x14FFFFFF), Offset(x, trackTop), Size(w, track), CornerRadius(w / 2f, w / 2f))
        drawRoundRect(
            if (state.isScrollInProgress) Color(0xB3FFFFFF) else Color(0x66FFFFFF),
            Offset(x, y), Size(w, thumbH), CornerRadius(w / 2f, w / 2f)
        )
    }
}

/**
 * A column whose rows can be dragged into a new order, like the layers list:
 * long-press a row (or drag its handle at once, through [row]'s handle
 * modifier) and move it; the others make room as it passes them.
 */
@Composable
fun <T> ReorderColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, handle: Modifier) -> Unit
) {
    val current = androidx.compose.runtime.rememberUpdatedState(items)
    val move = androidx.compose.runtime.rememberUpdatedState(onMove)
    var dragging by remember { mutableStateOf<Any?>(null) }
    var offset by remember { mutableStateOf(0f) }
    var rowH by remember { mutableStateOf(1f) }
    Column(modifier) {
        items.forEach { item ->
            val k = key(item)
            androidx.compose.runtime.key(k) {
                fun start() { dragging = k; offset = 0f }
                fun by(dy: Float) {
                    offset += dy
                    val list = current.value
                    var i = list.indexOfFirst { key(it) == k }
                    while (offset > rowH / 2f && i in 0 until list.size - 1) {
                        move.value(i, i + 1)
                        offset -= rowH
                        i++
                    }
                    while (offset < -rowH / 2f && i > 0) {
                        move.value(i, i - 1)
                        offset += rowH
                        i--
                    }
                }
                fun end() { dragging = null; offset = 0f }
                val lifted = dragging == k
                val handle = Modifier.pointerInput(k) {
                    detectDragGestures(
                        onDragStart = { start() },
                        onDrag = { c, d -> c.consume(); by(d.y) },
                        onDragEnd = { end() },
                        onDragCancel = { end() }
                    )
                }
                Box(
                    Modifier
                        .onSizeChanged { if (it.height > 0) rowH = it.height.toFloat() }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (lifted) offset else 0f
                            scaleX = if (lifted) 1.02f else 1f
                            scaleY = if (lifted) 1.02f else 1f
                            shadowElevation = if (lifted) 12f else 0f
                        }
                        .pointerInput(k) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start() },
                                onDrag = { c, d -> c.consume(); by(d.y) },
                                onDragEnd = { end() },
                                onDragCancel = { end() }
                            )
                        }
                ) { row(item, handle) }
            }
        }
    }
}
