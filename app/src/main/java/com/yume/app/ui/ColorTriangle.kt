package com.yume.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Hue ring around a saturation/brightness triangle (the classic "triangle"
 * picker). The triangle's corners are the pure hue, white and black; it turns
 * with the hue so the pure-hue corner always points at the ring position.
 * The fill is drawn with per-vertex colours, which interpolate exactly the
 * colour = sv * hue + v(1-s) * white model the picker maps back from.
 */
@Composable
fun ColorTriangle(
    hue: Float,
    sat: Float,
    value: Float,
    size: Dp,
    onHue: (Float) -> Unit,
    onSv: (Float, Float) -> Unit
) {
    val hueNow = rememberUpdatedState(hue)
    val spectrum = listOf(
        Color(0xFFFF0000), Color(0xFFFF00FF), Color(0xFF0000FF), Color(0xFF00FFFF),
        Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFFF0000)
    )
    Canvas(
        Modifier
            .size(size)
            .pointerInput(Unit) {
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val outer = minOf(c.x, c.y)
                val ring = outer * 0.16f
                val r = outer - ring - outer * 0.06f
                fun angleOf(p: Offset) = Math.toDegrees(atan2(-(p.y - c.y), p.x - c.x).toDouble()).toFloat()
                fun huePick(p: Offset) = onHue(((angleOf(p) % 360f) + 360f) % 360f)
                fun svPick(p: Offset) {
                    val (ph, pw, pb) = corners(c, r, hueNow.value)
                    // Barycentric weights, clamped onto the triangle.
                    val d = (pw.y - pb.y) * (ph.x - pb.x) + (pb.x - pw.x) * (ph.y - pb.y)
                    var wh = ((pw.y - pb.y) * (p.x - pb.x) + (pb.x - pw.x) * (p.y - pb.y)) / d
                    var ww = ((pb.y - ph.y) * (p.x - pb.x) + (ph.x - pb.x) * (p.y - pb.y)) / d
                    wh = wh.coerceAtLeast(0f)
                    ww = ww.coerceAtLeast(0f)
                    var wb = (1f - wh - ww).coerceAtLeast(0f)
                    val sum = wh + ww + wb
                    wh /= sum; ww /= sum; wb /= sum
                    val v = (wh + ww).coerceIn(0f, 1f)
                    val s = if (v > 0.0001f) (wh / v).coerceIn(0f, 1f) else 0f
                    onSv(s, v)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val onRing = hypot(down.position.x - c.x, down.position.y - c.y) > r + outer * 0.03f
                    if (onRing) huePick(down.position) else svPick(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        if (onRing) huePick(change.position) else svPick(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val outer = minOf(c.x, c.y)
        val ring = outer * 0.16f
        val r = outer - ring - outer * 0.06f
        drawCircle(
            brush = Brush.sweepGradient(spectrum, c),
            radius = outer - ring / 2f,
            center = c,
            style = Stroke(ring)
        )
        val (ph, pw, pb) = corners(c, r, hue)
        val pure = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply { isAntiAlias = true }
            canvas.nativeCanvas.drawVertices(
                android.graphics.Canvas.VertexMode.TRIANGLES, 6,
                floatArrayOf(ph.x, ph.y, pw.x, pw.y, pb.x, pb.y), 0,
                null, 0,
                intArrayOf(pure, 0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0,
                null, 0, 0, paint
            )
        }
        // Ring marker.
        val a = Math.toRadians(hue.toDouble())
        val m = Offset(c.x + ((outer - ring / 2f) * cos(a)).toFloat(), c.y - ((outer - ring / 2f) * sin(a)).toFloat())
        drawCircle(Color.White, ring * 0.42f, m, style = Stroke(3f))
        // Triangle marker.
        val wh = sat * value
        val ww = value * (1f - sat)
        val wb = 1f - value
        val t = Offset(ph.x * wh + pw.x * ww + pb.x * wb, ph.y * wh + pw.y * ww + pb.y * wb)
        drawCircle(Color.Black.copy(alpha = 0.5f), 9f, t, style = Stroke(5f))
        drawCircle(Color.White, 9f, t, style = Stroke(2.5f))
    }
}

/** Pure-hue, white and black corners for a triangle of circumradius [r]. */
private fun corners(c: Offset, r: Float, hue: Float): Triple<Offset, Offset, Offset> {
    fun at(deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(c.x + (r * cos(a)).toFloat(), c.y - (r * sin(a)).toFloat())
    }
    return Triple(at(hue), at(hue + 120f), at(hue + 240f))
}
