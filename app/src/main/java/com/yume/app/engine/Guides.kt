package com.yume.app.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Drawing guides: what is drawn over the canvas and what strokes snap to. */
enum class GuideMode(val label: String) {
    Off("Off"),
    Grid("Grid"),
    Isometric("Isometric"),
    Perspective("Perspective"),
    Fisheye("Fisheye"),
    Ellipse("Ellipse"),
    Curve("Curve"),
    Ruler("Ruler")
}

/**
 * Something an assisted stroke can follow: it maps a point onto itself and
 * knows its direction at a point (to pick the family a stroke starts along).
 */
sealed class GuideSnap {
    abstract fun project(x: Float, y: Float, out: FloatArray)
    /** Unit tangent at the point closest to ([x], [y]). */
    abstract fun tangent(x: Float, y: Float, out: FloatArray)

    class Line(val ox: Float, val oy: Float, dx: Float, dy: Float) : GuideSnap() {
        private val l = hypot(dx, dy).coerceAtLeast(1e-6f)
        val ux = dx / l
        val uy = dy / l
        override fun project(x: Float, y: Float, out: FloatArray) {
            val t = (x - ox) * ux + (y - oy) * uy
            out[0] = ox + ux * t
            out[1] = oy + uy * t
        }
        override fun tangent(x: Float, y: Float, out: FloatArray) {
            out[0] = ux; out[1] = uy
        }
    }

    class Circle(val cx: Float, val cy: Float, val r: Float) : GuideSnap() {
        override fun project(x: Float, y: Float, out: FloatArray) {
            val dx = x - cx
            val dy = y - cy
            val l = hypot(dx, dy)
            if (l < 1e-6f) { out[0] = cx + r; out[1] = cy; return }
            out[0] = cx + dx / l * r
            out[1] = cy + dy / l * r
        }
        override fun tangent(x: Float, y: Float, out: FloatArray) {
            val dx = x - cx
            val dy = y - cy
            val l = hypot(dx, dy).coerceAtLeast(1e-6f)
            out[0] = -dy / l; out[1] = dx / l
        }
    }

