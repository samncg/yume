package com.yume.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yume.app.engine.AdjustmentKind
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.EditorUiState
import com.yume.app.engine.FramesArt
import com.yume.app.engine.FramesLayout
import com.yume.app.engine.InsertKind
import com.yume.app.engine.MangaArt
import com.yume.app.engine.MangaParams
import com.yume.app.engine.Material
import com.yume.app.engine.MaterialKind
import com.yume.app.engine.MaterialParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val EFFECT_TABS = listOf("Adjust", "Effects", "Manga", "Frames", "Materials")

/** The tab last open, so the panel reopens where it was. */
private var lastEffectsTab = 0

/** Which tab a pending preview lives in (-1: none). */
private fun pendingTab(state: EditorUiState): Int {
    state.pendingAdjust?.let { k -> return if (ADJUST_GROUPS.take(3).any { k in it.second }) 0 else 1 }
    return when (state.pendingInsert) {
        InsertKind.FocusLines, InsertKind.SpeedLines -> 2
        InsertKind.Frames -> 3
        InsertKind.Material -> 4
        null -> -1
    }
}

private fun pendingLabel(state: EditorUiState): String =
    state.pendingAdjust?.label ?: state.pendingInsert?.let {
        if (it == InsertKind.Material) state.material.kind.label else it.label
    } ?: ""

/**
 * Effects: adjustments, effects, manga lines, comic frames and materials in
 * tabs. Every one previews live on the canvas (pan and zoom still work) and
 * waits for Apply or Cancel; closing the panel keeps the preview, and
 * reopening it goes back to it.
 */
@Composable
fun EffectsPanel(state: EditorUiState, session: DrawingSession, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(pendingTab(state).takeIf { it >= 0 } ?: lastEffectsTab) }
    PanelCard(Modifier.width(PANEL_WIDTH.dp), scrollable = true) {
        PanelHeader("Effects", onClose)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EFFECT_TABS.forEachIndexed { i, name ->
                val sel = i == tab
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sel) YumeColors.accent else YumeColors.textDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) YumeColors.accentSoft else YumeColors.panelAlt)
                        .clickable { tab = i; lastEffectsTab = i }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val pt = pendingTab(state)
        if (pt >= 0 && pt != tab) {
            PreviewWaiting("${pendingLabel(state)} is previewing, not applied") { tab = pt; lastEffectsTab = pt }
        }
        key(tab) {
            when (tab) {
                0 -> AdjustContent(state, session, ADJUST_GROUPS.take(3))
                1 -> AdjustContent(state, session, ADJUST_GROUPS.drop(3))
                2 -> MangaTab(state, session)
                3 -> FramesTab(state, session)
                else -> MaterialsTab(state, session)
            }
        }
    }
}

/** "Something is previewing": opens it. */
@Composable
internal fun PreviewWaiting(text: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(YumeColors.accentSoft)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = YumeColors.accent, modifier = Modifier.weight(1f))
        Text("Open", style = MaterialTheme.typography.labelLarge, color = YumeColors.accent)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun EditingTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        YumeIconButton(Glyph.ChevronDown, onBack, size = 32.dp, iconSize = 18.dp, label = "Back")
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = YumeColors.text, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ApplyRow(pending: Boolean, session: DrawingSession, onApplied: () -> Unit, extra: @Composable () -> Unit = {}) {
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallButton("Apply", enabled = pending) {
            session.applyInsert()
            onApplied()
        }
        SmallButton("Cancel", enabled = pending) { session.cancelInsert() }
        extra()
    }
    if (pending) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Not applied yet: it goes on a new layer when you apply. Closing the panel keeps the preview.",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.accent
        )
    }
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
}

/** A gallery button: a picture with its name under it. */
@Composable
private fun SampleTile(
    label: String, picture: Bitmap?, aspect: Float, modifier: Modifier, pending: Boolean,
    background: Color = Color.White, onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pending) YumeColors.accentSoft else YumeColors.panelAlt)
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(8.dp))
                .background(background)
        ) {
            if (picture != null) {
                Image(picture.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label, style = MaterialTheme.typography.bodySmall,
            color = if (pending) YumeColors.accent else YumeColors.text, maxLines = 1
        )
    }
}

// ================================================================ manga lines

