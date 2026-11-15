package com.yume.app.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.yume.app.YumeApp
import com.yume.app.engine.BrushCategory
import com.yume.app.engine.BrushLibrary
import com.yume.app.engine.BrushPreset
import com.yume.app.engine.DrawingEngine
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.History
import com.yume.app.engine.InputEvent
import com.yume.app.engine.TileStoreConfig
import com.yume.app.engine.Tool
import java.io.File
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paints a picture through the app's own brush engine: every mark is a pen
 * stroke (down, moves with pressure, up) fed to a [DrawingSession], exactly
 * what the canvas view sends for a stylus. Used to exercise the watercolour
 * and acrylic brushes on a real composition, and saved as a normal project.
 *
 * "Sunset over the city": an anime-style evening sky in watercolour washes
 * blended with the wet-bleed blender, cumulus lit from below, a skyline in
 * flat acrylic, power lines, a rooftop ledge with cosmos flowers, birds, and a
 * cat watching the sun go down.
 */
object ScenePainter {
    private const val TAG = "YumeScene"
    private const val W = 2800
    private const val H = 1800
    private const val SUNX = 1830f
    private const val SUNY = 1128f

    private class Pt(val x: Float, val y: Float, val p: Float)

    private fun c(rgb: Int): Int = rgb or 0xFF000000.toInt()

