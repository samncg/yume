package com.yume.app.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A recognised or drawn shape, as the path the brush follows. Points are in
 * document pixels; a closed shape repeats its first point at the end.
 */
class ShapePath(val xs: FloatArray, val ys: FloatArray, val closed: Boolean, val kind: String) {
    val size: Int get() = xs.size

    /** The same shape scaled and rotated about ([cx], [cy]). */
    fun transformed(cx: Float, cy: Float, scale: Float, angle: Float): ShapePath {
        val c = cos(angle)
        val s = sin(angle)
        val nx = FloatArray(size)
        val ny = FloatArray(size)
        for (i in 0 until size) {
            val dx = (xs[i] - cx) * scale
            val dy = (ys[i] - cy) * scale
            nx[i] = cx + dx * c - dy * s
            ny[i] = cy + dx * s + dy * c
        }
        return ShapePath(nx, ny, closed, kind)
    }
}

/** Shapes the Shape tool draws from a drag. */
enum class ShapeKind(val label: String) {
    Line("Line"), Rectangle("Rectangle"), Ellipse("Ellipse"), Polygon("Polygon"), Star("Star")
}

object ShapeGeometry {

    /** Ellipse centred on ([cx], [cy]) with radii and rotation, as a closed path. */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, angle: Float): ShapePath {
        val r = max(abs(rx), abs(ry))
        // Dense enough that the brush spacing, not the polygon, sets the look.
        val n = (2f * PI.toFloat() * r / 3f).toInt().coerceIn(32, 2048)
        val xs = FloatArray(n + 1)
        val ys = FloatArray(n + 1)
        val c = cos(angle)
        val s = sin(angle)
        for (i in 0..n) {
            val t = 2.0 * PI * i / n
            val ex = rx * cos(t).toFloat()
            val ey = ry * sin(t).toFloat()
            xs[i] = cx + ex * c - ey * s
            ys[i] = cy + ex * s + ey * c
        }
        return ShapePath(xs, ys, closed = true, kind = if (abs(rx - ry) < 0.5f) "circle" else "ellipse")
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float): ShapePath =
        ShapePath(floatArrayOf(x0, x1), floatArrayOf(y0, y1), closed = false, kind = "line")

    fun polygon(xs: FloatArray, ys: FloatArray, kind: String): ShapePath {
        val n = xs.size
        val ox = FloatArray(n + 1)
        val oy = FloatArray(n + 1)
        for (i in 0 until n) {
            ox[i] = xs[i]
            oy[i] = ys[i]
        }
        ox[n] = xs[0]
        oy[n] = ys[0]
        return ShapePath(ox, oy, closed = true, kind = kind)
    }

    /** Regular polygon (or star) inscribed in a circle of [r], first vertex at [angle]. */
    fun regular(cx: Float, cy: Float, r: Float, sides: Int, angle: Float, star: Boolean): ShapePath {
        val n = sides.coerceIn(3, 24)
        val count = if (star) n * 2 else n
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        for (i in 0 until count) {
            val rr = if (star && i % 2 == 1) r * 0.45f else r
            val t = angle + 2.0 * PI * i / count
            xs[i] = cx + rr * cos(t).toFloat()
            ys[i] = cy + rr * sin(t).toFloat()
        }
        return polygon(xs, ys, if (star) "star" else "polygon")
    }

    /**
     * The Shape tool: a drag from ([x0], [y0]) to ([x1], [y1]). [perfect]
     * forces a square / circle. Rectangles and ellipses fill the drag box;
     * polygons and stars are centred on the press point.
     */
    fun fromDrag(
        kind: ShapeKind,
        x0: Float, y0: Float, x1: Float, y1: Float,
        perfect: Boolean,
        sides: Int
    ): ShapePath {
        var w = x1 - x0
        var h = y1 - y0
        if (perfect && (kind == ShapeKind.Rectangle || kind == ShapeKind.Ellipse)) {
            val m = max(abs(w), abs(h))
            w = if (w < 0) -m else m
            h = if (h < 0) -m else m
        }
        return when (kind) {
            ShapeKind.Line -> line(x0, y0, x1, y1)
            ShapeKind.Rectangle -> polygon(
                floatArrayOf(x0, x0 + w, x0 + w, x0),
                floatArrayOf(y0, y0, y0 + h, y0 + h),
                "rectangle"
            )
            ShapeKind.Ellipse -> ellipse(x0 + w / 2f, y0 + h / 2f, abs(w) / 2f, abs(h) / 2f, 0f)
            ShapeKind.Polygon, ShapeKind.Star -> {
                val r = hypot(x1 - x0, y1 - y0)
                val a = atan2(y1 - y0, x1 - x0)
                regular(x0, y0, r, sides, a, kind == ShapeKind.Star)
            }
        }
    }

    // ---------------------------------------------------------- recognition

    /**
     * QuickShape: what the user meant by a freehand stroke. Open strokes become
     * a line or a polyline; closed ones become a polygon (a rectangle when its
     * corners are square) or an ellipse (a circle when nearly round). Returns
     * null when nothing fits well enough to replace the stroke.
     */
    fun recognize(xs: FloatArray, ys: FloatArray, count: Int): ShapePath? {
        if (count < 4) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var length = 0f
        for (i in 0 until count) {
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
            if (i > 0) length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 8f) return null
        val x0 = xs[0]; val y0 = ys[0]
        val x1 = xs[count - 1]; val y1 = ys[count - 1]
        val gap = hypot(x1 - x0, y1 - y0)
        val closed = gap < 0.22f * diag && length > 2.2f * gap + diag

        if (!closed) {
            // A line when nothing strays far from the chord.
            val dev = maxDeviation(xs, ys, 0, count - 1)
            if (dev < max(3f, 0.07f * gap)) return line(x0, y0, x1, y1)
            // Otherwise a polyline with a few straight segments, if it fits.
            val keep = simplify(xs, ys, count, 0.05f * diag)
            if (keep.size in 3..6) {
                val px = FloatArray(keep.size) { xs[keep[it]] }
                val py = FloatArray(keep.size) { ys[keep[it]] }
                return ShapePath(px, py, closed = false, kind = "polyline")
            }
            return null
        }

        // Closed: compare an ellipse fit with a polygon fit.
        val (rx, ry, ex) = resample(xs, ys, count, 128)
        var cx = 0f; var cy = 0f
        for (i in rx.indices) { cx += rx[i]; cy += ry[i] }
        cx /= rx.size; cy /= ry.size
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in rx.indices) {
            val dx = rx[i] - cx; val dy = ry[i] - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= rx.size; syy /= rx.size; sxy /= rx.size
        val angle = 0.5f * atan2(2f * sxy, sxx - syy)
        val tr = (sxx + syy) / 2f
        val det = sqrt(((sxx - syy) / 2f) * ((sxx - syy) / 2f) + sxy * sxy)
        // Uniform samples on an ellipse have variance a²/2 along each axis.
        var a = sqrt(2f * (tr + det))
        var b = sqrt(2f * max(0f, tr - det))
        // Both fits as a mean distance in pixels from the drawn path.
        val ellipsePx = ellipseError(rx, ry, cx, cy, a, b, angle)

        val keep = simplify(ex.first, ex.second, ex.first.size, 0.06f * diag)
        // A closed loop's simplification keeps the start twice.
        val corners = keep.dropLast(1)
        if (corners.size in 3..6) {
            val px = FloatArray(corners.size) { ex.first[corners[it]] }
            val py = FloatArray(corners.size) { ex.second[corners[it]] }
            val polyPx = polygonError(rx, ry, px, py)
            // A polygon only when its edges fit clearly better than a curve.
            if (polyPx < 0.025f * diag && polyPx * 1.6f < ellipsePx) {
                if (corners.size == 4 && squareCorners(px, py)) return rectangleFrom(px, py)
                return polygon(px, py, if (corners.size == 3) "triangle" else "polygon")
            }
        }
        if (ellipsePx / max(1f, (a + b) / 2f) < 0.16f) {
            if (b / max(a, 0.001f) > 0.9f) {
                val r = (a + b) / 2f
                a = r; b = r
            }
            return ellipse(cx, cy, a, b, angle)
        }
        return null
    }

    private fun maxDeviation(xs: FloatArray, ys: FloatArray, from: Int, to: Int): Float {
        val ax = xs[from]; val ay = ys[from]
        val bx = xs[to]; val by = ys[to]
        val len = hypot(bx - ax, by - ay)
        var dev = 0f
        for (i in from + 1 until to) {
            val d = if (len < 1e-3f) hypot(xs[i] - ax, ys[i] - ay)
            else abs((bx - ax) * (ay - ys[i]) - (ax - xs[i]) * (by - ay)) / len
            if (d > dev) dev = d
        }
        return dev
    }

    /** Ramer-Douglas-Peucker: indices of the points that carry the shape. */
    private fun simplify(xs: FloatArray, ys: FloatArray, count: Int, eps: Float): List<Int> {
        val keep = BooleanArray(count)
        keep[0] = true
        keep[count - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.add(intArrayOf(0, count - 1))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast().let { it[0] to it[1] }
            if (e - s < 2) continue
            val ax = xs[s]; val ay = ys[s]; val bx = xs[e]; val by = ys[e]
            val len = hypot(bx - ax, by - ay)
            var best = -1
            var bestD = 0f
            for (i in s + 1 until e) {
                val d = if (len < 1e-3f) hypot(xs[i] - ax, ys[i] - ay)
                else abs((bx - ax) * (ay - ys[i]) - (ax - xs[i]) * (by - ay)) / len
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0 && bestD > eps) {
                keep[best] = true
                stack.add(intArrayOf(s, best))
                stack.add(intArrayOf(best, e))
            }
        }
        return (0 until count).filter { keep[it] }
    }

    /**
     * Arc-length resampling, so a slow corner does not weigh more than a fast
     * edge. Returns the samples and the samples rotated to start at the point
     * farthest from the centroid (a corner, for polygon simplification).
     */
    private fun resample(xs: FloatArray, ys: FloatArray, count: Int, n: Int):
        Triple<FloatArray, FloatArray, Pair<FloatArray, FloatArray>> {
        val cum = FloatArray(count)
        for (i in 1 until count) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = max(cum[count - 1], 1e-3f)
        val rx = FloatArray(n)
        val ry = FloatArray(n)
        var j = 0
        for (k in 0 until n) {
            val t = total * k / n
            while (j < count - 2 && cum[j + 1] < t) j++
            val seg = max(cum[j + 1] - cum[j], 1e-6f)
            val f = ((t - cum[j]) / seg).coerceIn(0f, 1f)
            rx[k] = xs[j] + (xs[j + 1] - xs[j]) * f
            ry[k] = ys[j] + (ys[j + 1] - ys[j]) * f
        }
        var cx = 0f; var cy = 0f
        for (k in 0 until n) { cx += rx[k]; cy += ry[k] }
        cx /= n; cy /= n
        var far = 0
        var farD = -1f
        for (k in 0 until n) {
            val d = hypot(rx[k] - cx, ry[k] - cy)
            if (d > farD) { farD = d; far = k }
        }
        val ox = FloatArray(n + 1)
        val oy = FloatArray(n + 1)
        for (k in 0..n) {
            ox[k] = rx[(far + k) % n]
            oy[k] = ry[(far + k) % n]
        }
        return Triple(rx, ry, ox to oy)
    }

    private fun ellipseError(
        xs: FloatArray, ys: FloatArray, cx: Float, cy: Float, a: Float, b: Float, angle: Float
    ): Float {
        if (a < 1f || b < 0.5f) return Float.MAX_VALUE
        val c = cos(-angle)
        val s = sin(-angle)
        var err = 0f
        for (i in xs.indices) {
            val dx = xs[i] - cx
            val dy = ys[i] - cy
            val ux = dx * c - dy * s
            val uy = dx * s + dy * c
            val r = sqrt((ux / a) * (ux / a) + (uy / b) * (uy / b))
            // Radial distance to the ellipse, in pixels.
            err += abs(r - 1f) * hypot(ux, uy) / max(r, 1e-3f)
        }
        return err / xs.size
    }

    private fun polygonError(xs: FloatArray, ys: FloatArray, px: FloatArray, py: FloatArray): Float {
        var err = 0f
        for (i in xs.indices) {
            var best = Float.MAX_VALUE
            for (k in px.indices) {
                val ax = px[k]; val ay = py[k]
                val bx = px[(k + 1) % px.size]; val by = py[(k + 1) % py.size]
                best = min(best, segmentDistance(xs[i], ys[i], ax, ay, bx, by))
            }
            err += best
        }
        return err / xs.size
    }

    private fun segmentDistance(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6f) 0f else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (ax + dx * t), y - (ay + dy * t))
    }

    private fun squareCorners(px: FloatArray, py: FloatArray): Boolean {
        for (k in 0 until 4) {
            val ax = px[(k + 3) % 4] - px[k]; val ay = py[(k + 3) % 4] - py[k]
            val bx = px[(k + 1) % 4] - px[k]; val by = py[(k + 1) % 4] - py[k]
            val cos = (ax * bx + ay * by) / max(1e-3f, hypot(ax, ay) * hypot(bx, by))
            if (abs(cos) > 0.26f) return false // within ~15° of square
        }
        return true
    }

    /** A true rectangle through four rough corners: their mean axes and extents. */
    private fun rectangleFrom(px: FloatArray, py: FloatArray): ShapePath {
        val cx = px.average().toFloat()
        val cy = py.average().toFloat()
        val angle = atan2(py[1] - py[0], px[1] - px[0])
        val c = cos(-angle)
        val s = sin(-angle)
        var hw = 0f
        var hh = 0f
        for (k in 0 until 4) {
            val dx = px[k] - cx
            val dy = py[k] - cy
            hw += abs(dx * c - dy * s)
            hh += abs(dx * s + dy * c)
        }
        hw /= 4f
        hh /= 4f
        val cc = cos(angle)
        val ss = sin(angle)
        val lx = floatArrayOf(-hw, hw, hw, -hw)
        val ly = floatArrayOf(-hh, -hh, hh, hh)
        return polygon(
            FloatArray(4) { cx + lx[it] * cc - ly[it] * ss },
            FloatArray(4) { cy + lx[it] * ss + ly[it] * cc },
            "rectangle"
        )
    }
}
