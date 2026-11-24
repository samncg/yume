package com.yume.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import com.yume.app.engine.PressureCurve
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yume.app.engine.BrushAssets
import com.yume.app.engine.BrushCategory
import com.yume.app.engine.BrushDab
import com.yume.app.engine.BrushPreset
import com.yume.app.engine.BrushPresetFile
import com.yume.app.engine.DrawingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A sample stroke of [preset] in [color]: an S-curve whose pressure rises and
 * falls, so size, opacity, spacing, shape, grain and rotation all show.
 */
@Composable
fun BrushStrokePreview(preset: BrushPreset, color: Int) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(preset, color) {
        image = withContext(Dispatchers.Default) { renderStroke(preset, color, 900, 200).asImageBitmap() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF1EFEA))
    ) {
        image?.let { Image(it, contentDescription = "Brush preview", modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * The example stroke beside the size and opacity sliders while they move:
 * the current brush at [screenSize] px (its size on screen at this zoom, as
 * far as the box allows) and [opacity].
 */
@Composable
fun RailStrokeSample(preset: BrushPreset, color: Int, screenSize: Float, opacity: Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val wPx = with(density) { 200.dp.roundToPx() }
    val hPx = with(density) { 76.dp.roundToPx() }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(preset, color, screenSize, opacity) {
        // Renders follow the finger; a newer value cancels a waiting one.
        kotlinx.coroutines.delay(16)
        val p = preset.copy(size = screenSize.coerceAtLeast(1f), opacity = opacity.coerceIn(0.02f, 1f))
        image = withContext(Dispatchers.Default) { renderStroke(p, color, wPx, hPx).asImageBitmap() }
    }
    Box(
        Modifier
            .width(200.dp)
            .height(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF1EFEA))
    ) {
        image?.let { Image(it, contentDescription = "Example stroke", modifier = Modifier.fillMaxSize()) }
    }
}

private fun renderStroke(preset: BrushPreset, color: Int, w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val dab = BrushDab.dab(preset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val tint = if (preset.category == BrushCategory.Eraser) 0xFF6B6B70.toInt() else (color or 0xFF000000.toInt())
    paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
    val maxSize = min(preset.size, h * 0.42f).coerceAtLeast(3f)
    val rnd = Random(11)
    // Sample the S-curve densely, then stamp at the brush's own spacing.
    val n = 400
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (i in 0 until n) {
        val t = i / (n - 1f)
        xs[i] = w * (0.08f + 0.84f * t)
        ys[i] = h * (0.5f + 0.28f * sin(t * 2f * Math.PI.toFloat()))
    }
    var travelled = 0f
    var next = 0f
    for (i in 1 until n) {
        val seg = hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val t = i / (n - 1f)
        val pressure = (PressureCurve.of(preset.pressureCurve)?.map(sin(t * Math.PI.toFloat()).coerceIn(0.15f, 1f))
            ?: sin(t * Math.PI.toFloat()).coerceIn(0.15f, 1f))
        val sizeP = 1f - preset.pressureSize.coerceIn(0f, 1f) * (1f - pressure)
        val size = max(1.2f, maxSize * sizeP)
        val step = max(0.8f, size * max(0.02f, preset.spacing))
        travelled += seg
        while (travelled >= next) {
            val f = ((next - (travelled - seg)) / max(seg, 1e-3f)).coerceIn(0f, 1f)
            val x = xs[i - 1] + (xs[i] - xs[i - 1]) * f
            val y = ys[i - 1] + (ys[i] - ys[i - 1]) * f
            val alphaP = 1f - preset.pressureOpacity.coerceIn(0f, 1f) * (1f - pressure)
            paint.alpha = ((preset.opacity * preset.flow).coerceIn(0.02f, 1f) * alphaP * 255f).roundToInt().coerceIn(1, 255)
            val angle = when (preset.rotationMode) {
                1 -> Math.toDegrees(atan2(ys[i] - ys[i - 1], xs[i] - xs[i - 1]).toDouble()).toFloat()
                2 -> 45f
                3 -> rnd.nextFloat() * 360f
                else -> 0f
            } + preset.rotation
            val jx = (rnd.nextFloat() - 0.5f) * preset.jitter * size * 2f
            val jy = (rnd.nextFloat() - 0.5f) * preset.jitter * size * 2f
            val half = size / 2f
            val save = c.save()
            if (angle != 0f) c.rotate(angle, x + jx, y + jy)
            c.drawBitmap(dab, null, RectF(x + jx - half, y + jy - half, x + jx + half, y + jy + half), paint)
            c.restoreToCount(save)
            next += step
        }
    }
    return out
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun signedPct(v: Float) = (if (v > 0f) "+" else "") + "${(v * 100).roundToInt()}%"

/**
 * The brush's own dynamics: its pressure curve, speed response, per-dab and
 * per-stroke jitter, spray, taper, wet edges, blend mode and wet bleeding.
 */
@Composable
fun BrushDynamics(preset: BrushPreset, session: DrawingSession) {
    fun upd(f: (BrushPreset) -> BrushPreset) = session.updatePreset(f)
    val isBrush = preset.category == BrushCategory.Brush
    val isEraser = preset.category == BrushCategory.Eraser

    DividerLine()
    SectionLabel("Pressure curve")
    Spacer(Modifier.height(6.dp))
    PressureCurveEditor(preset.pressureCurve) { spec -> upd { it.copy(pressureCurve = spec) } }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton(if (preset.pressureCurve == PressureCurve.LINEAR) "• Linear" else "Linear") { upd { it.copy(pressureCurve = PressureCurve.LINEAR) } }
        SmallButton(if (preset.pressureCurve == PressureCurve.SOFT) "• Soft" else "Soft") { upd { it.copy(pressureCurve = PressureCurve.SOFT) } }
        SmallButton(if (preset.pressureCurve == PressureCurve.FIRM) "• Firm" else "Firm") { upd { it.copy(pressureCurve = PressureCurve.FIRM) } }
        SmallButton(if (preset.pressureCurve == PressureCurve.S_CURVE) "• S" else "S") { upd { it.copy(pressureCurve = PressureCurve.S_CURVE) } }
    }

    DividerLine()
    SectionLabel("Speed")
    YumeSlider(
        value = preset.velocitySize, onChange = { v -> upd { it.copy(velocitySize = v) } },
        label = "Speed → Size", valueLabel = signedPct(preset.velocitySize), min = -1f, max = 1f
    )
    YumeSlider(
        value = preset.velocityOpacity, onChange = { v -> upd { it.copy(velocityOpacity = v) } },
        label = "Speed → Opacity", valueLabel = signedPct(preset.velocityOpacity), min = -1f, max = 1f
    )
    YumeSlider(
        value = preset.taperIn, onChange = { v -> upd { it.copy(taperIn = v) } },
        label = "Start taper", valueLabel = if (preset.taperIn < 0.05f) "Off" else "%.1f×".format(preset.taperIn),
        min = 0f, max = 10f
    )

    DividerLine()
    SectionLabel("Jitter")
    YumeSlider(
        value = preset.sizeJitter, onChange = { v -> upd { it.copy(sizeJitter = v) } },
        label = "Size jitter", valueLabel = pct(preset.sizeJitter)
    )
    YumeSlider(
        value = preset.opacityJitter, onChange = { v -> upd { it.copy(opacityJitter = v) } },
        label = "Opacity jitter", valueLabel = pct(preset.opacityJitter)
    )
    YumeSlider(
        value = preset.angleJitter, onChange = { v -> upd { it.copy(angleJitter = v) } },
        label = "Angle jitter", valueLabel = "${preset.angleJitter.roundToInt()}°", min = 0f, max = 180f
    )
    YumeSlider(
        value = preset.sprayCount.toFloat(), onChange = { v -> upd { it.copy(sprayCount = v.roundToInt().coerceIn(1, 32)) } },
        label = "Spray particles", valueLabel = if (preset.sprayCount <= 1) "Off" else "${preset.sprayCount}",
        min = 1f, max = 24f
    )
    if (preset.sprayCount > 1) {
        YumeSlider(
            value = preset.sprayParticle, onChange = { v -> upd { it.copy(sprayParticle = v.coerceIn(0.05f, 1f)) } },
            label = "Particle size", valueLabel = pct(preset.sprayParticle), min = 0.05f, max = 1f
        )
    }

    if (isBrush) {
        DividerLine()
        SectionLabel("Colour jitter, every dab")
        YumeSlider(value = preset.hueJitter, onChange = { v -> upd { it.copy(hueJitter = v) } }, label = "Hue", valueLabel = pct(preset.hueJitter))
        YumeSlider(value = preset.satJitter, onChange = { v -> upd { it.copy(satJitter = v) } }, label = "Saturation", valueLabel = pct(preset.satJitter))
        YumeSlider(value = preset.lumJitter, onChange = { v -> upd { it.copy(lumJitter = v) } }, label = "Lightness", valueLabel = pct(preset.lumJitter))
        SectionLabel("Colour jitter, each stroke")
        YumeSlider(value = preset.strokeHueJitter, onChange = { v -> upd { it.copy(strokeHueJitter = v) } }, label = "Hue", valueLabel = pct(preset.strokeHueJitter))
        YumeSlider(value = preset.strokeSatJitter, onChange = { v -> upd { it.copy(strokeSatJitter = v) } }, label = "Saturation", valueLabel = pct(preset.strokeSatJitter))
        YumeSlider(value = preset.strokeLumJitter, onChange = { v -> upd { it.copy(strokeLumJitter = v) } }, label = "Lightness", valueLabel = pct(preset.strokeLumJitter))

        DividerLine()
        SectionLabel("Stroke")
        YumeSlider(
            value = preset.wetEdges, onChange = { v -> upd { it.copy(wetEdges = v) } },
            label = "Wet edges", valueLabel = pct(preset.wetEdges)
        )
        Spacer(Modifier.height(4.dp))
        Text("Blend mode", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
        Spacer(Modifier.height(4.dp))
        BrushPreset.BLENDS.map { it.first }.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                row.forEach { name ->
                    val on = preset.blend == name
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) YumeColors.accent else YumeColors.text,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) YumeColors.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                            .clickable { upd { it.copy(blend = name) } }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }

    if (!isEraser) {
        DividerLine()
        SectionLabel(if (preset.category == BrushCategory.Smudge) "Wet bleed blender" else "Wet bleed")
        YumeSlider(
            value = preset.bleed, onChange = { v -> upd { it.copy(bleed = v) } },
            label = "Bleed", valueLabel = if (preset.bleed <= 0.001f) "Off" else pct(preset.bleed)
        )
        if (preset.bleed > 0.001f) {
            YumeSlider(
                value = preset.bleedSpread, onChange = { v -> upd { it.copy(bleedSpread = v) } },
                label = "Spread", valueLabel = pct(preset.bleedSpread)
            )
            YumeSlider(
                value = preset.bleedPaint, onChange = { v -> upd { it.copy(bleedPaint = v) } },
                label = "Paint carried", valueLabel = pct(preset.bleedPaint)
            )
        }
    }
}

/**
 * Pressure curve editor: drag the two handles. x is the pen's pressure, y
 * what the brush receives; the diagonal is linear.
 */
@Composable
fun PressureCurveEditor(spec: String, onChange: (String) -> Unit) {
    val curve = PressureCurve.of(spec)
    var h1 by remember(spec) { mutableStateOf(androidx.compose.ui.geometry.Offset(curve?.x1 ?: 0.33f, curve?.y1 ?: 0.33f)) }
    var h2 by remember(spec) { mutableStateOf(androidx.compose.ui.geometry.Offset(curve?.x2 ?: 0.67f, curve?.y2 ?: 0.67f)) }
    var active by remember { mutableStateOf(0) }
    val accent = YumeColors.accent
    val grid = YumeColors.textDim.copy(alpha = 0.25f)
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(spec) {
                fun toUnit(o: androidx.compose.ui.geometry.Offset) = androidx.compose.ui.geometry.Offset(
                    (o.x / size.width).coerceIn(0f, 1f), (1f - o.y / size.height).coerceIn(0f, 1f)
                )
                // Only a touch that starts on a handle edits the curve; anything
                // else is left alone, so the panel can be scrolled past it.
                val grab = 30.dp.toPx()
                fun toPx(u: androidx.compose.ui.geometry.Offset) =
                    androidx.compose.ui.geometry.Offset(u.x * size.width, (1f - u.y) * size.height)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val d1 = (down.position - toPx(h1)).getDistance()
                    val d2 = (down.position - toPx(h2)).getDistance()
                    if (minOf(d1, d2) > grab) return@awaitEachGesture
                    active = if (d1 <= d2) 1 else 2
                    down.consume()
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val u = toUnit(change.position)
                        if (active == 1) h1 = u else h2 = u
                        moved = true
                        change.consume()
                    }
                    active = 0
                    if (moved) onChange("%.3f,%.3f,%.3f,%.3f".format(java.util.Locale.US, h1.x, h1.y, h2.x, h2.y))
                }
            }
    ) {
        val w = size.width
        val h = size.height
        for (k in 1..3) {
            drawLine(grid, androidx.compose.ui.geometry.Offset(w * k / 4f, 0f), androidx.compose.ui.geometry.Offset(w * k / 4f, h))
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, h * k / 4f), androidx.compose.ui.geometry.Offset(w, h * k / 4f))
        }
        drawLine(grid, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(w, 0f))
        val p1 = androidx.compose.ui.geometry.Offset(h1.x * w, (1f - h1.y) * h)
        val p2 = androidx.compose.ui.geometry.Offset(h2.x * w, (1f - h2.y) * h)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, h)
            cubicTo(p1.x, p1.y, p2.x, p2.y, w, 0f)
        }
        drawLine(accent.copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(0f, h), p1)
        drawLine(accent.copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(w, 0f), p2)
        drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
        drawCircle(accent, 14f, p1)
        drawCircle(accent, 14f, p2)
    }
}

