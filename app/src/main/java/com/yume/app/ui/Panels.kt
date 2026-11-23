package com.yume.app.ui

import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.BlurLinear
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Filter
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.ui.draw.drawBehind

import androidx.compose.foundation.layout.offset

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.foundation.verticalScroll

import androidx.compose.runtime.LaunchedEffect

import kotlinx.coroutines.launch

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.ui.zIndex

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import androidx.compose.runtime.collectAsState

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yume.app.BuildConfig
import com.yume.app.engine.tolerancePercent
import com.yume.app.engine.TOLERANCE_MAX
import com.yume.app.engine.AdjustmentKind
import com.yume.app.engine.BrushCategory
import com.yume.app.engine.BrushDab
import com.yume.app.engine.BrushPreset
import com.yume.app.engine.ColorMode
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.EditKind
import com.yume.app.engine.EditorUiState
import com.yume.app.engine.HistoryEntry
import com.yume.app.engine.LayerBlend
import com.yume.app.engine.LayerUi
import com.yume.app.engine.SelectionOp
import com.yume.app.engine.SelectionShape
import com.yume.app.engine.Tool
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PanelId { Layers, Brush, Color, Adjust, Selection, Actions, History, Fill, Gradient, Shape, Liquify, Guides }

private const val PANEL_WIDTH = 348

/** Approximate row height, used to turn a drag distance into whole rows. */
private val LAYER_ROW_HEIGHT = 48.dp

// ===================================================================== layers