@Composable
private fun MangaTab(state: EditorUiState, session: DrawingSession) {
    val longEdge = maxOf(state.width, state.height).coerceAtLeast(1)
    val pendingKind = state.pendingInsert.takeIf { it == InsertKind.FocusLines || it == InsertKind.SpeedLines }
    var editing by remember { mutableStateOf(pendingKind != null) }
    var p by remember { mutableStateOf(session.mangaParamsNow()) }
    fun update(f: (MangaParams) -> MangaParams) {
        val n = f(session.mangaParamsNow())
        p = n
        session.previewManga(n)
    }
    val samples = remember {
        listOf(false, true).map { speed ->
            val w = 360
            val h = 220
            val art = MangaArt.build(
                MangaParams(speed = speed, count = if (speed) 70 else 90, thickness = 5f, clear = if (speed) 0.25f else 0.4f, seed = 3L),
                w, h, null, 0xFF000000.toInt()
            )
            Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888).also { art.render(it, 0f, 0f, 0.5f) }
        }
    }
    if (!editing) {
        Text("Lines added as a new layer in the current colour.", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false, true).forEachIndexed { i, speed ->
                val kind = if (speed) InsertKind.SpeedLines else InsertKind.FocusLines
                SampleTile(kind.label, samples[i], 360f / 220f, Modifier.weight(1f), pendingKind == kind) {
                    editing = true
                    if (pendingKind != kind) {
                        update {
                            MangaParams(
                                speed = speed, thickness = longEdge * 0.005f,
                                seed = System.nanoTime() and 0xFFFFFF
                            )
                        }
                    }
                }
            }
        }
        return
    }
    val speed = p.speed
    EditingTitle(if (speed) "Speed lines" else "Focus lines") { editing = false }
    val thickShare = p.thickness / longEdge * 100f
    YumeSlider(value = p.count.toFloat(), onChange = { v -> update { it.copy(count = v.roundToInt()) } },
        label = "Lines", valueLabel = "${p.count}", min = 30f, max = 500f)
    YumeSlider(value = thickShare, onChange = { v -> update { it.copy(thickness = (v / 100f * longEdge).coerceAtLeast(1f)) } },
        label = "Thickness", valueLabel = "${p.thickness.roundToInt().coerceAtLeast(1)} px", min = 0.05f, max = 3f)
    if (!speed && state.hasSelection) {
        Hint("The lines aim at the selection and stop at it.")
    } else {
        YumeSlider(value = p.clear, onChange = { v -> update { it.copy(clear = v) } },
            label = if (speed) "Clear band" else "Clear area", valueLabel = "${(p.clear * 100).roundToInt()}%", min = 0f, max = 0.9f)
    }
    YumeSlider(value = p.variation, onChange = { v -> update { it.copy(variation = v) } },
        label = "Variation", valueLabel = "${(p.variation * 100).roundToInt()}%")
    if (speed) {
        YumeSlider(value = p.angle, onChange = { v -> update { it.copy(angle = v) } },
            label = "Direction", valueLabel = "${p.angle.roundToInt()}°", min = 0f, max = 180f)
    } else if (!state.hasSelection) {
        Hint("Touch or drag on the canvas to aim the lines. Two fingers still pan and zoom.")
    }
    ApplyRow(pendingKind != null, session, onApplied = { editing = false }) {
        SmallButton("New variation") { update { it.copy(seed = it.seed * 31 + 17) } }
    }
}

// ============================================================== comic frames

