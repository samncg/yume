package com.yume.app.ui

import androidx.compose.foundation.layout.padding
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yume.app.ai.AiDownloads
import com.yume.app.ai.AiModel
import com.yume.app.ai.BgSettings
import com.yume.app.ai.UpscaleJob
import com.yume.app.ai.UpscaleOptions
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.EditorUiState
import com.yume.app.engine.MemoryBudget
import kotlin.math.roundToInt

/** Which AI page was open, so the panel reopens there. */
private var lastAiPage = 0

/**
 * The AI tab: background removal and upscaling, both on the tablet. A model
 * is downloaded once, the first time its tool needs it.
 */
@Composable
internal fun AiTab(
    state: EditorUiState,
    session: DrawingSession,
    controller: EditorController?,
    onOpenProject: (String) -> Unit
) {
    val cutting = state.cutOutPhase != DrawingSession.CutOutPhase.None
    var page by remember { mutableIntStateOf(if (cutting) 1 else lastAiPage) }
    LaunchedEffect(cutting) { if (cutting) page = 1 }
    fun go(p: Int) {
        page = p
        lastAiPage = p
    }
    when (page) {
        1 -> BackgroundPage(state, session) {
            session.cancelCutOut()
            go(0)
        }
        2 -> if (controller != null) UpscalePage(state, controller, onOpenProject) { go(0) } else go(0)
        else -> AiHome(state, session, controller != null) { p ->
            if (p == 1) session.startCutOut(state.cutOutSettings)
            go(p)
        }
    }
}

@Composable
private fun AiHome(state: EditorUiState, session: DrawingSession, canUpscale: Boolean, open: (Int) -> Unit) {
    val samples = remember { aiSamples() }
    Text(
        "Runs entirely on this tablet. Each model downloads once, the first time a tool needs it.",
        style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SampleTile("Remove background", samples[0], 1.4f, Modifier.weight(1f), false, background = Color(0xFF9A9AA2)) { open(1) }
        if (canUpscale) {
            SampleTile("Upscale", samples[1], 1.4f, Modifier.weight(1f), false, background = Color(0xFF9A9AA2)) { open(2) }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(14.dp))
    SectionLabel("Models")
    Spacer(Modifier.height(6.dp))
    val status by AiDownloads.status.collectAsState()
    for (m in AiModel.entries) {
        val st = status[m]
        val ready = remember(st?.installed, st?.running) { AiDownloads.isReady(m) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.label, style = MaterialTheme.typography.bodySmall, color = YumeColors.text)
                Text(
                    when {
                        st?.running == true -> "Downloading… ${(st.fraction * 100).roundToInt()}%"
                        ready -> "On this tablet · ${m.megabytes} MB"
                        else -> "Not downloaded · ${m.megabytes} MB"
                    },
                    style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
                )
            }
            if (ready) SmallButton("Delete") { AiDownloads.delete(m) }
            else if (st?.running == true) SmallButton("Stop") { AiDownloads.cancel(m) }
        }
        Spacer(Modifier.height(6.dp))
    }
    if (com.yume.app.ai.Onnx.npuSupported) {
        Spacer(Modifier.height(4.dp))
        NpuRow()
        Spacer(Modifier.height(6.dp))
    }
    Text(
        "Models: IS-Net (Apache-2.0), Real-ESRGAN (BSD-3-Clause). Nothing you draw leaves the tablet.",
        style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
    )
}

/** The Snapdragon NPU: on or off, and what it is doing. */
@Composable
private fun NpuRow() {
    var on by remember { mutableStateOf(com.yume.app.ai.Onnx.npuWanted && !com.yume.app.ai.Onnx.npuStruckOut) }
    val preparing by com.yume.app.ai.Onnx.preparing.collectAsState()
    ToggleRow(Glyph.Sparkle, "Use the NPU (much faster)", on) {
        on = it
        com.yume.app.ai.Onnx.setNpu(it)
        if (it) {
            val store = AiDownloads.storeOrNull()
            if (store != null) com.yume.app.ai.Onnx.warmUp(AiModel.entries.filter { m -> store.isReady(m) }.map { m -> store.file(m) })
        }
    }
    val model = preparing?.let { name -> AiModel.entries.firstOrNull { it.name == name } }
    Text(
        when {
            model != null -> "Preparing ${model.label} for the NPU. This happens once per model and can take up to a minute."
            com.yume.app.ai.Onnx.npuStruckOut -> "Turned off after a problem with the NPU. Turn it on to try again."
            on -> "The tools run on this tablet's Snapdragon NPU, many times faster than the CPU."
            else -> "The tools run on the CPU."
        },
        style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim,
        modifier = Modifier.padding(start = 8.dp)
    )
}

