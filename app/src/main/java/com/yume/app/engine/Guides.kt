package com.yume.app.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** Drawing guides: what is drawn over the canvas and what strokes snap to. */
enum class GuideMode(val label: String) { Off("Off"), Grid("Grid"), Perspective("Perspective"), Ruler("Ruler") }

/**
 * Drawing guides for one document, in document pixels: a square grid, a
 * one-, two- or three-point perspective with draggable vanishing points, or a
 * straightedge ruler with two draggable ends. With assisted drawing on, a
 * stroke locks to the guide direction closest to how it starts.
 */
class Guides(private val width: Int, private val height: Int) {
    @Volatile var mode: GuideMode = GuideMode.Off
    @Volatile var assist: Boolean = true
    @Volatile var gridSize: Float = 128f
    @Volatile var vpCount: Int = 2

    /** Vanishing points (up to 3); the first [vpCount] are used. */
    val vpX = floatArrayOf(width * 0.18f, width * 0.82f, width * 0.5f)
    val vpY = floatArrayOf(height * 0.42f, height * 0.42f, height * 2.4f)

    /** Ruler ends. */
    val rulerX = floatArrayOf(width * 0.25f, width * 0.75f)
    val rulerY = floatArrayOf(height * 0.5f, height * 0.5f)

    val visible: Boolean get() = mode != GuideMode.Off

    /** Draggable handles of the current mode: (x, y) pairs. */
    fun handleCount(): Int = when (mode) {
        GuideMode.Perspective -> vpCount
        GuideMode.Ruler -> 2
        else -> 0
    }

    fun handleX(i: Int): Float = if (mode == GuideMode.Ruler) rulerX[i] else vpX[i]
    fun handleY(i: Int): Float = if (mode == GuideMode.Ruler) rulerY[i] else vpY[i]

    fun moveHandle(i: Int, x: Float, y: Float) {
        // Vanishing points may sit well off the canvas; keep them findable.
        val m = maxOf(width, height) * 3f
        val cx = x.coerceIn(-m, width + m)
        val cy = y.coerceIn(-m, height + m)
        if (mode == GuideMode.Ruler) {
            rulerX[i] = cx; rulerY[i] = cy
        } else {
            vpX[i] = cx; vpY[i] = cy
        }
    }

    /**
     * Where an assisted stroke from ([x0], [y0]) may go: unit directions, and
     * for the ruler, whether the stroke starts close enough to the ruler to be
     * drawn *on* it (the straightedge) rather than parallel to it.
     */
    fun directionsFrom(x0: Float, y0: Float): FloatArray {
        val out = ArrayList<Float>()
        fun add(dx: Float, dy: Float) {
            val l = hypot(dx, dy)
            if (l > 1e-3f) { out.add(dx / l); out.add(dy / l) }
        }
        when (mode) {
            GuideMode.Grid -> { add(1f, 0f); add(0f, 1f) }
            GuideMode.Perspective -> {
                for (k in 0 until vpCount) add(vpX[k] - x0, vpY[k] - y0)
                // One and two points keep verticals; one point also keeps
                // horizontals (the classic box view).
                if (vpCount < 3) add(0f, 1f)
                if (vpCount == 1) add(1f, 0f)
            }
            GuideMode.Ruler -> add(rulerX[1] - rulerX[0], rulerY[1] - rulerY[0])
            GuideMode.Off -> Unit
        }
        return out.toFloatArray()
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
    }
}
