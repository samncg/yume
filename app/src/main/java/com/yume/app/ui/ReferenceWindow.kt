package com.yume.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val OPACITY_STEPS = floatArrayOf(1f, 0.6f, 0.3f)

/**
 * A floating reference picture over the canvas. Drag it anywhere to move it,
 * pinch it (or drag the corner grip) to resize it, tap the eye to fade the
 * whole window so the canvas shows through, tap the clear button to remove
 * the picture's background (AI on the tablet; the plain colour key when the
 * model is not there and is not wanted), and long-press to pick a colour.
 */
@Composable
fun ReferenceWindow(
    bitmap: Bitmap,
    onPickColor: (Int) -> Unit,
    onClose: () -> Unit,
    /** Window px below the toolbar: the window stays under it. */
    topInset: Float = 0f
) {
    val density = LocalDensity.current
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var clear by remember { mutableStateOf(false) }
    var keyed by remember(bitmap) { mutableStateOf<ImageBitmap?>(null) }
    // The AI model this picture needs but the tablet does not have yet.
    var needModel by remember(bitmap) { mutableStateOf<com.yume.app.ai.AiModel?>(null) }
    // The user chose the plain colour key instead of downloading.
    var simple by remember(bitmap) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val downloads by com.yume.app.ai.AiDownloads.status.collectAsState()
    val installed = needModel?.let { downloads[it]?.installed } ?: 0
    LaunchedEffect(bitmap, clear, simple, installed) {
        if (!clear || keyed != null) return@LaunchedEffect
        busy = true
        val (img, missing) = withContext(Dispatchers.Default) {
            if (simple) return@withContext keyOutBackground(bitmap).asImageBitmap() to null
            val store = com.yume.app.ai.AiDownloads.storeOrNull()
            val grid = com.yume.app.ai.CutOut.grid(bitmap)
            val model = com.yume.app.ai.CutOut.modelFor(0, grid)
            if (store == null || !store.isReady(model)) return@withContext null to model
            val cut = runCatching { com.yume.app.ai.CutOut.cutOut(store, bitmap) }.getOrNull()
            com.yume.app.ai.Onnx.closeIdle()
            // If the model fails, the plain key still does something useful.
            (cut ?: keyOutBackground(bitmap)).asImageBitmap() to null
        }
        keyed = img
        needModel = missing
        busy = false
    }
    val aspect = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        var x by remember { mutableFloatStateOf(maxW - with(density) { 340.dp.toPx() }) }
        var y by remember { mutableFloatStateOf(with(density) { 120.dp.toPx() }) }
        val top = topInset + with(density) { 8.dp.toPx() }
        // Keep the title bar reachable below the toolbar (two rows when narrow).
        LaunchedEffect(top) { if (y < top) y = top }
        var widthPx by remember { mutableFloatStateOf(with(density) { 300.dp.toPx() }) }
        var opacityStep by remember { mutableIntStateOf(0) }
        val minW = with(density) { 120.dp.toPx() }
        val bar = with(density) { 34.dp.toPx() }
        val imageH = widthPx * aspect
        fun moveBy(dx: Float, dy: Float) {
            x = (x + dx).coerceIn(-widthPx * 0.7f, maxW - widthPx * 0.3f)
            y = (y + dy).coerceIn(top, (maxH - bar).coerceAtLeast(top))
        }
        fun resizeBy(f: Float) {
            val old = widthPx
            widthPx = (widthPx * f).coerceIn(minW, maxW * 0.9f)
            // Grow about the window's centre.
            x -= (widthPx - old) / 2f
            y -= (widthPx - old) * aspect / 2f
        }
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(with(density) { widthPx.toDp() })
                .graphicsLayer { alpha = OPACITY_STEPS[opacityStep] }
                .clip(RoundedCornerShape(14.dp))
                .background(if (clear) Color.Transparent else Color(0xE6202024))
                .claimCanvasTouches()
                // One finger moves the window, two fingers pinch it.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            // The corner grip handles its own drags.
                            if (event.changes.any { it.isConsumed }) continue
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (event.changes.size >= 2 && abs(zoom - 1f) > 0.001f) resizeBy(zoom)
                            if (pan.x != 0f || pan.y != 0f) moveBy(pan.x, pan.y)
                            if (event.changes.any { it.positionChanged() }) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { bar.toDp() })
                    .background(Color(0xCC202024))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (busy) "Cutting out…" else "Reference", style = MaterialTheme.typography.labelMedium, color = YumeColors.textDim)
                Spacer(Modifier.weight(1f))
                YumeIconButton(
                    Glyph.Sparkle, { clear = !clear },
                    selected = clear, size = 28.dp, iconSize = 15.dp, label = "Clear background"
                )
                YumeIconButton(
                    if (opacityStep == 0) Glyph.Eye else Glyph.EyeOff,
                    { opacityStep = (opacityStep + 1) % OPACITY_STEPS.size },
                    size = 28.dp, iconSize = 15.dp, label = "Fade"
                )
                YumeIconButton(Glyph.Close, onClose, size = 28.dp, iconSize = 14.dp, label = "Close reference")
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { imageH.toDp() })
            ) {
                Image(
                    bitmap = if (clear) keyed ?: image else image,
                    contentDescription = "Reference image",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap) {
                            detectTapGestures(onLongPress = { o ->
                                val bx = (o.x / size.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                                val by = (o.y / size.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                                val c = bitmap.getPixel(bx, by)
                                if ((c ushr 24) > 0) onPickColor(c or 0xFF000000.toInt())
                            })
                        }
                )
                // The AI model is not on the tablet yet: offer it, or the plain key.
                val missing = needModel
                if (clear && keyed == null && missing != null) {
                    val st = downloads[missing]
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xEE202024))
                            .padding(10.dp)
                    ) {
                        Text(
                            "Remove the background with AI: a one-time ${missing.megabytes} MB download, then it runs on this tablet.",
                            style = MaterialTheme.typography.bodySmall, color = YumeColors.text
                        )
                        Spacer(Modifier.height(6.dp))
                        if (st?.running == true) {
                            Text("Downloading… ${(st.fraction * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = YumeColors.textDim)
                        } else {
                            st?.error?.let { Text("Download failed: $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE07070)) }
                            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                SmallButton("Download") { com.yume.app.ai.AiDownloads.start(missing) }
                                SmallButton("Simple") { simple = true }
                            }
                        }
                    }
                }
                // Resize grip.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(40.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, d ->
                                change.consume()
                                val nw = (widthPx + max(d.x, d.y / aspect.coerceAtLeast(0.1f))).coerceIn(minW, maxW * 0.9f)
                                widthPx = nw
                            }
                        }
                        .background(Color(0x66000000), RoundedCornerShape(topStart = 12.dp))
                ) {
                    GlyphIcon(Glyph.Grip, YumeColors.ink.copy(alpha = 0xCC / 255f), size = 18.dp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/**
 * Makes the picture's background transparent: the colour shared by its
 * corners (paper, a flat backdrop) fades out with a soft edge, everything
 * that differs from it stays.
 */
private fun keyOutBackground(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    // Average of small corner patches.
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    val patch = max(1, minOf(w, h) / 40)
    for ((cx, cy) in listOf(0 to 0, w - patch to 0, 0 to h - patch, w - patch to h - patch)) {
        for (yy in cy until cy + patch) for (xx in cx until cx + patch) {
            val c = px[yy * w + xx]
            r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
        }
    }
    val kr = (r / n).toInt(); val kg = (g / n).toInt(); val kb = (b / n).toInt()
    val lo = 0.10f
    val hi = 0.24f
    for (i in px.indices) {
        val c = px[i]
        val a = c ushr 24
        if (a == 0) continue
        val dr = ((c shr 16 and 0xFF) - kr) / 255f
        val dg = ((c shr 8 and 0xFF) - kg) / 255f
        val db = ((c and 0xFF) - kb) / 255f
        val d = sqrt((dr * dr + dg * dg + db * db) / 3f)
        val t = ((d - lo) / (hi - lo)).coerceIn(0f, 1f)
        val k = t * t * (3f - 2f * t)
        px[i] = ((a * k).toInt() shl 24) or (c and 0x00FFFFFF)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}