@Composable
fun LayersPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    var renameTarget by remember { mutableStateOf<LayerUi?>(null) }
    var renameText by remember { mutableStateOf("") }
    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
        PanelHeader("Layers", onClose)
        val soloActive = state.soloLayerId != -1L
        if (soloActive) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(YumeColors.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlyphIcon(Glyph.Eye, YumeColors.accent, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Isolated layer",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.accent
                )
                Spacer(Modifier.weight(1f))
                SmallButton("Show all") { session.toggleSoloLayer() }
            }
            Spacer(Modifier.height(8.dp))
        }
        val active = state.activeNode
        if (active != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlendMenu(active.blend) { blend ->
                    if (active.isGroup) session.setGroupBlend(active.id, blend)
                    else session.setLayerBlend(active.id, blend)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        active.isGroup -> "Group"
                        active.editingMask -> "Editing mask"
                        active.alphaLock -> "Alpha locked"
                        active.reference -> "Reference for fills"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active.editingMask) YumeColors.accent else YumeColors.textDim
                )
                Spacer(Modifier.weight(1f))
                if (!active.isGroup) {
                    YumeIconButton(
                        glyph = Glyph.Bucket,
                        onClick = { session.toggleReference(active.id) },
                        selected = active.reference,
                        size = 30.dp,
                        iconSize = 16.dp,
                        label = "Reference"
                    )
                    Spacer(Modifier.width(4.dp))
                    YumeIconButton(
                        glyph = Glyph.Lock,
                        onClick = { session.toggleAlphaLock(active.id) },
                        selected = active.alphaLock,
                        size = 30.dp,
                        iconSize = 16.dp,
                        label = "Alpha lock"
                    )
                }
            }
            if (!active.isGroup && active.depth > 0) {
                // Group children take their opacity from the group.
                Text(
                    text = "Opacity is set by the group",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                YumeSlider(
                    value = active.opacity,
                    onChange = {
                        if (active.isGroup) session.setGroupOpacity(active.id, it)
                        else session.setLayerOpacity(active.id, it)
                    },
                    onChangeFinished = { session.commitPropertyEdit() },
                    label = "Opacity",
                    valueLabel = formatPercent(active.opacity)
                )
            }
            DividerLine()
        }

        val density = LocalDensity.current
        val rowPx = with(density) { LAYER_ROW_HEIGHT.toPx() }
        val gapPx = with(density) { 3.dp.toPx() }
        val layerCount = state.layers.count { !it.isGroup }
        val rows = state.layers
        // Measured height of every row (group rows and layer rows differ), so
        // slots and shifts line up with the real layout.
        val heights = remember { androidx.compose.runtime.mutableStateMapOf<Long, Int>() }
        fun stepOf(i: Int): Float = (heights[rows[i].id]?.toFloat() ?: rowPx) + gapPx
        /** How many rows a drag of [offset] from row [from] has passed (signed). */
        fun slotsFor(from: Int, offset: Float): Int {
            var n = 0
            var acc = 0f
            if (offset > 0f) {
                var k = from + 1
                while (k < rows.size && offset > acc + stepOf(k) / 2f) { acc += stepOf(k); n++; k++ }
            } else {
                var k = from - 1
                while (k >= 0 && -offset > acc + stepOf(k) / 2f) { acc += stepOf(k); n--; k-- }
            }
            return n
        }
        /** Distance the dragged row travels when it passes [slots] rows. */
        fun distanceFor(from: Int, slots: Int): Float {
            var d = 0f
            if (slots > 0) for (k in from + 1..(from + slots).coerceAtMost(rows.size - 1)) d += stepOf(k)
            if (slots < 0) for (k in (from + slots).coerceAtLeast(0) until from) d -= stepOf(k)
            return d
        }

        var dragId by remember { mutableStateOf(-1L) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        // Sideways drag: pulling a layer left takes it out of its group.
        var dragOffsetX by remember { mutableFloatStateOf(0f) }
        val outdentPx = with(density) { 28.dp.toPx() }
        var dragFrom by remember { mutableStateOf(-1) }
        // After a drop, until the new order arrives from the engine, rows hold
        // the positions they were pushed to and the dropped row stays where the
        // finger left it, so nothing jumps back for a frame.
        var frozenOrder by remember { mutableStateOf<List<Long>?>(null) }
        var frozenFrom by remember { mutableStateOf(-1) }
        var frozenSlots by remember { mutableStateOf(0) }
        var frozenStep by remember { mutableFloatStateOf(0f) }
        var frozenDistance by remember { mutableFloatStateOf(0f) }
        val settle = remember { androidx.compose.animation.core.Animatable(0f) }
        var settleId by remember { mutableStateOf(-1L) }
        val scope = rememberCoroutineScope()
        val order = rows.map { it.id }
        val holding = frozenOrder != null && frozenOrder == order
        val reordered = frozenOrder != null && frozenOrder != order
        LaunchedEffect(order) {
            if (frozenOrder != null && frozenOrder != order) {
                // The reorder landed. The dropped row is now laid out in its new
                // slot: re-express its offset from there and spring it home.
                settle.snapTo(settle.value - frozenDistance)
                frozenOrder = null
                frozenFrom = -1
                frozenSlots = 0
                settle.animateTo(0f, AppleSpring.move())
                settleId = -1L
            }
        }
        val liveSlots = when {
            dragId != -1L -> slotsFor(dragFrom, dragOffset)
            holding -> frozenSlots
            else -> 0
        }
        val from = when {
            dragId != -1L -> dragFrom
            holding -> frozenFrom
            else -> -1
        }
        val moverStep = when {
            dragId != -1L && dragFrom in rows.indices -> stepOf(dragFrom)
            else -> frozenStep
        }

        // A plain scrolling column: a lazy list re-anchors its scroll on the
        // first visible key, which made the list jump when that row moved.
        GutteredColumn(
            Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            rows.forEachIndexed { index, row ->
              androidx.compose.runtime.key(row.id) {
                // Rows the dragged layer has passed slide one slot out of its way.
                val shiftTarget = when {
                    from < 0 || index == from -> 0f
                    liveSlots > 0 && index in (from + 1)..(from + liveSlots) -> -moverStep
                    liveSlots < 0 && index in (from + liveSlots) until from -> moverStep
                    else -> 0f
                }
                val shift by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = shiftTarget,
                    // When the new order lands, rows are already where they were
                    // shifted to: drop the shift in the same frame.
                    animationSpec = if (reordered) {
                        androidx.compose.animation.core.snap()
                    } else {
                        AppleSpring.move()
                    },
                    label = "layerShift"
                )
                val dragging = dragId == row.id
                val settling = settleId == row.id
                Box(
                    Modifier
                        // On the row container, so the lifted row draws above its
                        // neighbours instead of sliding under them.
                        .zIndex(if (dragging || settling) 1f else 0f)
                        .onSizeChanged { heights[row.id] = it.height }
                        .graphicsLayer { translationY = shift }
                ) {
                    if (row.isGroup) {
                        GroupRow(
                            row = row,
                            active = row.id == state.activeLayerId,
                            onSelect = { session.selectGroup(row.id) },
                            onToggleVisible = { session.toggleGroupVisibility(row.id) },
                            onToggleExpanded = { session.toggleGroupExpanded(row.id) },
                            onToggleLock = { session.toggleGroupLock(row.id) }
                        )
                    } else {
                        LayerRow(
                            layer = row,
                            active = row.id == state.activeLayerId,
                            onSelect = { session.selectLayer(row.id) },
                            onToggleVisible = { session.toggleLayerVisibility(row.id) },
                            onToggleLock = { session.toggleLayerLock(row.id) },
                            dragOffset = when {
                                dragging -> dragOffset
                                settling && reordered -> settle.value - frozenDistance
                                settling -> settle.value
                                else -> 0f
                            },
                            lifted = dragging,
                            onDragStart = {
                                scope.launch { settle.snapTo(0f) }
                                settleId = -1L
                                frozenOrder = null
                                dragId = row.id
                                dragFrom = index
                                dragOffset = 0f
                                dragOffsetX = 0f
                            },
                            onDrag = { dragOffset += it },
                            onDragX = { dragOffsetX = (dragOffsetX + it).coerceIn(-outdentPx * 2f, outdentPx) },
                            dragOffsetX = if (dragging) dragOffsetX.coerceAtMost(0f) else 0f,
                            onDragEnd = {
                                val slots = slotsFor(dragFrom, dragOffset)
                                val id = dragId
                                val offset = dragOffset
                                dragId = -1L
                                settleId = id
                                if (slots != 0 && layerCount > 1) {
                                    frozenFrom = dragFrom
                                    frozenSlots = slots
                                    frozenStep = stepOf(dragFrom)
                                    frozenDistance = distanceFor(dragFrom, slots)
                                    frozenOrder = order
                                    val heldOrder = order
                                    scope.launch {
                                        settle.snapTo(offset)
                                        // If the order does not change (a no-op move),
                                        // release the shifts instead of holding them.
                                        kotlinx.coroutines.delay(700)
                                        if (frozenOrder == heldOrder) {
                                            frozenOrder = null
                                            frozenFrom = -1
                                            frozenSlots = 0
                                            settle.animateTo(0f, AppleSpring.move())
                                            settleId = -1L
                                        }
                                    }
                                    val targetPanel = (index + slots).coerceIn(0, rows.size - 1)
                                    // Rows that end up above it: everything up to and
                                    // including the target row when moving down, or
                                    // strictly before it when moving up.
                                    val upTo = if (slots > 0) targetPanel + 1 else targetPanel
                                    val above = rows.take(upTo)
                                        .count { !it.isGroup && it.id != id }
                                    // The row the layer lands under decides its
                                    // group: under an open group's header or
                                    // among its layers joins it; anywhere else
                                    // leaves any group.
                                    val landing = if (slots > 0) rows.getOrNull(targetPanel)
                                    else rows.getOrNull(targetPanel - 1)
                                    val joinGroup: Long = when {
                                        // Pulled left: out of any group, where it lands.
                                        dragOffsetX < -outdentPx -> 0L
                                        landing == null -> 0L
                                        landing.isGroup -> if (landing.expanded) landing.id else 0L
                                        landing.depth > 0 -> {
                                            val at = rows.indexOf(landing)
                                            (at downTo 0).firstOrNull { rows[it].isGroup }?.let { rows[it].id } ?: 0L
                                        }
                                        else -> 0L
                                    }
                                    session.moveLayerTo(
                                        id,
                                        (layerCount - 1 - above).coerceIn(0, layerCount - 1),
                                        joinGroup
                                    )
                                } else {
                                    if (dragOffsetX < -outdentPx && row.depth > 0) {
                                        // Pulled left in place: out of its group, same spot.
                                        val above = rows.take(index).count { !it.isGroup && it.id != id }
                                        session.moveLayerTo(id, (layerCount - 1 - above).coerceIn(0, layerCount - 1), 0L)
                                    }
                                    // Not moved: spring back home from the finger.
                                    scope.launch {
                                        settle.snapTo(offset)
                                        settle.animateTo(0f, AppleSpring.move())
                                        settleId = -1L
                                    }
                                }
                                dragOffset = 0f
                                dragOffsetX = 0f
                            }
                        )
                    }
                }
              }
            }
        }

        // The paper under the layers: its colour, or none at all.
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Background", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim, modifier = Modifier.weight(1f))
            ColorSwatch(0xFFFFFFFF.toInt(), state.backgroundColor == 0xFFFFFFFF.toInt(), { session.setBackgroundColor(0xFFFFFFFF.toInt()) })
            ColorSwatch(0xFF121214.toInt(), state.backgroundColor == 0xFF121214.toInt(), { session.setBackgroundColor(0xFF121214.toInt()) })
            ColorSwatch(state.color or 0xFF000000.toInt(), false, { session.setBackgroundColor(state.color or 0xFF000000.toInt()) })
            SmallButton(if (state.backgroundColor == 0x00000000) "• None" else "None") { session.setBackgroundColor(0x00000000) }
        }
        DividerLine()
        Row(verticalAlignment = Alignment.CenterVertically) {
            YumeIconButton(Glyph.Plus, { session.addLayer() }, size = 36.dp, iconSize = 18.dp, label = "Add layer")
            YumeIconButton(Glyph.Copy, { session.duplicateLayer() }, size = 36.dp, iconSize = 17.dp, label = "Duplicate")
            YumeIconButton(Glyph.Merge, { session.mergeDown() }, size = 36.dp, iconSize = 17.dp, label = "Merge down")
            YumeIconButton(Glyph.Clear, { session.clearLayer() }, size = 36.dp, iconSize = 17.dp, label = "Clear layer")
            YumeIconButton(
                Glyph.Trash,
                { session.deleteLayer() },
                enabled = state.layers.count { !it.isGroup } > 1,
                size = 36.dp,
                iconSize = 17.dp,
                label = "Delete layer"
            )
            Spacer(Modifier.weight(1f))
            YumeIconButton(
                Glyph.Eye,
                { session.toggleSoloLayer() },
                selected = soloActive,
                size = 36.dp,
                iconSize = 16.dp,
                label = "Show only this layer"
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val layerNode = active?.takeIf { !it.isGroup }
            MaskButton(
                hasMask = layerNode?.hasMask == true,
                editing = layerNode?.editingMask == true,
                enabled = layerNode != null,
                onClick = {
                    if (layerNode?.hasMask == true) session.toggleEditMask()
                    else session.addLayerMask()
                },
                onLongClick = { if (layerNode?.hasMask == true) session.removeLayerMask() }
            )
            YumeIconButton(
                Glyph.Clip,
                { layerNode?.let { session.toggleClipping(it.id) } },
                selected = layerNode?.clipping == true,
                enabled = layerNode != null,
                size = 36.dp,
                iconSize = 18.dp,
                label = "Clip to layer below"
            )
            val inGroup = active?.isGroup == true || (active?.depth ?: 0) > 0
            YumeIconButton(
                Glyph.Folder,
                { if (inGroup) session.ungroupActive() else session.groupActiveLayer() },
                selected = inGroup,
                enabled = active != null,
                size = 36.dp,
                iconSize = 18.dp,
                label = "Group or ungroup"
            )
            YumeIconButton(
                Glyph.More,
                { renameTarget = active; renameText = active?.name ?: "" },
                enabled = active != null,
                size = 36.dp,
                iconSize = 17.dp,
                label = "Rename"
            )
            Spacer(Modifier.weight(1f))
            val nodeId = active?.id ?: -1L
            val isLayer = active != null && !active.isGroup
            YumeIconButton(
                Glyph.ChevronUp,
                { session.nudgeLayer(nodeId, 1) },
                enabled = isLayer,
                size = 36.dp,
                iconSize = 16.dp,
                label = "Move up"
            )
            YumeIconButton(
                Glyph.ChevronDown,
                { session.nudgeLayer(nodeId, -1) },
                enabled = isLayer,
                size = 36.dp,
                iconSize = 16.dp,
                label = "Move down"
            )
        }
    }

    renameTarget?.let { target ->
        Dialog(onDismissRequest = { renameTarget = null }) {
            PanelCard(Modifier.width(320.dp)) {
                PanelHeader("Rename", onClose = { renameTarget = null })
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(YumeColors.accent)
                        .clickable {
                            if (target.isGroup) session.renameGroup(target.id, renameText)
                            else session.renameLayer(target.id, renameText)
                            renameTarget = null
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Save",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1A1206)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(
    layer: LayerUi,
    active: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLock: () -> Unit,
    dragOffset: Float,
    lifted: Boolean = false,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragX: (Float) -> Unit = {},
    dragOffsetX: Float = 0f,
    onDragEnd: () -> Unit
) {
    val dragStart by androidx.compose.runtime.rememberUpdatedState(onDragStart)
    val dragBy by androidx.compose.runtime.rememberUpdatedState(onDrag)
    val dragByX by androidx.compose.runtime.rememberUpdatedState(onDragX)
    val dragEnd by androidx.compose.runtime.rememberUpdatedState(onDragEnd)
    // A picked-up row lifts off the list: a little larger, with a shadow and a
    // solid backing, and it springs into and out of that state.
    val lift by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = AppleSpring.sheet(),
        label = "lift"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = dragOffset
                translationX = dragOffsetX
                scaleX = 1f + 0.035f * lift
                scaleY = 1f + 0.035f * lift
                shadowElevation = 14.dp.toPx() * lift
                shape = RoundedCornerShape(11.dp)
                clip = false
            }
            .clip(RoundedCornerShape(11.dp))
            .background(
                when {
                    lift > 0.01f -> Color(0xFF2A2A31).copy(alpha = 0.6f + 0.4f * lift)
                    active -> YumeColors.accentSoft
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onSelect)
            // Long press picks the layer up; dragging then reorders the stack.
            .pointerInput(layer.id) {
                // Through State: the gesture outlives recompositions, and the
                // row's index changes whenever layers are added or reordered.
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        dragBy(amount.y)
                        dragByX(amount.x)
                    },
                    onDragEnd = { dragEnd() },
                    onDragCancel = { dragEnd() }
                )
            }
            .padding(
                start = if (layer.depth > 0) 14.dp else 4.dp,
                end = 6.dp,
                top = 3.dp,
                bottom = 3.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        YumeIconButton(
            glyph = if (layer.visible) Glyph.Eye else Glyph.EyeOff,
            onClick = onToggleVisible,
            size = 28.dp,
            iconSize = 15.dp,
            tint = if (layer.visible) YumeColors.text else YumeColors.textDim,
            label = "Show or hide"
        )
        LayerBadge(layer.thumb, highlighted = !layer.editingMask)
        if (layer.hasMask) {
            Spacer(Modifier.width(3.dp))
            LayerBadge(layer.maskThumb, highlighted = layer.editingMask)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = layer.name,
                style = MaterialTheme.typography.bodyMedium,
                color = YumeColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(layer.blend.label)
                    append(" \u00b7 ")
                    append(formatPercent(layer.opacity))
                    if (layer.clipping) append(" \u00b7 clipped")
                    if (!layer.maskEnabled && layer.hasMask) append(" \u00b7 mask off")
                },
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        YumeIconButton(
            glyph = Glyph.Lock,
            onClick = onToggleLock,
            selected = layer.locked,
            size = 26.dp,
            iconSize = 13.dp,
            tint = if (layer.locked) YumeColors.accent else YumeColors.textDim,
            label = "Lock layer"
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    row: LayerUi,
    active: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleLock: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) YumeColors.accentSoft else YumeColors.ink.copy(alpha = 0x14 / 255f))
            .clickable(onClick = onSelect)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        YumeIconButton(
            glyph = if (row.expanded) Glyph.ChevronDown else Glyph.ChevronUp,
            onClick = onToggleExpanded,
            size = 26.dp,
            iconSize = 13.dp,
            label = "Expand or collapse"
        )
        YumeIconButton(
            glyph = if (row.visible) Glyph.Eye else Glyph.EyeOff,
            onClick = onToggleVisible,
            size = 28.dp,
            iconSize = 15.dp,
            tint = if (row.visible) YumeColors.text else YumeColors.textDim,
            label = "Show or hide"
        )
        Spacer(Modifier.width(4.dp))
        GlyphIcon(Glyph.Folder, YumeColors.accent, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = YumeColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Group \u00b7 ${row.blend.label} \u00b7 ${formatPercent(row.opacity)}",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim,
                maxLines = 1
            )
        }
        YumeIconButton(
            glyph = Glyph.Lock,
            onClick = onToggleLock,
            selected = row.locked,
            size = 26.dp,
            iconSize = 13.dp,
            tint = if (row.locked) YumeColors.accent else YumeColors.textDim,
            label = "Lock group"
        )
    }
}