    /** The ellipse of the guide's shape (centre, rotation, aspect) scaled by [k]. */
    class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float) : GuideSnap() {
        private val c = cos(angle)
        private val s = sin(angle)
        override fun project(x: Float, y: Float, out: FloatArray) {
            // Into the ellipse frame, scaled to a unit circle; nearest by angle
            // (a close fit for strokes, which stay near the ellipse).
            val dx = x - cx
            val dy = y - cy
            val u = (dx * c + dy * s) / rx.coerceAtLeast(1e-3f)
            val v = (-dx * s + dy * c) / ry.coerceAtLeast(1e-3f)
            val a = atan2(v, u)
            val ex = cos(a) * rx
            val ey = sin(a) * ry
            out[0] = cx + ex * c - ey * s
            out[1] = cy + ex * s + ey * c
        }
        override fun tangent(x: Float, y: Float, out: FloatArray) {
            val dx = x - cx
            val dy = y - cy
            val u = (dx * c + dy * s) / rx.coerceAtLeast(1e-3f)
            val v = (-dx * s + dy * c) / ry.coerceAtLeast(1e-3f)
            val a = atan2(v, u)
            val tx = -sin(a) * rx
            val ty = cos(a) * ry
            val wx = tx * c - ty * s
            val wy = tx * s + ty * c
            val l = hypot(wx, wy).coerceAtLeast(1e-6f)
            out[0] = wx / l; out[1] = wy / l
        }
    }

    /** A polyline (the curve guide, sampled) offset sideways by [offset]. */
    class Path(private val pts: FloatArray, private val offset: Float) : GuideSnap() {
        private val n = pts.size / 2
        /** Nearest segment index and its parameter. */
        private fun nearest(x: Float, y: Float): Pair<Int, Float> {
            var best = 0
            var bestT = 0f
            var bestD = Float.MAX_VALUE
            for (i in 0 until n - 1) {
                val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
                val bx = pts[i * 2 + 2]; val by = pts[i * 2 + 3]
                val dx = bx - ax; val dy = by - ay
                val l2 = (dx * dx + dy * dy).coerceAtLeast(1e-9f)
                val t = (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
                val px = ax + dx * t - x
                val py = ay + dy * t - y
                val d = px * px + py * py
                if (d < bestD) { bestD = d; best = i; bestT = t }
            }
            return best to bestT
        }
        override fun project(x: Float, y: Float, out: FloatArray) {
            val (i, t) = nearest(x, y)
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val dx = pts[i * 2 + 2] - ax; val dy = pts[i * 2 + 3] - ay
            val l = hypot(dx, dy).coerceAtLeast(1e-6f)
            out[0] = ax + dx * t - dy / l * offset
            out[1] = ay + dy * t + dx / l * offset
        }
        override fun tangent(x: Float, y: Float, out: FloatArray) {
            val (i, _) = nearest(x, y)
            val dx = pts[i * 2 + 2] - pts[i * 2]; val dy = pts[i * 2 + 3] - pts[i * 2 + 1]
            val l = hypot(dx, dy).coerceAtLeast(1e-6f)
            out[0] = dx / l; out[1] = dy / l
        }

        /** Signed sideways distance of a point from the curve. */
        fun sideOf(x: Float, y: Float): Float {
            val (i, t) = nearest(x, y)
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val dx = pts[i * 2 + 2] - ax; val dy = pts[i * 2 + 3] - ay
            val l = hypot(dx, dy).coerceAtLeast(1e-6f)
            val px = x - (ax + dx * t)
            val py = y - (ay + dy * t)
            return (-dy / l) * px + (dx / l) * py
        }
    }

    companion object {
        /**
         * The circle through three points, or the line through the first two
         * when the three are (nearly) in a row.
         */
        fun through(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): GuideSnap {
            val d = 2f * (ax * (by - py) + bx * (py - ay) + px * (ay - by))
            val span = maxOf(hypot(bx - ax, by - ay), 1f)
            if (abs(d) < 1e-4f * span * span) return Line(ax, ay, bx - ax, by - ay)
            val a2 = ax * ax + ay * ay
            val b2 = bx * bx + by * by
            val p2 = px * px + py * py
            val ux = (a2 * (by - py) + b2 * (py - ay) + p2 * (ay - by)) / d
            val uy = (a2 * (px - bx) + b2 * (ax - px) + p2 * (bx - ax)) / d
            val r = hypot(ax - ux, ay - uy)
            // A huge circle is a line for any stroke.
            if (r > span * 200f) return Line(ax, ay, bx - ax, by - ay)
            return Circle(ux, uy, r)
        }
    }
}

/**
 * Drawing guides for one document, in document pixels: grids, perspective
 * (1-3 points, or curvilinear "fisheye" with 4 or 5), an ellipse, a
 * French curve, or a straightedge. With assisted drawing on, a stroke follows
 * the guide family closest to how it starts.
 */
class Guides(private val width: Int, private val height: Int) {
    @Volatile var mode: GuideMode = GuideMode.Off
    @Volatile var assist: Boolean = true
    @Volatile var gridSize: Float = 128f
    @Volatile var vpCount: Int = 2

    /** Vanishing points (up to 3); the first [vpCount] are used. */
    val vpX = floatArrayOf(width * 0.18f, width * 0.82f, width * 0.5f)
    // The third point starts inside the canvas so its handle can be grabbed.
    val vpY = floatArrayOf(height * 0.42f, height * 0.42f, height * 0.92f)

    /** Ruler ends. */
    val rulerX = floatArrayOf(width * 0.25f, width * 0.75f)
    val rulerY = floatArrayOf(height * 0.5f, height * 0.5f)

    /** Fisheye: centre and radius (the four points sit on this circle); 4 or 5 points. */
    @Volatile var fishX = width * 0.5f
    @Volatile var fishY = height * 0.5f
    @Volatile var fishR = minOf(width, height) * 0.45f
    @Volatile var fishCount = 4

