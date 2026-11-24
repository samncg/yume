package com.yume.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One ONNX Runtime session per model file, made on first use (CPU). */
object Onnx {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<String, OrtSession>()
    private val users = HashMap<String, Int>()

    /**
     * Runs [block] with the model's session. Sessions in use are never
     * closed (closing one mid-run would crash in native code).
     */
    fun <T> use(file: File, block: (OrtSession) -> T): T {
        val s = synchronized(this) {
            users[file.path] = (users[file.path] ?: 0) + 1
            try {
                session(file)
            } catch (t: Throwable) {
                users[file.path] = (users[file.path] ?: 1) - 1
                throw t
            }
        }
        try {
            return block(s)
        } finally {
            synchronized(this) { users[file.path] = (users[file.path] ?: 1) - 1 }
        }
    }

    @Synchronized
    private fun session(file: File): OrtSession {
        sessions[file.path]?.let { return it }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        // Plain CPU execution, all cores. (The XNNPACK provider aborted the
        // process while loading IS-Net: a native abort cannot be caught.)
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
        }
        val s = env.createSession(file.path, options)
        sessions[file.path] = s
        return s
    }

    /** Frees the sessions nobody is using (their memory) after a tool closes. */
    @Synchronized
    fun closeIdle() {
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if ((users[e.key] ?: 0) > 0) continue
            runCatching { e.value.close() }
            it.remove()
        }
    }
}

/** Thrown when the user cancels a long AI job. */
class AiCancelled : RuntimeException("Cancelled")

/**
 * IS-Net foreground matte at [SIZE] x [SIZE] (the picture is stretched to a
 * square, as the model was trained). Input: unpremultiplied ARGB of that size.
 * Output: 0..1 per pixel, 1 = foreground.
 */
class Segmenter(private val modelFile: File) {

    /** [opts]: lets another thread stop the run (setTerminate). */
    fun matte(argb: IntArray, opts: OrtSession.RunOptions? = null): FloatArray {
        require(argb.size == SIZE * SIZE)
        val n = SIZE * SIZE
        // rembg's normalisation for IS-Net: divide by the brightest value,
        // subtract the ImageNet mean, unit std.
        var peak = 1
        for (p in argb) {
            peak = max(peak, max((p shr 16) and 255, max((p shr 8) and 255, p and 255)))
        }
        val scale = 1f / peak
        val input = FloatBuffer.allocate(3 * n)
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val p = argb[i]
            r[i] = ((p shr 16) and 255) * scale - 0.485f
            g[i] = ((p shr 8) and 255) * scale - 0.456f
            b[i] = (p and 255) * scale - 0.406f
        }
        input.put(r).put(g).put(b).rewind()
        val out = Onnx.use(modelFile) { session ->
            val inputName = session.inputNames.first()
            OnnxTensor.createTensor(Onnx.env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
                (if (opts != null) session.run(mapOf(inputName to t), opts) else session.run(mapOf(inputName to t))).use { res ->
                    val v = res.get(0) as OnnxTensor
                    val fb = v.floatBuffer
                    FloatArray(n).also { fb.get(it, 0, n) }
                }
            }
        }
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in out) { lo = min(lo, v); hi = max(hi, v) }
        android.util.Log.i("YumeAI", "matte raw range ${"%.3f".format(lo)}..${"%.3f".format(hi)}")
        lastPeak = hi
        // No clear subject (the model is unsure everywhere): stretching its
        // noise to 0..1, as rembg does, would keep random scraps. Callers
        // see [lastPeak] and keep the picture instead.
        if (hi < NO_SUBJECT) return FloatArray(n)
        val span = hi - lo
        if (span < 1e-6f) return FloatArray(n)
        for (i in 0 until n) out[i] = (out[i] - lo) / span
        return out
    }

    /** The model's highest score in the last [matte] (0..1). */
    @Volatile var lastPeak = 0f
        private set

    companion object {
        const val SIZE = 1024

        /** Below this peak score the model found no subject. */
        const val NO_SUBJECT = 0.35f
    }
}

/** Real-ESRGAN x4, run in [TILE] px tiles with [OVERLAP] px of context. */
class Upscaler(private val modelFile: File) {

