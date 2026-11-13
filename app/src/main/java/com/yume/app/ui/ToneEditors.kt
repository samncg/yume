package com.yume.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yume.app.engine.AdjustmentKind
import com.yume.app.engine.EditorUiState
import com.yume.app.engine.GradientMaps
import com.yume.app.engine.ToneLut
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The editor for a tone adjustment; every change hands a fresh table to [onLut]. */
@Composable
fun ToneEditor(kind: AdjustmentKind, state: EditorUiState, onLut: (ToneLut) -> Unit) {
    when (kind) {
        AdjustmentKind.Curves -> CurvesEditor(onLut)
        AdjustmentKind.Levels -> LevelsEditor(onLut)
        AdjustmentKind.ColorBalance -> ColorBalanceEditor(onLut)
        AdjustmentKind.GradientMap -> GradientMapEditor(state.color, onLut)
        else -> Unit
    }
}

private val CHANNELS = listOf("Master", "Red", "Green", "Blue")
private val CHANNEL_COLORS = listOf(Color(0xFFEDEDED), Color(0xFFFF6B6B), Color(0xFF6BE08C), Color(0xFF6BA8FF))

@Composable
private fun CurvesEditor(onLut: (ToneLut) -> Unit) {
    var channel by remember { mutableIntStateOf(0) }
    // One point list per channel, (x, y) in 0..1; the ends are always present.
    val curves = remember {
        List(4) { mutableStateListOf(0f to 0f, 1f to 1f) }
    }
    var dragging by remember { mutableIntStateOf(-1) }
    var version by remember { mutableIntStateOf(0) }
    fun publish() {
        version++
        onLut(ToneLut.curves(curves[0].toList(), curves[1].toList(), curves[2].toList(), curves[3].toList()))
    }
    SegmentedRow(options = CHANNELS, selectedIndex = channel, onSelect = { channel = it })
    Spacer(Modifier.height(8.dp))
    val density = LocalDensity.current
    val reach = with(density) { 22.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF17171B))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
            .pointerInput(channel) {
                val pts = curves[channel]
                fun toUnit(o: Offset) = (o.x / size.width).coerceIn(0f, 1f) to (1f - o.y / size.height).coerceIn(0f, 1f)
                detectDragGestures(
                    onDragStart = { o ->
                        var best = -1
                        var bestD = reach
                        pts.forEachIndexed { i, (x, y) ->
                            val d = hypot(x * size.width - o.x, (1f - y) * size.height - o.y)
                            if (d < bestD) { bestD = d; best = i }
                        }
                        if (best < 0) {
                            // Grab the curve anywhere: a new point there.
                            val (ux, uy) = toUnit(o)
                            var at = pts.indexOfFirst { it.first > ux }
                            if (at < 0) at = pts.size
                            pts.add(at, ux to uy)
                            best = at
                        }
                        dragging = best
                    },
                    onDrag = { change, _ ->
                        val i = dragging
                        if (i >= 0 && i < pts.size) {
                            val (ux, uy) = toUnit(change.position)
                            // Points keep their order; the ends stay at the edges.
                            val x = when (i) {
                                0 -> 0f
                                pts.size - 1 -> 1f
                                else -> ux.coerceIn(pts[i - 1].first + 0.01f, pts[i + 1].first - 0.01f)
                            }
                            pts[i] = x to uy
                            publish()
                        }
                    },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 }
                )
            }
            .pointerInput(channel) {
                // Double tap a middle point to remove it.
                detectTapGestures(onDoubleTap = { o ->
                    val pts = curves[channel]
                    val i = pts.indexOfFirst { (x, y) ->
                        hypot(x * size.width - o.x, (1f - y) * size.height - o.y) < reach
                    }
                    if (i > 0 && i < pts.size - 1) {
                        pts.removeAt(i)
                        publish()
                    }
                })
            }
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            @Suppress("UNUSED_EXPRESSION") version
            val w = size.width
            val h = size.height
            for (k in 1..3) {
                drawLine(Color(0x1FFFFFFF), Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), 1f)
                drawLine(Color(0x1FFFFFFF), Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
            }
            drawLine(Color(0x26FFFFFF), Offset(0f, h), Offset(w, 0f), 1f)
            // Other channels faintly, the edited one on top.
            for (c in (0..3).sortedBy { if (it == channel) 1 else 0 }) {
                val table = ToneLut.curveTable(curves[c].toList())
                val path = Path()
                for (v in 0..255) {
                    val x = v / 255f * w
                    val y = (1f - table[v] / 255f) * h
                    if (v == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val col = CHANNEL_COLORS[c].copy(alpha = if (c == channel) 1f else 0.25f)
                drawPath(path, col, style = Stroke(width = if (c == channel) 2.5f * density.density else 1.2f * density.density))
            }
            curves[channel].forEach { (x, y) ->
                drawCircle(Color.White, 6f * density.density, Offset(x * w, (1f - y) * h))
                drawCircle(CHANNEL_COLORS[channel], 4f * density.density, Offset(x * w, (1f - y) * h))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallButton("Reset curve") {
            curves[channel].clear()
            curves[channel].addAll(listOf(0f to 0f, 1f to 1f))
            publish()
        }
        Text(
            "Drag to add or move points; double tap to remove.",
            style = MaterialTheme.typography.bodySmall,
            color = YumeColors.textDim
        )
    }
}

@Composable
private fun LevelsEditor(onLut: (ToneLut) -> Unit) {
    var inBlack by remember { mutableFloatStateOf(0f) }
    var inWhite by remember { mutableFloatStateOf(1f) }
    var gamma by remember { mutableFloatStateOf(1f) }
    var outBlack by remember { mutableFloatStateOf(0f) }
    var outWhite by remember { mutableFloatStateOf(1f) }
    fun publish() = onLut(ToneLut.levels(inBlack, inWhite, gamma, outBlack, outWhite))
    YumeSlider(inBlack, { inBlack = it.coerceAtMost(inWhite - 0.02f); publish() },
        label = "Input black", valueLabel = "${(inBlack * 255).roundToInt()}")
    YumeSlider(inWhite, { inWhite = it.coerceAtLeast(inBlack + 0.02f); publish() },
        label = "Input white", valueLabel = "${(inWhite * 255).roundToInt()}")
    // Gamma on a log scale: the slider centre is 1.0.
    YumeSlider(
        (kotlin.math.ln(gamma) / kotlin.math.ln(4f) + 1f) / 2f,
        { gamma = Math.pow(4.0, (it * 2f - 1f).toDouble()).toFloat(); publish() },
        label = "Midtones (gamma)", valueLabel = String.format("%.2f", gamma)
    )
    YumeSlider(outBlack, { outBlack = it.coerceAtMost(outWhite); publish() },
        label = "Output black", valueLabel = "${(outBlack * 255).roundToInt()}")
    YumeSlider(outWhite, { outWhite = it.coerceAtLeast(outBlack); publish() },
        label = "Output white", valueLabel = "${(outWhite * 255).roundToInt()}")
}

@Composable
private fun ColorBalanceEditor(onLut: (ToneLut) -> Unit) {
    var tone by remember { mutableIntStateOf(1) }
    val shifts = remember { FloatArray(9) }
    var version by remember { mutableIntStateOf(0) }
    SegmentedRow(listOf("Shadows", "Midtones", "Highlights"), tone, { tone = it })
    Spacer(Modifier.height(6.dp))
    val labels = listOf("Cyan – Red", "Magenta – Green", "Yellow – Blue")
    for (k in 0 until 3) {
        @Suppress("UNUSED_EXPRESSION") version
        val i = tone * 3 + k
        YumeSlider(
            value = shifts[i],
            onChange = {
                shifts[i] = it
                version++
                onLut(ToneLut.colorBalance(shifts.copyOf()))
            },
            label = labels[k],
            valueLabel = "${(shifts[i] * 100).roundToInt()}",
            min = -1f,
            max = 1f
        )
    }
}

@Composable
private fun GradientMapEditor(brushColor: Int, onLut: (ToneLut) -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    val all = GradientMaps.presets + ("From brush colour" to listOf(
        0f to 0xFF000000.toInt(), 0.5f to (brushColor or 0xFF000000.toInt()), 1f to 0xFFFFFFFF.toInt()
    ))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        all.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (name, stops) ->
                    val selected = chosen == name
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) YumeColors.accentSoft else YumeColors.panelAlt)
                            .clickable {
                                chosen = name
                                onLut(ToneLut.gradientMap(stops))
                            }
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        colorStops = stops.map { it.first to Color(it.second) }.toTypedArray()
                                    )
                                )
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) YumeColors.accent else YumeColors.text,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Dark parts of the layer take the left colour, light parts the right.",
        style = MaterialTheme.typography.bodySmall,
        color = YumeColors.textDim
    )
}