    /** Ellipse: centre, radii, rotation (radians). */
    @Volatile var ellX = width * 0.5f
    @Volatile var ellY = height * 0.5f
    @Volatile var ellRx = minOf(width, height) * 0.3f
    @Volatile var ellRy = minOf(width, height) * 0.15f
    @Volatile var ellAngle = 0f

    /** Curve: four points the curve passes through. */
    val curveX = floatArrayOf(width * 0.15f, width * 0.4f, width * 0.6f, width * 0.85f)
    val curveY = floatArrayOf(height * 0.65f, height * 0.4f, height * 0.6f, height * 0.35f)

    val visible: Boolean get() = mode != GuideMode.Off

    /** Draggable handles of the current mode: (x, y) pairs. */
    fun handleCount(): Int = when (mode) {
        GuideMode.Perspective -> vpCount
        GuideMode.Ruler -> 2
        GuideMode.Fisheye -> 2
        GuideMode.Ellipse -> 3
        GuideMode.Curve -> 4
        else -> 0
    }

    fun handleX(i: Int): Float = when (mode) {
        GuideMode.Ruler -> rulerX[i]
        GuideMode.Fisheye -> if (i == 0) fishX else fishX + fishR
        GuideMode.Ellipse -> when (i) {
            0 -> ellX
            1 -> ellX + ellRx * cos(ellAngle)
            else -> ellX - ellRy * sin(ellAngle)
        }
        GuideMode.Curve -> curveX[i]
        else -> vpX[i]
    }

    fun handleY(i: Int): Float = when (mode) {
        GuideMode.Ruler -> rulerY[i]
        GuideMode.Fisheye -> fishY
        GuideMode.Ellipse -> when (i) {
            0 -> ellY
            1 -> ellY + ellRx * sin(ellAngle)
            else -> ellY + ellRy * cos(ellAngle)
        }
        GuideMode.Curve -> curveY[i]
        else -> vpY[i]
    }

    fun moveHandle(i: Int, x: Float, y: Float) {
        // Vanishing points may sit well off the canvas; keep them findable.
        val m = maxOf(width, height) * 3f
        val cx = x.coerceIn(-m, width + m)
        val cy = y.coerceIn(-m, height + m)
        val minR = 4f
        when (mode) {
            GuideMode.Ruler -> { rulerX[i] = cx; rulerY[i] = cy }
            GuideMode.Fisheye -> if (i == 0) { fishX = cx; fishY = cy } else {
                fishR = hypot(cx - fishX, cy - fishY).coerceAtLeast(minR * 4f)
            }
            GuideMode.Ellipse -> when (i) {
                0 -> { ellX = cx; ellY = cy }
                1 -> {
                    ellRx = hypot(cx - ellX, cy - ellY).coerceAtLeast(minR)
                    ellAngle = atan2(cy - ellY, cx - ellX)
                }
                else -> {
                    // Along the minor axis only.
                    val nx = -sin(ellAngle)
                    val ny = cos(ellAngle)
                    ellRy = abs((cx - ellX) * nx + (cy - ellY) * ny).coerceAtLeast(minR)
                }
            }
            GuideMode.Curve -> { curveX[i] = cx; curveY[i] = cy }
            else -> { vpX[i] = cx; vpY[i] = cy }
        }
    }

