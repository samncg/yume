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
 * whole window so the canvas shows through, tap the clear button to drop the
 * picture's flat background, and long-press the picture to pick a colour.
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
    LaunchedEffect(bitmap, clear) {
        if (clear && keyed == null) keyed = withContext(Dispatchers.Default) { keyOutBackground(bitmap).asImageBitmap() }
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
                Text("Reference", style = MaterialTheme.typography.labelMedium, color = YumeColors.textDim)
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
                    GlyphIcon(Glyph.Grip, Color(0xCCFFFFFF), size = 18.dp, modifier = Modifier.align(Alignment.Center))
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