@Composable
private fun LayerBadge(bitmap: Bitmap?, highlighted: Boolean) {
    Box(
        Modifier
            .size(width = 40.dp, height = 32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF32323A))
            .border(
                width = if (highlighted) 1.5.dp else 0.dp,
                color = if (highlighted) YumeColors.accent else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/** Mask control: tap to add/toggle editing, long press to remove the mask. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MaskButton(
    hasMask: Boolean,
    editing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (editing) YumeColors.accentSoft else Color.Transparent)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(
            Glyph.Mask,
            if (hasMask) YumeColors.accent else YumeColors.textDim,
            size = 19.dp
        )
    }
}

@Composable
private fun BlendMenu(current: LayerBlend, onSelect: (LayerBlend) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(YumeColors.panelAlt)
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = current.label,
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.text
            )
            Spacer(Modifier.width(6.dp))
            GlyphIcon(Glyph.ChevronDown, YumeColors.textDim, size = 13.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LayerBlend.entries.forEach { blend ->
                DropdownMenuItem(
                    text = { Text(blend.label) },
                    onClick = {
                        onSelect(blend)
                        open = false
                    }
                )
            }
        }
    }
}

// ====================================================================== brush

@Composable
fun BrushPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    var advanced by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val preset = state.presets.firstOrNull { it.id == state.activePresetId }
    // Wide, in two panes: the brushes on the left, the selected brush (its
    // stroke preview and settings) on the right.
    // A constant height: switching brushes or opening "More settings" never
    // makes the panel jump.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val scale = com.yume.app.data.AppSettings.uiScale * UI_SCALE_BASE
    val screenW = (config.screenWidthDp / scale).toInt()
    val screenH = (config.screenHeightDp / scale).toInt()
    // Phones: one pane at a time, switched at the top.
    val compact = isCompact()
    var pane by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val panelH = (screenH - if (compact) 170 else 130).coerceIn(360, if (compact) 900 else 720)
    PanelCard(Modifier.width(minOf(780, screenW - 24).dp).height(panelH.dp)) {
        PanelHeader("Brush Studio", onClose)
        if (compact) {
            SegmentedRow(listOf("Brushes", "Settings"), pane, { pane = it })
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.weight(1f)) {
        if (!compact || pane == 0) Column(if (compact) Modifier.weight(1f) else Modifier.width(336.dp)) {
        SegmentedRow(
            options = listOf("Brush", "Eraser", "Smudge"),
            selectedIndex = when (state.category) {
                BrushCategory.Brush -> 0
                BrushCategory.Eraser -> 1
                BrushCategory.Smudge -> 2
            },
            onSelect = { index ->
                session.chooseTool(
                    when (index) {
                        0 -> Tool.Brush
                        1 -> Tool.Eraser
                        else -> Tool.Smudge
                    }
                )
            }
        )
        Spacer(Modifier.height(10.dp))
        // Library groups of this tool's brushes, plus Favourites and Recent.
        val prefs = com.yume.app.data.BrushPrefs
        val present = state.presets.map { it.group.ifEmpty { "Custom" } }.toSet()
        val groups = listOf(com.yume.app.data.BrushPrefs.FAVORITES, com.yume.app.data.BrushPrefs.RECENT, com.yume.app.data.BrushPrefs.ALL) +
            com.yume.app.engine.BrushLibrary.groupOrder.filter { it in present } +
            present.filter { it !in com.yume.app.engine.BrushLibrary.groupOrder }.sorted()
        val group = prefs.group.takeIf { it in groups } ?: com.yume.app.data.BrushPrefs.ALL
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            groups.forEach { g ->
                val sel = g == group
                Text(
                    text = g,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sel) YumeColors.accent else YumeColors.textDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) YumeColors.accentSoft else YumeColors.panelAlt)
                        .clickable { prefs.chooseGroup(g) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val shown = when (group) {
            com.yume.app.data.BrushPrefs.FAVORITES -> state.presets.filter { it.id in prefs.favorites }
            com.yume.app.data.BrushPrefs.RECENT -> prefs.recents.mapNotNull { id -> state.presets.firstOrNull { it.id == id } }
            com.yume.app.data.BrushPrefs.ALL -> state.presets
            else -> state.presets.filter { it.group.ifEmpty { "Custom" } == group }
        }
        if (shown.isEmpty()) {
            Text(
                text = if (group == com.yume.app.data.BrushPrefs.FAVORITES) "Long-press any brush to add it to Favorites."
                else "Brushes you paint with show up here.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
        // The brush grid scrolls on its own, beside the settings; brushes
        // passing out of view blur gradually at its top and bottom.
        val gridScroll = rememberScrollState()
        // A scrollbar strip beside the grid, as beside the settings.
        Row(Modifier.weight(1f)) {
        Column(
            Modifier
                .weight(1f)
                .edgeBlur(gridScroll)
                .verticalScroll(gridScroll),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            shown.chunked(3).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunk.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            BrushChip(
                                item,
                                item.id == state.activePresetId,
                                favorite = item.id in prefs.favorites,
                                onLongClick = { prefs.toggleFavorite(item.id) }
                            ) {
                                session.selectPreset(item.id)
                                prefs.noteUsed(item.id)
                            }
                        }
                    }
                    repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        ScrollGutter(gridScroll, Modifier.width(26.dp).fillMaxHeight())
        }
        }
        if (!compact) Spacer(Modifier.width(10.dp))
        if (!compact || pane == 1) Column(Modifier.weight(1f)) {
        if (preset != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = YumeColors.text,
                    modifier = Modifier.weight(1f)
                )
                val fav = preset.id in com.yume.app.data.BrushPrefs.favorites
                Text(
                    text = if (fav) "★ Favorite" else "☆ Favorite",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (fav) YumeColors.accent else YumeColors.textDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { com.yume.app.data.BrushPrefs.toggleFavorite(preset.id) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
                // The brush (eraser, blender) new projects start with: any
                // brush, imported and custom ones included.
                val cu = com.yume.app.data.Customization
                val isDefault = when (preset.category) {
                    BrushCategory.Brush -> cu.defaultBrush == preset.id
                    BrushCategory.Eraser -> cu.defaultEraser == preset.id
                    BrushCategory.Smudge -> cu.defaultSmudge == preset.id
                }
                Text(
                    text = if (isDefault) "● Default" else "○ Default",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isDefault) YumeColors.accent else YumeColors.textDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = !isDefault) {
                            when (preset.category) {
                                BrushCategory.Brush -> cu.setDefaultBrushValue(preset.id)
                                BrushCategory.Eraser -> cu.setDefaultEraserValue(preset.id)
                                BrushCategory.Smudge -> cu.setDefaultSmudgeValue(preset.id)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            BrushStrokePreview(preset, state.color)
            Spacer(Modifier.height(8.dp))
            // Only the settings scroll; the preview above stays put. A blank
            // strip on the right carries the scrollbar: dragging there only
            // scrolls, never moves a slider.
            val settingsScroll = rememberScrollState()
            Row(Modifier.weight(1f)) {
            Column(Modifier.weight(1f).edgeBlur(settingsScroll, 18.dp).verticalScroll(settingsScroll)) {
            YumeSlider(
                value = state.assist,
                onChange = { session.updateAssist(it) },
                label = "Stroke assist",
                valueLabel = formatPercent(state.assist)
            )
            YumeSlider(
                value = preset.size,
                onChange = { session.setBrushSize(it) },
                label = "Size",
                valueLabel = "${preset.size.roundToInt()} px",
                min = 1f,
                max = 900f
            )
            YumeSlider(
                value = preset.opacity,
                onChange = { session.setBrushOpacity(it) },
                label = "Opacity",
                valueLabel = formatPercent(preset.opacity),
                min = 0.02f
            )
            YumeSlider(
                value = preset.flow,
                onChange = { v -> session.updatePreset { it.copy(flow = v) } },
                label = "Flow",
                valueLabel = formatPercent(preset.flow)
            )
            YumeSlider(
                value = preset.hardness,
                onChange = { v -> session.updatePreset { it.copy(hardness = v) } },
                label = "Hardness",
                valueLabel = formatPercent(preset.hardness)
            )
            YumeSlider(
                value = preset.spacing,
                onChange = { v -> session.updatePreset { it.copy(spacing = v) } },
                label = "Spacing",
                valueLabel = formatPercent(preset.spacing),
                max = 0.6f
            )
            if (advanced) {
                YumeSlider(
                    value = preset.smoothing,
                    onChange = { v -> session.updatePreset { it.copy(smoothing = v) } },
                    label = "Streamline",
                    valueLabel = formatPercent(preset.smoothing)
                )
                YumeSlider(
                    value = preset.grain,
                    onChange = { v -> session.updatePreset { it.copy(grain = v) } },
                    label = "Grain",
                    valueLabel = formatPercent(preset.grain)
                )
                YumeSlider(
                    value = preset.jitter,
                    onChange = { v -> session.updatePreset { it.copy(jitter = v) } },
                    label = "Jitter",
                    valueLabel = formatPercent(preset.jitter)
                )
                YumeSlider(
                    value = preset.pressureSize,
                    onChange = { v -> session.updatePreset { it.copy(pressureSize = v) } },
                    label = "Pressure → Size",
                    valueLabel = formatPercent(preset.pressureSize)
                )
                YumeSlider(
                    value = preset.pressureOpacity,
                    onChange = { v -> session.updatePreset { it.copy(pressureOpacity = v) } },
                    label = "Pressure → Opacity",
                    valueLabel = formatPercent(preset.pressureOpacity)
                )
                YumeSlider(
                    value = preset.tiltSize,
                    onChange = { v -> session.updatePreset { it.copy(tiltSize = v) } },
                    label = "Tilt → Size",
                    valueLabel = formatPercent(preset.tiltSize)
                )
                YumeSlider(
                    value = preset.tiltOpacity,
                    onChange = { v -> session.updatePreset { it.copy(tiltOpacity = v) } },
                    label = "Tilt → Opacity",
                    valueLabel = formatPercent(preset.tiltOpacity)
                )
                if (preset.category == BrushCategory.Smudge) {
                    YumeSlider(
                        value = preset.smudgeAmount,
                        onChange = { v -> session.updatePreset { it.copy(smudgeAmount = v) } },
                        label = "Smudge strength",
                        valueLabel = formatPercent(preset.smudgeAmount)
                    )
                }
            }
            if (advanced) {
                DividerLine()
                BrushExtras(preset, session)
            }
            Text(
                text = if (advanced) "Fewer settings" else "More settings",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { advanced = !advanced }
                    .padding(vertical = 7.dp)
            )
            DividerLine()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Save as new") {
                    newName = preset.name
                    showSaveDialog = true
                }
                SmallButton("Reset", enabled = preset.builtIn) {
                    session.resetActivePreset()
                }
                if (!preset.builtIn) {
                    SmallButton("Delete") { session.deleteActivePreset() }
                }
            }
            Spacer(Modifier.height(4.dp))
            }
            ScrollGutter(settingsScroll, Modifier.width(30.dp).fillMaxHeight())
            }
        }
        }
        }
    }

    if (showSaveDialog) {
        Dialog(onDismissRequest = { showSaveDialog = false }) {
            PanelCard(Modifier.width(320.dp)) {
                PanelHeader("Save brush preset", onClose = { showSaveDialog = false })
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Adds it to the “${state.category.label}” list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(YumeColors.accent)
                        .clickable {
                            session.savePresetAs(newName)
                            showSaveDialog = false
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Save preset",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1A1206)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BrushChip(
    preset: BrushPreset,
    selected: Boolean,
    favorite: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit
) {
    val preview = remember(preset.dabKey, preset.size.roundToInt()) {
        BrushDab.cachedPreview(preset, 72)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) YumeColors.accentSoft else YumeColors.panelAlt)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = preset.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            if (favorite) {
                Text(
                    "★",
                    style = MaterialTheme.typography.labelMedium,
                    color = YumeColors.accent,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-6).dp)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = preset.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) YumeColors.accent else YumeColors.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ====================================================================== color

@Composable
fun ColorPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(state.color, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    // The last colour this panel set, to tell its own changes from ones made
    // elsewhere (eyedropper, a swatch outside, another panel).
    var lastSent by remember { androidx.compose.runtime.mutableIntStateOf(state.color) }
    // Bumped when the colour changed elsewhere: the harmony scheme re-centres.
    var outsideChange by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    fun emit() {
        val color = android.graphics.Color.HSVToColor(
            floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        )
        lastSent = color
        session.chooseColor(color)
    }

    LaunchedEffect(state.color) {
        if (state.color != lastSent) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(state.color, hsv)
            if (hsv[1] > 0.004f) hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
            lastSent = state.color
            outsideChange++
        }
    }

    fun adopt(color: Int, keepHueWhenGrey: Boolean = false) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        if (keepHueWhenGrey && hsv[1] <= 0.004f) {
            sat = hsv[1]
            value = hsv[2]
        } else {
            hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
        }
        lastSent = color
        session.chooseColor(color)
    }

    val current = android.graphics.Color.HSVToColor(
        floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    )

    PanelCard(Modifier.width(PANEL_WIDTH.dp), scrollable = true) {
        PanelHeader("Color", onClose)
        SegmentedRow(
            options = ColorMode.entries.map { it.label },
            selectedIndex = state.colorMode.ordinal,
            onSelect = { session.chooseColorMode(ColorMode.entries[it]) }
        )
        Spacer(Modifier.height(12.dp))

        when (state.colorMode) {
            ColorMode.Wheel -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    HueWheel(hue, 150.dp) { h ->
                        hue = h
                        emit()
                    }
                }
                Spacer(Modifier.height(10.dp))
                SvSquare(hue, sat, value, 104.dp) { s, v ->
                    sat = s
                    value = v
                    emit()
                }
            }

            ColorMode.Triangle -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    ColorTriangle(
                        hue = hue, sat = sat, value = value, size = 236.dp,
                        onHue = { h ->
                            hue = h
                            emit()
                        },
                        onSv = { s, v ->
                            sat = s
                            value = v
                            emit()
                        }
                    )
                }
            }

            ColorMode.Rgb -> {
                val r = android.graphics.Color.red(current)
                val g = android.graphics.Color.green(current)
                val b = android.graphics.Color.blue(current)
                YumeSlider(
                    value = r.toFloat(),
                    onChange = { v ->
                        adopt(android.graphics.Color.rgb(v.roundToInt(), g, b), true)
                    },
                    label = "Red",
                    valueLabel = r.toString(),
                    min = 0f,
                    max = 255f,
                    accent = Color(0xFFE05A5A)
                )
                YumeSlider(
                    value = g.toFloat(),
                    onChange = { v ->
                        adopt(android.graphics.Color.rgb(r, v.roundToInt(), b), true)
                    },
                    label = "Green",
                    valueLabel = g.toString(),
                    min = 0f,
                    max = 255f,
                    accent = Color(0xFF5AC97A)
                )
                YumeSlider(
                    value = b.toFloat(),
                    onChange = { v ->
                        adopt(android.graphics.Color.rgb(r, g, v.roundToInt()), true)
                    },
                    label = "Blue",
                    valueLabel = b.toString(),
                    min = 0f,
                    max = 255f,
                    accent = Color(0xFF5A8AE0)
                )
            }

            ColorMode.Harmony -> {
                SegmentedRow(
                    options = com.yume.app.engine.Harmony.entries.map { it.label },
                    selectedIndex = state.harmony.ordinal,
                    onSelect = { session.chooseHarmony(com.yume.app.engine.Harmony.entries[it]) }
                )
                // The scheme stays put: its colours keep their order, and the
                // one in use is [pick]. Choosing another only moves the pick,
                // so you can step either way through the scheme.
                // Starts on (and returns to, when the colour changes elsewhere)
                // the middle colour: the current one, partners either side.
                var pick by remember(state.harmony, outsideChange) {
                    androidx.compose.runtime.mutableIntStateOf(state.harmony.centre)
                }
                val all = state.harmony.order
                if (pick !in all.indices) pick = state.harmony.centre
                val base = hue - all[pick]
                val others = all.indices.filter { it != pick }.map { all[it] - all[pick] }.toFloatArray()
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    HarmonyDisc(hue, sat, value, others, 200.dp) { h, s ->
                        hue = h
                        sat = s
                        emit()
                    }
                }
                Spacer(Modifier.height(8.dp))
                YumeSlider(
                    value = value,
                    onChange = { v ->
                        value = v.coerceIn(0f, 1f)
                        emit()
                    },
                    label = "Brightness",
                    valueLabel = "${(value * 100).roundToInt()}%"
                )
                Spacer(Modifier.height(6.dp))
                // The scheme as swatches, always in the same order; the one in
                // use is outlined. Tapping another makes it the colour.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((i, off) in all.withIndex()) {
                        val h = ((base + off) % 360f + 360f) % 360f
                        val c = android.graphics.Color.HSVToColor(
                            floatArrayOf(h, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(c))
                                .then(
                                    if (i == pick) Modifier.border(2.dp, YumeColors.accent, RoundedCornerShape(10.dp))
                                    else Modifier.clickable {
                                        // Set the hue directly: a grey colour
                                        // has no hue to read back.
                                        pick = i
                                        hue = h
                                        emit()
                                    }
                                )
                        )
                    }
                }
            }

            ColorMode.Hsl -> {
                // The sliders keep their own values: black or white has no hue
                // or saturation to read back, so values derived from the colour
                // snapped to 0 and the sliders seemed dead. They take the colour
                // when the tab opens or the colour changes elsewhere.
                val startHsl = remember(outsideChange) {
                    rgbToHsl(current).also { if (it[1] <= 0.004f || it[2] <= 0.002f || it[2] >= 0.998f) it[0] = hue }
                }
                var hslH by remember(outsideChange) { mutableFloatStateOf(startHsl[0]) }
                var hslS by remember(outsideChange) {
                    // A grey keeps the HSV saturation it came with (often 0).
                    mutableFloatStateOf(startHsl[1])
                }
                var hslL by remember(outsideChange) { mutableFloatStateOf(startHsl[2]) }
                fun applyHsl() {
                    hue = hslH
                    adopt(hslToRgb(hslH, hslS, hslL), true)
                }
                YumeSlider(
                    value = hslH,
                    onChange = { h ->
                        hslH = h.coerceIn(0f, 359f)
                        applyHsl()
                    },
                    label = "Hue",
                    valueLabel = "${hslH.roundToInt()}°",
                    min = 0f,
                    max = 359f,
                    // Every hue at full strength, so the track reads even for a grey.
                    trackColors = (0..6).map { Color(hslToRgb(it * 60f, 1f, 0.5f)) },
                    knobColor = Color(hslToRgb(hslH, 1f, 0.5f))
                )
                YumeSlider(
                    value = hslS,
                    onChange = { v ->
                        hslS = v.coerceIn(0f, 1f)
                        applyHsl()
                    },
                    label = "Saturation",
                    valueLabel = "${(hslS * 100).roundToInt()}%",
                    // Shown at mid lightness when the colour is black or white,
                    // where every saturation looks the same.
                    trackColors = run {
                        val l = if (hslL <= 0.02f || hslL >= 0.98f) 0.5f else hslL
                        listOf(Color(hslToRgb(hslH, 0f, l)), Color(hslToRgb(hslH, 1f, l)))
                    },
                    knobColor = Color(current)
                )
                YumeSlider(
                    value = hslL,
                    onChange = { v ->
                        hslL = v.coerceIn(0f, 1f)
                        applyHsl()
                    },
                    label = "Lightness",
                    valueLabel = "${(hslL * 100).roundToInt()}%",
                    trackColors = listOf(
                        Color(hslToRgb(hslH, hslS, 0f)),
                        Color(hslToRgb(hslH, hslS, 0.5f)),
                        Color(hslToRgb(hslH, hslS, 1f))
                    ),
                    knobColor = Color(current)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(current))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = String.format("#%06X", current and 0xFFFFFF),
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "H${hue.roundToInt()} S${(sat * 100).roundToInt()} B${(value * 100).roundToInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
        }
        DividerLine()
        PaletteSection(current = current, onPick = { picked -> adopt(picked); outsideChange++ })
        if (state.recentColors.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Recent")
            Spacer(Modifier.height(8.dp))
            SwatchGrid(state.recentColors) { picked -> adopt(picked); outsideChange++ }
        }
    }
}

/**
 * Hue and saturation disc for the harmony mode: hue by angle, saturation by
 * distance from the centre, shaded to the current brightness. The current
 * colour's marker can be dragged; its partners' markers (hue [offsets]
 * away) follow it.
 */
@Composable
private fun HarmonyDisc(
    hue: Float, sat: Float, value: Float, offsets: FloatArray, size: Dp,
    onChange: (Float, Float) -> Unit
) {
    val spectrum = listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
    )
    Canvas(
        Modifier
            .size(size)
            .pointerInput(Unit) {
                fun update(offset: Offset) {
                    val r = this.size.width / 2f
                    val dx = offset.x - r
                    val dy = offset.y - this.size.height / 2f
                    var deg = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                    if (deg < 0f) deg += 360f
                    val s = (kotlin.math.hypot(dx, dy) / (r * 0.92f)).coerceIn(0f, 1f)
                    onChange(deg, s)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        val radius = this.size.minDimension / 2f * 0.92f
        drawCircle(brush = Brush.sweepGradient(spectrum), radius = radius)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius),
            radius = radius
        )
        // Shaded towards the brightness, but never so far that the hues
        // vanish (a black colour would blank the disc); the markers and the
        // swatches show the true colours.
        drawCircle(Color.Black.copy(alpha = ((1f - value) * 0.6f).coerceIn(0f, 0.6f)), radius)
        fun marker(h: Float, main: Boolean) {
            val a = Math.toRadians(h.toDouble())
            val d = radius * sat.coerceIn(0f, 1f)
            val p = Offset(center.x + (d * kotlin.math.cos(a)).toFloat(), center.y + (d * kotlin.math.sin(a)).toFloat())
            val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, sat, value)))
            val r = if (main) 11.dp.toPx() else 8.dp.toPx()
            drawCircle(c, r, p)
            drawCircle(Color.Black.copy(alpha = 0.45f), r + 1.5f.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, r, p, style = Stroke(if (main) 3.dp.toPx() else 2.dp.toPx()))
        }
        // Lines from the centre tie the scheme together.
        for (off in offsets) {
            val a = Math.toRadians((hue + off).toDouble())
            val d = radius * sat.coerceIn(0f, 1f)
            drawLine(
                Color.White.copy(alpha = 0.55f), center,
                Offset(center.x + (d * kotlin.math.cos(a)).toFloat(), center.y + (d * kotlin.math.sin(a)).toFloat()),
                strokeWidth = 1.5f.dp.toPx()
            )
        }
        val a0 = Math.toRadians(hue.toDouble())
        val d0 = radius * sat.coerceIn(0f, 1f)
        drawLine(
            Color.White.copy(alpha = 0.85f), center,
            Offset(center.x + (d0 * kotlin.math.cos(a0)).toFloat(), center.y + (d0 * kotlin.math.sin(a0)).toFloat()),
            strokeWidth = 2.dp.toPx()
        )
        for (off in offsets) marker(hue + off, false)
        marker(hue, true)
    }
}