    /** The curve guide sampled as a polyline (extended a little past its ends). */
    fun curvePoints(samples: Int = 24): FloatArray {
        val out = ArrayList<Float>()
        fun p(k: Int, axis: Int): Float {
            val a = if (axis == 0) curveX else curveY
            return when {
                k < 0 -> a[0] * 2f - a[1]
                k > 3 -> a[3] * 2f - a[2]
                else -> a[k]
            }
        }
        for (seg in 0 until 3) {
            for (s in 0 until samples) {
                val t = s / samples.toFloat()
                val t2 = t * t
                val t3 = t2 * t
                for (axis in 0..1) {
                    val p0 = p(seg - 1, axis)
                    val p1 = p(seg, axis)
                    val p2 = p(seg + 1, axis)
                    val p3 = p(seg + 2, axis)
                    out.add(0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3))
                }
            }
        }
        out.add(curveX[3]); out.add(curveY[3])
        // Straight extensions so strokes can run past the ends.
        val ext = maxOf(width, height) * 0.5f
        fun extend(ax: Float, ay: Float, bx: Float, by: Float): Pair<Float, Float> {
            val dx = ax - bx; val dy = ay - by
            val l = hypot(dx, dy).coerceAtLeast(1e-3f)
            return (ax + dx / l * ext) to (ay + dy / l * ext)
        }
        val (sx, sy) = extend(out[0], out[1], out[2], out[3])
        val n = out.size
        val (ex, ey) = extend(out[n - 2], out[n - 1], out[n - 4], out[n - 3])
        return (listOf(sx, sy) + out + listOf(ex, ey)).toFloatArray()
    }

    /** Fisheye points: left, right, top, bottom (and centre for 5). */
    fun fishPoints(): FloatArray = floatArrayOf(
        fishX - fishR, fishY, fishX + fishR, fishY,
        fishX, fishY - fishR, fishX, fishY + fishR
    )

    /**
     * What an assisted stroke starting at ([x0], [y0]) may follow. [nearPx]:
     * how close (document px) counts as "on" a ruler or curve.
     */
    fun snapsFrom(x0: Float, y0: Float, nearPx: Float): List<GuideSnap> {
        val out = ArrayList<GuideSnap>()
        fun line(dx: Float, dy: Float) {
            if (hypot(dx, dy) > 1e-3f) out.add(GuideSnap.Line(x0, y0, dx, dy))
        }
        when (mode) {
            GuideMode.Grid -> { line(1f, 0f); line(0f, 1f) }
            GuideMode.Isometric -> {
                line(cos(Math.toRadians(30.0)).toFloat(), sin(Math.toRadians(30.0)).toFloat())
                line(cos(Math.toRadians(150.0)).toFloat(), sin(Math.toRadians(150.0)).toFloat())
                line(0f, 1f)
            }
            GuideMode.Perspective -> {
                for (k in 0 until vpCount) line(vpX[k] - x0, vpY[k] - y0)
                // One and two points keep verticals; one point also keeps
                // horizontals (the classic box view).
                if (vpCount < 3) line(0f, 1f)
                if (vpCount == 1) line(1f, 0f)
            }
            GuideMode.Fisheye -> {
                val p = fishPoints()
                out.add(GuideSnap.through(p[0], p[1], p[2], p[3], x0, y0))
                out.add(GuideSnap.through(p[4], p[5], p[6], p[7], x0, y0))
                if (fishCount == 5) line(x0 - fishX, y0 - fishY)
            }
            GuideMode.Ellipse -> {
                // The guide's ellipse scaled to pass through the start.
                val c = cos(ellAngle)
                val s = sin(ellAngle)
                val dx = x0 - ellX
                val dy = y0 - ellY
                val u = (dx * c + dy * s) / ellRx.coerceAtLeast(1e-3f)
                val v = (-dx * s + dy * c) / ellRy.coerceAtLeast(1e-3f)
                val k = sqrt(u * u + v * v).coerceAtLeast(1e-3f)
                out.add(GuideSnap.Ellipse(ellX, ellY, ellRx * k, ellRy * k, ellAngle))
                // Spokes through the centre.
                line(x0 - ellX, y0 - ellY)
            }
            GuideMode.Curve -> {
                val pts = curvePoints()
                val side = GuideSnap.Path(pts, 0f).sideOf(x0, y0)
                // Near the curve: on it; further: alongside it.
                out.add(GuideSnap.Path(pts, if (abs(side) < nearPx) 0f else side))
            }
            GuideMode.Ruler -> {
                val dx = rulerX[1] - rulerX[0]
                val dy = rulerY[1] - rulerY[0]
                if (rulerDistance(x0, y0) < nearPx) out.add(GuideSnap.Line(rulerX[0], rulerY[0], dx, dy))
                else line(dx, dy)
            }
            GuideMode.Off -> Unit
        }
        return out
    }

    /** Distance from a point to the ruler's line (document px). */
    fun rulerDistance(x: Float, y: Float): Float {
        val ax = rulerX[0]; val ay = rulerY[0]
        val dx = rulerX[1] - ax; val dy = rulerY[1] - ay
        val l = hypot(dx, dy)
        if (l < 1e-3f) return hypot(x - ax, y - ay)
        return abs(dx * (ay - y) - (ax - x) * dy) / l
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("assist", assist)
        put("grid", gridSize.toDouble())
        put("vpCount", vpCount)
        put("vp", JSONArray().apply { for (i in 0 until 3) { put(vpX[i].toDouble()); put(vpY[i].toDouble()) } })
        put("ruler", JSONArray().apply { for (i in 0 until 2) { put(rulerX[i].toDouble()); put(rulerY[i].toDouble()) } })
        put("fish", JSONArray().apply { put(fishX.toDouble()); put(fishY.toDouble()); put(fishR.toDouble()); put(fishCount) })
        put("ellipse", JSONArray().apply {
            put(ellX.toDouble()); put(ellY.toDouble()); put(ellRx.toDouble()); put(ellRy.toDouble()); put(ellAngle.toDouble())
        })
        put("curve", JSONArray().apply { for (i in 0 until 4) { put(curveX[i].toDouble()); put(curveY[i].toDouble()) } })
    }

    fun load(json: JSONObject?) {
        if (json == null) return
        mode = GuideMode.entries.firstOrNull { it.name == json.optString("mode") } ?: GuideMode.Off
        assist = json.optBoolean("assist", true)
        gridSize = json.optDouble("grid", 128.0).toFloat().coerceIn(8f, 4096f)
        vpCount = json.optInt("vpCount", 2).coerceIn(1, 3)
        json.optJSONArray("vp")?.let { a ->
            for (i in 0 until minOf(3, a.length() / 2)) {
                vpX[i] = a.optDouble(i * 2, vpX[i].toDouble()).toFloat()
                vpY[i] = a.optDouble(i * 2 + 1, vpY[i].toDouble()).toFloat()
            }
        }
        json.optJSONArray("ruler")?.let { a ->
            for (i in 0 until minOf(2, a.length() / 2)) {
                rulerX[i] = a.optDouble(i * 2, rulerX[i].toDouble()).toFloat()
                rulerY[i] = a.optDouble(i * 2 + 1, rulerY[i].toDouble()).toFloat()
            }
        }
        json.optJSONArray("fish")?.let { a ->
            if (a.length() >= 4) {
                fishX = a.optDouble(0, fishX.toDouble()).toFloat()
                fishY = a.optDouble(1, fishY.toDouble()).toFloat()
                fishR = a.optDouble(2, fishR.toDouble()).toFloat().coerceAtLeast(16f)
                fishCount = if (a.optInt(3, 4) == 5) 5 else 4
            }
        }
        json.optJSONArray("ellipse")?.let { a ->
            if (a.length() >= 5) {
                ellX = a.optDouble(0, ellX.toDouble()).toFloat()
                ellY = a.optDouble(1, ellY.toDouble()).toFloat()
                ellRx = a.optDouble(2, ellRx.toDouble()).toFloat().coerceAtLeast(4f)
                ellRy = a.optDouble(3, ellRy.toDouble()).toFloat().coerceAtLeast(4f)
                ellAngle = a.optDouble(4, 0.0).toFloat()
            }
        }
        json.optJSONArray("curve")?.let { a ->
            for (i in 0 until minOf(4, a.length() / 2)) {
                curveX[i] = a.optDouble(i * 2, curveX[i].toDouble()).toFloat()
                curveY[i] = a.optDouble(i * 2 + 1, curveY[i].toDouble()).toFloat()
            }
        }
    }
}
