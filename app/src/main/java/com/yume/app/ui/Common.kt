package com.yume.app.ui

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
    label: String? = null
) {
    val bg = when {
        selected -> YumeColors.accentSoft
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
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.forEach { it.consume() }
        }
    }
}
