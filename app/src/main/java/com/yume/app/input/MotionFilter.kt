package com.yume.app.input

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max

/**
 * Motion filtering for the pen path: a 2D "one euro" filter. Its cut-off
 * frequency follows the pen's speed, so slow, careful movement (where hand
 * tremor shows) is smoothed hard, while fast strokes pass almost unfiltered
 * and do not lag. [strength] 0 turns it off.
 */
class MotionFilter {
    var strength: Float = 0f

    private var init = false
    private var x = 0.0
    private var y = 0.0
    private var dx = 0.0
    private var dy = 0.0
    private var t = 0L

    fun reset(x0: Float, y0: Float, timeMs: Long) {
        init = true
        x = x0.toDouble()
        y = y0.toDouble()
        dx = 0.0
        dy = 0.0
        t = timeMs
    }

    /** Filters one sample in place in [out] (x, y). */
    fun filter(px: Float, py: Float, timeMs: Long, out: FloatArray) {
        if (strength <= 0.001f || !init) {
            if (!init) reset(px, py, timeMs)
            out[0] = px
            out[1] = py
            return
        }
        // Samples can share a timestamp (batched history): assume ~4 ms apart.
        val te = max(0.002, (timeMs - t) / 1000.0)
        t = timeMs
        val s = strength.toDouble()
        // Minimum cut-off: 9 Hz (barely any smoothing) down to 0.7 Hz.
        val minCutoff = 9.0 * (1 - s) + 0.7 * s
        val beta = 0.012
        val dCutoff = 1.2
        val ad = alpha(te, dCutoff)
        val rdx = (px - x) / te
        val rdy = (py - y) / te
        dx = ad * rdx + (1 - ad) * dx
        dy = ad * rdy + (1 - ad) * dy
        val cutoff = minCutoff + beta * hypot(dx, dy)
        val a = alpha(te, cutoff)
        x = a * px + (1 - a) * x
        y = a * py + (1 - a) * y
        out[0] = x.toFloat()
        out[1] = y.toFloat()
    }

    /** The filtered position (after the last sample). */
    val lastX: Float get() = x.toFloat()
    val lastY: Float get() = y.toFloat()

    private fun alpha(te: Double, cutoff: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / te)
    }
}

/**
 * Predicts where the pen will be a little ahead in time from its recent
 * (filtered) samples, so the screen can show the stroke tip where the pen
 * is rather than where the last processed sample was.
 */
class StrokePredictor {
    private val ts = LongArray(6)
    private val xs = FloatArray(6)
    private val ys = FloatArray(6)
    private var n = 0

    fun reset() {
        n = 0
    }

    fun add(x: Float, y: Float, timeMs: Long) {
        if (n == ts.size) {
            System.arraycopy(ts, 1, ts, 0, n - 1)
            System.arraycopy(xs, 1, xs, 0, n - 1)
            System.arraycopy(ys, 1, ys, 0, n - 1)
            n--
        }
        ts[n] = timeMs
        xs[n] = x
        ys[n] = y
        n++
    }

    /**
     * The predicted point [aheadMs] after the newest sample, limited to
     * [maxDist] px, or null without enough motion to predict from.
     */
    fun predict(aheadMs: Float, maxDist: Float, out: FloatArray): Boolean {
        if (n < 3) return false
        val dt = (ts[n - 1] - ts[0]).toFloat()
        if (dt <= 0f || dt > 120f) return false
        // Average velocity over the window, with the newest half weighted in
        // twice: responsive, but not thrown by one jittery sample.
        val vx = (xs[n - 1] - xs[0]) / dt
        val vy = (ys[n - 1] - ys[0]) / dt
        val m = n / 2
        val dt2 = (ts[n - 1] - ts[m]).toFloat()
        val vx2 = if (dt2 > 0f) (xs[n - 1] - xs[m]) / dt2 else vx
        val vy2 = if (dt2 > 0f) (ys[n - 1] - ys[m]) / dt2 else vy
        var px = (vx + 2 * vx2) / 3f * aheadMs
        var py = (vy + 2 * vy2) / 3f * aheadMs
        val d = hypot(px, py)
        if (d < 0.5f) return false
        if (d > maxDist) {
            px *= maxDist / d
            py *= maxDist / d
        }
        out[0] = xs[n - 1] + px
        out[1] = ys[n - 1] + py
        return true
    }
}