@Composable
private fun FramesTab(state: EditorUiState, session: DrawingSession) {
    val pending = state.pendingInsert == InsertKind.Frames
    var editing by remember { mutableStateOf(pending) }
    val unit = minOf(state.width, state.height).coerceAtLeast(1)
    val aspect = if (state.height > 0) state.width.toFloat() / state.height else 0.7f
    fun update(f: (FramesLayout) -> FramesLayout) = session.previewFrames(f(session.framesLayoutNow()))
    val layout = state.frames
    if (!editing) {
        val thumbs = remember(aspect) {
            FramesLayout.presets.map { (_, l) ->
                val h = 220
                val w = (h * aspect).roundToInt().coerceIn(60, 440)
                val art = FramesArt.build(l.copy(border = 3f), w, h)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { art.render(it, 0f, 0f, 1f) }
            }
        }
        Text(
            "Pick a page, then drag on the canvas to add, move and resize panels. They snap to a grid and never overlap.",
            style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        if (pending) {
            PreviewWaiting("Your page is previewing, not applied") { editing = true }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FramesLayout.presets.withIndex().chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, preset) in row) {
                        SampleTile(preset.first, thumbs[i], aspect, Modifier.weight(1f), false, background = Color(0xFF9A9AA2)) {
                            editing = true
                            session.previewFrames(preset.second.copy(border = (unit * 0.0035f).coerceAtLeast(1f)))
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        return
    }
    EditingTitle("Comic frames") { editing = false }
    Text(
        "On the canvas: drag across empty cells to add a panel, drag a panel to move it, drag its corner dot to resize. " +
            "Tap a panel to select it; its red button deletes it.",
        style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
    )
    Spacer(Modifier.height(8.dp))
    var cols by remember { mutableFloatStateOf(layout.cols.toFloat()) }
    var rows by remember { mutableFloatStateOf(layout.rows.toFloat()) }
    YumeSlider(value = cols, onChange = { v ->
        cols = v
        update { it.regrid(v.roundToInt(), it.rows) }
    }, label = "Grid columns", valueLabel = "${cols.roundToInt()}", min = 1f, max = 8f)
    YumeSlider(value = rows, onChange = { v ->
        rows = v
        update { it.regrid(it.cols, v.roundToInt()) }
    }, label = "Grid rows", valueLabel = "${rows.roundToInt()}", min = 1f, max = 12f)
    YumeSlider(value = layout.gutter, onChange = { v -> update { it.copy(gutter = v) } },
        label = "Gutter", valueLabel = "${(layout.gutter * unit).roundToInt()} px", min = 0f, max = 0.08f)
    YumeSlider(value = layout.margin, onChange = { v -> update { it.copy(margin = v) } },
        label = "Margin", valueLabel = "${(layout.margin * unit).roundToInt()} px", min = 0f, max = 0.15f)
    val borderShare = layout.border / unit * 100f
    YumeSlider(value = borderShare, onChange = { v -> update { it.copy(border = (v / 100f * unit).coerceAtLeast(1f)) } },
        label = "Border", valueLabel = "${layout.border.roundToInt()} px", min = 0.05f, max = 1.5f)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("Fill empty cells") { update { it.fillEmpty() } }
        SmallButton("Delete panel", enabled = layout.selected in layout.panels.indices) {
            update { l -> l.copy(panels = l.panels.filterIndexed { i, _ -> i != l.selected }, selected = -1) }
        }
        SmallButton("Clear", enabled = layout.panels.isNotEmpty()) { update { it.copy(panels = emptyList(), selected = -1) } }
    }
    ApplyRow(pending, session, onApplied = { editing = false })
}

// ================================================================= materials

/** Tile pictures of every material (black ink), made once and kept. */
private object MaterialSamples {
    const val W = 180
    const val H = 110
    private val cache = HashMap<MaterialKind, Bitmap>()

    @Synchronized
    fun get(kind: MaterialKind): Bitmap? = cache[kind]

    fun build() {
        for (k in MaterialKind.entries) {
            if (get(k) != null) continue
            val m = Material(k, 12f, defaultMaterialAngle(k), k.defaultDensity.coerceAtLeast(0.35f), Rect(0, 0, W, H), 7)
            val bmp = m.sample(W, H, 0xFF000000.toInt())
            synchronized(this) { cache[k] = bmp }
        }
    }
}

/** Screentones sit at 45° by tradition; a gradation runs top to bottom. */
private fun defaultMaterialAngle(kind: MaterialKind): Float = when (kind) {
    MaterialKind.Dots, MaterialKind.Lines, MaterialKind.Cross -> 45f
    MaterialKind.Gradation -> 90f
    else -> 0f
}

@Composable
private fun MaterialsTab(state: EditorUiState, session: DrawingSession) {
    val longEdge = maxOf(state.width, state.height).coerceAtLeast(1)
    val pending = state.pendingInsert == InsertKind.Material
    var editing by remember { mutableStateOf(pending) }
    var samplesReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { MaterialSamples.build() }
        samplesReady = true
    }
    var p by remember { mutableStateOf(session.materialParamsNow()) }
    fun update(f: (MaterialParams) -> MaterialParams) {
        val n = f(session.materialParamsNow())
        p = n
        session.previewMaterial(n)
    }
    if (!editing) {
        Text(
            if (state.hasSelection) "Added as a new layer, inside the selection." else "Added as a new layer over the whole canvas.",
            style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        for ((group, kinds) in MaterialKind.groups) {
            SectionLabel(group)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in row) {
                            val isPending = pending && state.material.kind == k
                            SampleTile(
                                k.label, if (samplesReady) MaterialSamples.get(k) else null,
                                MaterialSamples.W.toFloat() / MaterialSamples.H, Modifier.weight(1f), isPending
                            ) {
                                editing = true
                                if (!isPending) {
                                    update {
                                        MaterialParams(
                                            kind = k, size = Material.sizeFor(0.12f, longEdge),
                                            angle = defaultMaterialAngle(k), density = k.defaultDensity,
                                            ink = it.ink, seed = 1 + (System.nanoTime() % 9973).toInt().let { s -> if (s < 0) -s else s }
                                        )
                                    }
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        return
    }
    EditingTitle(p.kind.label) { editing = false }
    // The size slider is the inverse of Material.sizeFor.
    val sizeSlider = sqrt(((p.size - 3f) / (0.08f * longEdge)).coerceIn(0f, 1f))
    YumeSlider(value = sizeSlider, onChange = { v -> update { it.copy(size = Material.sizeFor(v, longEdge)) } },
        label = "Size", valueLabel = "${p.size.roundToInt()} px")
    YumeSlider(value = p.angle, onChange = { v -> update { it.copy(angle = v) } },
        label = "Angle", valueLabel = "${p.angle.roundToInt()}°", min = 0f, max = 180f)
    p.kind.densityLabel?.let { label ->
        YumeSlider(value = p.density, onChange = { v -> update { it.copy(density = v) } },
            label = label, valueLabel = "${(p.density * 100).roundToInt()}%")
    }
    Spacer(Modifier.height(4.dp))
    Text("Ink", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(listOf("Current colour", "Black", "White"), p.ink, { i -> update { it.copy(ink = i) } })
    Hint("Zoom in to see it at full detail. Move or scale it afterwards with Transform.")
    ApplyRow(pending, session, onApplied = { editing = false }) {
        if (p.kind.seeded) SmallButton("New variation") { update { it.copy(seed = it.seed * 31 + 17) } }
    }
}
