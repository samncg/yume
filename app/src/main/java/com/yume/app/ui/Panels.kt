package com.yume.app.ui

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

enum class PanelId { Layers, Brush, Color, Adjust, Selection, Actions, History, Fill, Gradient }

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
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active.editingMask) YumeColors.accent else YumeColors.textDim
                )
                Spacer(Modifier.weight(1f))
                if (!active.isGroup) {
                    YumeIconButton(
                        glyph = Glyph.Lock,
                        onClick = { session.toggleAlphaLock(active.id) },
                        selected = active.alphaLock,
                        size = 30.dp,
                        iconSize = 16.dp
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
        val layerCount = state.layers.count { !it.isGroup }
        var dragId by remember { mutableStateOf(-1L) }
        var dragOffset by remember { mutableFloatStateOf(0f) }

        LazyColumn(
            Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            itemsIndexed(state.layers, key = { _, row -> row.id }) { index, row ->
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
                    val dragging = dragId == row.id
                    LayerRow(
                        layer = row,
                        active = row.id == state.activeLayerId,
                        onSelect = { session.selectLayer(row.id) },
                        onToggleVisible = { session.toggleLayerVisibility(row.id) },
                        onToggleLock = { session.toggleLayerLock(row.id) },
                        dragOffset = if (dragging) dragOffset else 0f,
                        onDragStart = { dragId = row.id; dragOffset = 0f },
                        onDrag = { dragOffset += it },
                        onDragEnd = {
                            val slots = (dragOffset / rowPx).roundToInt()
                            val id = dragId
                            dragId = -1L
                            dragOffset = 0f
                            if (slots != 0 && layerCount > 1) {
                                val targetPanel = (index + slots).coerceIn(0, state.layers.size)
                                val above = state.layers.take(targetPanel)
                                    .count { !it.isGroup && it.id != id }
                                session.moveLayerTo(
                                    id,
                                    (layerCount - 1 - above).coerceIn(0, layerCount - 1)
                                )
                            }
                        }
                    )
                }
            }
        }

        DividerLine()
        Row(verticalAlignment = Alignment.CenterVertically) {
            YumeIconButton(Glyph.Plus, { session.addLayer() }, size = 36.dp, iconSize = 18.dp)
            YumeIconButton(Glyph.Copy, { session.duplicateLayer() }, size = 36.dp, iconSize = 17.dp)
            YumeIconButton(Glyph.Merge, { session.mergeDown() }, size = 36.dp, iconSize = 17.dp)
            YumeIconButton(Glyph.Clear, { session.clearLayer() }, size = 36.dp, iconSize = 17.dp)
            YumeIconButton(
                Glyph.Trash,
                { session.deleteLayer() },
                enabled = state.layers.count { !it.isGroup } > 1,
                size = 36.dp,
                iconSize = 17.dp
            )
            Spacer(Modifier.weight(1f))
            YumeIconButton(
                Glyph.Eye,
                { session.toggleSoloLayer() },
                selected = soloActive,
                size = 36.dp,
                iconSize = 16.dp
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
                iconSize = 18.dp
            )
            val inGroup = active?.isGroup == true || (active?.depth ?: 0) > 0
            YumeIconButton(
                Glyph.Folder,
                { if (inGroup) session.ungroupActive() else session.groupActiveLayer() },
                selected = inGroup,
                enabled = active != null,
                size = 36.dp,
                iconSize = 18.dp
            )
            YumeIconButton(
                Glyph.More,
                { renameTarget = active; renameText = active?.name ?: "" },
                enabled = active != null,
                size = 36.dp,
                iconSize = 17.dp
            )
            Spacer(Modifier.weight(1f))
            val nodeId = active?.id ?: -1L
            val isLayer = active != null && !active.isGroup
            YumeIconButton(
                Glyph.ChevronUp,
                { session.nudgeLayer(nodeId, 1) },
                enabled = isLayer,
                size = 36.dp,
                iconSize = 16.dp
            )
            YumeIconButton(
                Glyph.ChevronDown,
                { session.nudgeLayer(nodeId, -1) },
                enabled = isLayer,
                size = 36.dp,
                iconSize = 16.dp
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
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffset }
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) YumeColors.accentSoft else Color.Transparent)
            .clickable(onClick = onSelect)
            // Long press picks the layer up; dragging then reorders the stack.
            .pointerInput(layer.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
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
            tint = if (layer.visible) YumeColors.text else YumeColors.textDim
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
            tint = if (layer.locked) YumeColors.accent else YumeColors.textDim
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
            .background(if (active) YumeColors.accentSoft else Color(0x14FFFFFF))
            .clickable(onClick = onSelect)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        YumeIconButton(
            glyph = if (row.expanded) Glyph.ChevronDown else Glyph.ChevronUp,
            onClick = onToggleExpanded,
            size = 26.dp,
            iconSize = 13.dp
        )
        YumeIconButton(
            glyph = if (row.visible) Glyph.Eye else Glyph.EyeOff,
            onClick = onToggleVisible,
            size = 28.dp,
            iconSize = 15.dp,
            tint = if (row.visible) YumeColors.text else YumeColors.textDim
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
            tint = if (row.locked) YumeColors.accent else YumeColors.textDim
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
    PanelCard(Modifier.width(PANEL_WIDTH.dp), scrollable = true) {
        PanelHeader("Brush Studio", onClose)
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
        // A plain column, not a lazy grid: a nested lazy list swallows vertical
        // drags and stops the whole panel from scrolling.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.presets.chunked(4).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunk.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            BrushChip(item, item.id == state.activePresetId) {
                                session.selectPreset(item.id)
                            }
                        }
                    }
                    repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        DividerLine()
        if (preset != null) {
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
                max = 300f
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
                if (preset.category == BrushCategory.Smudge) {
                    YumeSlider(
                        value = preset.smudgeAmount,
                        onChange = { v -> session.updatePreset { it.copy(smudgeAmount = v) } },
                        label = "Smudge strength",
                        valueLabel = formatPercent(preset.smudgeAmount)
                    )
                }
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

@Composable
private fun BrushChip(preset: BrushPreset, selected: Boolean, onClick: () -> Unit) {
    val preview = remember(preset.dabKey, preset.size.roundToInt()) {
        BrushDab.cachedPreview(preset, 72)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) YumeColors.accentSoft else Color(0xFF23232A))
            .clickable(onClick = onClick)
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

    fun emit() {
        val color = android.graphics.Color.HSVToColor(
            floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        )
        session.chooseColor(color)
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
        session.chooseColor(color)
    }

    val current = android.graphics.Color.HSVToColor(
        floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    )

    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
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

            ColorMode.Square -> {
                SvSquare(hue, sat, value, 150.dp) { s, v ->
                    sat = s
                    value = v
                    emit()
                }
                Spacer(Modifier.height(10.dp))
                HueBar(hue) { h ->
                    hue = h
                    emit()
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

            ColorMode.Hsl -> {
                val hsl = remember(hue, sat, value) { rgbToHsl(current) }
                YumeSlider(
                    value = hsl[0],
                    onChange = { h ->
                        hue = h
                        adopt(hslToRgb(h, hsl[1], hsl[2]), true)
                    },
                    label = "Hue",
                    valueLabel = "${hsl[0].roundToInt()}°",
                    min = 0f,
                    max = 359f
                )
                YumeSlider(
                    value = hsl[1],
                    onChange = { s ->
                        hue = hsl[0]
                        adopt(hslToRgb(hsl[0], s.coerceIn(0f, 1f), hsl[2]), true)
                    },
                    label = "Saturation",
                    valueLabel = "${(hsl[1] * 100).roundToInt()}%"
                )
                YumeSlider(
                    value = hsl[2],
                    onChange = { l ->
                        hue = hsl[0]
                        adopt(hslToRgb(hsl[0], hsl[1], l.coerceIn(0f, 1f)), true)
                    },
                    label = "Lightness",
                    valueLabel = "${(hsl[2] * 100).roundToInt()}%"
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
        SectionLabel("Palette")
        Spacer(Modifier.height(8.dp))
        SwatchGrid(state.palette) { picked -> adopt(picked) }
        if (state.recentColors.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Recent")
            Spacer(Modifier.height(8.dp))
            SwatchGrid(state.recentColors) { picked -> adopt(picked) }
        }
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

@Composable
private fun SvSquare(
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
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
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

    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
        PanelHeader("Adjustments", onClose)
        Text(
            text = "Drag a slider to preview live on “${state.activeLayer?.name ?: "-"}”.",
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
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) YumeColors.accent else YumeColors.text
                            )
                        }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
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
                onChange = { session.chooseMotionAngle(it * 180f - 90f) },
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
                text = "${kind.label} is applied when you press Apply.",
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
}

private fun adjustValueLabel(kind: AdjustmentKind, amount: Float): String = when (kind) {
    AdjustmentKind.Hue -> "${amount.roundToInt()}°"
    AdjustmentKind.Saturation -> "${(amount * 100).roundToInt()}%"
    AdjustmentKind.Brightness -> "${(amount * 100).roundToInt()}%"
    AdjustmentKind.Blur -> "${(amount * 24).roundToInt()} px"
    AdjustmentKind.Noise -> "${(amount * 100).roundToInt()}%"
    AdjustmentKind.Sharpen -> "${(amount * 200).roundToInt()}%"
    AdjustmentKind.MotionBlur -> "${(amount * 96).roundToInt()} px"
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
    onCreate: (Int, Int, Boolean, Boolean) -> Unit
) {
    var wText by remember { mutableStateOf(width.toString()) }
    var hText by remember { mutableStateOf(height.toString()) }
    var scale by remember { mutableStateOf(true) }
    var center by remember { mutableStateOf(true) }
    var keepRatio by remember { mutableStateOf(true) }
    val w = wText.toIntOrNull()?.coerceIn(16, 8192)
    val h = hText.toIntOrNull()?.coerceIn(16, 8192)
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
                        wText = v.filter(Char::isDigit).take(4)
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
                        hText = v.filter(Char::isDigit).take(4)
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
            .background(if (entry.applied) Color.Transparent else Color(0x11FFFFFF))
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
                    .border(1.dp, Color(0x55FFFFFF), CircleShape)
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
            tint = YumeColors.textDim
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
private fun SmallButton(
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
            value = state.fillTolerance / 100f,
            onChange = { session.chooseFillTolerance((it * 100f).roundToInt()) },
            label = "Tolerance",
            valueLabel = "${state.fillTolerance}%",
            min = 0.01f,
            max = 1f
        )
        Spacer(Modifier.height(4.dp))
        YumeSlider(
            value = state.fillExpand / 4f,
            onChange = { session.chooseFillExpand((it * 4f).roundToInt()) },
            label = "Close edges",
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
        ToggleRow(Glyph.Select, "Tap inside a selection fills it", state.fillSelection) {
            session.chooseFillSelection(it)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap to fill a region with the current colour. You can also drag the " +
                "colour chip onto the canvas from any tool.",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
    }
}

// =================================================================== gradient

@Composable
fun GradientPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    PanelCard(Modifier.width(PANEL_WIDTH.dp)) {
        PanelHeader("Gradient", onClose)
        SegmentedRow(
            options = com.yume.app.engine.Gradients.Kind.entries.map { it.label },
            selectedIndex = state.gradientKind.ordinal,
            onSelect = { session.chooseGradientKind(com.yume.app.engine.Gradients.Kind.entries[it]) }
        )
        Spacer(Modifier.height(10.dp))
        SectionLabel("End colour")
        Spacer(Modifier.height(6.dp))
        val transparent = (state.gradientSecond ushr 24) == 0
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(if (transparent) "• Transparent" else "Transparent") {
                session.chooseGradientSecond(state.color and 0x00FFFFFF)
            }
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
                value = state.wandTolerance / 100f,
                onChange = { session.chooseWandTolerance((it * 100f).roundToInt()) },
                label = "Tolerance",
                valueLabel = "${state.wandTolerance}%",
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
    onSave: () -> Unit,
    onCanvasCopy: (width: Int, height: Int, scale: Boolean, center: Boolean) -> Unit = { _, _, _, _ -> }
) {
    var sizeDialog by remember { mutableStateOf(false) }
    if (sizeDialog) {
        CanvasSizeDialog(
            width = state.width,
            height = state.height,
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
            text = "${state.width} × ${state.height} px   ·   ${(state.zoom * 100).roundToInt()}%",
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
        DividerLine()
        ToggleRow(Glyph.Grid, "Vertical symmetry", state.symmetryX) {
            session.setSymmetry(it, state.symmetryY)
        }
        ToggleRow(Glyph.Grid, "Horizontal symmetry", state.symmetryY) {
            session.setSymmetry(state.symmetryX, it)
        }
        if (state.symmetryX || state.symmetryY) {
            ToggleRow(Glyph.Lock, "Lock symmetry guides", state.symmetryLocked) {
                session.setSymmetryLocked(it)
            }
            MenuRow(Glyph.Fit, "Centre guides") { session.centerSymmetry() }
            Text(
                text = "Drag a dashed guide on the canvas to move its axis.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
            )
        }
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
        MenuRow(Glyph.Select, "Crop or resize canvas…") { sizeDialog = true }
        MenuRow(Glyph.Droplet, "Fill layer with colour") { session.fillLayer(state.color) }
        MenuRow(Glyph.Share, "Export PNG to gallery") { onExport() }
        MenuRow(Glyph.Save, "Save now") { onSave() }
        DividerLine()
        MenuRow(Glyph.Fit, "Fit to screen") { session.fitToScreen() }
        MenuRow(Glyph.Rotate, "Reset rotation") { session.resetRotation() }
        MenuRow(Glyph.ZoomIn, "Zoom in") { session.zoomBy(1.25f) }
        MenuRow(Glyph.ZoomOut, "Zoom out") { session.zoomBy(1f / 1.25f) }
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
