package com.yume.app.ui

import androidx.compose.runtime.collectAsState

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
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

/** A canvas shape: [w]:[h] (A-series paper is 1 : sqrt 2). */
private data class CanvasRatio(val label: String, val w: Float, val h: Float)

private val CANVAS_RATIOS = listOf(
    CanvasRatio("1:1", 1f, 1f),
    CanvasRatio("3:4", 3f, 4f),
    CanvasRatio("4:3", 4f, 3f),
    CanvasRatio("2:3", 2f, 3f),
    CanvasRatio("3:2", 3f, 2f),
    CanvasRatio("9:16", 9f, 16f),
    CanvasRatio("16:9", 16f, 9f),
    CanvasRatio("A paper", 1f, 1.41421356f),
    CanvasRatio("A wide", 1.41421356f, 1f),
    CanvasRatio("4:5", 4f, 5f)
)

/** Long-edge resolutions offered for every ratio. */
private val CANVAS_LONG_EDGES = listOf(2048, 4096, 6144, 8192, 12288, 16384)
private val CANVAS_EDGE_LABELS = listOf("2K", "4K", "6K", "8K", "12K", "16K")

/** Canvases this large (long edge) are experimental: shown in [EXPERIMENTAL_COLOR] with a warning. */
private const val EXPERIMENTAL_EDGE = 12288
private val EXPERIMENTAL_COLOR = Color(0xFFE8833A)

private const val CANVAS_MIN_EDGE = 64
private const val CANVAS_MAX_EDGE = MemoryBudget.MAX_SPARSE_EDGE

/** Width and height of [ratio] with its long edge at [longEdge]. */
private fun sizeFor(ratio: CanvasRatio, longEdge: Int): Pair<Int, Int> =
    if (ratio.w >= ratio.h) {
        longEdge to (longEdge * ratio.h / ratio.w).toInt().coerceAtLeast(CANVAS_MIN_EDGE)
    } else {
        (longEdge * ratio.w / ratio.h).toInt().coerceAtLeast(CANVAS_MIN_EDGE) to longEdge
    }

