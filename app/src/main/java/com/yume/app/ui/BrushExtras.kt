package com.yume.app.ui

import android.graphics.Bitmap
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
        val pressure = sin(t * Math.PI.toFloat()).coerceIn(0.15f, 1f)
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
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BrushPresetFile.import(it.readBytes().decodeToString()) }
                }.getOrNull().orEmpty()
            }
            if (list.isEmpty()) note = "That file is not a Yume brush."
            else {
                session.importPresets(list)
                note = "Imported ${list.size} brush${if (list.size == 1) "" else "es"}."
            }
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
