package com.yume.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yume.app.engine.Material
import com.yume.app.engine.MaterialKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
            val m = Material(k, 12f, defaultAngle(k), k.defaultDensity.coerceAtLeast(0.35f), Rect(0, 0, W, H), 7)
            val bmp = m.sample(W, H, 0xFF000000.toInt())
            synchronized(this) { cache[k] = bmp }
        }
    }
}

/** Screentones sit at 45° by tradition; a gradation runs top to bottom. */
private fun defaultAngle(kind: MaterialKind): Float = when (kind) {
    MaterialKind.Dots, MaterialKind.Lines, MaterialKind.Cross -> 45f
    MaterialKind.Gradation -> 90f
    else -> 0f
}

/**
 * The materials library: tones, patterns and textures in groups of sample
 * buttons; choosing one shows its settings and a preview, and adds it as a
 * new layer (over the selection when there is one).
 */
@Composable
fun MaterialsDialog(
    width: Int,
    height: Int,
    hasSelection: Boolean,
    /** The brush colour (the "Current colour" ink). */
    currentColor: Int,
    onDismiss: () -> Unit,
    onAdd: (kind: MaterialKind, size: Float, angle: Float, density: Float, ink: Int, seed: Int) -> Unit
) {
    val longEdge = maxOf(width, height)
    var kind by remember { mutableStateOf<MaterialKind?>(null) }
    var sizeSlider by remember { mutableFloatStateOf(0.12f) }
    var angle by remember { mutableFloatStateOf(45f) }
    var density by remember { mutableFloatStateOf(0.3f) }
    var ink by remember { mutableIntStateOf(1) }
    var seed by remember { mutableIntStateOf(1 + (System.nanoTime() % 9973).toInt().let { if (it < 0) -it else it }) }
    var samplesReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { MaterialSamples.build() }
        samplesReady = true
    }
    val period = Material.sizeFor(sizeSlider, longEdge)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        PanelCard(Modifier.width(420.dp).heightIn(max = 640.dp), scrollable = true) {
            val chosen = kind
            if (chosen == null) {
                PanelHeader("Materials", onClose = onDismiss)
                Text(
                    if (hasSelection) "Added as a new layer, inside the selection."
                    else "Added as a new layer over the whole canvas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = YumeColors.textDim
                )
                Spacer(Modifier.height(10.dp))
                for ((group, kinds) in MaterialKind.groups) {
                    SectionLabel(group)
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        kinds.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (k in row) {
                                    MaterialTile(k, samplesReady, Modifier.weight(1f)) {
                                        kind = k
                                        angle = defaultAngle(k)
                                        density = k.defaultDensity
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                return@PanelCard
            }

            // ------------------------------------------------------ settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                YumeIconButton(Glyph.ChevronDown, { kind = null }, size = 32.dp, iconSize = 18.dp, label = "All materials")
                Spacer(Modifier.width(6.dp))
                Text(chosen.label, style = MaterialTheme.typography.titleMedium, color = YumeColors.text, modifier = Modifier.weight(1f))
                YumeIconButton(Glyph.Close, onDismiss, size = 32.dp, iconSize = 16.dp, label = "Close")
            }
            Spacer(Modifier.height(8.dp))
            MaterialPreview(chosen, period, angle, density, ink, seed, currentColor)
            Spacer(Modifier.height(10.dp))
            YumeSlider(
                value = sizeSlider, onChange = { sizeSlider = it }, label = "Size",
                valueLabel = "${period.roundToInt()} px"
            )
            YumeSlider(
                value = angle, onChange = { angle = it }, label = "Angle",
                valueLabel = "${angle.roundToInt()}°", min = 0f, max = 180f
            )
            chosen.densityLabel?.let { label ->
                YumeSlider(
                    value = density, onChange = { density = it }, label = label,
                    valueLabel = "${(density * 100).roundToInt()}%"
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("Ink", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
            Spacer(Modifier.height(4.dp))
            SegmentedRow(listOf("Current colour", "Black", "White"), ink, { ink = it })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Add as a new layer") {
                    onAdd(chosen, period, angle, density, ink, seed)
                }
                if (chosen.seeded) {
                    SmallButton("New variation") { seed = seed * 31 + 17 }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Move or scale it afterwards with Transform; lower the layer's opacity for a lighter tone.",
                style = MaterialTheme.typography.bodySmall,
                color = YumeColors.textDim
            )
        }
    }
}

@Composable
private fun MaterialTile(kind: MaterialKind, ready: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val sample = if (ready) MaterialSamples.get(kind) else null
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(YumeColors.panelAlt)
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(MaterialSamples.W.toFloat() / MaterialSamples.H)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
        ) {
            if (sample != null) {
                Image(
                    bitmap = sample.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(kind.label, style = MaterialTheme.typography.bodySmall, color = YumeColors.text, maxLines = 1)
    }
}

/**
 * The chosen material with the current settings: at 100% when a few periods
 * fit, else scaled down so the pattern still reads.
 */
@Composable
private fun MaterialPreview(kind: MaterialKind, period: Float, angle: Float, density: Float, ink: Int, seed: Int, currentColor: Int) {
    val w = 640
    val h = 260
    val scale = minOf(1f, w / (period * 4f))
    var picture by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(kind, period, angle, density, ink, seed, currentColor) {
        picture = withContext(Dispatchers.Default) {
            val color = when (ink) {
                0 -> currentColor or 0xFF000000.toInt()
                1 -> 0xFF000000.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            Material(kind, period * scale, angle, density, Rect(0, 0, w, h), seed).sample(w, h, color)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(w.toFloat() / h)
            .clip(RoundedCornerShape(10.dp))
            .background(if (ink == 2) Color(0xFF2A2A30) else Color.White)
    ) {
        picture?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Preview",
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (scale < 0.999f) {
            Text(
                "${(scale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF606068),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
            )
        }
    }
}