/** Download card for [model]; calls [onReady] once it is installed. */
@Composable
private fun ModelNeeded(model: AiModel, why: String, onReady: () -> Unit) {
    val status by AiDownloads.status.collectAsState()
    val st = status[model]
    LaunchedEffect(st?.installed) { if (AiDownloads.isReady(model)) onReady() }
    Text(why, style = MaterialTheme.typography.bodySmall, color = YumeColors.text)
    Spacer(Modifier.height(4.dp))
    Text(
        "${model.purpose}: a one-time download of ${model.megabytes} MB. It runs entirely on this tablet.",
        style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
    )
    Spacer(Modifier.height(8.dp))
    if (st?.running == true) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { st.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = YumeColors.accent,
            trackColor = YumeColors.track
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${(st.done / 1_048_576L)} of ${model.megabytes} MB",
                style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim, modifier = Modifier.weight(1f)
            )
            SmallButton("Stop") { AiDownloads.cancel(model) }
        }
    } else {
        st?.error?.let {
            Text("Download failed: $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE07070))
            Spacer(Modifier.height(6.dp))
        }
        SmallButton(if (st?.error != null) "Try again" else "Download") { AiDownloads.start(model) }
    }
}

@Composable
private fun BackgroundPage(state: EditorUiState, session: DrawingSession, onBack: () -> Unit) {
    EditingTitle("Remove background", onBack)
    val s = state.cutOutSettings
    fun set(n: BgSettings) = session.setCutOutSettings(n)
    when (state.cutOutPhase) {
        DrawingSession.CutOutPhase.None -> {
            Text("Choose a layer with a picture on it, then try again.", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
            Spacer(Modifier.height(8.dp))
            SmallButton("Start") { session.startCutOut(s) }
        }
        DrawingSession.CutOutPhase.NeedsModel -> {
            val model = state.cutOutModel ?: return
            ModelNeeded(
                model,
                if (model == AiModel.SegmentIllustration) "This looks like a drawing." else "This looks like a photo."
            ) { session.startCutOut(s) }
            Spacer(Modifier.height(10.dp))
            Text("Picture type", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
            Spacer(Modifier.height(4.dp))
            SegmentedRow(listOf("Auto", "Drawing", "Photo"), s.subject, { set(s.copy(subject = it)) })
            Spacer(Modifier.height(8.dp))
            SmallButton("Cancel") { onBack() }
        }
        DrawingSession.CutOutPhase.Finding -> {
            Text("Finding the subject…", style = MaterialTheme.typography.bodySmall, color = YumeColors.text)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(), color = YumeColors.accent, trackColor = YumeColors.track
            )
            Spacer(Modifier.height(8.dp))
            SmallButton("Cancel") { onBack() }
        }
        DrawingSession.CutOutPhase.Failed -> {
            Text(state.cutOutError ?: "Something went wrong.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE07070))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Try again") { session.startCutOut(s) }
                SmallButton("Cancel") { onBack() }
            }
        }
        DrawingSession.CutOutPhase.Ready -> BackgroundControls(state, session, onBack)
    }
}