/** Circular hue picker used by the wheel mode. */
@Composable
private fun HueWheel(hue: Float, size: Dp, onChange: (Float) -> Unit) {
    val spectrum = listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
    )
    Canvas(
        Modifier
            .size(size)
            .pointerInput(Unit) {
                fun update(offset: Offset) {
                    val dx = offset.x - this.size.width / 2f
                    val dy = offset.y - this.size.height / 2f
                    var deg = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
                    if (deg < 0f) deg += 360f
                    onChange(deg)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        val radius = this.size.minDimension / 2f
        val stroke = radius * 0.22f
        drawCircle(
            brush = Brush.sweepGradient(spectrum),
            radius = radius - stroke / 2f,
            style = Stroke(width = stroke)
        )
        val angle = Math.toRadians(hue.toDouble())
        val knobR = radius - stroke / 2f
        val cx = center.x + (knobR * kotlin.math.cos(angle)).toFloat()
        val cy = center.y + (knobR * kotlin.math.sin(angle)).toFloat()
        drawCircle(Color.Black.copy(alpha = 0.45f), 10.dp.toPx(), Offset(cx, cy), style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), Offset(cx, cy), style = Stroke(2.5f.dp.toPx()))
        // Live colour in the middle of the wheel.
        drawCircle(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))), radius * 0.52f)
    }
}