    /** One tile: [rgb] is 3 x TILE x TILE planar 0..1; returns 3 x OUT x OUT. */
    fun runTile(rgb: FloatArray): FloatArray = Onnx.use(modelFile) { session -> runTile(session, rgb) }

    private fun runTile(session: OrtSession, rgb: FloatArray): FloatArray {
        val inputName = session.inputNames.first()
        val buf = FloatBuffer.wrap(rgb)
        return OnnxTensor.createTensor(Onnx.env, buf, longArrayOf(1, 3, TILE.toLong(), TILE.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val v = res.get(0) as OnnxTensor
                val fb = v.floatBuffer
                FloatArray(3 * OUT * OUT).also { fb.get(it) }
            }
        }
    }

    /**
     * Upscales unpremultiplied [argb] ([w] x [h]) by 4. Colour goes through
     * the model; alpha (when not fully opaque) goes through it too, as grey,
     * so edges stay as crisp as the colour. [onTile] reports progress and may
     * throw [AiCancelled].
     */
    fun upscale4(argb: IntArray, w: Int, h: Int, onTile: (done: Int, total: Int) -> Unit): IntArray =
        Onnx.use(modelFile) { session -> upscale4(session, argb, w, h, onTile) }

    /**
     * What the model makes of a flat tile of one colour (packed RGB), kept:
     * a flat window anywhere gets exactly this, so skipped tiles match the
     * tiles the model ran on around them.
     */
    private val flatCache = HashMap<Int, Int>()

    /** Harness: false runs every tile through the model (to compare). */
    @Volatile var skipFlat = true