    private fun mix(a: Int, b: Int, t0: Float): Int {
        val t = t0.coerceIn(0f, 1f)
        fun ch(sh: Int) = (((a shr sh) and 255) + (((b shr sh) and 255) - ((a shr sh) and 255)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private class Painter(val session: DrawingSession, val engine: DrawingEngine, seed: Long) {
        val rnd = java.util.Random(seed)
        var strokes = 0

        fun r(a: Float, b: Float) = a + (b - a) * rnd.nextFloat()
        fun chance(p: Float) = rnd.nextFloat() < p

        fun jitter(col: Int, amt: Float): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(col, hsv)
            hsv[0] = ((hsv[0] + r(-1f, 1f) * amt * 60f) % 360f + 360f) % 360f
            hsv[1] = (hsv[1] + r(-1f, 1f) * amt).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + r(-1f, 1f) * amt).coerceIn(0f, 1f)
            return Color.HSVToColor(hsv)
        }

        fun layer(name: String) {
            val l = engine.newLayer(name)
            engine.selectLayer(l.id)
            session.runCommands()
        }

        /** Picks a library brush at [size], optionally adjusted for the job. */
        fun brush(id: String, size: Float, tweak: (BrushPreset) -> BrushPreset = { it }) {
            val base = BrushLibrary.defaults.first { it.id == id }
            val p = tweak(base.copy(size = size))
            session.presets = session.presets.map { if (it.id == id) p else it }
            session.category = p.category
            session.activePresetId = p.id
            session.tool = when (p.category) {
                BrushCategory.Brush -> Tool.Brush
                BrushCategory.Eraser -> Tool.Eraser
                BrushCategory.Smudge -> Tool.Smudge
            }
            session.runCommands()
        }

        fun color(col: Int) {
            session.color = col
        }

        fun stroke(pts: List<Pt>) {
            if (pts.isEmpty()) return
            val f = pts.first()
            session.pushInput(InputEvent.Down(0, f.x, f.y, f.p, 0f, true, false))
            session.runInput()
            for (i in 1 until pts.size) {
                val q = pts[i]
                session.pushInput(InputEvent.Move(0, floatArrayOf(q.x), floatArrayOf(q.y), floatArrayOf(q.p), 0f))
                session.runInput()
            }
            session.pushInput(InputEvent.Up(0))
            session.runInput()
            strokes++
        }

        /**
         * A hand-drawn path through [ctrl]: a smooth curve resampled every
         * [step] px, with a slow wobble across it and a pressure that rises
         * from [pIn] to [pMid] and falls to [pOut], never perfectly steady.
         */
        fun path(
            ctrl: List<Pair<Float, Float>>, wobble: Float = 4f,
            pIn: Float = 0.35f, pMid: Float = 1f, pOut: Float = 0.35f, step: Float = 6f
        ): List<Pt> {
            if (ctrl.size < 2) return emptyList()
            // Catmull-Rom through the control points, sampled densely.
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (i in 0 until ctrl.size - 1) {
                val p0 = ctrl[max(0, i - 1)]
                val p1 = ctrl[i]
                val p2 = ctrl[i + 1]
                val p3 = ctrl[min(ctrl.size - 1, i + 2)]
                val n = 24
                for (k in 0 until n) {
                    val t = k / n.toFloat()
                    val t2 = t * t
                    val t3 = t2 * t
                    xs.add(0.5f * ((2 * p1.first) + (-p0.first + p2.first) * t + (2 * p0.first - 5 * p1.first + 4 * p2.first - p3.first) * t2 + (-p0.first + 3 * p1.first - 3 * p2.first + p3.first) * t3))
                    ys.add(0.5f * ((2 * p1.second) + (-p0.second + p2.second) * t + (2 * p0.second - 5 * p1.second + 4 * p2.second - p3.second) * t2 + (-p0.second + 3 * p1.second - 3 * p2.second + p3.second) * t3))
                }
            }
            xs.add(ctrl.last().first)
            ys.add(ctrl.last().second)
            var total = 0f
            for (i in 1 until xs.size) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            if (total < 0.5f) return listOf(Pt(xs[0], ys[0], pMid), Pt(xs[0] + 0.8f, ys[0] + 0.4f, pMid))
            val ph1 = r(0f, 6.28f)
            val ph2 = r(0f, 6.28f)
            val ph3 = r(0f, 6.28f)
            val l1 = r(90f, 170f)
            val l2 = r(28f, 55f)
            val out = ArrayList<Pt>((total / step).toInt() + 2)
            var seg = 1
            var acc = 0f
            var s = 0f
            while (s <= total) {
                while (seg < xs.size - 1 && acc + hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1]) < s) {
                    acc += hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1])
                    seg++
                }
                val dx = xs[seg] - xs[seg - 1]
                val dy = ys[seg] - ys[seg - 1]
                val len = max(1e-3f, hypot(dx, dy))
                val f = ((s - acc) / len).coerceIn(0f, 1f)
                val nx = -dy / len
                val ny = dx / len
                val w = wobble * (0.6f * sin(s / l1 + ph1) + 0.4f * sin(s / l2 + ph2) + r(-0.15f, 0.15f))
                val t = s / total
                var p = when {
                    t < 0.15f -> pIn + (pMid - pIn) * (t / 0.15f)
                    t > 0.8f -> pMid + (pOut - pMid) * ((t - 0.8f) / 0.2f)
                    else -> pMid
                }
                p *= 1f + 0.1f * sin(s / 70f + ph3)
                out.add(Pt(xs[seg - 1] + dx * f + nx * w, ys[seg - 1] + dy * f + ny * w, p.coerceIn(0.04f, 1f)))
                s += step
            }
            return out
        }

        fun line(
            x0: Float, y0: Float, x1: Float, y1: Float, wobble: Float = 4f,
            pIn: Float = 0.35f, pMid: Float = 1f, pOut: Float = 0.35f
        ) = stroke(path(listOf(x0 to y0, x1 to y1), wobble, pIn, pMid, pOut))

        fun curve(vararg pts: Pair<Float, Float>, wobble: Float = 3f, pIn: Float = 0.35f, pMid: Float = 1f, pOut: Float = 0.35f) =
            stroke(path(pts.toList(), wobble, pIn, pMid, pOut))

        /** A single touch of the brush. */
        fun dab(x: Float, y: Float, p: Float = 1f) =
            stroke(listOf(Pt(x, y, p), Pt(x + r(-1.5f, 1.5f), y + r(-1.5f, 1.5f), p)))

        /**
         * Fills a shape with brush strokes laid side by side at [angle], the
         * way a painter blocks in an area: each row alternates direction, sits
         * a little off its line, and long rows are broken into shorter strokes
         * ([maxLen]). [colorAt] picks each stroke's colour where it lands.
         */
        fun fill(
            bl: Float, bt: Float, br: Float, bb: Float, size: Float, angle: Float = 0f,
            spacing: Float = 0.5f, wob: Float = size * 0.06f, maxLen: Float = Float.MAX_VALUE,
            colorAt: ((Float, Float) -> Int)? = null, inside: (Float, Float) -> Boolean = { _, _ -> true }
        ) {
            val cx = (bl + br) / 2f
            val cy = (bt + bb) / 2f
            val a = Math.toRadians(angle.toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val half = hypot(br - bl, bb - bt) / 2f
            val stepU = max(3f, size * 0.12f)
            var flip = false
            var v = -half
            while (v <= half) {
                val row = v
                fun emit(u0: Float, u1: Float) {
                    var s0 = u0
                    while (s0 < u1) {
                        val pieceEnd = if (u1 - s0 > maxLen) s0 + maxLen * r(0.6f, 1f) else u1
                        val inset = size * 0.28f
                        var a0 = s0 + inset
                        var a1 = pieceEnd - inset
                        if (a1 - a0 < 2f) {
                            val m = (s0 + pieceEnd) / 2f
                            a0 = m - 1f
                            a1 = m + 1f
                        }
                        val vv = row + r(-0.08f, 0.08f) * size
                        val x0 = cx + a0 * ca - vv * sa
                        val y0 = cy + a0 * sa + vv * ca
                        val x1 = cx + a1 * ca - vv * sa
                        val y1 = cy + a1 * sa + vv * ca
                        colorAt?.let { color(it((x0 + x1) / 2f, (y0 + y1) / 2f)) }
                        if (flip) line(x1, y1, x0, y0, wob, 0.7f, 1f, 0.7f) else line(x0, y0, x1, y1, wob, 0.7f, 1f, 0.7f)
                        flip = !flip
                        // Pieces of a broken row overlap a little.
                        s0 = if (pieceEnd >= u1) u1 else pieceEnd - size * 0.3f
                    }
                }
                var u = -half
                var start = Float.NaN
                while (u <= half) {
                    val x = cx + u * ca - row * sa
                    val y = cy + u * sa + row * ca
                    val ins = x >= bl && x <= br && y >= bt && y <= bb && inside(x, y)
                    if (ins && start.isNaN()) start = u
                    if (!ins && !start.isNaN()) {
                        emit(start, u)
                        start = Float.NaN
                    }
                    u += stepU
                }
                if (!start.isNaN()) emit(start, half)
                v += size * spacing
            }
        }
    }

    private fun poly(vararg p: Pair<Float, Float>): (Float, Float) -> Boolean = { x, y ->
        var inside = false
        var j = p.size - 1
        for (i in p.indices) {
            val xi = p[i].first
            val yi = p[i].second
            val xj = p[j].first
            val yj = p[j].second
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        inside
    }

    /** A closed outline through [pts], rounded (Catmull-Rom), as polygon points. */
    private fun smoothClosed(pts: List<Pair<Float, Float>>, per: Int = 6): List<Pair<Float, Float>> {
        val n = pts.size
        val out = ArrayList<Pair<Float, Float>>(n * per)
        for (i in 0 until n) {
            val p0 = pts[(i - 1 + n) % n]
            val p1 = pts[i]
            val p2 = pts[(i + 1) % n]
            val p3 = pts[(i + 2) % n]
            for (k in 0 until per) {
                val t = k / per.toFloat()
                val t2 = t * t
                val t3 = t2 * t
                out.add(
                    0.5f * ((2 * p1.first) + (-p0.first + p2.first) * t + (2 * p0.first - 5 * p1.first + 4 * p2.first - p3.first) * t2 + (-p0.first + 3 * p1.first - 3 * p2.first + p3.first) * t3) to
                        0.5f * ((2 * p1.second) + (-p0.second + p2.second) * t + (2 * p0.second - 5 * p1.second + 4 * p2.second - p3.second) * t2 + (-p0.second + 3 * p1.second - 3 * p2.second + p3.second) * t3)
                )
            }
        }
        return out
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): (Float, Float) -> Boolean = { x, y ->
        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        dx * dx + dy * dy <= 1f
    }

    // ------------------------------------------------------------ colours

    private val SKY = listOf(
        0f to c(0x232863), 0.16f to c(0x3A3285), 0.32f to c(0x7B3F96), 0.46f to c(0xC24E86),
        0.56f to c(0xEE6C5C), 0.63f to c(0xF89A4E), 0.69f to c(0xFFC565), 0.78f to c(0xFFE09A)
    )

    private fun skyBase(y: Float): Int {
        val t = (y / H).coerceIn(0f, 1f)
        for (i in 1 until SKY.size) {
            if (t <= SKY[i].first) {
                val a = SKY[i - 1]
                val b = SKY[i]
                return mix(a.second, b.second, (t - a.first) / (b.first - a.first))
            }
        }
        return SKY.last().second
    }

    /** How much of the sun's glow reaches ([x], [y]): 1 at the sun, 0 far away. */
    private fun glow(x: Float, y: Float, reach: Float = 560f): Float {
        val d = hypot(x - SUNX, (y - SUNY) * 1.25f)
        return exp(-(d / reach) * (d / reach))
    }

    private fun sky(x: Float, y: Float): Int = mix(skyBase(y), c(0xFFD884), 0.42f * glow(x, y))

    private fun glowX(x: Float): Float {
        val d = (x - SUNX) / 650f
        return exp(-d * d)
    }

    // ------------------------------------------------------------ the picture

    /** [save] = false paints a draft only (files/sunset.png, no project). */
    fun paintSunset(app: YumeApp, save: Boolean = true): String {
        val out = StringBuilder()
        val oldS = TileStoreConfig.useSparseLayers
        TileStoreConfig.useSparseLayers = true
        val t0 = System.nanoTime()
        try {
            val ps = app.projectStore
            val paper = c(0xF4ECDD)
            val projectId = if (save) ps.createProject("Sunset over the city", W, H, paper).id else "draft"
            val engine = DrawingEngine(W, H)
            engine.backgroundColor = paper
            val session = DrawingSession(projectId, "Sunset over the city", engine, History())
            session.view.setViewport(W, H)
            session.view.pan(W / 2f, H / 2f)
            session.presets = BrushLibrary.defaults
            session.assist = 0f
            session.quickShapeEnabled = false
            val p = Painter(session, engine, 20261115L)
            engine.layers.first().name = "Sky"
            engine.selectLayer(engine.layers.first().id)

            paintSky(p)
            Log.i(TAG, "sky done: ${p.strokes} strokes")
            p.layer("Clouds")
            paintClouds(p)
            Log.i(TAG, "clouds done: ${p.strokes} strokes")
            p.layer("City")
            val wire = paintCity(p)
            Log.i(TAG, "city done: ${p.strokes} strokes")
            p.layer("Rooftop and plants")
            paintForeground(p)
            Log.i(TAG, "foreground done: ${p.strokes} strokes")
            p.layer("Cat and birds")
            paintLife(p, wire)
            Log.i(TAG, "life done: ${p.strokes} strokes")

            session.runCommands()
            engine.compositeAll()
            val flat = engine.flatten()
            java.io.FileOutputStream(File(app.filesDir, "sunset.png")).use {
                flat.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            flat.recycle()
            if (save) ps.saveProject(projectId, session.snapshotNow())
            out.append("sunset: ${p.strokes} strokes, ${engine.layers.size} layers, ${(System.nanoTime() - t0) / 1_000_000_000} s, ${if (save) "saved $projectId" else "draft"}\n")
        } catch (t: Throwable) {
            Log.e(TAG, "sunset failed", t)
            out.append("sunset: FAIL ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            TileStoreConfig.useSparseLayers = oldS
        }
        return out.toString()
    }

    // ------------------------------------------------------------ sky

    private fun paintSky(p: Painter) {
        // A ragged painted edge: the paper shows around the picture.
        val edge: (Float, Float) -> Boolean = { x, y ->
            x > 44f + 16f * (0.5f + 0.5f * sin(y / 41f)) + 8f * sin(y / 13f) &&
                x < W - 44f - 16f * (0.5f + 0.5f * sin(y / 37f + 2f)) - 8f * sin(y / 11f) &&
                y > 44f + 14f * (0.5f + 0.5f * sin(x / 53f + 1f)) + 6f * sin(x / 17f)
        }
        // 1. Underpainting in gouache: broad horizontal strokes, colour by height
        //    and by distance from the sun.
        p.brush("gouache", 170f) { it.copy(pressureSize = 0.3f, opacity = 0.96f, wetMix = 0.3f) }
        p.fill(40f, 40f, W - 40f, 1345f, 170f, 0f, spacing = 0.4f, wob = 9f, maxLen = 760f,
            colorAt = { x, y -> p.jitter(sky(x, y), 0.03f) }, inside = edge)
        // 2. Loose acrylic strokes over it, so the brushwork shows.
        p.brush("acr-round", 120f) { it.copy(opacity = 0.55f, flow = 0.7f, pressureSize = 0.5f) }
        repeat(120) {
            val y = p.r(90f, 1260f)
            val x0 = p.r(80f, W - 700f)
            val len = p.r(260f, 760f)
            val slope = p.r(-0.06f, 0.06f)
            p.color(p.jitter(sky(x0 + len / 2f, y + p.r(-55f, 55f)), 0.05f))
            p.line(x0, y, min(W - 70f, x0 + len), y + len * slope, 8f, 0.4f, 0.9f, 0.3f)
        }
        // 3. Watercolour glazes: rose across the middle, indigo into the top
        //    corners, a warm wash around the sun.
        p.brush("wc-wet-edge", 260f) { it.copy(flow = 0.1f, opacity = 0.8f) }
        p.color(c(0xD9508C))
        p.curve(120f to 760f, 900f to 690f, 1700f to 740f, 2650f to 640f, wobble = 26f)
        p.curve(200f to 880f, 1100f to 830f, 2600f to 800f, wobble = 26f)
        p.color(c(0x1A1D55))
        p.curve(70f to 110f, 500f to 90f, 1100f to 140f, wobble = 22f)
        p.curve(1700f to 120f, 2300f to 80f, 2730f to 150f, wobble = 22f)
        p.curve(70f to 260f, 380f to 230f, 700f to 300f, wobble = 22f)
        p.brush("wc-glaze", 300f) { it.copy(flow = 0.07f) }
        p.color(c(0xFFB45A))
        p.curve(1250f to 1090f, 1830f to 1010f, 2420f to 1090f, wobble = 20f)
        p.curve(1100f to 1200f, 1830f to 1150f, 2600f to 1200f, wobble = 20f)
        // 4. The wet-bleed blender pulls the bands into each other.
        p.brush("wc-wet-bleed-soft", 250f) { it.copy(bleed = 0.55f, bleedSpread = 0.55f, spacing = 0.16f) }
        var y = 250f
        while (y < 1300f) {
            p.curve(260f to y + p.r(-20f, 20f), 950f to y + p.r(-40f, 40f), 1900f to y + p.r(-40f, 40f), 2540f to y + p.r(-20f, 20f),
                wobble = 30f, pIn = 0.6f, pMid = 0.9f, pOut = 0.6f)
            y += 135f
        }
        // 5. First stars, high up where the sky is darkest.
        p.brush("gouache", 7f) { it.copy(pressureSize = 0.2f, wetMix = 0f) }
        repeat(46) {
            val sx = p.r(90f, W - 90f)
            val sy = p.r(70f, 430f)
            p.color(mix(c(0xFFF6DC), c(0xCFC8FF), p.r(0f, 1f)))
            p.brush("gouache", p.r(4f, 9f)) { it.copy(pressureSize = 0.2f, wetMix = 0f, opacity = p.r(0.55f, 1f)) }
            p.dab(sx, sy)
        }
        // 6. The sun: a wide soft glow, then the disc.
        p.brush("soft-brush", 900f) { it.copy(opacity = 0.22f, flow = 0.5f, pressureSize = 0f, spacing = 0.2f) }
        p.color(c(0xFFD890))
        repeat(3) { p.dab(SUNX + p.r(-20f, 20f), SUNY + p.r(-20f, 20f)) }
        p.brush("soft-brush", 460f) { it.copy(opacity = 0.4f, flow = 0.6f, pressureSize = 0f, spacing = 0.2f) }
        p.color(c(0xFFEDB8))
        repeat(3) { p.dab(SUNX + p.r(-10f, 10f), SUNY + p.r(-10f, 10f)) }
        p.brush("gouache", 52f) { it.copy(pressureSize = 0.2f, wetMix = 0f) }
        p.color(c(0xFFF8E2))
        p.fill(SUNX - 100f, SUNY - 100f, SUNX + 100f, SUNY + 100f, 52f, 25f, spacing = 0.4f,
            inside = ellipse(SUNX, SUNY, 92f, 92f))
        // Its edge, drawn round in one go.
        p.brush("gouache", 16f) { it.copy(pressureSize = 0.1f, wetMix = 0f, hardness = 0.8f) }
        val ring = ArrayList<Pair<Float, Float>>()
        for (k in 0..40) {
            val a = k / 40f * 6.2832f + 0.4f
            ring.add(SUNX + cos(a) * 88f to SUNY + sin(a) * 88f)
        }
        p.stroke(p.path(ring, 0.8f, 0.9f, 1f, 0.9f, step = 4f))
        p.brush("soft-brush", 300f) { it.copy(opacity = 0.4f, flow = 0.6f, pressureSize = 0f, spacing = 0.2f) }
        p.color(c(0xFFFBEA))
        p.dab(SUNX, SUNY)
    }

    // ------------------------------------------------------------ clouds

    private class Puff(val x: Float, val y: Float, val r: Float)

    private fun paintClouds(p: Painter) {
        fun cloud(puffs: List<Puff>, base: Float) {
            fun puffInside(q: Puff): (Float, Float) -> Boolean = { x, y ->
                hypot(x - q.x, y - q.y) < q.r && y < base + 7f * sin(x / 23f)
            }
            // Shadow masses first: dusk violet, a little warmer low down.
            for (q in puffs) {
                p.brush("acr-round", q.r * 0.55f) { it.copy(hardness = 0.45f, opacity = 0.93f, pressureSize = 0.3f, wetMix = 0.3f) }
                val low = ((q.y - 250f) / 650f).coerceIn(0f, 1f)
                p.color(p.jitter(mix(mix(c(0x4B3C84), c(0x86508E), low), c(0xB0607A), 0.35f * glow(q.x, q.y, 900f)), 0.035f))
                p.fill(q.x - q.r, q.y - q.r, q.x + q.r, q.y + q.r, q.r * 0.55f, p.r(-40f, 40f), spacing = 0.42f, inside = puffInside(q))
            }
            // Light from the low sun: a rose half-tone, coral on the side
            // facing it, and a pale gold crescent on the puffs at the edge.
            fun arc(q: Puff, ang: Float, radius: Float, spread: Float): List<Pair<Float, Float>> {
                val pts = ArrayList<Pair<Float, Float>>()
                val n = 10
                for (k in 0..n) {
                    val a = ang - spread + 2f * spread * k / n
                    pts.add(q.x + cos(a) * radius to q.y + sin(a) * radius)
                }
                return pts
            }
            for (q in puffs) {
                val dx = SUNX - q.x
                val dy = SUNY - q.y
                val d = max(1f, hypot(dx, dy))
                val ux = dx / d
                val uy = dy / d
                val sunAng = kotlin.math.atan2(uy, ux)
                val deg = Math.toDegrees(sunAng.toDouble()).toFloat() + 90f
                val near = glow(q.x, q.y, 1100f)
                val inP = puffInside(q)
                // Half-tone.
                val mx = q.x + ux * q.r * 0.22f
                val my = q.y + uy * q.r * 0.22f
                p.brush("acr-round", q.r * 0.42f) { it.copy(hardness = 0.35f, opacity = 0.6f, pressureSize = 0.4f, wetMix = 0.4f) }
                p.color(p.jitter(mix(c(0xB85E8E), c(0xE0808A), near), 0.04f))
                p.fill(q.x - q.r, q.y - q.r, q.x + q.r, q.y + q.r, q.r * 0.42f, deg + p.r(-14f, 14f), spacing = 0.45f,
                    inside = { x, y -> inP(x, y) && hypot(x - mx, y - my) < q.r * 0.82f })
                // Coral light.
                val lx = q.x + ux * q.r * 0.5f
                val ly = q.y + uy * q.r * 0.5f
                p.brush("acr-round", q.r * 0.34f) { it.copy(hardness = 0.35f, opacity = 0.88f, pressureSize = 0.4f, wetMix = 0.35f) }
                p.color(p.jitter(mix(c(0xF27870), c(0xFFB470), near), 0.04f))
                p.fill(q.x - q.r, q.y - q.r, q.x + q.r, q.y + q.r, q.r * 0.34f, deg + p.r(-14f, 14f), spacing = 0.45f,
                    inside = { x, y -> inP(x, y) && hypot(x - lx, y - ly) < q.r * 0.58f })
            }
            // Wet-bleed along the turn from light to shadow, so the forms are soft.
            for (q in puffs) {
                val dx = SUNX - q.x
                val dy = SUNY - q.y
                val d = max(1f, hypot(dx, dy))
                val ux = dx / d
                val uy = dy / d
                for (off in listOf(-0.15f, 0.3f)) {
                    val mx = q.x + ux * q.r * off
                    val my = q.y + uy * q.r * off
                    p.brush("wc-wet-bleed", q.r * 0.45f) { it.copy(bleed = 0.55f, bleedSpread = 0.45f) }
                    p.line(mx + uy * q.r * 0.6f, my - ux * q.r * 0.6f, mx - uy * q.r * 0.6f, my + ux * q.r * 0.6f, 6f, 0.5f, 0.8f, 0.5f)
                }
            }
        }
        // The big cumulus on the left.
        cloud(
            listOf(
                Puff(560f, 380f, 150f), Puff(720f, 330f, 130f), Puff(470f, 450f, 140f), Puff(850f, 430f, 130f),
                Puff(260f, 640f, 150f), Puff(430f, 560f, 190f), Puff(640f, 500f, 220f), Puff(860f, 560f, 190f),
                Puff(1040f, 640f, 150f), Puff(1180f, 700f, 110f), Puff(150f, 740f, 110f), Puff(330f, 720f, 150f),
                Puff(560f, 730f, 180f), Puff(800f, 740f, 170f), Puff(980f, 760f, 130f)
            ), 812f
        )
        // A second bank, upper right.
        cloud(
            listOf(
                Puff(2320f, 280f, 170f), Puff(2150f, 330f, 130f), Puff(2520f, 300f, 160f), Puff(2680f, 350f, 110f),
                Puff(2250f, 400f, 130f), Puff(2450f, 410f, 150f), Puff(2620f, 420f, 115f)
            ), 472f
        )
        // Small ones near the horizon.
        cloud(listOf(Puff(2350f, 822f, 70f), Puff(2450f, 800f, 90f), Puff(2560f, 815f, 80f), Puff(2650f, 838f, 58f)), 862f)
        cloud(listOf(Puff(520f, 1012f, 58f), Puff(610f, 996f, 74f), Puff(702f, 1012f, 58f)), 1040f)
        cloud(listOf(Puff(1330f, 930f, 46f), Puff(1400f, 920f, 58f), Puff(1475f, 934f, 44f)), 956f)
        // Thin streaks of cloud catching the last light.
        repeat(11) {
            val y = p.r(860f, 1130f)
            val x0 = p.r(120f, 2200f)
            val len = p.r(280f, 880f)
            val warm = glow(x0 + len / 2f, y, 900f)
            p.brush("wc-round", p.r(14f, 34f)) { it.copy(flow = 0.5f, opacity = p.r(0.45f, 0.85f), wetEdges = 0.2f) }
            p.color(p.jitter(mix(mix(c(0x8E5C9C), c(0xFF8E8A), p.r(0.3f, 1f)), c(0xFFDFA0), warm * 0.7f), 0.04f))
            p.curve(x0 to y, x0 + len * 0.5f to y - p.r(4f, 22f), min(W - 70f, x0 + len) to y - p.r(-8f, 20f),
                wobble = 5f, pIn = 0.15f, pMid = 1f, pOut = 0.1f)
        }
    }

    // ------------------------------------------------------------ city

    /** Paints the skyline, tower, pole and wires. Returns the upper wire's height at x. */
    private fun paintCity(p: Painter): (Float) -> Float {
        // Far skyline, pale in the haze and warm near the sun.
        fun hazeFar(x: Float) = mix(c(0x96608F), c(0xD67E5C), 0.78f * glowX(x))
        p.brush("acr-flat", 60f) { it.copy(pressureSize = 0.2f, opacity = 0.95f) }
        p.fill(46f, 1246f, W - 46f, 1352f, 60f, 0f, spacing = 0.45f, maxLen = 520f,
            colorAt = { x, _ -> p.jitter(hazeFar(x), 0.03f) })
        var x = 44f
        while (x < W - 60f) {
            val w = p.r(50f, 130f)
            val top = 1262f - p.r(20f, 120f) - (if (p.chance(0.12f)) p.r(60f, 120f) else 0f)
            p.brush("acr-flat", 30f) { it.copy(pressureSize = 0.2f, opacity = 0.95f) }
            p.color(p.jitter(hazeFar(x + w / 2f), 0.035f))
            p.fill(x, top, min(W - 48f, x + w), 1300f, 30f, 90f, spacing = 0.45f, wob = 1.5f)
            if (p.chance(0.5f)) {
                p.brush("gouache", 5f) { it.copy(wetMix = 0f, pressureSize = 0.2f) }
                repeat(p.r(1f, 5f).toInt()) {
                    p.color(mix(c(0xFFE2A0), c(0xFFF4D0), p.r(0f, 1f)))
                    p.dab(x + p.r(8f, w - 8f), p.r(top + 12f, 1290f))
                }
            }
            x += w + p.r(-6f, 10f)
        }
        // A broadcast tower standing in the haze.
        val tx = 1185f
        p.color(c(0x6A416F))
        p.brush("technical-pen", 9f)
        p.line(tx - 62f, 1352f, tx - 6f, 770f, 1.5f, 0.9f, 1f, 0.7f)
        p.line(tx + 62f, 1352f, tx + 6f, 770f, 1.5f, 0.9f, 1f, 0.7f)
        p.brush("technical-pen", 5f)
        var ty = 1320f
        var k = 0
        while (ty > 800f) {
            val half = 6f + 56f * ((ty - 770f) / 582f)
            val up = ty - 62f
            val halfUp = 6f + 56f * ((up - 770f) / 582f)
            p.line(tx - half, ty, tx + half, ty, 1f, 0.8f, 1f, 0.8f)
            if (k % 2 == 0) p.line(tx - half, ty, tx + halfUp, up, 1f, 0.8f, 1f, 0.8f) else p.line(tx + half, ty, tx - halfUp, up, 1f, 0.8f, 1f, 0.8f)
            ty -= 62f
            k++
        }
        p.brush("technical-pen", 12f)
        p.line(tx - 34f, 1060f, tx + 34f, 1060f, 1f, 1f, 1f, 1f)
        p.line(tx - 20f, 900f, tx + 20f, 900f, 1f, 1f, 1f, 1f)
        p.brush("technical-pen", 5f)
        p.line(tx, 772f, tx, 650f, 1f, 1f, 0.9f, 0.5f)
        p.color(c(0xFF9A66))
        p.brush("technical-pen", 4f) { it.copy(opacity = 0.9f) }
        p.line(tx + 60f, 1348f, tx + 8f, 775f, 1.5f, 0.6f, 0.9f, 0.5f)
        p.color(c(0xFF5A50))
        p.brush("gouache", 9f) { it.copy(wetMix = 0f, pressureSize = 0.2f) }
        p.dab(tx, 648f)
        p.dab(tx, 900f)

        // Middle skyline: dusk purple blocks with lit windows.
        fun midCol(xx: Float) = mix(c(0x3B2B66), c(0x8A4C6C), 0.62f * glowX(xx))
        p.brush("acr-flat", 110f) { it.copy(pressureSize = 0.2f, opacity = 0.97f) }
        p.fill(46f, 1342f, W - 46f, 1762f, 110f, 0f, spacing = 0.42f, maxLen = 640f,
            colorAt = { xx, yy -> p.jitter(mix(midCol(xx), c(0x1E1836), ((yy - 1342f) / 430f).coerceIn(0f, 1f)), 0.025f) },
            inside = { xx, yy -> yy < H - 44f - 12f * (0.5f + 0.5f * sin(xx / 47f)) })
        class B(val x: Float, val w: Float, val top: Float)
        val mids = ArrayList<B>()
        x = 40f
        while (x < W - 70f) {
            val w = p.r(90f, 230f)
            val centre = x + w / 2f
            val boost = exp(-((centre - 1050f) / 480f) * ((centre - 1050f) / 480f))
            var h = p.r(90f, 250f) * (1f + 0.95f * boost)
            if (kotlin.math.abs(centre - SUNX) < 240f) h = min(h, p.r(70f, 125f))
            mids.add(B(x, w, 1352f - h))
            x += w + p.r(-4f, 16f)
        }
        for (b in mids) {
            val right = min(W - 48f, b.x + b.w)
            p.brush("acr-flat", 44f) { it.copy(pressureSize = 0.2f, opacity = 0.97f) }
            p.color(p.jitter(midCol(b.x + b.w / 2f), 0.035f))
            p.fill(b.x, b.top, right, 1474f, 44f, 90f, spacing = 0.44f, wob = 2f)
            // Things on the roof.
            p.color(p.jitter(mix(midCol(b.x), c(0x221A3C), 0.3f), 0.02f))
            if (p.chance(0.45f)) {
                p.brush("technical-pen", 5f)
                val ax = p.r(b.x + 12f, right - 12f)
                p.line(ax, b.top + 4f, ax + p.r(-3f, 3f), b.top - p.r(36f, 96f), 1f, 1f, 0.8f, 0.4f)
            }
            if (p.chance(0.35f) && b.w > 110f) {
                val bx = p.r(b.x + 14f, right - 60f)
                p.brush("acr-flat", 20f) { it.copy(pressureSize = 0.2f) }
                p.fill(bx, b.top - 30f, bx + 46f, b.top + 4f, 20f, 0f, spacing = 0.45f, wob = 1f)
            }
        }
        // Edges that face the sun catch its light.
        for (b in mids) {
            val right = min(W - 48f, b.x + b.w)
            val centre = b.x + b.w / 2f
            val ex = if (centre < SUNX) right - 4f else b.x + 4f
            val g = 0.35f + 0.65f * glowX(centre)
            p.brush("wc-round", 9f) { it.copy(opacity = 0.8f * g, flow = 0.8f, wetEdges = 0f, taperIn = 0f) }
            p.color(c(0xFFA468))
            p.line(ex, b.top + 2f, ex + p.r(-2f, 2f), b.top + (1400f - b.top) * p.r(0.5f, 0.85f), 1.5f, 0.9f, 0.7f, 0.1f)
            p.line(b.x + 5f, b.top + 3f, right - 5f, b.top + 3f + p.r(-2f, 2f), 1.5f, 0.5f, 0.8f, 0.5f)
        }
        // Windows coming on.
        val lit = intArrayOf(c(0xFFD98A), c(0xFFB866), c(0xFFE9B8), c(0xFF9E5A))
        for (b in mids) {
            val right = min(W - 48f, b.x + b.w)
            val cols = ((right - b.x - 24f) / 30f).toInt()
            val rows = ((1400f - b.top - 20f) / 38f).toInt()
            for (ri in 0 until rows) {
                for (ci in 0 until cols) {
                    if (!p.chance(0.3f)) continue
                    val wx = b.x + 18f + ci * 30f + p.r(-2f, 2f)
                    val wy = b.top + 24f + ri * 38f + p.r(-2f, 2f)
                    p.brush("gouache", p.r(9f, 12f)) { it.copy(wetMix = 0f, pressureSize = 0.2f, opacity = p.r(0.75f, 1f)) }
                    p.color(lit[p.rnd.nextInt(lit.size)])
                    p.line(wx, wy, wx + p.r(7f, 12f), wy + p.r(-1f, 1f), 0.5f, 0.9f, 1f, 0.9f)
                }
            }
        }
        // Evening haze where the buildings meet the streets.
        repeat(26) {
            val hx = p.r(60f, W - 420f)
            val hy = p.r(1410f, 1500f)
            p.brush("soft-brush", p.r(150f, 240f)) { it.copy(opacity = 0.16f, flow = 0.5f, pressureSize = 0.2f) }
            p.color(p.jitter(mix(c(0x5A3F7E), c(0xC0647A), 0.7f * glowX(hx)), 0.03f))
            p.line(hx, hy, hx + p.r(220f, 420f), hy + p.r(-12f, 12f), 8f, 0.4f, 0.8f, 0.4f)
        }
        // The streets below: scattered lights, soft and out of focus.
        repeat(150) {
            val lx = p.r(70f, W - 70f)
            val ly = p.r(1420f, 1730f)
            p.brush("soft-brush", p.r(10f, 30f)) { it.copy(opacity = p.r(0.35f, 0.85f), flow = 0.9f, pressureSize = 0f, hardness = 0.35f) }
            p.color(p.jitter(lit[p.rnd.nextInt(lit.size)], 0.05f))
            p.dab(lx, ly)
        }

        // A nearer rooftop on the right, with a water tank.
        val dark = c(0x1F1834)
        p.brush("acr-flat", 70f) { it.copy(pressureSize = 0.2f, opacity = 0.98f) }
        p.fill(2040f, 1490f, W - 46f, 1762f, 70f, 0f, spacing = 0.42f, maxLen = 420f,
            colorAt = { _, _ -> p.jitter(dark, 0.02f) },
            inside = { xx, yy -> poly(2040f to 1762f, 2040f to 1526f, 2754f to 1500f, 2754f to 1762f)(xx, yy) && yy < H - 44f - 12f * (0.5f + 0.5f * sin(xx / 47f)) })
        p.brush("acr-flat", 36f) { it.copy(pressureSize = 0.2f, opacity = 0.98f) }
        p.color(p.jitter(c(0x241C3C), 0.02f))
        p.fill(2180f, 1392f, 2330f, 1500f, 36f, 90f, spacing = 0.44f, wob = 1.5f)
        p.fill(2168f, 1372f, 2342f, 1400f, 24f, 0f, spacing = 0.45f, wob = 1.5f,
            inside = poly(2168f to 1400f, 2255f to 1366f, 2342f to 1400f))
        p.brush("technical-pen", 8f)
        p.line(2196f, 1498f, 2190f, 1530f, 1f, 1f, 1f, 1f)
        p.line(2314f, 1498f, 2320f, 1528f, 1f, 1f, 1f, 1f)
        p.color(c(0xFF9E62))
        p.brush("wc-round", 8f) { it.copy(opacity = 0.75f, flow = 0.8f, wetEdges = 0f, taperIn = 0f) }
        p.line(2183f, 1398f, 2184f, 1494f, 1.5f, 0.8f, 0.7f, 0.2f)
        p.line(2042f, 1528f, 2750f, 1503f, 2f, 0.4f, 0.7f, 0.3f)
        p.line(2172f, 1399f, 2254f, 1368f, 1.5f, 0.5f, 0.7f, 0.3f)

        // Utility pole and wires.
        val px = 2520f
        p.color(c(0x1B1530))
        p.brush("acr-round", 26f) { it.copy(hardness = 0.9f, pressureSize = 0.1f, opacity = 1f, wetMix = 0f, grain = 0.1f) }
        p.line(px, 566f, px + 4f, 1750f, 2f, 1f, 1f, 1f)
        p.brush("acr-round", 14f) { it.copy(hardness = 0.9f, pressureSize = 0.1f, opacity = 1f, wetMix = 0f) }
        p.line(px - 128f, 632f, px + 128f, 624f, 1.5f, 1f, 1f, 1f)
        p.line(px - 96f, 706f, px + 96f, 700f, 1.5f, 1f, 1f, 1f)
        p.brush("acr-flat", 30f) { it.copy(pressureSize = 0.2f, opacity = 1f) }
        p.fill(px + 14f, 800f, px + 84f, 900f, 30f, 90f, spacing = 0.44f, wob = 1f)
        p.brush("gouache", 12f) { it.copy(wetMix = 0f, pressureSize = 0.2f) }
        for (ix in listOf(-120f, -60f, 60f, 120f)) p.dab(px + ix, 618f)
        for (ix in listOf(-88f, 88f)) p.dab(px + ix, 692f)
        fun sag(x0: Float, y0: Float, x1: Float, y1: Float, dip: Float): (Float) -> Float = { xx ->
            val t = ((xx - x0) / (x1 - x0)).coerceIn(0f, 1f)
            y0 + (y1 - y0) * t + dip * 4f * t * (1f - t)
        }
        val wires = listOf(
            sag(px - 120f, 616f, 40f, 470f, 150f), sag(px - 60f, 616f, 40f, 520f, 150f),
            sag(px - 88f, 690f, 40f, 610f, 140f), sag(px + 120f, 612f, W - 40f, 560f, 20f), sag(px + 60f, 614f, W - 40f, 600f, 22f)
        )
        val spans = listOf(px - 120f to 60f, px - 60f to 60f, px - 88f to 60f, px + 120f to W - 60f, px + 60f to W - 60f)
        p.brush("technical-pen", 4.5f) { it.copy(opacity = 0.95f) }
        for (i in wires.indices) {
            val f = wires[i]
            val (xa, xb) = spans[i]
            val pts = ArrayList<Pair<Float, Float>>()
            val n = 9
            for (j in 0..n) {
                val xx = xa + (xb - xa) * j / n
                pts.add(xx to f(xx))
            }
            p.stroke(p.path(pts, 0.8f, 0.9f, 1f, 0.9f))
        }
        // Warm edge of the pole.
        p.color(c(0xFF9A60))
        p.brush("wc-round", 7f) { it.copy(opacity = 0.75f, flow = 0.8f, wetEdges = 0f, taperIn = 0f) }
        p.line(px - 11f, 640f, px - 8f, 1500f, 2f, 0.5f, 0.7f, 0.3f)
        return wires[0]
    }

    // ------------------------------------------------------------ foreground

    private fun paintForeground(p: Painter) {
        val bottom: (Float, Float) -> Boolean = { xx, yy -> yy < H - 44f - 12f * (0.5f + 0.5f * sin(xx / 47f)) && xx > 46f + 10f * sin(yy / 29f) }
        // The ledge the cat sits on: a lit top and a dark face.
        val top = poly(40f to 1512f, 1560f to 1478f, 1650f to 1532f, 40f to 1582f)
        val face = poly(40f to 1580f, 1650f to 1530f, 1650f to 1770f, 40f to 1770f)
        p.brush("acr-flat", 64f) { it.copy(pressureSize = 0.2f, opacity = 0.98f) }
        p.fill(40f, 1520f, 1660f, 1770f, 64f, -2f, spacing = 0.42f, maxLen = 520f,
            colorAt = { xx, yy -> p.jitter(mix(c(0x2C2244), c(0x1B1530), ((yy - 1540f) / 220f).coerceIn(0f, 1f)), 0.025f) },
            inside = { xx, yy -> face(xx, yy) && bottom(xx, yy) })
        p.brush("acr-flat", 30f) { it.copy(pressureSize = 0.2f, opacity = 0.97f) }
        p.fill(40f, 1470f, 1660f, 1590f, 30f, -1.6f, spacing = 0.44f, maxLen = 460f,
            colorAt = { xx, _ -> p.jitter(mix(c(0x6A4A70), c(0xC47860), 0.75f * glowX(xx + 500f)), 0.03f) },
            inside = { xx, yy -> top(xx, yy) && bottom(xx, yy) })
        // Worn concrete: dry-brushed light, rain stains, cracks, a bright back edge.
        repeat(46) {
            val wx = p.r(70f, 1560f)
            val wy = p.r(1596f, 1730f)
            p.brush("acr-dry", p.r(36f, 70f)) { it.copy(opacity = p.r(0.12f, 0.28f), velocityOpacity = 0f) }
            p.color(p.jitter(mix(c(0x4A3862), c(0x7A4E62), 0.6f * glowX(wx + 500f)), 0.04f))
            p.line(wx, wy, wx + p.r(90f, 260f), wy + p.r(-8f, 4f), 3f, 0.3f, 0.8f, 0.2f)
        }
        repeat(12) {
            val wx = p.r(90f, 1600f)
            val wy0 = 1590f - (wx - 40f) * 0.03f
            p.brush("wc-wet-edge", p.r(18f, 40f)) { it.copy(flow = 0.12f, opacity = 0.7f) }
            p.color(c(0x120E22))
            p.line(wx, wy0, wx + p.r(-8f, 8f), wy0 + p.r(60f, 150f), 3f, 0.8f, 0.6f, 0.1f)
        }
        p.color(c(0x140F24))
        p.brush("pencil-6b", 5f) { it.copy(opacity = 0.45f) }
        repeat(4) {
            val cx0 = p.r(700f, 1500f)
            val cy0 = p.r(1600f, 1690f)
            p.curve(cx0 to cy0, cx0 + p.r(-14f, 14f) to cy0 + p.r(20f, 40f), cx0 + p.r(-24f, 24f) to cy0 + p.r(50f, 80f), wobble = 2f)
        }
        p.color(c(0xFFB478))
        p.brush("wc-round", 9f) { it.copy(opacity = 0.85f, flow = 0.85f, wetEdges = 0f, taperIn = 0f) }
        p.curve(60f to 1513f, 800f to 1497f, 1556f to 1480f, wobble = 2f, pIn = 0.4f, pMid = 0.8f, pOut = 0.5f)
        p.color(c(0xE89A6E))
        p.brush("wc-round", 7f) { it.copy(opacity = 0.6f, flow = 0.8f, wetEdges = 0f, taperIn = 0f) }
        p.curve(60f to 1581f, 820f to 1557f, 1646f to 1532f, wobble = 2f, pIn = 0.3f, pMid = 0.7f, pOut = 0.4f)

        // A planter on the ledge, full of leaves.
        p.brush("acr-flat", 34f) { it.copy(pressureSize = 0.2f, opacity = 0.98f) }
        p.fill(150f, 1440f, 600f, 1560f, 34f, 0f, spacing = 0.44f, wob = 2f,
            colorAt = { xx, _ -> p.jitter(mix(c(0x5A3142), c(0x9A5548), ((xx - 150f) / 450f)), 0.03f) },
            inside = poly(160f to 1446f, 590f to 1438f, 572f to 1556f, 178f to 1562f))
        p.color(c(0xFFAE74))
        p.brush("wc-round", 8f) { it.copy(opacity = 0.8f, flow = 0.8f, wetEdges = 0f, taperIn = 0f) }
        p.line(164f, 1447f, 588f, 1439f, 2f, 0.4f, 0.8f, 0.5f)
        p.line(589f, 1441f, 573f, 1552f, 1.5f, 0.7f, 0.6f, 0.2f)
        val greens = intArrayOf(c(0x1F3D36), c(0x2B5545), c(0x3B6E4E), c(0x284A3C))
        repeat(30) {
            val gx = p.r(170f, 590f)
            p.brush("foliage", p.r(60f, 100f))
            p.color(p.jitter(greens[p.rnd.nextInt(greens.size)], 0.04f))
            p.curve(gx to 1452f, gx + p.r(-30f, 30f) to p.r(1380f, 1430f), gx + p.r(-60f, 60f) to p.r(1320f, 1400f),
                wobble = 6f, pIn = 0.8f, pMid = 1f, pOut = 0.7f)
        }
        // Ivy spilling over the edge of the planter and down the wall.
        for (ix in listOf(200f, 265f, 330f, 520f, 560f)) {
            val len = p.r(90f, 230f)
            val pts = listOf(ix to 1556f, ix + p.r(-16f, 16f) to 1556f + len * 0.5f, ix + p.r(-30f, 30f) to 1556f + len)
            p.color(c(0x24443A))
            p.brush("taper-brush-pen", 4.5f) { it.copy(taperIn = 0f) }
            p.stroke(p.path(pts, 3f, 1f, 0.8f, 0.3f))
            p.brush("foliage", p.r(40f, 58f))
            p.color(p.jitter(greens[p.rnd.nextInt(greens.size)], 0.05f))
            p.stroke(p.path(pts, 8f, 0.9f, 1f, 0.6f))
            p.color(p.jitter(c(0x4E8A5C), 0.05f))
            p.brush("foliage", p.r(30f, 44f))
            p.stroke(p.path(pts, 12f, 0.6f, 0.9f, 0.5f))
        }
        // Cosmos: thin stems leaning toward the light, eight petals each.
        val petals = intArrayOf(c(0xFF8FB4), c(0xFFD9E6), c(0xF05C9A), c(0xFFB0C8), c(0xFFF0F4))
        val heads = ArrayList<Pair<Float, Float>>()
        repeat(11) { i ->
            val sx = 185f + i * 38f + p.r(-10f, 10f)
            val hx = sx + p.r(10f, 70f)
            val hy = p.r(1150f, 1385f)
            p.color(p.jitter(c(0x2E5A40), 0.04f))
            p.brush("taper-brush-pen", p.r(5f, 7f)) { it.copy(taperIn = 0f) }
            p.curve(sx to 1450f, sx + (hx - sx) * 0.3f + p.r(-10f, 10f) to 1450f + (hy - 1450f) * 0.5f, hx to hy + 8f,
                wobble = 2f, pIn = 1f, pMid = 0.8f, pOut = 0.5f)
            // A few feathery leaves on the stem.
            repeat(3) {
                val t = p.r(0.25f, 0.8f)
                val lx = sx + (hx - sx) * t
                val ly = 1450f + (hy - 1450f) * t
                p.brush("taper-brush-pen", 3.5f) { it.copy(taperIn = 0f) }
                p.line(lx, ly, lx + p.r(-34f, 34f), ly - p.r(8f, 26f), 1.5f, 0.9f, 0.6f, 0.2f)
            }
            heads.add(hx to hy)
        }
        for ((hx, hy) in heads) {
            val col = petals[p.rnd.nextInt(petals.size)]
            val rr = p.r(20f, 30f)
            val tilt = p.r(0f, 0.8f)
            for (k in 0 until 8) {
                val a = tilt + k * (Math.PI.toFloat() / 4f) + p.r(-0.08f, 0.08f)
                p.brush("gouache", p.r(11f, 15f)) { it.copy(wetMix = 0f, pressureSize = 0.6f, opacity = 0.95f) }
                p.color(p.jitter(col, 0.04f))
                p.line(hx + cos(a) * 5f, hy + sin(a) * 4f, hx + cos(a) * rr, hy + sin(a) * rr * 0.78f, 1f, 0.5f, 1f, 0.7f)
            }
            p.brush("gouache", 10f) { it.copy(wetMix = 0f, pressureSize = 0.2f) }
            p.color(p.jitter(c(0xFFCB45), 0.04f))
            p.dab(hx, hy)
        }
        // Buds.
        repeat(4) {
            val bx = p.r(220f, 640f)
            val by = p.r(1240f, 1400f)
            p.color(c(0x2E5A40))
            p.brush("taper-brush-pen", 4.5f) { it.copy(taperIn = 0f) }
            p.curve(bx - p.r(10f, 30f) to 1450f, bx - 6f to (1450f + by) / 2f, bx to by, wobble = 2f, pIn = 1f, pMid = 0.8f, pOut = 0.5f)
            p.brush("gouache", 11f) { it.copy(wetMix = 0f, pressureSize = 0.2f) }
            p.color(p.jitter(c(0xF05C9A), 0.04f))
            p.dab(bx, by - 3f)
        }
        // Weeds in the cracks at the far end of the ledge.
        repeat(34) {
            val gx = p.r(1380f, 1640f)
            val gy = 1500f + (gx - 1380f) * 0.12f + p.r(-4f, 10f)
            p.brush("taper-brush-pen", p.r(4f, 6.5f)) { it.copy(taperIn = 0f) }
            p.color(p.jitter(if (p.chance(0.25f)) c(0xD08A48) else greens[p.rnd.nextInt(greens.size)], 0.05f))
            val lean = p.r(-26f, 34f)
            p.curve(gx to gy, gx + lean * 0.4f to gy - p.r(30f, 60f), gx + lean to gy - p.r(60f, 120f), wobble = 2f, pIn = 1f, pMid = 0.7f, pOut = 0.1f)
        }

        // A branch reaching in from the top left, its leaves turning.
        p.color(c(0x2A1C2E))
        p.brush("taper-brush-pen", 26f) { it.copy(taperIn = 0f, pressureSize = 0.9f) }
        p.curve(40f to 96f, 260f to 150f, 520f to 172f, 760f to 236f, wobble = 5f, pIn = 1f, pMid = 0.75f, pOut = 0.12f)
        p.brush("taper-brush-pen", 13f) { it.copy(taperIn = 0f, pressureSize = 0.9f) }
        p.curve(250f to 148f, 360f to 230f, 430f to 330f, wobble = 4f, pIn = 1f, pMid = 0.7f, pOut = 0.1f)
        p.curve(470f to 168f, 600f to 120f, 720f to 104f, wobble = 4f, pIn = 1f, pMid = 0.7f, pOut = 0.1f)
        p.curve(120f to 118f, 170f to 220f, 150f to 320f, wobble = 4f, pIn = 1f, pMid = 0.7f, pOut = 0.1f)
        p.curve(620f to 196f, 700f to 290f, 690f to 360f, wobble = 4f, pIn = 1f, pMid = 0.7f, pOut = 0.1f)
        val leaf = intArrayOf(c(0x1E3A38), c(0x2C5246), c(0x244640), c(0x35604A))
        val warmLeaf = intArrayOf(c(0xE9A04A), c(0xD9783C), c(0xF2B95A), c(0xC2553A))
        val knots = listOf(
            90f to 150f, 180f to 120f, 300f to 170f, 420f to 180f, 540f to 190f, 660f to 220f, 740f to 250f,
            330f to 250f, 420f to 330f, 150f to 270f, 160f to 330f, 600f to 130f, 710f to 110f, 690f to 330f, 250f to 100f
        )
        // Dark masses of leaves first, then the ones the light catches.
        for ((kx, ky) in knots) {
            repeat(7) {
                p.brush("foliage", p.r(90f, 150f))
                p.color(p.jitter(leaf[p.rnd.nextInt(leaf.size)], 0.04f))
                val ax = kx + p.r(-70f, 70f)
                val ay = ky + p.r(-45f, 80f)
                p.curve(ax to ay, ax + p.r(-50f, 50f) to ay + p.r(10f, 60f), wobble = 8f, pIn = 0.8f, pMid = 1f, pOut = 0.8f)
            }
        }
        for ((kx, ky) in knots) {
            repeat(3) {
                p.brush("foliage", p.r(60f, 100f))
                p.color(p.jitter(warmLeaf[p.rnd.nextInt(warmLeaf.size)], 0.04f))
                val ax = kx + p.r(-50f, 80f)
                val ay = ky + p.r(-10f, 90f)
                p.curve(ax to ay, ax + p.r(-30f, 30f) to ay + p.r(10f, 40f), wobble = 6f, pIn = 0.7f, pMid = 1f, pOut = 0.7f)
            }
        }
        // Leaves let go.
        repeat(9) {
            val lx = p.r(260f, 1250f)
            val ly = p.r(420f, 1120f)
            p.brush("gouache", p.r(9f, 14f)) { it.copy(wetMix = 0f, pressureSize = 0.7f, opacity = 0.95f) }
            p.color(p.jitter(warmLeaf[p.rnd.nextInt(warmLeaf.size)], 0.04f))
            val a = p.r(0f, 6.28f)
            p.line(lx, ly, lx + cos(a) * 18f, ly + sin(a) * 12f, 1f, 0.3f, 1f, 0.3f)
        }
    }

    // ------------------------------------------------------------ cat, birds

    private fun paintLife(p: Painter, wire: (Float) -> Float) {
        // A loose flock heading home.
        val flock = listOf(
            Triple(1480f, 560f, 56f), Triple(1390f, 606f, 46f), Triple(1570f, 610f, 48f), Triple(1310f, 660f, 38f),
            Triple(1650f, 668f, 40f), Triple(1240f, 720f, 30f), Triple(1716f, 730f, 32f), Triple(1520f, 700f, 28f),
            Triple(1100f, 790f, 24f), Triple(1790f, 800f, 24f), Triple(980f, 850f, 20f)
        )
        p.color(c(0x2B2146))
        for ((bx, by, s) in flock) {
            p.brush("taper-brush-pen", s * 0.2f) { it.copy(taperIn = 0f) }
            val lift = p.r(0.3f, 0.6f)
            p.curve(bx - s to by - s * lift * 0.4f, bx - s * 0.45f to by - s * lift, bx to by, wobble = 1f, pIn = 0.15f, pMid = 0.9f, pOut = 1f)
            p.curve(bx to by, bx + s * 0.45f to by - s * lift, bx + s to by - s * lift * 0.3f, wobble = 1f, pIn = 1f, pMid = 0.9f, pOut = 0.15f)
        }
        // Sparrows on the wire.
        for (sx in listOf(1460f, 1520f, 1650f)) {
            val sy = wire(sx)
            p.color(c(0x1E1832))
            p.brush("gouache", 17f) { it.copy(wetMix = 0f, pressureSize = 0.2f, hardness = 0.9f) }
            p.line(sx - 5f, sy - 11f, sx + 5f, sy - 12f, 0.3f, 1f, 1f, 0.9f)
            p.brush("gouache", 11f) { it.copy(wetMix = 0f, pressureSize = 0.2f, hardness = 0.9f) }
            p.dab(sx + 8f, sy - 21f)
            p.brush("taper-brush-pen", 5f) { it.copy(taperIn = 0f) }
            p.line(sx - 9f, sy - 9f, sx - 24f, sy + 1f, 0.3f, 1f, 0.8f, 0.3f)
            p.line(sx + 13f, sy - 21f, sx + 19f, sy - 20f, 0.2f, 1f, 0.8f, 0.4f)
            p.color(c(0xFFA468))
            p.brush("wc-round", 3f) { it.copy(opacity = 0.75f, wetEdges = 0f, taperIn = 0f) }
            p.line(sx + 4f, sy - 25f, sx + 12f, sy - 26f, 0.3f, 0.5f, 0.8f, 0.4f)
        }
        // A butterfly over the cosmos.
        p.brush("gouache", 13f) { it.copy(wetMix = 0f, pressureSize = 0.6f) }
        p.color(c(0xFFF2C8))
        p.line(742f, 1210f, 722f, 1190f, 0.5f, 0.4f, 1f, 0.8f)
        p.line(746f, 1210f, 768f, 1194f, 0.5f, 0.4f, 1f, 0.8f)
        p.color(c(0xFFC66A))
        p.line(742f, 1214f, 730f, 1226f, 0.5f, 0.4f, 0.9f, 0.6f)
        p.line(746f, 1214f, 760f, 1224f, 0.5f, 0.4f, 0.9f, 0.6f)
        p.color(c(0x2B2146))
        p.brush("technical-pen", 3.5f)
        p.line(744f, 1204f, 744f, 1220f, 0.3f, 1f, 1f, 1f)

        // ---- The cat, seen from behind, watching the sun.
        val cx = 1020f
        val catDark = c(0x1D1630)
        // Its shadow, thrown along the ledge away from the light.
        p.brush("soft-brush", 60f) { it.copy(opacity = 0.4f, flow = 0.8f, pressureSize = 0.2f, hardness = 0.3f) }
        p.color(c(0x140F24))
        p.fill(690f, 1528f, 1090f, 1576f, 60f, -2f, spacing = 0.4f, inside = ellipse(890f, 1552f, 200f, 22f))
        // The silhouette, drawn as one outline: ears, cheeks, neck, shoulders,
        // the weight settling into the haunches.
        val leftSide = listOf(
            1020f to 1172f, 993f to 1175f, 964f to 1136f, 946f to 1194f, 939f to 1224f, 941f to 1256f,
            956f to 1282f, 980f to 1300f, 954f to 1326f, 930f to 1380f, 912f to 1442f, 902f to 1492f,
            910f to 1532f, 946f to 1549f, 1020f to 1553f
        )
        val rightSide = leftSide.reversed().drop(1).map { (x, y) -> (2f * 1020f - x) to y }
        val outline = smoothClosed(leftSide + rightSide.dropLast(1))
        val body = poly(*outline.toTypedArray())
        p.brush("acr-round", 24f) { it.copy(hardness = 0.85f, opacity = 0.98f, pressureSize = 0.2f, wetMix = 0f, grain = 0.15f) }
        p.fill(900f, 1126f, 1140f, 1556f, 24f, 84f, spacing = 0.36f, wob = 1.5f,
            colorAt = { _, _ -> p.jitter(catDark, 0.015f) }, inside = body)
        // Around the outline once more, so the silhouette is one clean shape.
        p.brush("acr-round", 12f) { it.copy(hardness = 0.9f, opacity = 0.98f, pressureSize = 0.1f, wetMix = 0f) }
        p.color(catDark)
        val inner = outline.map { (x, y) -> (x + (1020f - x) * 0.035f) to (y + (1370f - y) * 0.02f) }
        p.stroke(p.path(inner + inner.take(2), 0.6f, 0.9f, 1f, 0.9f, step = 4f))
        // Ear tips, kept sharp.
        p.brush("taper-brush-pen", 12f) { it.copy(taperIn = 0f) }
        p.color(catDark)
        p.line(976f, 1172f, 963f, 1132f, 0.3f, 1f, 0.7f, 0.1f)
        p.line(1064f, 1172f, 1077f, 1132f, 0.3f, 1f, 0.7f, 0.1f)
        // Tail, resting along the ledge with the tip curled up.
        p.brush("acr-round", 38f) { it.copy(hardness = 0.85f, opacity = 0.98f, pressureSize = 0.85f, wetMix = 0f) }
        p.curve(1096f to 1508f, 1190f to 1532f, 1290f to 1528f, 1348f to 1492f, 1340f to 1450f,
            wobble = 2f, pIn = 1f, pMid = 0.9f, pOut = 0.3f)
        // Fur: short strokes that follow the coat, a shade lighter where the
        // back turns toward the light.
        repeat(60) {
            val fx = p.r(915f, 1125f)
            val fy = p.r(1200f, 1530f)
            if (!body(fx, fy) || !body(fx, fy + 26f)) return@repeat
            val towardLight = ((fx - 940f) / 180f).coerceIn(0f, 1f)
            p.brush("taper-brush-pen", p.r(4f, 6f)) { it.copy(taperIn = 0f, opacity = 0.4f) }
            p.color(p.jitter(mix(c(0x171226), c(0x2E2242), towardLight * p.r(0.3f, 1f)), 0.02f))
            p.line(fx, fy, fx + p.r(-4f, 6f), fy + p.r(14f, 30f), 0.5f, 0.9f, 0.7f, 0.1f)
        }
        // A few hairs break the outline.
        p.brush("taper-brush-pen", 4.5f) { it.copy(taperIn = 0f) }
        for (i in outline.indices step 3) {
            val (ox, oy) = outline[i]
            if (oy > 1535f || oy < 1190f || !p.chance(0.55f)) continue
            val out = if (ox < 1020f) -1f else 1f
            p.color(p.jitter(catDark, 0.02f))
            p.line(ox - out * 4f, oy - 2f, ox + out * p.r(3f, 7f), oy + p.r(5f, 11f), 0.3f, 1f, 0.6f, 0.1f)
        }
        // The last light: a thin warm line down the side that faces the sun,
        // from the tip of the ear to the hip, lifted where the form turns.
        val lightSide = leftSide.subList(2, 13).map { (x, y) -> (2f * 1020f - x) to y }
        fun rimPart(from: Int, to: Int, size: Float, op: Float, col: Int, inset: Float) {
            val pts = lightSide.subList(from, to + 1).map { (x, y) -> (x - inset) to y }
            p.brush("wc-round", size) { it.copy(opacity = op, flow = 0.9f, wetEdges = 0f, taperIn = 0f) }
            p.color(col)
            p.stroke(p.path(pts, 0.8f, 0.15f, 0.9f, 0.15f, step = 4f))
        }
        val rimCol = c(0xFFB678)
        for ((a, b) in listOf(0 to 4, 6 to 8, 8 to 10)) {
            rimPart(a, b, 15f, 0.2f, c(0xD06A48), 7f)
            rimPart(a, b, 5.5f, 0.92f, rimCol, 3f)
        }
        p.brush("wc-round", 5f) { it.copy(opacity = 0.9f, flow = 0.9f, wetEdges = 0f, taperIn = 0f) }
        p.color(rimCol)
        p.line(968f, 1146f, 989f, 1174f, 0.8f, 0.2f, 0.7f, 0.15f)
        p.curve(1200f to 1518f, 1290f to 1513f, 1338f to 1486f, 1333f to 1456f, wobble = 1f, pIn = 0.15f, pMid = 0.8f, pOut = 0.2f)
        // Inside of the ears, just warm enough to read.
        p.brush("wc-round", 8f) { it.copy(opacity = 0.5f, flow = 0.8f, wetEdges = 0f, taperIn = 0f) }
        p.color(c(0x6A3A52))
        p.line(1068f, 1158f, 1060f, 1180f, 0.8f, 0.5f, 0.8f, 0.3f)
        p.line(972f, 1158f, 980f, 1180f, 0.8f, 0.5f, 0.8f, 0.3f)
    }
}