/** HSL -> packed ARGB. */
private fun hslToRgb(h: Float, s: Float, l: Float): Int {
    val hh = ((h % 360f) + 360f) % 360f
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = hh / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val r1: Float
    val g1: Float
    val b1: Float
    when {
        hp < 1f -> { r1 = c; g1 = x; b1 = 0f }
        hp < 2f -> { r1 = x; g1 = c; b1 = 0f }
        hp < 3f -> { r1 = 0f; g1 = c; b1 = x }
        hp < 4f -> { r1 = 0f; g1 = x; b1 = c }
        hp < 5f -> { r1 = x; g1 = 0f; b1 = c }
        else -> { r1 = c; g1 = 0f; b1 = x }
    }
    val m = l - c / 2f
    return android.graphics.Color.rgb(
        ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
        ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
        ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
    )
}

/** Packed ARGB -> [hue, saturation, lightness]. */
private fun rgbToHsl(color: Int): FloatArray {
    val r = android.graphics.Color.red(color) / 255f
    val g = android.graphics.Color.green(color) / 255f
    val b = android.graphics.Color.blue(color) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d < 0.0001f) return floatArrayOf(0f, 0f, l)
    val s = d / (1f - kotlin.math.abs(2f * l - 1f)).coerceAtLeast(0.0001f)
    var hue = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    if (hue < 0f) hue += 360f
    return floatArrayOf(hue, s, l)
}

@Composable
private fun SwatchGrid(colors: List<Int>, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { color ->
                    ColorSwatch(color, selected = false, onClick = { onPick(color) }, size = 27.dp)
                }
            }
        }
    }
}

