package com.yume.app.ui

import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.sp

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.interaction.collectIsPressedAsState

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yume.app.data.ProjectMeta
import com.yume.app.data.ProjectStore
import com.yume.app.engine.MemoryBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CanvasSize(val label: String, val width: Int, val height: Int)

private val CANVAS_SIZES = listOf(
    CanvasSize("Square · 2048 × 2048", 2048, 2048),
    CanvasSize("Square 4K · 4096 × 4096", 4096, 4096),
    CanvasSize("Portrait 3:4 · 2048 × 2732", 2048, 2732),
    CanvasSize("Landscape 4:3 · 2732 × 2048", 2732, 2048),
    CanvasSize("A4 300dpi · 2480 × 3508", 2480, 3508),
    CanvasSize("A3 300dpi · 3508 × 4961", 3508, 4961),
    CanvasSize("HD 16:9 · 1920 × 1080", 1920, 1080),
    CanvasSize("4K 16:9 · 3840 × 2160", 3840, 2160),
    CanvasSize("Square 6K · 6144 × 6144", 6144, 6144),
    CanvasSize("Square 8K · 8192 × 8192", 8192, 8192),
    CanvasSize("Tall 9:16 · 2160 × 3840", 2160, 3840)
)

@Composable
fun GalleryScreen(store: ProjectStore, onOpenProject: (String) -> Unit) {
    var projects by remember { mutableStateOf<List<ProjectMeta>>(emptyList()) }
    var thumbs by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var refresh by remember { mutableIntStateOf(0) }
    var showNewDialog by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<ProjectMeta?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectMeta?>(null) }
    val scope = rememberCoroutineScope()
    val pinContext = androidx.compose.ui.platform.LocalContext.current
    val pins = remember { com.yume.app.data.PinStore(pinContext) }
    var pinTick by remember { mutableIntStateOf(0) }
    // Pinned projects first; the rest keep the store's (most recent) order.
    val shownProjects = remember(projects, pinTick) { pins.order(projects) { it.id } }

    LaunchedEffect(refresh) {
        val list = withContext(Dispatchers.IO) { store.listProjects() }
        val loaded = withContext(Dispatchers.IO) {
            list.mapNotNull { meta ->
                store.loadThumb(meta, 512)?.let { meta.id to it.asImageBitmap() }
            }.toMap()
        }
        projects = list
        thumbs = loaded
    }

    Box(Modifier.fillMaxSize().background(YumeColors.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp)
        ) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    androidx.compose.foundation.Image(
                        bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(com.yume.app.R.drawable.yume_banner),
                        contentDescription = "Yume",
                        // Pixel letters: scale without smoothing so the cells stay crisp.
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.height(40.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = when (projects.size) {
                            0 -> "No artworks yet"
                            1 -> "1 artwork"
                            else -> "${projects.size} artworks"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = YumeColors.textDim
                    )
                }
                Spacer(Modifier.weight(1f))
                PressableRow(onClick = { showNewDialog = true }) {
                    GlyphIcon(Glyph.Plus, YumeColors.text, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "New canvas",
                        style = MaterialTheme.typography.labelLarge,
                        color = YumeColors.text
                    )
                }
            }
            Spacer(Modifier.height(26.dp))

            if (projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Start your first painting",
                            style = MaterialTheme.typography.titleMedium,
                            color = YumeColors.text
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Tap New canvas to pick a size.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = YumeColors.textDim
                        )
                    }
                }
            } else {
                // Staggered, so every thumbnail keeps its document's shape.
                androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
                    columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Adaptive(230.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalItemSpacing = 30.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(shownProjects.size, key = { shownProjects[it].id }) { idx ->
                        val meta = shownProjects[idx]
                        ProjectCard(
                            meta = meta,
                            thumb = thumbs[meta.id],
                            pinned = pins.isPinned(meta.id).also { pinTick },
                            // Pinning slides cards to their new places.
                            modifier = Modifier.animateItem(),
                            onOpen = { onOpenProject(meta.id) },
                            onMenu = { menuTarget = meta }
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewCanvasDialog(
            defaultName = "Untitled",
            onDismiss = { showNewDialog = false },
            onCreate = { name, width, height ->
                showNewDialog = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        store.createProject(name, width, height, 0xFFFFFFFF.toInt())
                    }
                    onOpenProject(created.id)
                }
            }
        )
    }

    menuTarget?.let { meta ->
        Dialog(onDismissRequest = { menuTarget = null }) {
            PanelCard(Modifier.width(300.dp)) {
                PanelHeader(meta.name, onClose = { menuTarget = null })
                val isPinned = pins.isPinned(meta.id)
                MenuRow(Glyph.Pin, if (isPinned) "Unpin" else "Pin to top") {
                    menuTarget = null
                    pins.setPinned(meta.id, !isPinned)
                    pinTick++
                }
                MenuRow(Glyph.Sliders, "Rename") {
                    menuTarget = null
                    renameTarget = meta
                }
                MenuRow(Glyph.Copy, "Duplicate") {
                    menuTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.duplicateProject(meta.id) }
                        refresh++
                    }
                }
                MenuRow(Glyph.Trash, "Delete", danger = true) {
                    menuTarget = null
                    pins.setPinned(meta.id, false)
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deleteProject(meta.id) }
                        refresh++
                    }
                }
            }
        }
    }

    renameTarget?.let { meta ->
        var name by remember(meta.id) { mutableStateOf(meta.name) }
        Dialog(onDismissRequest = { renameTarget = null }) {
            PanelCard(Modifier.width(320.dp)) {
                PanelHeader("Rename canvas", onClose = { renameTarget = null })
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(YumeColors.accent)
                            .clickable {
                                val newName = name.ifBlank { "Untitled" }
                                renameTarget = null
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.renameProject(meta.id, newName) }
                                    refresh++
                                }
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Save", color = Color(0xFF1A1206), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Shrinks a little on touch-down and springs back: immediate feedback. */
@Composable
private fun pressScale(source: androidx.compose.foundation.interaction.MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "press"
    )
    return scale
}

@Composable
private fun PressableRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scale = pressScale(source)
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(Color(0x1AFFFFFF))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    meta: ProjectMeta,
    thumb: ImageBitmap?,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    pinned: Boolean = false,
    modifier: Modifier = Modifier
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scale = pressScale(source)
    // The card is the document's own shape (clamped so extreme strips stay usable).
    val ratio = (meta.width.toFloat() / meta.height.coerceAtLeast(1)).coerceIn(0.45f, 2.4f)
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .graphicsLayer {
                    shadowElevation = 18.dp.toPx()
                    shape = RoundedCornerShape(12.dp)
                    clip = true
                }
                .background(Color(0xFF1C1C22))
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onClick = onOpen,
                    onLongClick = onMenu
                )
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = meta.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pinned) {
                GlyphIcon(Glyph.Pin, YumeColors.accent, size = 16.dp)
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = YumeColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatDate(meta.updatedAt)}  ·  ${meta.width} × ${meta.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim,
                    maxLines = 1
                )
            }
            YumeIconButton(Glyph.More, onMenu, size = 32.dp, iconSize = 15.dp, tint = YumeColors.textDim, label = "More")
        }
    }
}

