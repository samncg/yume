package com.yume.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A floating reference picture over the canvas. Drag the title bar to move
 * it, the corner grip to resize, tap the eye to fade it, and long-press the
 * picture to pick its colour.
 */
@Composable
fun ReferenceWindow(
    bitmap: Bitmap,
    onPickColor: (Int) -> Unit,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        var x by remember { mutableFloatStateOf(maxW - with(density) { 340.dp.toPx() }) }
        var y by remember { mutableFloatStateOf(with(density) { 120.dp.toPx() }) }
        var widthPx by remember { mutableFloatStateOf(with(density) { 300.dp.toPx() }) }
        var faded by remember { mutableFloatStateOf(1f) }
        val bar = with(density) { 30.dp.toPx() }
        val imageH = widthPx * aspect
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(with(density) { widthPx.toDp() })
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE6202024))
                .claimCanvasTouches()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { bar.toDp() })
                    .pointerInput(Unit) {
                        detectDragGestures { change, d ->
                            change.consume()
                            x = (x + d.x).coerceIn(-widthPx * 0.6f, maxW - widthPx * 0.4f)
                            y = (y + d.y).coerceIn(0f, maxH - bar)
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reference", style = MaterialTheme.typography.labelMedium, color = YumeColors.textDim)
                Spacer(Modifier.weight(1f))
                YumeIconButton(
                    Glyph.Eye, { faded = if (faded > 0.9f) 0.45f else 1f },
                    size = 26.dp, iconSize = 15.dp, label = "Fade"
                )
                YumeIconButton(Glyph.Close, onClose, size = 26.dp, iconSize = 14.dp, label = "Close reference")
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { imageH.toDp() })
            ) {
                Image(
                    bitmap = image,
                    contentDescription = "Reference image",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = faded }
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
                        .size(28.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, d ->
                                change.consume()
                                widthPx = (widthPx + d.x).coerceIn(with(density) { 140.dp.toPx() }, maxW * 0.8f)
                            }
                        }
                        .background(Color(0x66000000), RoundedCornerShape(topStart = 10.dp))
                ) {
                    GlyphIcon(Glyph.Grip, Color(0xCCFFFFFF), size = 16.dp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