/**
 * Saved palettes: pick one from the chips, tap a swatch to use it, "+" adds
 * the current colour. Edit mode removes swatches, renames or deletes the
 * palette. Palettes are app-wide and survive restarts.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PaletteSection(current: Int, onPick: (Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { (context.applicationContext as com.yume.app.YumeApp).paletteStore }
    val palettes by store.palettes.collectAsState()
    val activeId by store.activeId.collectAsState()
    val active = palettes.firstOrNull { it.id == activeId } ?: palettes.first()
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Palettes")
        Spacer(Modifier.weight(1f))
        Text(
            text = if (editing) "Done" else "Edit",
            style = MaterialTheme.typography.labelMedium,
            color = YumeColors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { editing = !editing }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        palettes.forEach { p ->
            val on = p.id == active.id
            Text(
                text = p.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (on) YumeColors.bg else YumeColors.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) YumeColors.text else YumeColors.ink.copy(alpha = 0x1F / 255f))
                    .clickable { store.select(p.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Text(
            text = "+ New",
            style = MaterialTheme.typography.labelMedium,
            color = YumeColors.textDim,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(YumeColors.ink.copy(alpha = 0x14 / 255f))
                .clickable { store.create(current) }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
    Spacer(Modifier.height(10.dp))
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 8
    ) {
        active.colors.forEachIndexed { i, c ->
            Box(contentAlignment = Alignment.Center) {
                ColorSwatch(
                    c, selected = false, size = 27.dp,
                    onClick = { if (editing) store.removeColorAt(active.id, i) else onPick(c) }
                )
                if (editing) GlyphIcon(Glyph.Close, Color.White, size = 12.dp)
            }
        }
        // Add the current colour.
        Box(
            Modifier
                .size(27.dp)
                .clip(CircleShape)
                .border(1.5.dp, YumeColors.ink.copy(alpha = 0x55 / 255f), CircleShape)
                .clickable { store.addColor(active.id, current) },
            contentAlignment = Alignment.Center
        ) {
            GlyphIcon(Glyph.Plus, YumeColors.text, size = 14.dp)
        }
    }
    if (editing) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Rename") { renaming = true }
            SmallButton("Delete palette", enabled = palettes.size > 1) {
                store.delete(active.id)
                editing = false
            }
        }
    }
    if (renaming) {
        RenameDialog(
            current = active.name,
            onDismiss = { renaming = false },
            onRename = { name ->
                store.rename(active.id, name)
                renaming = false
            }
        )
    }
}

@Composable
internal fun SvSquare(
    hue: Float,
    sat: Float,
    value: Float,
    height: Dp,
    onChange: (Float, Float) -> Unit
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                fun update(p: Offset) {
                    onChange(
                        (p.x / size.width).coerceIn(0f, 1f),
                        (1f - p.y / size.height).coerceIn(0f, 1f)
                    )
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        drawRect(pure)
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        val cx = sat.coerceIn(0f, 1f) * size.width
        val cy = (1f - value.coerceIn(0f, 1f)) * size.height
        drawCircle(Color.Black.copy(alpha = 0.4f), 9.dp.toPx(), Offset(cx, cy), style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val spectrum = listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                fun update(p: Offset) {
                    onChange((p.x / size.width).coerceIn(0f, 1f) * 359f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        val h = size.height
        val r = h / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(spectrum),
            topLeft = Offset(r, 0f),
            size = Size(size.width - r * 2f, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
        val x = r + (size.width - r * 2f) * (hue / 359f).coerceIn(0f, 1f)
        drawCircle(Color.Black.copy(alpha = 0.35f), r * 0.95f, Offset(x, r), style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, r * 0.85f, Offset(x, r), style = Stroke(2.dp.toPx()))
    }
}

// ================================================================ adjustments

@Composable
fun AdjustPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(AdjustmentKind.Hue) }
    var amount by remember { mutableFloatStateOf(0f) }

    fun pick(next: AdjustmentKind) {
        if (next != kind) {
            session.cancelAdjustment()
            amount = 0f
            kind = next
        }
    }

    PanelCard(Modifier.width(PANEL_WIDTH.dp), scrollable = true) {
        PanelHeader("Adjustments", onClose)
        Text(
            text = if (state.adjustAllLayers) "Drag a slider to preview live on every visible layer." else "Drag a slider to preview live on “${state.activeLayer?.name ?: "-"}”.",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            AdjustmentKind.entries.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowItems.forEach { item ->
                        val selected = item == kind
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) YumeColors.accentSoft else YumeColors.panelAlt
                                )
                                .clickable { pick(item) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(adjustIcon(item)),
                                    contentDescription = null,
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                        if (selected) YumeColors.accent else YumeColors.textDim
                                    ),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) YumeColors.accent else YumeColors.text
                                )
                            }
                        }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SegmentedRow(
            options = listOf("This layer", "All layers"),
            selectedIndex = if (state.adjustAllLayers) 1 else 0,
            onSelect = {
                session.chooseAdjustAllLayers(it == 1)
                amount = 0f
            }
        )
        Spacer(Modifier.height(10.dp))
        if (kind.isTone) {
            ToneEditor(kind, state) { lut ->
                amount = 1f
                session.previewTone(kind, lut)
            }
        } else {
        val range = adjustRange(kind)
        YumeSlider(
            value = amount,
            onChange = { value ->
                amount = value
                session.previewAdjustment(kind, value)
            },
            label = "${kind.label} amount",
            valueLabel = adjustValueLabel(kind, amount),
            min = range.first,
            max = range.second
        )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Apply") {
                session.commitAdjustment()
                amount = 0f
            }
            SmallButton("Cancel", enabled = abs(amount) > 0.0001f) {
                session.cancelAdjustment()
                amount = 0f
            }
            // How long Apply will take on this canvas, once it is worth saying.
            val eta = session.adjustEstimateSeconds(kind).also { state.strokeCount }
            if (eta >= 1.5f) {
                Text(
                    text = "Apply: about ${formatDuration(eta)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
        DividerLine()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Invert") { session.invertColors() }
            SmallButton("Grayscale") { session.grayscale() }
        }
        if (kind == AdjustmentKind.MotionBlur) {
            Spacer(Modifier.height(4.dp))
            YumeSlider(
                value = (state.motionAngle + 90f) / 180f,
                onChange = {
                    session.chooseMotionAngle(it * 180f - 90f)
                    // The live preview follows the direction too.
                    if (abs(amount) > 0.0001f) session.previewAdjustment(kind, amount)
                },
                label = "Direction",
                valueLabel = "${state.motionAngle.roundToInt()}°",
                min = 0f,
                max = 1f
            )
        }
        if (kind == AdjustmentKind.Noise || kind == AdjustmentKind.Sharpen ||
            kind == AdjustmentKind.MotionBlur
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The preview is approximate; Apply gives the exact result.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
        }
    }
}

private fun adjustRange(kind: AdjustmentKind): Pair<Float, Float> = when (kind) {
    AdjustmentKind.Hue -> -180f to 180f
    AdjustmentKind.Saturation -> -1f to 1f
    AdjustmentKind.Brightness -> -0.9f to 0.9f
    AdjustmentKind.Blur -> 0f to 1f
    AdjustmentKind.Noise -> 0f to 1f
    AdjustmentKind.Sharpen -> 0f to 1f
    AdjustmentKind.MotionBlur -> 0f to 1f
    else -> 0f to 1f
}

private fun adjustValueLabel(kind: AdjustmentKind, amount: Float): String = when (kind) {
    AdjustmentKind.Hue -> "${amount.roundToInt()}°"
    AdjustmentKind.Saturation -> "${(amount * 100).roundToInt()}%"
    AdjustmentKind.Brightness -> "${(amount * 100).roundToInt()}%"
    AdjustmentKind.Blur -> "${(amount * 24).roundToInt()} px"
    AdjustmentKind.Noise -> "${(amount * 100).roundToInt()}%"
    AdjustmentKind.Sharpen -> "${(amount * 200).roundToInt()}%"
    AdjustmentKind.MotionBlur -> "${(amount * 96).roundToInt()} px"
    else -> ""
}

// ============================================================ canvas size

/**
 * Crop or resize the canvas. The result is a new document (the original and
 * its undo history stay as they are), opened as soon as it is written.
 */
@Composable
fun CanvasSizeDialog(
    width: Int,
    height: Int,
    onDismiss: () -> Unit,
    initialScale: Boolean = true,
    onCreate: (Int, Int, Boolean, Boolean) -> Unit
) {
    var wText by remember { mutableStateOf(width.toString()) }
    var hText by remember { mutableStateOf(height.toString()) }
    var scale by remember { mutableStateOf(initialScale) }
    var center by remember { mutableStateOf(true) }
    var keepRatio by remember { mutableStateOf(true) }
    val w = wText.toIntOrNull()?.coerceIn(16, com.yume.app.engine.MemoryBudget.MAX_SPARSE_EDGE)
    val h = hText.toIntOrNull()?.coerceIn(16, com.yume.app.engine.MemoryBudget.MAX_SPARSE_EDGE)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        PanelCard(Modifier.width(360.dp)) {
            PanelHeader("Canvas size", onClose = onDismiss)
            SegmentedRow(
                options = listOf("Resize", "Crop / extend"),
                selectedIndex = if (scale) 0 else 1,
                onSelect = { scale = it == 0 }
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = wText,
                    onValueChange = { v ->
                        wText = v.filter(Char::isDigit).take(5)
                        val nw = wText.toIntOrNull()
                        if (keepRatio && scale && nw != null) {
                            hText = (nw.toLong() * height / width).coerceAtLeast(1).toString()
                        }
                    },
                    singleLine = true,
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
                androidx.compose.material3.OutlinedTextField(
                    value = hText,
                    onValueChange = { v ->
                        hText = v.filter(Char::isDigit).take(5)
                        val nh = hText.toIntOrNull()
                        if (keepRatio && scale && nh != null) {
                            wText = (nh.toLong() * width / height).coerceAtLeast(1).toString()
                        }
                    },
                    singleLine = true,
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
            }
            Spacer(Modifier.height(6.dp))
            if (scale) {
                ToggleRow(Glyph.Lock, "Keep proportions", keepRatio) { keepRatio = it }
            } else {
                SegmentedRow(
                    options = listOf("Centered", "Top left"),
                    selectedIndex = if (center) 0 else 1,
                    onSelect = { center = it == 0 }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Now $width × $height px. A copy is made and opened; this document stays as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Cancel") { onDismiss() }
                SmallButton("Create copy", enabled = w != null && h != null) {
                    if (w != null && h != null) onCreate(w, h, scale, center)
                }
            }
        }
    }
}

// =================================================================== history

@Composable
fun HistoryPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
        PanelHeader("History", onClose)
        Text(
            text = if (state.history.isEmpty()) {
                "No actions yet."
            } else {
                "Tap a step to jump to that state, or delete it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(state.history.asReversed(), key = { it.index }) { entry ->
                HistoryRow(
                    entry = entry,
                    onJump = { session.revertHistory(entry.index) },
                    onDelete = { session.deleteHistoryAt(entry.index) }
                )
            }
        }
        DividerLine()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Undo", enabled = state.canUndo) { session.undo() }
            SmallButton("Redo", enabled = state.canRedo) { session.redo() }
            SmallButton("Clear", enabled = state.history.isNotEmpty()) { session.clearHistory() }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onJump: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (entry.applied) Color.Transparent else YumeColors.ink.copy(alpha = 0x11 / 255f))
            .clickable(onClick = onJump)
            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.swatch != 0) {
            Box(
                Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color(entry.swatch))
                    .border(1.dp, YumeColors.ink.copy(alpha = 0x55 / 255f), CircleShape)
            )
            Spacer(Modifier.width(7.dp))
        }
        GlyphIcon(
            glyph = historyGlyph(entry.kind),
            tint = if (entry.applied) YumeColors.accent else YumeColors.textDim,
            size = 15.dp
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.applied) YumeColors.text else YumeColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.detail.isNotEmpty()) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        YumeIconButton(
            glyph = Glyph.Trash,
            onClick = onDelete,
            size = 28.dp,
            iconSize = 14.dp,
            tint = YumeColors.textDim,
            label = "Delete this step"
        )
    }
}

private fun historyGlyph(kind: EditKind): Glyph = when (kind) {
    EditKind.Stroke -> Glyph.Brush
    EditKind.Erase -> Glyph.Eraser
    EditKind.LayerAdd -> Glyph.Plus
    EditKind.LayerDelete -> Glyph.Trash
    EditKind.LayerMerge -> Glyph.Merge
    EditKind.LayerDuplicate -> Glyph.Copy
    EditKind.LayerClear -> Glyph.Clear
    EditKind.LayerOrder -> Glyph.ChevronUp
    EditKind.LayerFill -> Glyph.Droplet
    EditKind.LayerMask -> Glyph.Mask
    EditKind.Opacity -> Glyph.Sliders
    EditKind.Transform -> Glyph.Transform
    EditKind.Adjust -> Glyph.Wand
    EditKind.Import -> Glyph.Picture
}

@Composable
internal fun SmallButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) YumeColors.panelAlt else Color(0xFF1D1D22))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) YumeColors.text else YumeColors.textDim.copy(alpha = 0.5f)
        )
    }
}