@Composable
fun GalleryScreen(store: ProjectStore, onOpenProject: (String) -> Unit) {
    var projects by remember { mutableStateOf<List<ProjectMeta>>(emptyList()) }
    var thumbs by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var refresh by remember { mutableIntStateOf(0) }
    var showNewDialog by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<ProjectMeta?>(null) }
    var renameTarget by remember { mutableStateOf<ProjectMeta?>(null) }
    val scope = rememberCoroutineScope()
    var galleryNote by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val savingIds by store.saving.collectAsState()
    // A save that finishes after we came back (8K) refreshes its card.
    LaunchedEffect(store) { store.saved.collect { refresh++ } }
    val pinContext = androidx.compose.ui.platform.LocalContext.current
    val pins = remember { com.yume.app.data.PinStore(pinContext) }
    var pinTick by remember { mutableIntStateOf(0) }
    // Pinned projects first; the rest keep the store's (most recent) order.
    val shownProjects = remember(projects, pinTick) { pins.order(projects) { it.id } }
    // Folders: one level, kept beside the pins. [openFolder] is the folder
    // being shown (null: the top of the gallery).
    val folders = remember { com.yume.app.data.FolderStore(pinContext) }
    var folderTick by remember { mutableIntStateOf(0) }
    var openFolder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val folderList = remember(folderTick) { folders.folders() }
    val currentFolder = remember(openFolder, folderTick) { folders.folder(openFolder) }
    LaunchedEffect(openFolder, currentFolder) { if (openFolder != null && currentFolder == null) openFolder = null }
    val byFolder = remember(shownProjects, folderTick) { shownProjects.groupBy { folders.folderOf(it.id) } }
    val inView = byFolder[openFolder].orEmpty()
    var folderMenu by remember { mutableStateOf<com.yume.app.data.GalleryFolder?>(null) }
    var folderRename by remember { mutableStateOf<com.yume.app.data.GalleryFolder?>(null) }
    var newFolderFor by remember { mutableStateOf<ProjectMeta?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<ProjectMeta?>(null) }
    androidx.activity.compose.BackHandler(enabled = openFolder != null) { openFolder = null }
    /** A new artwork made while a folder is open goes into it. */
    fun fileNew(id: String?) {
        val f = openFolder ?: return
        if (id != null) {
            folders.move(id, f)
            folderTick++
        }
    }
    var backupTarget by remember { mutableStateOf<ProjectMeta?>(null) }
    val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val meta = backupTarget
        if (uri != null && meta != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { store.openOutput(uri)?.use { store.backupProject(meta.id, it) } ?: false }.getOrDefault(false)
            }
            galleryNote = if (ok) "Backed up “${meta.name}”" else "Backup failed"
        }
    }
    LaunchedEffect(galleryNote) {
        if (galleryNote != null) {
            kotlinx.coroutines.delay(3500)
            galleryNote = null
        }
    }
    val restoreContext = androidx.compose.ui.platform.LocalContext.current
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            galleryNote = "Importing…"
            // A Photoshop document becomes a new artwork; anything else is
            // read as a Yume backup.
            val note = withContext(Dispatchers.IO) {
                val tmp = java.io.File(store.cacheRoot(), "import-${System.nanoTime()}")
                try {
                    val copied = runCatching {
                        store.openInput(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } != null
                    }.getOrDefault(false)
                    if (!copied) return@withContext "That file could not be read"
                    if (com.yume.app.data.Psd.isPsd(tmp)) {
                        val title = displayName(restoreContext, uri)
                            ?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported PSD"
                        try {
                            val id = com.yume.app.data.Psd.importAsProject(store, tmp, title)
                            withContext(Dispatchers.Main) { fileNew(id) }
                            "Opened “$title”"
                        } catch (e: com.yume.app.data.PsdException) {
                            e.message ?: "That PSD could not be opened"
                        }
                    } else {
                        val id = runCatching { tmp.inputStream().use { store.restoreProject(it) } }.getOrNull()
                        if (id != null) withContext(Dispatchers.Main) { fileNew(id) }
                        if (id != null) "Project restored" else "That file is not a Yume backup or a PSD"
                    }
                } finally {
                    tmp.delete()
                }
            }
            galleryNote = note
            refresh++
        }
    }

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

    // The header floats over the grid: cards scroll up under it, where they
    // blur more and more and fade into the background.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var headerPx by remember { mutableIntStateOf(0) }
    val headerDp = with(density) { headerPx.toDp() }
    // Phones: two columns, narrower margins, and the header on two rows.
    val compact = isCompact()
    val edge = if (compact) 16.dp else 32.dp
    Box(Modifier.fillMaxSize().background(YumeColors.bg)) {
        if (inView.isEmpty() && (openFolder != null || folderList.isEmpty())) {
            Box(
                Modifier.fillMaxSize().padding(top = headerDp).navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (openFolder != null) "This folder is empty" else "Start your first painting",
                        style = MaterialTheme.typography.titleMedium,
                        color = YumeColors.text
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            openFolder != null -> "Move artworks here from their ⋯ menu, or make a new canvas."
                            compact -> "Tap New to pick a size."
                            else -> "Tap New canvas to pick a size."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = YumeColors.textDim
                    )
                }
            }
        } else {
            // Staggered, so every thumbnail keeps its document's shape.
            androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
                columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Adaptive(if (compact) 140.dp else 230.dp),
                contentPadding = PaddingValues(start = edge, end = edge, top = headerDp + 20.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 28.dp),
                verticalItemSpacing = if (compact) 20.dp else 30.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .then(GalleryEdge.blurModifier(headerPx.toFloat()))
            ) {
                // Folders first, at the top of the gallery only.
                val shownFolders = if (openFolder == null) folderList else emptyList()
                items(shownFolders.size, key = { "folder:" + shownFolders[it].id }) { idx ->
                    val folder = shownFolders[idx]
                    val inside = byFolder[folder.id].orEmpty()
                    FolderCard(
                        folder = folder,
                        count = inside.size,
                        thumbs = inside.take(4).mapNotNull { thumbs[it.id] },
                        modifier = Modifier.animateItem(),
                        onOpen = { openFolder = folder.id },
                        onMenu = { folderMenu = folder }
                    )
                }
                items(inView.size, key = { inView[it].id }) { idx ->
                    val meta = inView[idx]
                    ProjectCard(
                        meta = meta,
                        thumb = thumbs[meta.id],
                        saving = meta.id in savingIds,
                        pinned = pins.isPinned(meta.id).also { pinTick },
                        // Pinning slides cards to their new places.
                        modifier = Modifier.animateItem(),
                        onOpen = { onOpenProject(meta.id) },
                        onMenu = { menuTarget = meta }
                    )
                }
            }
        }
        // A frosted bar: the cards passing under the header show through,
        // blurred (Android 13+) and veiled a little, with a clean edge.
        if (headerPx > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(headerDp)
                    .background(YumeColors.bg.copy(alpha = if (android.os.Build.VERSION.SDK_INT >= 33) 0.72f else 0.94f))
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = headerDp)
                    .height(1.dp)
                    .background(YumeColors.hairline)
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerPx = it.height }
                .statusBarsPadding()
                .padding(horizontal = edge)
        ) {
            Spacer(Modifier.height(if (compact) 16.dp else 28.dp))
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (currentFolder != null) {
                            FolderTitle(currentFolder.name, compact = true) { openFolder = null }
                        } else {
                            androidx.compose.foundation.Image(
                                bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(com.yume.app.R.drawable.yume_banner),
                                contentDescription = "Yume",
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier = Modifier.height(30.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = galleryNote ?: countText(if (currentFolder != null) inView.size else projects.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = YumeColors.textDim
                        )
                    }
                    PressableRow(onClick = { showNewDialog = true }) {
                        GlyphIcon(Glyph.Plus, YumeColors.text, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("New", style = MaterialTheme.typography.labelLarge, color = YumeColors.text)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PressableRow(onClick = { GuideHost.open = true }) {
                        Text("Guide", style = MaterialTheme.typography.labelLarge, color = YumeColors.textDim)
                    }
                    PressableRow(onClick = { SettingsHost.open = true }) {
                        Text("Settings", style = MaterialTheme.typography.labelLarge, color = YumeColors.textDim)
                    }
                    PressableRow(onClick = { restoreLauncher.launch(IMPORT_TYPES) }) {
                        Text("Import…", style = MaterialTheme.typography.labelLarge, color = YumeColors.textDim)
                    }
                    if (openFolder == null) PressableRow(onClick = { newFolder = true }) {
                        Text("Folder", style = MaterialTheme.typography.labelLarge, color = YumeColors.textDim)
                    }
                }
            } else
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    if (currentFolder != null) {
                        FolderTitle(currentFolder.name, compact = false) { openFolder = null }
                    } else {
                        androidx.compose.foundation.Image(
                            bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(com.yume.app.R.drawable.yume_banner),
                            contentDescription = "Yume",
                            // Pixel letters: scale without smoothing so the cells stay crisp.
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier.height(40.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = galleryNote ?: countText(if (currentFolder != null) inView.size else projects.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = YumeColors.textDim
                    )
                }
                Spacer(Modifier.weight(1f))
                PressableRow(onClick = { GuideHost.open = true }) {
                    Text(
                        text = "Guide",
                        style = MaterialTheme.typography.labelLarge,
                        color = YumeColors.textDim
                    )
                }
                Spacer(Modifier.width(10.dp))
                PressableRow(onClick = { SettingsHost.open = true }) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.labelLarge,
                        color = YumeColors.textDim
                    )
                }
                Spacer(Modifier.width(10.dp))
                PressableRow(onClick = { restoreLauncher.launch(IMPORT_TYPES) }) {
                    Text(
                        text = "Import…",
                        style = MaterialTheme.typography.labelLarge,
                        color = YumeColors.textDim
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (openFolder == null) {
                    PressableRow(onClick = { newFolder = true }) {
                        GlyphIcon(Glyph.Folder, YumeColors.textDim, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "New folder",
                            style = MaterialTheme.typography.labelLarge,
                            color = YumeColors.textDim
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
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
            Spacer(Modifier.height(6.dp))
        }
    }

    if (creating) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            PanelCard(Modifier.width(260.dp)) {
                Text("Creating canvas…", style = MaterialTheme.typography.titleMedium, color = YumeColors.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Large canvases take a few seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim
                )
            }
        }
    }

    if (showNewDialog) {
        NewCanvasDialog(
            defaultName = "Untitled",
            onDismiss = { showNewDialog = false },
            onCreate = { name, width, height ->
                showNewDialog = false
                creating = true
                scope.launch {
                    val created = runCatching {
                        withContext(Dispatchers.IO) { store.createProject(name, width, height, 0xFFFFFFFF.toInt()) }
                    }.getOrNull()
                    creating = false
                    if (created != null) {
                        fileNew(created.id)
                        onOpenProject(created.id)
                    } else galleryNote = "Could not create that canvas"
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
                MenuRow(Glyph.Save, "Back up…") {
                    menuTarget = null
                    backupTarget = meta
                    backupLauncher.launch(meta.name.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "Yume" } + ".yume.zip")
                }
                MenuRow(Glyph.Folder, "Move to folder…") {
                    menuTarget = null
                    moveTarget = meta
                }
                MenuRow(Glyph.Copy, "Duplicate") {
                    menuTarget = null
                    scope.launch {
                        val copy = withContext(Dispatchers.IO) { store.duplicateProject(meta.id) }
                        // The copy sits beside the original.
                        folders.folderOf(meta.id)?.let { f -> if (copy != null) folders.move(copy, f) }
                        folderTick++
                        refresh++
                    }
                }
                MenuRow(Glyph.Trash, "Delete", danger = true) {
                    menuTarget = null
                    pins.setPinned(meta.id, false)
                    folders.move(meta.id, null)
                    folderTick++
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deleteProject(meta.id) }
                        refresh++
                    }
                }
            }
        }
    }

    moveTarget?.let { meta ->
        val current = folders.folderOf(meta.id)
        Dialog(onDismissRequest = { moveTarget = null }) {
            PanelCard(Modifier.width(320.dp)) {
                PanelHeader("Move “${meta.name}”", onClose = { moveTarget = null })
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    MenuRow(Glyph.Gallery, "No folder", trailing = if (current == null) "Here" else null) {
                        moveTarget = null
                        folders.move(meta.id, null)
                        folderTick++
                    }
                    for (f in folderList) {
                        MenuRow(Glyph.Folder, f.name, trailing = if (current == f.id) "Here" else null) {
                            moveTarget = null
                            folders.move(meta.id, f.id)
                            folderTick++
                        }
                    }
                }
                MenuRow(Glyph.Plus, "New folder…") {
                    moveTarget = null
                    newFolderFor = meta
                    newFolder = true
                }
            }
        }
    }

    if (newFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            action = "Create",
            onDismiss = { newFolder = false; newFolderFor = null },
            onDone = { name ->
                val f = folders.create(name)
                newFolderFor?.let { folders.move(it.id, f.id) }
                newFolder = false
                newFolderFor = null
                folderTick++
            }
        )
    }

    folderMenu?.let { folder ->
        Dialog(onDismissRequest = { folderMenu = null }) {
            PanelCard(Modifier.width(300.dp)) {
                PanelHeader(folder.name, onClose = { folderMenu = null })
                MenuRow(Glyph.Sliders, "Rename") {
                    folderMenu = null
                    folderRename = folder
                }
                MenuRow(Glyph.Trash, "Delete folder", danger = true) {
                    folderMenu = null
                    folders.delete(folder.id)
                    folderTick++
                    galleryNote = "Folder deleted; its artworks are back in the gallery"
                }
                Text(
                    "Deleting a folder keeps its artworks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                )
            }
        }
    }

    folderRename?.let { folder ->
        NameDialog(
            title = "Rename folder",
            initial = folder.name,
            action = "Save",
            onDismiss = { folderRename = null },
            onDone = { name ->
                folders.rename(folder.id, name)
                folderRename = null
                folderTick++
            }
        )
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

private fun countText(n: Int) = when (n) {
    0 -> "No artworks yet"
    1 -> "1 artwork"
    else -> "$n artworks"
}

/** Inside a folder: a way back to the whole gallery, then the folder's name. */
@Composable
private fun FolderTitle(name: String, compact: Boolean, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PressableRow(onClick = onBack) {
            Text("‹  Gallery", style = MaterialTheme.typography.labelLarge, color = YumeColors.textDim)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = YumeColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A name field with one button (new folder, rename folder). */
@Composable
private fun NameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        PanelCard(Modifier.width(320.dp)) {
            PanelHeader(title, onClose = onDismiss)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Folder", color = YumeColors.textDim) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(YumeColors.accent)
                    .clickable { onDone(name) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(action, color = Color(0xFF1A1206), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A folder in the gallery: up to four of its artworks, its name and count. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: com.yume.app.data.GalleryFolder,
    count: Int,
    thumbs: List<ImageBitmap>,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val scale = pressScale(source)
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    shadowElevation = 18.dp.toPx()
                    shape = RoundedCornerShape(12.dp)
                    clip = true
                }
                .background(YumeColors.panelAlt)
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onClick = onOpen,
                    onLongClick = onMenu
                )
                .padding(8.dp)
        ) {
            if (thumbs.isEmpty()) {
                GlyphIcon(Glyph.Folder, YumeColors.textDim, Modifier.align(Alignment.Center), size = 48.dp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in 0..1) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in 0..1) {
                                val t = thumbs.getOrNull(r * 2 + c)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (t != null) Color.White else Color.White.copy(alpha = 0.04f))
                                ) {
                                    if (t != null) {
                                        Image(
                                            bitmap = t,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Folder, YumeColors.accent, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = YumeColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (count == 1) "1 artwork" else "$count artworks",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim,
                    maxLines = 1
                )
            }
            YumeIconButton(Glyph.More, onMenu, size = 32.dp, iconSize = 15.dp, tint = YumeColors.textDim, label = "More")
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
            .background(YumeColors.ink.copy(alpha = 0x1A / 255f))
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
    modifier: Modifier = Modifier,
    saving: Boolean = false
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
                .background(YumeColors.panelAlt)
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
            if (saving) {
                Text(
                    text = "Saving…",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Time spent and strokes on a line of their own, so neither is cut off.
                val extra = listOfNotNull(
                    if (meta.timeMs >= 60_000L) "${com.yume.app.engine.formatTimeSpent(meta.timeMs)} spent" else null,
                    if (meta.strokeCount > 0) "${meta.strokeCount} strokes" else null
                )
                if (extra.isNotEmpty()) {
                    Text(
                        text = extra.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = YumeColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
    var ratioIndex by remember { mutableIntStateOf(0) }
    var edgeIndex by remember { mutableIntStateOf(0) }
    var widthText by remember { mutableStateOf("2048") }
    var heightText by remember { mutableStateOf("2048") }
    fun applyPreset(r: Int, e: Int) {
        ratioIndex = r
        edgeIndex = e
        if (r >= 0 && e >= 0) {
            val (w, h) = sizeFor(CANVAS_RATIOS[r], CANVAS_LONG_EDGES[e])
            widthText = w.toString()
            heightText = h.toString()
        }
    }

    val width = widthText.toIntOrNull()?.coerceIn(CANVAS_MIN_EDGE, CANVAS_MAX_EDGE) ?: 2048
    val height = heightText.toIntOrNull()?.coerceIn(CANVAS_MIN_EDGE, CANVAS_MAX_EDGE) ?: 2048
    val mbPerLayer = (width.toLong() * height * 4L) / (1024L * 1024L)
    val maxLayers = MemoryBudget.maxLayersFor(width, height)
    val fits = MemoryBudget.fitsDocument(width, height, 1)
    val sparseTiles = com.yume.app.engine.TileStoreConfig.useSparseLayers

    Dialog(onDismissRequest = onDismiss) {
        PanelCard(Modifier.width(420.dp)) {
            PanelHeader("New canvas", onClose = onDismiss)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            SectionLabel("Shape")
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxItemsInEachRow = 5
            ) {
                CANVAS_RATIOS.forEachIndexed { i, r ->
                    val selected = i == ratioIndex
                    Column(
                        Modifier
                            .width(70.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) YumeColors.accentSoft else YumeColors.panelAlt)
                            .clickable { applyPreset(i, if (edgeIndex >= 0) edgeIndex else 0) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // The shape itself, fitted in a 26 dp square.
                        val long = maxOf(r.w, r.h)
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(26.dp * (r.w / long), 26.dp * (r.h / long))
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (selected) YumeColors.accent else YumeColors.ink.copy(alpha = 0x66 / 255f))
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = r.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) YumeColors.accent else YumeColors.textDim
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("Resolution (long edge)")
            Spacer(Modifier.height(6.dp))
            // 12K and up are experimental: marked in their own colour.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CANVAS_EDGE_LABELS.forEachIndexed { e, label ->
                    val selected = e == edgeIndex
                    val heavy = CANVAS_LONG_EDGES[e] >= EXPERIMENTAL_EDGE
                    val tone = if (heavy) EXPERIMENTAL_COLOR else YumeColors.accent
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) tone.copy(alpha = 0.22f) else YumeColors.panelAlt)
                            .then(if (heavy) Modifier.border(1.dp, EXPERIMENTAL_COLOR.copy(alpha = 0.6f), RoundedCornerShape(10.dp)) else Modifier)
                            .clickable { applyPreset(if (ratioIndex >= 0) ratioIndex else 0, e) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (heavy) EXPERIMENTAL_COLOR else if (selected) tone else YumeColors.text
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = {
                        widthText = it.filter(Char::isDigit).take(5)
                        ratioIndex = -1
                        edgeIndex = -1
                    },
                    singleLine = true,
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = {
                        heightText = it.filter(Char::isDigit).take(5)
                        ratioIndex = -1
                        edgeIndex = -1
                    },
                    singleLine = true,
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$width × $height px  ·  ${"%.1f".format(width.toLong() * height / 1_000_000.0)} MP  ·  ≈ $mbPerLayer MB per full layer",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (!fits) {
                    if (sparseTiles) MemoryBudget.documentRefusal(width, height, 1)
                    else MemoryBudget.canvasRefusal(width, height)
                } else if (sparseTiles) {
                    "Memory follows the painted area; flat and empty areas cost almost nothing"
                } else if (maxLayers <= 4) {
                    "Tight fit: about $maxLayers layer${if (maxLayers == 1) "" else "s"} at this size"
                } else {
                    "Up to $maxLayers layers at this size"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!fits) YumeColors.danger else YumeColors.textDim
            )
            if (maxOf(width, height) >= EXPERIMENTAL_EDGE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Experimental: 12K and above can lag significantly while painting, " +
                        "zooming, saving and opening. Use 8K or less for comfortable work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EXPERIMENTAL_COLOR,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EXPERIMENTAL_COLOR.copy(alpha = 0.12f))
                        .padding(10.dp)
                )
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

/**
 * The gallery's top edge: cards passing under the header blur gradually
 * (sharp below it, fully blurred at the top of the screen). Android 13+ does
 * it on the GPU: the grid's layer is blurred, masked by a ramp in an AGSL
 * shader, and laid over the sharp layer. Older versions only get the fade.
 */
private object GalleryEdge {
    private const val MASK = """
        uniform shader content;
        uniform float edge;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            // Blurred under the header bar, sharp below it.
            float t = 1.0 - smoothstep(edge - 1.0, edge, p.y);
            return c * t;
        }
    """

    fun blurModifier(edgePx: Float): Modifier {
        if (edgePx <= 0f || android.os.Build.VERSION.SDK_INT < 33) return Modifier
        return Modifier.graphicsLayer {
            renderEffect = effect(edgePx)
        }
    }

    private var cachedEdge = -1f
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    @androidx.annotation.RequiresApi(33)
    private fun effect(edgePx: Float): androidx.compose.ui.graphics.RenderEffect {
        cached?.let { if (cachedEdge == edgePx) return it }
        val shader = android.graphics.RuntimeShader(MASK)
        shader.setFloatUniform("edge", edgePx)
        val blur = android.graphics.RenderEffect.createBlurEffect(28f, 28f, android.graphics.Shader.TileMode.CLAMP)
        val masked = android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"),
            blur
        )
        // Sharp grid underneath, the masked blur over it.
        val sharp = android.graphics.RenderEffect.createOffsetEffect(0f, 0f)
        val out = android.graphics.RenderEffect.createBlendModeEffect(sharp, masked, android.graphics.BlendMode.SRC_OVER)
            .asComposeRenderEffect()
        cached = out
        cachedEdge = edgePx
        return out
    }
}

/** What Import… offers: Yume backups and Photoshop documents. */
private val IMPORT_TYPES = arrayOf(
    "application/zip", "application/octet-stream", "image/vnd.adobe.photoshop",
    "application/x-photoshop", "image/x-photoshop", "application/photoshop", "image/psd", "application/psd"
)

/** The file name a document picker reports for [uri], if any. */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