    private fun flatResult(session: OrtSession, rgb: Int): Int = flatCache.getOrPut(rgb and 0xFFFFFF) {
        val tile = FloatArray(3 * TILE * TILE)
        val plane = TILE * TILE
        java.util.Arrays.fill(tile, 0, plane, ((rgb shr 16) and 255) / 255f)
        java.util.Arrays.fill(tile, plane, 2 * plane, ((rgb shr 8) and 255) / 255f)
        java.util.Arrays.fill(tile, 2 * plane, 3 * plane, (rgb and 255) / 255f)
        val res = runTile(session, tile)
        // The middle of the output, clear of the model's own zero border.
        val op = OUT * OUT
        var r = 0f; var g = 0f; var b = 0f; var n = 0
        for (y in OUT / 4 until OUT * 3 / 4 step 4) for (x in OUT / 4 until OUT * 3 / 4 step 4) {
            val k = y * OUT + x
            r += res[k]; g += res[op + k]; b += res[2 * op + k]; n++
        }
        fun c(v: Float) = (v / n * 255f).roundToInt().coerceIn(0, 255)
        (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun upscale4(session: OrtSession, argb: IntArray, w: Int, h: Int, onTile: (done: Int, total: Int) -> Unit): IntArray {
        val opaque = argb.all { (it ushr 24) == 255 }
        val color = if (opaque) argb else bleed(argb, w, h)
        val stride = TILE - 2 * OVERLAP
        val tx = max(1, (w + stride - 1) / stride)
        val ty = max(1, (h + stride - 1) / stride)
        val passes = if (opaque) 1 else 2
        // First, which tiles need the model: a window of one colour (or, for
        // alpha, one alpha) takes the cached flat result, and a window with
        // nothing visible needs no colour at all.
        val flatColor = IntArray(tx * ty) { -1 }
        val flatAlpha = IntArray(tx * ty) { -1 }
        val invisible = BooleanArray(tx * ty)
        var total = 0
        for (j in 0 until ty) for (i in 0 until tx) {
            val t = j * tx + i
            val wx0 = i * stride - OVERLAP
            val wy0 = j * stride - OVERLAP
            val c0 = color[mirror(wy0, h) * w + mirror(wx0, w)] and 0xFFFFFF
            val a0 = argb[mirror(wy0, h) * w + mirror(wx0, w)] ushr 24
            var oneColor = true
            var oneAlpha = true
            var visible = false
            for (y in 0 until TILE) {
                val row = mirror(wy0 + y, h) * w
                for (x in 0 until TILE) {
                    val k = row + mirror(wx0 + x, w)
                    if (oneColor && (color[k] and 0xFFFFFF) != c0) oneColor = false
                    val a = argb[k] ushr 24
                    if (a != 0) visible = true
                    if (oneAlpha && a != a0) oneAlpha = false
                }
                if (!oneColor && !oneAlpha && visible) break
            }
            invisible[t] = !visible && skipFlat
            if (oneColor && skipFlat) flatColor[t] = c0
            if (oneAlpha && skipFlat) flatAlpha[t] = a0
            if (!invisible[t] && flatColor[t] < 0) total++
            if (passes == 2 && flatAlpha[t] < 0) total++
        }
        var done = 0
        val ow = w * 4
        val oh = h * 4
        val out = IntArray(ow * oh)
        val tile = FloatArray(3 * TILE * TILE)
        val plane = TILE * TILE
        for (pass in 0 until passes) {
            val alphaPass = pass == 1
            for (j in 0 until ty) for (i in 0 until tx) {
                val t = j * tx + i
                // The tile's core (written) and its window (read, with context).
                val cx0 = i * stride
                val cy0 = j * stride
                val cx1 = min(w, cx0 + stride)
                val cy1 = min(h, cy0 + stride)
                if (!alphaPass && invisible[t]) continue
                val flat = if (alphaPass) flatAlpha[t].let { if (it < 0) -1 else (it shl 16) or (it shl 8) or it } else flatColor[t]
                if (flat >= 0) {
                    val v = flatResult(session, flat)
                    for (y in cy0 * 4 until cy1 * 4) for (x in cx0 * 4 until cx1 * 4) {
                        val o = y * ow + x
                        out[o] = if (alphaPass) {
                            val a = (((v shr 16) and 255) + ((v shr 8) and 255) + (v and 255) + 1) / 3
                            (out[o] and 0x00FFFFFF) or (a shl 24)
                        } else (0xFF shl 24) or v
                    }
                    continue
                }
                // The window may reach past the picture: mirrored pixels
                // there, so the model's own (zero-padded) border never lands
                // on the picture's edge.
                val wx0 = cx0 - OVERLAP
                val wy0 = cy0 - OVERLAP
                for (y in 0 until TILE) {
                    val sy = mirror(wy0 + y, h)
                    for (x in 0 until TILE) {
                        val sx = mirror(wx0 + x, w)
                        val p = color[sy * w + sx]
                        val k = y * TILE + x
                        if (alphaPass) {
                            val a = (argb[sy * w + sx] ushr 24) / 255f
                            tile[k] = a; tile[plane + k] = a; tile[2 * plane + k] = a
                        } else {
                            tile[k] = ((p shr 16) and 255) / 255f
                            tile[plane + k] = ((p shr 8) and 255) / 255f
                            tile[2 * plane + k] = (p and 255) / 255f
                        }
                    }
                }
                val res = runTile(session, tile)
                val op = OUT * OUT
                for (y in cy0 * 4 until cy1 * 4) {
                    val ty0 = y - wy0 * 4
                    if (ty0 < 0 || ty0 >= OUT) continue
                    for (x in cx0 * 4 until cx1 * 4) {
                        val tx0 = x - wx0 * 4
                        if (tx0 < 0 || tx0 >= OUT) continue
                        val k = ty0 * OUT + tx0
                        val o = y * ow + x
                        if (alphaPass) {
                            val a = ((res[k] + res[op + k] + res[2 * op + k]) / 3f * 255f).roundToInt().coerceIn(0, 255)
                            out[o] = (out[o] and 0x00FFFFFF) or (a shl 24)
                        } else {
                            val r = (res[k] * 255f).roundToInt().coerceIn(0, 255)
                            val g = (res[op + k] * 255f).roundToInt().coerceIn(0, 255)
                            val b = (res[2 * op + k] * 255f).roundToInt().coerceIn(0, 255)
                            out[o] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                onTile(++done, total)
            }
        }
        if (total == 0) onTile(0, 0)
        return out
    }

    companion object {
        const val TILE = 128
        const val OUT = 512
        const val OVERLAP = 12

        /** Reflects [i] into 0 until [n] (edge pixel not repeated). */
        fun mirror(i: Int, n: Int): Int {
            if (n <= 1) return 0
            var k = i
            val period = 2 * n - 2
            k = ((k % period) + period) % period
            return if (k < n) k else period - k
        }

        /**
         * Colours of fully transparent pixels are meaningless (often black):
         * spread the nearest visible colours into them so the model does not
         * see dark fringes around shapes. Alpha is kept as is.
         */
        fun bleed(argb: IntArray, w: Int, h: Int): IntArray {
            val out = argb.copyOf()
            val known = BooleanArray(w * h) { (argb[it] ushr 24) > 0 }
            if (known.none { it }) return out
            var frontier = true
            var guard = 0
            while (frontier && guard++ < 64) {
                frontier = false
                val next = known.copyOf()
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (known[i]) continue
                    var r = 0; var g = 0; var b = 0; var c = 0
                    if (x > 0 && known[i - 1]) { val p = out[i - 1]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++ }
                    if (x < w - 1 && known[i + 1]) { val p = out[i + 1]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++ }
                    if (y > 0 && known[i - w]) { val p = out[i - w]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++ }
                    if (y < h - 1 && known[i + w]) { val p = out[i + w]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++ }
                    if (c > 0) {
                        out[i] = (argb[i] and 0xFF000000.toInt()) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
                        next[i] = true
                        frontier = true
                    }
                }
                System.arraycopy(next, 0, known, 0, known.size)
            }
            return out
        }
    }
}

/** Settings of a background removal; defaults work for most pictures. */
data class BgSettings(
    /** 0 auto, 1 illustration, 2 photo. */
    val subject: Int = 0,
    /** Where the matte is cut, 0..1. */
    val threshold: Float = 0.5f,
    /** Edge softness, 0..1 (0 = hard cut at the threshold). */
    val softness: Float = 0.35f,
    /** Grow (+) or shrink (-) the kept area, in document px. */
    val grow: Float = 0f,
    /** Feather radius, document px. */
    val feather: Float = 0f,
    /** Drop loose specks smaller than this share of the picture (0..0.05). */
    val specks: Float = 0.002f,
    /** Keep the background instead of the subject. */
    val invert: Boolean = false,
    /** How close a colour must be for a keep stroke to take its surroundings (0..1). */
    val keepTolerance: Float = 0.12f
)

/**
 * Turns IS-Net's matte into the final mask, on the model's [Segmenter.SIZE]
 * grid. [hints]: 0 none, 1 keep, 2 remove (painted by the user, same grid).
 * [rgb]: the picture on the grid, for keep strokes that grow by colour.
 * [docScale]: grid px per document px (for px settings).
 */
object Matte {
    fun refine(raw: FloatArray, rgb: IntArray, hints: ByteArray?, s: BgSettings, docScale: Float): FloatArray {
        val n = Segmenter.SIZE
        val size = n * n
        val t = s.threshold.coerceIn(0.02f, 0.98f)
        val soft = (s.softness.coerceIn(0f, 1f) * 0.45f).coerceAtLeast(0.001f)
        // 1. Soft cut around the threshold.
        val a = FloatArray(size)
        for (i in 0 until size) a[i] = smooth(t - soft, t + soft, raw[i])
        // 2. Pieces of the cut-out (4-connected).
        val label = IntArray(size) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(size)
        for (i in 0 until size) {
            if (label[i] >= 0 || raw[i] < t) continue
            val id = sizes.size
            var sp = 0
            stack[sp++] = i
            label[i] = id
            var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                count++
                val x = p % n
                val y = p / n
                if (x > 0) { val q = p - 1; if (label[q] < 0 && raw[q] >= t) { label[q] = id; stack[sp++] = q } }
                if (x < n - 1) { val q = p + 1; if (label[q] < 0 && raw[q] >= t) { label[q] = id; stack[sp++] = q } }
                if (y > 0) { val q = p - n; if (label[q] < 0 && raw[q] >= t) { label[q] = id; stack[sp++] = q } }
                if (y < n - 1) { val q = p + n; if (label[q] < 0 && raw[q] >= t) { label[q] = id; stack[sp++] = q } }
            }
            sizes.add(count)
        }
        val drop = BooleanArray(sizes.size)
        val force = BooleanArray(sizes.size)
        // Remove marks: a piece mostly covered (a stray object) goes whole;
        // a mark on part of a bigger piece takes only the matching colours
        // there (the head, not the body it touches) - see step 4.
        val removeMarked = IntArray(sizes.size)
        if (hints != null) {
            for (i in 0 until size) {
                val l = label[i]
                if (l < 0) continue
                if (hints[i].toInt() == 2) removeMarked[l]++
                if (hints[i].toInt() == 1) force[l] = true
            }
            for (l in sizes.indices) if (removeMarked[l] * 5 >= sizes[l]) drop[l] = true
        }
        val minSize = (s.specks.coerceIn(0f, 0.05f) * size).toInt()
        // Specks: small loose pieces, unless the user kept them. The biggest
        // piece always stays (a tiny subject is still the subject).
        val biggest = sizes.indices.maxByOrNull { sizes[it] } ?: -1
        for (l in sizes.indices) if (sizes[l] < minSize && !force[l] && l != biggest) drop[l] = true
        // 3. Soft edges belong to the piece they surround: a dropped piece
        // takes its rim with it, by distance on the grid.
        for (i in 0 until size) {
            val l = label[i]
            if (l >= 0) {
                if (drop[l]) a[i] = 0f else if (force[l]) a[i] = 1f
            }
        }
        if (drop.any { it }) {
            // Rim pixels (below the threshold) next to a dropped piece go too.
            val rim = 3
            for (pass in 0 until rim) {
                val copy = label.copyOf()
                for (i in 0 until size) {
                    if (label[i] >= 0 || a[i] <= 0f) continue
                    val x = i % n
                    val y = i / n
                    var near = -1
                    if (x > 0 && label[i - 1] >= 0) near = label[i - 1]
                    else if (x < n - 1 && label[i + 1] >= 0) near = label[i + 1]
                    else if (y > 0 && label[i - n] >= 0) near = label[i - n]
                    else if (y < n - 1 && label[i + n] >= 0) near = label[i + n]
                    if (near >= 0) {
                        copy[i] = near
                        if (drop[near]) a[i] = 0f
                    }
                }
                System.arraycopy(copy, 0, label, 0, size)
            }
        }
        // 4. Keep strokes on the background take the matching colours around
        // them (like the magic wand); remove strokes on part of a piece take
        // the matching colours of that piece; both cover what they paint.
        if (hints != null) {
            val tol = (s.keepTolerance.coerceIn(0f, 1f) * 255f * 1.7f)
            val tol2 = tol * tol
            val gone = BooleanArray(size)
            for (seed in 0 until size) {
                if (hints[seed].toInt() != 2 || gone[seed]) continue
                val l = label[seed]
                if (l < 0 || drop[l]) continue
                val c0 = rgb[seed]
                var sp = 0
                stack[sp++] = seed
                gone[seed] = true
                while (sp > 0) {
                    val p = stack[--sp]
                    a[p] = 0f
                    val x = p % n
                    val y = p / n
                    fun visit(q: Int) {
                        if (gone[q] || hints[q].toInt() == 1) return
                        // Within the piece and its soft rim only.
                        if (label[q] != l && a[q] <= 0f) return
                        if (dist2(rgb[q], c0) > tol2 && hints[q].toInt() != 2) return
                        gone[q] = true
                        stack[sp++] = q
                    }
                    if (x > 0) visit(p - 1)
                    if (x < n - 1) visit(p + 1)
                    if (y > 0) visit(p - n)
                    if (y < n - 1) visit(p + n)
                }
            }
            val seen = BooleanArray(size)
            for (seed in 0 until size) {
                if (hints[seed].toInt() != 1 || seen[seed] || label[seed] >= 0 && !drop[label[seed]]) continue
                val c0 = rgb[seed]
                var sp = 0
                stack[sp++] = seed
                seen[seed] = true
                while (sp > 0) {
                    val p = stack[--sp]
                    a[p] = 1f
                    val x = p % n
                    val y = p / n
                    fun visit(q: Int) {
                        if (seen[q] || hints[q].toInt() == 2) return
                        if (dist2(rgb[q], c0) > tol2 && hints[q].toInt() != 1) return
                        seen[q] = true
                        stack[sp++] = q
                    }
                    if (x > 0) visit(p - 1)
                    if (x < n - 1) visit(p + 1)
                    if (y > 0) visit(p - n)
                    if (y < n - 1) visit(p + n)
                }
            }
            // Colour-matched areas take their anti-aliased rim with them (one
            // grid pixel), or a faint outline would stay behind.
            for (pass in 0 until 2) {
                val area = if (pass == 0) gone else seen
                val v = if (pass == 0) 0f else 1f
                val rim = ArrayList<Int>()
                for (i in 0 until size) {
                    if (area[i]) continue
                    val x = i % n
                    val y = i / n
                    if ((x > 0 && area[i - 1]) || (x < n - 1 && area[i + 1]) || (y > 0 && area[i - n]) || (y < n - 1 && area[i + n])) rim.add(i)
                }
                for (i in rim) if (hints[i].toInt() != (if (pass == 0) 1 else 2)) a[i] = v
            }
            for (i in 0 until size) {
                when (hints[i].toInt()) {
                    1 -> a[i] = 1f
                    2 -> a[i] = 0f
                }
            }
        }
        // 5. Grow / shrink, then feather (radii from document px).
        val g = (s.grow * docScale).roundToInt().coerceIn(-40, 40)
        if (g > 0) maxFilter(a, n, g) else if (g < 0) minFilter(a, n, -g)
        val f = (s.feather * docScale).roundToInt().coerceIn(0, 40)
        if (f > 0) boxBlur(a, n, f)
        if (s.invert) for (i in 0 until size) a[i] = 1f - a[i]
        return a
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun dist2(p: Int, q: Int): Float {
        val dr = ((p shr 16) and 255) - ((q shr 16) and 255)
        val dg = ((p shr 8) and 255) - ((q shr 8) and 255)
        val db = (p and 255) - (q and 255)
        return (dr * dr + dg * dg + db * db).toFloat()
    }

    /** Separable square max filter of radius [r] (dilate). */
    fun maxFilter(a: FloatArray, n: Int, r: Int) = rank(a, n, r, true)

    fun minFilter(a: FloatArray, n: Int, r: Int) = rank(a, n, r, false)

    private fun rank(a: FloatArray, n: Int, r: Int, isMax: Boolean) {
        val tmp = FloatArray(a.size)
        for (y in 0 until n) for (x in 0 until n) {
            var v = if (isMax) 0f else 1f
            for (k in max(0, x - r)..min(n - 1, x + r)) {
                val s = a[y * n + k]
                v = if (isMax) max(v, s) else min(v, s)
            }
            tmp[y * n + x] = v
        }
        for (y in 0 until n) for (x in 0 until n) {
            var v = if (isMax) 0f else 1f
            for (k in max(0, y - r)..min(n - 1, y + r)) {
                val s = tmp[k * n + x]
                v = if (isMax) max(v, s) else min(v, s)
            }
            a[y * n + x] = v
        }
    }

    /** Two box passes ~ a soft blur of radius [r]. */
    fun boxBlur(a: FloatArray, n: Int, r: Int) {
        val tmp = FloatArray(a.size)
        repeat(2) {
            for (y in 0 until n) {
                var sum = 0f
                for (k in -r..r) sum += a[y * n + k.coerceIn(0, n - 1)]
                for (x in 0 until n) {
                    tmp[y * n + x] = sum / (2 * r + 1)
                    sum += a[y * n + (x + r + 1).coerceAtMost(n - 1)] - a[y * n + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until n) {
                var sum = 0f
                for (k in -r..r) sum += tmp[k.coerceIn(0, n - 1) * n + x]
                for (y in 0 until n) {
                    a[y * n + x] = sum / (2 * r + 1)
                    sum += tmp[(y + r + 1).coerceAtMost(n - 1) * n + x] - tmp[(y - r).coerceAtLeast(0) * n + x]
                }
            }
        }
    }

    /**
     * Picture kind for "Auto": drawn art has large flat areas and clean
     * edges; photos have noise everywhere. Share of near-flat neighbours.
     */
    fun looksDrawn(rgb: IntArray, n: Int): Boolean {
        var flat = 0
        var total = 0
        var y = 1
        while (y < n - 1) {
            var x = 1
            while (x < n - 1) {
                val p = rgb[y * n + x]
                val q = rgb[y * n + x + 1]
                val r = rgb[(y + 1) * n + x]
                if (abs(lum(p) - lum(q)) <= 2 && abs(lum(p) - lum(r)) <= 2) flat++
                total++
                x += 3
            }
            y += 3
        }
        return total > 0 && flat.toFloat() / total > 0.55f
    }

    private fun lum(p: Int): Int = (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
}