@Composable
private fun BackgroundControls(state: EditorUiState, session: DrawingSession, onBack: () -> Unit) {
    val s = state.cutOutSettings
    fun set(n: BgSettings) = session.setCutOutSettings(n)
    val longEdge = maxOf(state.width, state.height).coerceAtLeast(1)
    state.cutOutNote?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = YumeColors.accent)
        Spacer(Modifier.height(8.dp))
    }
    Text("Picture type", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(listOf("Auto", "Drawing", "Photo"), s.subject, { set(s.copy(subject = it)) })
    Spacer(Modifier.height(8.dp))
    Text("Keep", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(listOf("Subject", "Background"), if (s.invert) 1 else 0, { set(s.copy(invert = it == 1)) })
    Spacer(Modifier.height(8.dp))
    YumeSlider(value = s.threshold, onChange = { set(s.copy(threshold = it)) }, label = "Cut-off",
        valueLabel = "${(s.threshold * 100).roundToInt()}%", min = 0.05f, max = 0.95f, resetTo = 0.5f)
    YumeSlider(value = s.softness, onChange = { set(s.copy(softness = it)) }, label = "Soft edge",
        valueLabel = "${(s.softness * 100).roundToInt()}%", resetTo = 0.35f)
    val growMax = (longEdge * 0.01f).coerceIn(4f, 40f)
    YumeSlider(value = s.grow, onChange = { set(s.copy(grow = it)) }, label = "Grow / shrink",
        valueLabel = "${if (s.grow > 0.5f) "+" else ""}${s.grow.roundToInt()} px", min = -growMax, max = growMax, resetTo = 0f)
    YumeSlider(value = s.feather, onChange = { set(s.copy(feather = it)) }, label = "Feather",
        valueLabel = "${s.feather.roundToInt()} px", min = 0f, max = growMax, resetTo = 0f)
    YumeSlider(value = s.specks, onChange = { set(s.copy(specks = it)) }, label = "Clean up specks",
        valueLabel = "${(s.specks * 1000).roundToInt() / 10f}%", min = 0f, max = 0.02f, resetTo = 0.002f)
    Spacer(Modifier.height(6.dp))
    Text("Mark the picture", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(listOf("Off", "Keep", "Remove"), state.cutOutBrush, { session.setCutOutBrush(it) })
    if (state.cutOutBrush != 0) {
        YumeSlider(value = state.cutOutBrushSize, onChange = { session.setCutOutBrush(state.cutOutBrush, it) },
            label = "Mark size", valueLabel = "${state.cutOutBrushSize.roundToInt()}", min = 6f, max = 120f, resetTo = 28f)
    }
    Text(
        if (state.cutOutBrush == 0) "Choose Keep or Remove, then paint over a part: the whole part follows. Two fingers move the canvas."
        else "Paint over what to ${if (state.cutOutBrush == 1) "keep" else "remove"}. Two fingers move the canvas.",
        style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
    )
    if (state.cutOutMarks > 0) {
        Spacer(Modifier.height(6.dp))
        SmallButton("Clear marks") { session.clearCutOutMarks() }
    }
    Spacer(Modifier.height(10.dp))
    var output by remember { mutableIntStateOf(lastCutOutOutput) }
    Text("Result", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(listOf("Mask", "Erase", "New layer"), output, { output = it; lastCutOutOutput = it })
    Text(
        when (output) {
            0 -> "Hides the background with a layer mask: paint the mask to fix it later."
            1 -> "Erases the background pixels from this layer."
            else -> "Copies the subject to a new layer and hides this one."
        },
        style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("Apply") { session.applyCutOut(output) }
        SmallButton("Cancel") { onBack() }
    }
}

private var lastCutOutOutput = 0

@Composable
private fun UpscalePage(state: EditorUiState, controller: EditorController, onOpenProject: (String) -> Unit, onBack: () -> Unit) {
    val progress by controller.upscale.collectAsState()
    var opts by remember { mutableStateOf(lastUpscale) }
    fun set(o: UpscaleOptions) {
        opts = o
        lastUpscale = o
    }
    EditingTitle("Upscale") {
        controller.clearUpscale()
        onBack()
    }
    val p = progress
    if (p != null && !p.done) {
        Text("Upscaling… ${(p.fraction * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = YumeColors.text)
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { p.fraction }, modifier = Modifier.fillMaxWidth(),
            color = YumeColors.accent, trackColor = YumeColors.track
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "You can keep painting; the copy is made from the canvas as it was when you started.",
            style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
        )
        Spacer(Modifier.height(8.dp))
        SmallButton("Cancel") { controller.cancelUpscale() }
        return
    }
    if (p != null && p.done) {
        if (p.resultId != null) {
            Text("Saved as a new canvas in the gallery. This one is unchanged.", style = MaterialTheme.typography.bodySmall, color = YumeColors.text)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Open it") {
                    controller.clearUpscale()
                    onOpenProject(p.resultId)
                }
                SmallButton("Done") { controller.clearUpscale() }
            }
        } else {
            Text("Upscaling stopped: ${p.error}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE07070))
            Spacer(Modifier.height(8.dp))
            SmallButton("OK") { controller.clearUpscale() }
        }
        return
    }
    Text("Scale", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(listOf("2×", "3×", "4×"), opts.factor - 2, { set(opts.copy(factor = it + 2)) })
    Spacer(Modifier.height(8.dp))
    Text("Method", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
    Spacer(Modifier.height(4.dp))
    SegmentedRow(listOf("AI fast", "AI detail", "Smooth", "Pixel art"), opts.method, { set(opts.copy(method = it)) })
    Spacer(Modifier.height(4.dp))
    Text(
        when (opts.method) {
            0 -> "Real-ESRGAN General: sharp, clean lines and textures. Good for most drawings and photos."
            1 -> "Real-ESRGAN x4plus: more invented detail for photos and painterly work. Much slower."
            2 -> "Plain smooth scaling: no AI, nothing invented, instant."
            else -> "Each pixel becomes a crisp square: for pixel art."
        },
        style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
    )
    if (opts.method <= 1) {
        Spacer(Modifier.height(6.dp))
        YumeSlider(value = opts.strength, onChange = { set(opts.copy(strength = it)) }, label = "AI strength",
            valueLabel = "${(opts.strength * 100).roundToInt()}%", min = 0f, max = 1f, resetTo = 1f)
    }
    val newW = state.width * opts.factor
    val newH = state.height * opts.factor
    val fits = MemoryBudget.fitsDocument(newW, newH, state.layers.size.coerceAtLeast(1))
    Spacer(Modifier.height(6.dp))
    Text("Result: $newW × $newH px, saved as a new canvas (this one stays as it is).",
        style = MaterialTheme.typography.bodySmall, color = if (fits) YumeColors.textDim else Color(0xFFE07070))
    if (!fits) {
        Text(
            if (newW > MemoryBudget.MAX_SPARSE_EDGE || newH > MemoryBudget.MAX_SPARSE_EDGE) "Too big: canvases go up to ${MemoryBudget.MAX_SPARSE_EDGE} px. Choose a smaller scale."
            else "Not enough free storage for a canvas this size.",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFFE07070)
        )
    }
    val model = opts.model
    if (model != null) {
        val stride = 104f
        val tiles = (kotlin.math.ceil(state.width / stride) * kotlin.math.ceil(state.height / stride) * state.layers.size.coerceAtLeast(1)).toInt()
        val onNpu = remember(model) { AiDownloads.storeOrNull()?.let { com.yume.app.ai.Onnx.npuReady(it.file(model)) } == true }
        val secs = tiles * UpscaleJob.secondsPerTile(opts.method, onNpu)
        Text(
            "Takes up to about ${formatDuration(secs)} (empty areas are quick)" +
                if (opts.method == 1 && secs > 600) ". AI fast is much quicker." else ".",
            style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim
        )
    }
    Spacer(Modifier.height(10.dp))
    var ready by remember(model) { mutableStateOf(model == null || AiDownloads.isReady(model)) }
    if (!ready && model != null) {
        ModelNeeded(model, "This method needs its model.") { ready = true }
        return
    }
    SmallButton("Upscale", enabled = fits) { controller.startUpscale(opts) }
}

private var lastUpscale = UpscaleOptions()

/** Tile pictures: a figure on a checkerboard, and blocky vs smooth. */
private fun aiSamples(): List<Bitmap> {
    val w = 210
    val h = 150
    val a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    run {
        val c = Canvas(a)
        val sq = 15
        val p = Paint()
        for (y in 0 until h / sq + 1) for (x in 0 until w / sq + 1) {
            p.color = if ((x + y) % 2 == 0) 0xFFDCDCE0.toInt() else 0xFFF6F6F8.toInt()
            c.drawRect((x * sq).toFloat(), (y * sq).toFloat(), ((x + 1) * sq).toFloat(), ((y + 1) * sq).toFloat(), p)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE07A5F.toInt() }
        val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2CDAE.toInt() }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202020.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
        c.drawRoundRect(w / 2f - 30f, 70f, w / 2f + 30f, h + 10f, 14f, 14f, fill)
        c.drawRoundRect(w / 2f - 30f, 70f, w / 2f + 30f, h + 10f, 14f, 14f, ink)
        c.drawCircle(w / 2f, 46f, 26f, skin)
        c.drawCircle(w / 2f, 46f, 26f, ink)
    }
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    run {
        val small = Bitmap.createBitmap(14, 10, Bitmap.Config.ARGB_8888)
        val sc = Canvas(small)
        sc.drawColor(0xFF3D5AA8.toInt())
        sc.drawCircle(7f, 5f, 3.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2C14E.toInt() })
        val c = Canvas(b)
        // Left half blocky, right half smooth.
        c.save()
        c.clipRect(0f, 0f, w / 2f, h.toFloat())
        c.drawBitmap(small, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint().apply { isFilterBitmap = false })
        c.restore()
        c.save()
        c.clipRect(w / 2f, 0f, w.toFloat(), h.toFloat())
        c.drawColor(0xFF3D5AA8.toInt())
        c.drawCircle(w / 2f, h / 2f, 54f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2C14E.toInt() })
        c.restore()
        c.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), Paint().apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2f })
        small.recycle()
    }
    return listOf(a, b)
}