// ======================================================================= fill

@Composable
fun FillPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
        PanelHeader("Paint bucket", onClose)
        YumeSlider(
            value = state.fillTolerance / TOLERANCE_MAX.toFloat(),
            onChange = { session.chooseFillTolerance((it * TOLERANCE_MAX).roundToInt()) },
            label = "Tolerance",
            valueLabel = "${tolerancePercent(state.fillTolerance)}%",
            min = 0.01f,
            max = 1f
        )
        Spacer(Modifier.height(4.dp))
        YumeSlider(
            value = state.fillGap / com.yume.app.engine.FILL_GAP_MAX.toFloat(),
            onChange = { session.chooseFillGap((it * com.yume.app.engine.FILL_GAP_MAX).roundToInt()) },
            label = "Close gaps",
            valueLabel = if (state.fillGap == 0) "Off" else "${state.fillGap} px",
            min = 0f,
            max = 1f
        )
        Spacer(Modifier.height(4.dp))
        YumeSlider(
            value = state.fillExpand / 4f,
            onChange = { session.chooseFillExpand((it * 4f).roundToInt()) },
            label = "Grow under edges",
            valueLabel = "${state.fillExpand} px",
            min = 0f,
            max = 1f
        )
        Spacer(Modifier.height(8.dp))
        SectionLabel("Sample")
        Spacer(Modifier.height(6.dp))
        SegmentedRow(
            options = listOf("All layers", "Current layer"),
            selectedIndex = if (state.fillLayerOnly) 1 else 0,
            onSelect = { session.chooseFillLayerOnly(it == 1) }
        )
        Spacer(Modifier.height(4.dp))
        val ref = state.referenceLayer
        if (ref != null && !state.fillLayerOnly) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Following the lines of the reference layer “$ref”.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.accent
            )
        }
        Spacer(Modifier.height(4.dp))
        ToggleRow(Glyph.Select, "Tap inside a selection fills it", state.fillSelection) {
            session.chooseFillSelection(it)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap to fill a region with the current colour; press and drag sideways " +
                "to set the threshold as you watch. You can also drag the colour chip onto " +
                "the canvas from any tool. Mark a layer as Reference (Layers panel) to fill " +
                "between its lines while painting on another layer.",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
    }
}

// =================================================================== gradient

@Composable
fun GradientPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    PanelCard(Modifier.width(PANEL_WIDTH.dp), scrollable = true) {
        PanelHeader("Gradient", onClose)
        SegmentedRow(
            options = com.yume.app.engine.Gradients.Kind.entries.map { it.label },
            selectedIndex = state.gradientKind.ordinal,
            onSelect = { session.chooseGradientKind(com.yume.app.engine.Gradients.Kind.entries[it]) }
        )
        Spacer(Modifier.height(12.dp))
        // The gradient as it will be drawn: start (the current colour) on the
        // left, the end colour on the right, over a checkerboard so a
        // transparent end shows.
        val start = Color(state.color)
        val end = Color(state.gradientSecond)
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .drawBehind {
                    val cell = 8.dp.toPx()
                    var y = 0f
                    var row = 0
                    while (y < size.height) {
                        var x = if (row % 2 == 0) 0f else cell
                        while (x < size.width) {
                            drawRect(Color(0xFF9A9AA2), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
                            x += cell * 2
                        }
                        y += cell
                        row++
                    }
                }
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(start, end)))
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("Start: current colour", style = MaterialTheme.typography.labelSmall, color = YumeColors.textDim)
            Spacer(Modifier.weight(1f))
            Text("End", style = MaterialTheme.typography.labelSmall, color = YumeColors.textDim)
        }
        Spacer(Modifier.height(10.dp))
        SectionLabel("End colour")
        Spacer(Modifier.height(8.dp))
        // A full picker for the end colour, right here.
        val endHsv = remember(state.gradientSecond) {
            FloatArray(3).also { android.graphics.Color.colorToHSV(state.gradientSecond or 0xFF000000.toInt(), it) }
        }
        var endHue by remember { mutableFloatStateOf(endHsv[0]) }
        var endSat by remember { mutableFloatStateOf(endHsv[1]) }
        var endVal by remember { mutableFloatStateOf(endHsv[2]) }
        val endAlpha = state.gradientSecond ushr 24
        // Changed from outside (Swap, a swatch, White / Black): follow it.
        // A colour this picker emitted itself matches and keeps its hue.
        val pickerRgb = android.graphics.Color.HSVToColor(floatArrayOf(endHue, endSat, endVal)) or 0xFF000000.toInt()
        if (endAlpha != 0 && pickerRgb != state.gradientSecond) {
            endHue = endHsv[0]
            endSat = endHsv[1]
            endVal = endHsv[2]
        }
        fun emitEnd() {
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(endHue, endSat, endVal))
            // Picking a colour makes the end opaque again.
            session.chooseGradientSecond(rgb or 0xFF000000.toInt())
        }
        SvSquare(endHue, endSat, endVal, 96.dp) { sv, vv ->
            endSat = sv
            endVal = vv
            emitEnd()
        }
        Spacer(Modifier.height(8.dp))
        HueBar(endHue) { h ->
            endHue = h
            emitEnd()
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(if (endAlpha == 0) "• Transparent" else "Transparent") {
                session.chooseGradientSecond(state.color and 0x00FFFFFF)
            }
            SmallButton("White") { session.chooseGradientSecond(0xFFFFFFFF.toInt()) }
            SmallButton("Black") { session.chooseGradientSecond(0xFF000000.toInt()) }
            SmallButton("Swap") { session.swapGradientColors() }
        }
        if (state.recentColors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SwatchGrid(state.recentColors) { picked -> session.chooseGradientSecond(picked) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Drag across the canvas: from the current colour to the end colour. " +
                "The gradient covers the layer, clipped to the selection.",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
    }
}

// ================================================================== liquify

@Composable
fun LiquifyPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
        PanelHeader("Liquify", onClose)
        val modes = com.yume.app.engine.LiquifyMode.entries
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            modes.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { m ->
                        val selected = m == state.liquifyMode
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) YumeColors.accentSoft else YumeColors.panelAlt)
                                .clickable { session.setLiquifyMode(m) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                m.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) YumeColors.accent else YumeColors.text
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        YumeSlider(
            value = state.liquifySize,
            onChange = { session.setLiquifySize(it) },
            label = "Size",
            valueLabel = "${state.liquifySize.roundToInt()} px",
            min = 16f,
            max = 800f
        )
        YumeSlider(
            value = state.liquifyStrength,
            onChange = { session.setLiquifyStrength(it) },
            label = "Strength",
            valueLabel = formatPercent(state.liquifyStrength)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Drag on the canvas to push pixels; twirl, pinch and expand also work held in place. " +
                "Restore brings back the layer as it was when you started liquifying it.",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
    }
}

// ================================================================== guides