@Composable
private fun NewCanvasDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onCreate: (String, Int, Int) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var widthText by remember { mutableStateOf("2048") }
    var heightText by remember { mutableStateOf("2048") }

    val width = widthText.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
    val height = heightText.toIntOrNull()?.coerceIn(64, 8192) ?: 2048
    val mbPerLayer = (width.toLong() * height * 4L) / (1024L * 1024L)
    val maxLayers = MemoryBudget.maxLayersFor(width, height)
    val fits = MemoryBudget.fitsDocument(width, height, 1)
    val sparseTiles = com.yume.app.engine.TileStoreConfig.useSparseLayers

    Dialog(onDismissRequest = onDismiss) {
        PanelCard(Modifier.width(380.dp)) {
            PanelHeader("New canvas", onClose = onDismiss)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$width × $height px  ·  ≈ $mbPerLayer MB per layer",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (!fits) {
                    if (sparseTiles) MemoryBudget.documentRefusal(width, height, 1)
                    else MemoryBudget.canvasRefusal(width, height)
                } else if (sparseTiles) {
                    "Sparse tiles: memory follows the painted area, layers are limited by storage"
                } else if (maxLayers <= 4) {
                    "Tight fit: about $maxLayers layer${if (maxLayers == 1) "" else "s"} at this size"
                } else {
                    "Up to $maxLayers layers at this size"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!fits) YumeColors.danger else YumeColors.textDim
            )
            Spacer(Modifier.height(12.dp))
            SectionLabel("Presets")
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .heightIn(max = 208.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                CANVAS_SIZES.forEach { size ->
                    val selected = size.width == width && size.height == height
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) YumeColors.accentSoft else Color.Transparent
                            )
                            .clickable {
                                widthText = size.width.toString()
                                heightText = size.height.toString()
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = size.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) YumeColors.accent else YumeColors.text
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (fits) YumeColors.accent else YumeColors.panelAlt)
                    .clickable(enabled = fits) {
                        onCreate(name.ifBlank { "Untitled" }, width, height)
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Create",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (fits) Color(0xFF1A1206) else YumeColors.textDim
                )
            }
        }
    }
}

/** "Today, 14:05", "Yesterday, 09:12", or "Nov 3" / "Nov 3, 2025" further back. */
private fun formatDate(millis: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val now = java.util.Calendar.getInstance()
    val days = ((now.timeInMillis - then.timeInMillis) / 86_400_000L).toInt()
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    return when {
        sameDay -> "Today, $time"
        days <= 1 -> "Yesterday, $time"
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) ->
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
    }
}