/** Shape, grain, rotation and wet mix, plus import / export of presets. */
@Composable
fun BrushExtras(preset: BrushPreset, session: DrawingSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }

    fun loadBitmap(uri: android.net.Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    val shapePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val id = withContext(Dispatchers.IO) { loadBitmap(uri)?.let { BrushAssets.importShape(it) } }
            if (id != null) session.updatePreset { it.copy(shapeId = id) } else note = "That image could not be read."
        }
    }
    val grainPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val id = withContext(Dispatchers.IO) { loadBitmap(uri)?.let { BrushAssets.importGrain(it) } }
            if (id != null) session.updatePreset { it.copy(grainId = id, grain = if (it.grain < 0.05f) 0.6f else it.grain) }
            else note = "That image could not be read."
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(BrushPresetFile.export(listOf(preset)).toByteArray()) }
                }.isSuccess
            }
            note = if (ok) "Exported “${preset.name}”." else "Export failed."
        }
    }
    // Several files at once (a set exported as one file per brush).
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            note = if (uris.size == 1) "Importing…" else "Importing ${uris.size} files…"
            val (list, message) = withContext(Dispatchers.IO) {
                val all = ArrayList<BrushPreset>()
                val notes = ArrayList<String>()
                for (uri in uris) {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes == null) {
                        notes.add("A file could not be opened.")
                        continue
                    }
                    // Yume's own brush file first, then other apps' formats.
                    val own = runCatching { BrushPresetFile.import(bytes.decodeToString()) }.getOrNull().orEmpty()
                    if (own.isNotEmpty()) {
                        all.addAll(own)
                        notes.add("Imported ${own.size} brush${if (own.size == 1) "" else "es"}.")
                        continue
                    }
                    val name = runCatching {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull() ?: uri.lastPathSegment ?: "Imported"
                    val r = com.yume.app.engine.ForeignBrushes.import(bytes, name, context.cacheDir)
                    all.addAll(r.presets)
                    notes.add(r.note)
                }
                val msg = if (uris.size == 1) notes.firstOrNull().orEmpty()
                else "Imported ${all.size} brush${if (all.size == 1) "" else "es"} from ${uris.size} files." +
                    notes.filter { "could not" in it || "round tip" in it || "900 px" in it }.distinct()
                        .joinToString(" ", prefix = " ").trimEnd()
                all to msg
            }
            if (list.isNotEmpty()) {
                session.importPresets(list)
                com.yume.app.data.BrushPrefs.chooseGroup("Imported")
            }
            note = message
        }
    }

    SectionLabel("Stamp")
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(if (preset.shapeId.isEmpty()) "Import shape…" else "Replace shape…") { shapePicker.launch("image/*") }
        if (preset.shapeId.isNotEmpty()) SmallButton("Round") { session.updatePreset { it.copy(shapeId = "") } }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(if (preset.grainId.isEmpty()) "Import grain…" else "Replace grain…") { grainPicker.launch("image/*") }
        if (preset.grainId.isNotEmpty()) SmallButton("Default grain") { session.updatePreset { it.copy(grainId = "") } }
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("Rotation")
    Spacer(Modifier.height(6.dp))
    SegmentedRow(
        options = listOf("Fixed", "Stroke", "Tilt", "Random"),
        selectedIndex = preset.rotationMode,
        onSelect = { m -> session.updatePreset { it.copy(rotationMode = m) } }
    )
    YumeSlider(
        value = (preset.rotation % 360f + 360f) % 360f,
        onChange = { v -> session.updatePreset { it.copy(rotation = v) } },
        label = "Angle",
        valueLabel = "${preset.rotation.roundToInt()}°",
        min = 0f,
        max = 359f
    )
    if (preset.category == BrushCategory.Brush) {
        YumeSlider(
            value = preset.wetMix,
            onChange = { v -> session.updatePreset { it.copy(wetMix = v) } },
            label = "Wet mix",
            valueLabel = "${(preset.wetMix * 100).roundToInt()}%"
        )
    }
    BrushDynamics(preset, session)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("Export brush…") { exporter.launch("${preset.name}.yumebrush") }
        SmallButton("Import brushes…") { importer.launch(arrayOf("*/*")) }
    }
    note?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    }
}
