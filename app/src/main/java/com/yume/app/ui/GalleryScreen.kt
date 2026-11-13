package com.yume.app.ui

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
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        text = "Yume",
                        style = MaterialTheme.typography.titleLarge,
                        color = YumeColors.text
                    )
                    Text(
                        text = if (projects.isEmpty()) {
                            "Your gallery is empty"
                        } else {
                            "${projects.size} canvas${if (projects.size == 1) "" else "es"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = YumeColors.textDim
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(13.dp))
                        .background(YumeColors.accent)
                        .clickable { showNewDialog = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlyphIcon(Glyph.Plus, Color(0xFF1A1206), size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "New canvas",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF1A1206)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))

            if (projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GlyphIcon(Glyph.Palette, YumeColors.textDim.copy(alpha = 0.5f), size = 64.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Nothing here yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = YumeColors.text
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Tap “New canvas” to start painting.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = YumeColors.textDim
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 190.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(projects, key = { it.id }) { meta ->
                        ProjectCard(
                            meta = meta,
                            thumb = thumbs[meta.id],
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    meta: ProjectMeta,
    thumb: ImageBitmap?,
    onOpen: () -> Unit,
    onMenu: () -> Unit
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.28f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF191920))
                .border(1.dp, YumeColors.hairline, RoundedCornerShape(16.dp))
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = meta.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                GlyphIcon(
                    Glyph.Picture,
                    YumeColors.textDim.copy(alpha = 0.4f),
                    size = 36.dp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                YumeIconButton(Glyph.More, onMenu, size = 30.dp, iconSize = 15.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = meta.name,
            style = MaterialTheme.typography.titleMedium,
            color = YumeColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${meta.width} × ${meta.height} · ${meta.layerCount} layer${if (meta.layerCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim,
            maxLines = 1
        )
        Text(
            text = formatDate(meta.updatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim.copy(alpha = 0.7f),
            maxLines = 1
        )
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

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(millis))