@Composable
fun GuidesPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    PanelCard(Modifier.width(PANEL_WIDTH.dp), scrollable = true) {
        PanelHeader("Guides", onClose)
        SegmentedRow(
            options = com.yume.app.engine.GuideMode.entries.map { it.label },
            selectedIndex = state.guideMode.ordinal,
            onSelect = { session.setGuideMode(com.yume.app.engine.GuideMode.entries[it]) }
        )
        when (state.guideMode) {
            com.yume.app.engine.GuideMode.Grid -> {
                Spacer(Modifier.height(10.dp))
                YumeSlider(
                    value = state.gridSize,
                    onChange = { session.setGridSize(it) },
                    label = "Grid size",
                    valueLabel = "${state.gridSize.roundToInt()} px",
                    min = 16f,
                    max = 1024f
                )
            }
            com.yume.app.engine.GuideMode.Perspective -> {
                Spacer(Modifier.height(10.dp))
                SectionLabel("Vanishing points")
                Spacer(Modifier.height(6.dp))
                SegmentedRow(
                    options = listOf("1 point", "2 points", "3 points"),
                    selectedIndex = state.vanishingPoints - 1,
                    onSelect = { session.setVanishingPoints(it + 1) }
                )
            }
            else -> Unit
        }
        if (state.guideMode != com.yume.app.engine.GuideMode.Off) {
            Spacer(Modifier.height(6.dp))
            ToggleRow(Glyph.Guides, "Assisted drawing", state.guideAssist) { session.setGuideAssist(it) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (state.guideMode) {
                com.yume.app.engine.GuideMode.Off -> "Pick a guide to draw over the canvas."
                com.yume.app.engine.GuideMode.Grid -> "With assisted drawing, strokes run along the grid."
                com.yume.app.engine.GuideMode.Perspective ->
                    "Drag the vanishing points while this panel is open. Assisted strokes head to the nearest point, or run vertically."
                com.yume.app.engine.GuideMode.Ruler ->
                    "Drag the ruler's ends while this panel is open. Strokes that start on the ruler follow it; others run parallel."
            },
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
        DividerLine()
        SectionLabel("Symmetry")
        Spacer(Modifier.height(2.dp))
        ToggleRow(Glyph.Grid, "Vertical mirror", state.symmetryX) {
            session.setSymmetry(it, state.symmetryY)
        }
        ToggleRow(Glyph.Grid, "Horizontal mirror", state.symmetryY) {
            session.setSymmetry(state.symmetryX, it)
        }
        if (state.symmetryX || state.symmetryY) {
            ToggleRow(Glyph.Lock, "Lock mirror axes", state.symmetryLocked) {
                session.setSymmetryLocked(it)
            }
            Text(
                text = "Drag a dashed axis on the canvas to move it.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
        }
        Spacer(Modifier.height(8.dp))
        SectionLabel("Radial symmetry")
        Spacer(Modifier.height(6.dp))
        YumeSlider(
            value = state.symmetryRadial.toFloat(),
            onChange = { session.setRadialSymmetry(it.roundToInt().let { n -> if (n < 2) 0 else n }) },
            label = "Segments",
            valueLabel = if (state.symmetryRadial < 2) "Off" else state.symmetryRadial.toString(),
            min = 0f,
            max = 16f
        )
        if (state.symmetryRadial >= 2) {
            ToggleRow(Glyph.FlipH, "Mirror each segment", state.symmetryMirror) { session.setSymmetryMirror(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Centre on canvas") { session.centerSymmetry() }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Drag the centre point while this panel is open.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
        }
    }
}

// ================================================================== shapes

@Composable
fun ShapePanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
        PanelHeader("Shape", onClose)
        SegmentedRow(
            options = com.yume.app.engine.ShapeKind.entries.map { it.label },
            selectedIndex = state.shapeKind.ordinal,
            onSelect = { session.setShapeKind(com.yume.app.engine.ShapeKind.entries[it]) }
        )
        val kind = state.shapeKind
        if (kind == com.yume.app.engine.ShapeKind.Polygon || kind == com.yume.app.engine.ShapeKind.Star) {
            Spacer(Modifier.height(10.dp))
            YumeSlider(
                value = state.shapeSides.toFloat(),
                onChange = { session.setShapeSides(it.roundToInt()) },
                label = if (kind == com.yume.app.engine.ShapeKind.Star) "Points" else "Sides",
                valueLabel = state.shapeSides.toString(),
                min = 3f,
                max = 12f
            )
        }
        if (kind == com.yume.app.engine.ShapeKind.Rectangle || kind == com.yume.app.engine.ShapeKind.Ellipse) {
            Spacer(Modifier.height(6.dp))
            ToggleRow(Glyph.Shape, "Perfect square / circle", state.shapePerfect) { session.setShapePerfect(it) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (kind) {
                com.yume.app.engine.ShapeKind.Polygon, com.yume.app.engine.ShapeKind.Star ->
                    "Press at the centre and drag out: the distance sets the size, the direction turns it."
                else -> "Drag across the canvas. The shape is drawn with the current brush and colour."
            },
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
    }
}

// ================================================================== selection

@Composable
fun SelectionPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
        PanelHeader("Selection", onClose)
        SectionLabel("Shape")
        Spacer(Modifier.height(6.dp))
        SegmentedRow(
            options = SelectionShape.entries.map { it.label },
            selectedIndex = state.selectionShape.ordinal,
            onSelect = { session.chooseSelectionShape(SelectionShape.entries[it]) }
        )
        if (state.selectionShape == SelectionShape.Automatic) {
            Spacer(Modifier.height(4.dp))
            YumeSlider(
                value = state.wandTolerance / TOLERANCE_MAX.toFloat(),
                onChange = { session.chooseWandTolerance((it * TOLERANCE_MAX).roundToInt()) },
                label = "Tolerance",
                valueLabel = "${tolerancePercent(state.wandTolerance)}%",
                min = 0.01f,
                max = 1f
            )
        }
        Spacer(Modifier.height(12.dp))
        SectionLabel("Combine")
        Spacer(Modifier.height(6.dp))
        SegmentedRow(
            options = SelectionOp.entries.map { it.label },
            selectedIndex = state.selectionOp.ordinal,
            onSelect = { session.chooseSelectionOp(SelectionOp.entries[it]) }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (state.hasSelection) {
                "Painting is clipped to the selection. Drag inside it to move the outline."
            } else {
                "Drag on the canvas to select. “Auto” taps a colour region."
            },
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
        DividerLine()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Select all") { session.selectAll() }
            SmallButton("Invert", enabled = state.hasSelection) { session.invertSelection() }
            SmallButton("Clear", enabled = state.hasSelection) {
                session.clearSelection()
                session.chooseTool(Tool.Brush)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Copy") { session.copy() }
            SmallButton("Copy all") { session.copyMerged() }
            SmallButton("Cut") { session.cut() }
            SmallButton("Paste", enabled = state.canPaste) { session.pasteToPlace() }
        }
    }
}

// ==================================================================== actions

@Composable
fun ActionsPanel(
    state: EditorUiState,
    session: DrawingSession,
    onClose: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onReference: () -> Unit = {},
    onRemoveReference: (() -> Unit)? = null,
    onSave: () -> Unit,
    onCanvasCopy: (width: Int, height: Int, scale: Boolean, center: Boolean) -> Unit = { _, _, _, _ -> },
    onSettings: () -> Unit = {}
) {
    var sizeDialog by remember { mutableStateOf(false) }
    var sizeScale by remember { mutableStateOf(true) }
    if (sizeDialog) {
        CanvasSizeDialog(
            width = state.width,
            height = state.height,
            initialScale = sizeScale,
            onDismiss = { sizeDialog = false },
            onCreate = { w, h, scale, center ->
                sizeDialog = false
                onCanvasCopy(w, h, scale, center)
            }
        )
    }
    PanelCard(Modifier.width(PANEL_WIDTH.dp), scrollable = true) {
        PanelHeader("Actions", onClose)
        Text(
            text = "${state.width} × ${state.height} px   ·   ${(state.zoom * 100).roundToInt()}%   ·   ${state.strokeCount} strokes   ·   " +
                "${com.yume.app.engine.formatTimeSpent(session.engine.activeMs)} spent",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
        DividerLine()
        SectionLabel("Background")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColorSwatch(0xFFFFFFFF.toInt(), state.backgroundColor == 0xFFFFFFFF.toInt(), {
                session.setBackgroundColor(0xFFFFFFFF.toInt())
            })
            ColorSwatch(0xFF121214.toInt(), state.backgroundColor == 0xFF121214.toInt(), {
                session.setBackgroundColor(0xFF121214.toInt())
            })
            ColorSwatch(0xFFF2E9DA.toInt(), state.backgroundColor == 0xFFF2E9DA.toInt(), {
                session.setBackgroundColor(0xFFF2E9DA.toInt())
            })
            ColorSwatch(0x00000000, state.backgroundColor == 0x00000000, {
                session.setBackgroundColor(0x00000000)
            })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(if (state.backgroundColor == 0x00000000) "• Transparent" else "Transparent") {
                session.setBackgroundColor(0x00000000)
            }
            SmallButton("Use brush colour") { session.setBackgroundColor(state.color or 0xFF000000.toInt()) }
        }
        if (state.backgroundColor == 0x00000000) {
            Spacer(Modifier.height(4.dp))
            Text(
                "No background: PNG exports keep the transparency.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
        }
        DividerLine()
        ToggleRow(Glyph.Brush, "QuickShape hold", state.quickShape) {
            session.setQuickShape(it)
        }
        ToggleRow(Glyph.Droplet, "Finger painting", state.fingerPaint) {
            session.setFingerPainting(it)
        }
        ToggleRow(Glyph.Wand, "Eyedropper: current layer", state.eyedropperLayerOnly) {
            session.chooseEyedropperSource(it)
        }
        val soloName = state.layers.firstOrNull { it.id == state.soloLayerId }?.name
        MenuRow(
            glyph = Glyph.Eye,
            label = if (soloName != null) "Show all layers" else "Isolate layer",
            trailing = soloName
        ) { session.toggleSoloLayer() }
        DividerLine()
        MenuRow(Glyph.Picture, "Import image as layer") { onImport() }
        MenuRow(Glyph.Picture, if (onRemoveReference != null) "Replace reference image…" else "Reference image…") { onReference() }
        if (onRemoveReference != null) MenuRow(Glyph.Close, "Remove reference image") { onRemoveReference() }
        MenuRow(Glyph.Transform, "Resize image…") { sizeScale = true; sizeDialog = true }
        MenuRow(Glyph.Select, "Canvas size (crop / extend)…") { sizeScale = false; sizeDialog = true }
        MenuRow(Glyph.Droplet, "Fill layer with colour") { session.fillLayer(state.color) }
        MenuRow(Glyph.Share, "Export…") { onExport() }
        MenuRow(Glyph.Save, "Save now") { onSave() }
        DividerLine()
        MenuRow(Glyph.Fit, "Fit to screen") { session.fitToScreen() }
        MenuRow(Glyph.ZoomIn, "Actual size (100%)") { session.zoomActualSize() }
        ToggleRow(Glyph.FlipH, "Mirror view", session.viewMirrored) { session.toggleMirrorView() }
        MenuRow(Glyph.Rotate, "Reset rotation") { session.resetRotation() }
        MenuRow(Glyph.ZoomIn, "Zoom in") { session.zoomBy(1.25f) }
        MenuRow(Glyph.ZoomOut, "Zoom out") { session.zoomBy(1f / 1.25f) }
        DividerLine()
        MenuRow(Glyph.Sliders, "Settings…") { onSettings() }
        MenuRow(Glyph.History, "Gestures & tips") { GuideHost.open = true }
        DividerLine()
        PenGestureSettings()
        if (BuildConfig.DEBUG) {
            DividerLine()
            SectionLabel("Debug")
            ToggleRow(Glyph.Grid, "Sparse tile layers", state.sparseLayers) {
                session.setSparseLayers(it)
            }
            Text(
                text = "Applies to documents created or opened from now on.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
            )
        }
    }
}

/** "8 s", "1 min 20 s": a rough duration for estimates. */
internal fun formatDuration(seconds: Float): String {
    val s = seconds.roundToInt().coerceAtLeast(1)
    return if (s < 60) "$s s" else "${s / 60} min ${(s % 60) / 10 * 10} s"
}

/** The icon beside each adjustment's name. */
private fun adjustIcon(k: AdjustmentKind): androidx.compose.ui.graphics.vector.ImageVector {
    val i = androidx.compose.material.icons.Icons.Rounded
    return when (k) {
        AdjustmentKind.Hue -> i.ColorLens
        AdjustmentKind.Saturation -> i.InvertColors
        AdjustmentKind.Brightness -> i.LightMode
        AdjustmentKind.Blur -> i.BlurOn
        AdjustmentKind.Noise -> i.Grain
        AdjustmentKind.Sharpen -> i.CenterFocusStrong
        AdjustmentKind.MotionBlur -> i.BlurLinear
        AdjustmentKind.Curves -> i.ShowChart
        AdjustmentKind.Levels -> i.BarChart
        AdjustmentKind.ColorBalance -> i.Balance
        AdjustmentKind.GradientMap -> i.Gradient
        AdjustmentKind.Posterize -> i.Filter
        AdjustmentKind.Threshold -> i.Contrast
    }
}
