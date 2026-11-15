package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The heart of the brush engine.
 *
 * Input points are stabilised (assist), smoothed (streamline), converted to
 * pressure/tilt, then stamped along the path at a spacing proportional to the
 * current diameter.
 *
 * With a dense layer the stamp is one textured quad blit straight onto the
 * layer. Sparse layers have no whole-canvas surface, so each dab is rendered
 * once into a small reusable scratch bitmap sized to the dab itself and then
 * blended into the affected tiles with unscaled 1:1 blits. The scratch follows
 * the brush, never the canvas, so painting memory is independent of the
 * document size. See the Stage 2 notes in docs/reference/README.md.
 */
class StrokePainter(private val engine: DrawingEngine) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tileWritePaint = Paint().apply {
        blendMode = BlendMode.SRC
        isFilterBitmap = false
    }
    private val stampRect = Rect()
    private val dart = RectF()
    private val random = Random(0x5EED5EED)

    // Smudge machinery: sample the pixels under the previous stamp, mask them to
    // the dab shape, and stamp them at the current position. That drags real
    // texture along the stroke instead of painting a flat colour.
    private val smudgeBuffer = Bitmap.createBitmap(SMUDGE_RES, SMUDGE_RES, Bitmap.Config.ARGB_8888)
    private val smudgeCanvas = Canvas(smudgeBuffer)
    private val smudgeSrcPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val smudgeMaskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val smudgeDstPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val smudgeSrcRect = Rect()
    private val smudgeDstRect = RectF()
    private val smudgeFullRect = RectF()
    private var lastStampX = 0f
    private var lastStampY = 0f

    // Dab scratch: each dab is rendered here as colour + coverage and then
    // blended into the layer 1:1. Sized to the dab and never to the canvas, so
    // painting memory does not grow with the document, and the exact same
    // scratch geometry is used for the dense and the sparse store, which is what
    // keeps their results identical.
    private var dabScratch: Bitmap? = null
    private var dabScratchCanvas: Canvas? = null
    private val dabScratchPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
    )
    private val dabBlendPaint = Paint()
    private val dabSrcRect = Rect()
    private val dabDstRect = Rect()
    private val dabBounds = Rect()
    private val dabBoundsF = RectF()

    /** Scratch holding the smudge source region (dab bbox), never the canvas. */
    private var smudgePatch: Bitmap? = null
    private val smudgePatchRect = Rect()

    private var canvas: Canvas? = null
    private var store: TileStore? = null
    private var selectionClip: Path? = null
    private var selectionBounds: RectF? = null
    private var layer: Layer? = null
    private var preset: BrushPreset? = null
    private var dab: Bitmap? = null
    private var clipped = false

    var isActive = false
        private set

    /** Union of everything this stroke has touched, in document space. */
    val dirty = Rect()

    /**
     * Per-tile "before" snapshot for a sparse stroke. Owned by the session once
     * the stroke ends; null on the dense path.
     */
    var tileCapture: TileCapture? = null
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var smoothX = 0f
    private var smoothY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var distanceSinceStamp = 0f
    private var pressure = 1f
    private var tilt = 0f
    private var smudge = false
    private var baseAlpha = 1f

    /**
     * Stroke opacity below 1: the stroke is painted at its flow into shadow
     * tiles (a private copy of the pre-stroke layer), and the layer shows
     * pre-stroke + opacity * (shadow - pre-stroke). However often the stroke
     * crosses itself it never gets more opaque than the opacity, the way a
     * stroke layer at that opacity would look. 1 = off (dabs straight in).
     */
    private var strokeOpacity = 1f

    private class Shadow(val bmp: Bitmap, val canvas: Canvas, val base: IntArray)

    private val shadows = HashMap<Long, Shadow>()
    private var shadowPx = IntArray(0)
    private var shadowOut: Bitmap? = null
    private val shadowSrcPaint = Paint().apply { blendMode = BlendMode.SRC }
    private val shadowRect = Rect()
    private val shadowSrc = Rect()

    private fun releaseShadows() {
        for (sh in shadows.values) sh.bmp.recycle()
        shadows.clear()
    }

    /** The shadow tile ([tx], [ty]) of the stroke, made from the pre-stroke pixels. */
    private fun shadowAt(tx: Int, ty: Int, t: Int): Shadow {
        val k = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
        shadows[k]?.let { return it }
        val bmp = AllocTracker.bitmap("stroke.shadow", t, t)
        val c = Canvas(bmp)
        val st = store
        if (st != null) {
            tileCapture?.capture(tx, ty)
            val before = tileCapture?.beforeAt(tx, ty)
            if (before == null) c.drawColor(st.defaultPixel, BlendMode.SRC)
            else c.drawBitmap(before, null, Rect(0, 0, t, t), shadowSrcPaint)
        } else {
            val src = Rect(tx * t, ty * t, min(engine.width, tx * t + t), min(engine.height, ty * t + t))
            c.drawBitmap(engine.strokeBase, src, Rect(0, 0, src.width(), src.height()), shadowSrcPaint)
        }
        val base = IntArray(t * t)
        bmp.getPixels(base, 0, t, 0, 0, t, t)
        return Shadow(bmp, c, base).also { shadows[k] = it }
    }

    /**
     * Stamps the prepared dab (scratch + [dabBlendPaint] over [px]) into the
     * shadow tiles and shows pre-stroke + opacity * change on the layer.
     */
    private fun stampBuffered(scratch: Bitmap, px: Rect) {
        val st = store
        val t = st?.tileSize ?: 256
        val sel = selectionClip
        val o256 = (strokeOpacity * 256f).toInt().coerceIn(0, 256)
        val ty1 = (px.bottom - 1) / t
        val tx1 = (px.right - 1) / t
        for (ty in px.top / t..ty1) {
            for (tx in px.left / t..tx1) {
                val sh = shadowAt(tx, ty, t)
                val ox = tx * t
                val oy = ty * t
                val sc = sh.canvas
                val s0 = sc.save()
                sc.translate(-ox.toFloat(), -oy.toFloat())
                if (sel != null) sc.clipPath(sel)
                sc.drawBitmap(scratch, dabSrcRect, dabDstRect, dabBlendPaint)
                sc.restoreToCount(s0)
                val r = shadowRect
                r.set(max(px.left, ox), max(px.top, oy), min(px.right, ox + t), min(px.bottom, oy + t))
                if (!r.intersect(0, 0, engine.width, engine.height)) continue
                val w = r.width()
                val h = r.height()
                if (shadowPx.size < w * h) shadowPx = IntArray(w * h)
                val a = shadowPx
                sh.bmp.getPixels(a, 0, w, r.left - ox, r.top - oy, w, h)
                val base = sh.base
                var i = 0
                for (yy in 0 until h) {
                    var bi = (r.top - oy + yy) * t + (r.left - ox)
                    for (xx in 0 until w) {
                        val sv = a[i]
                        val bv = base[bi++]
                        a[i] = if (sv != bv) lerpPremul(bv, sv, o256) else bv
                        i++
                    }
                }
                var out = shadowOut
                if (out == null || out.isRecycled || out.width < w || out.height < h) {
                    val nw = max(w, out?.width ?: 0)
                    val nh = max(h, out?.height ?: 0)
                    out?.recycle()
                    out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
                    shadowOut = out
                }
                val ob = out!!
                ob.setPixels(a, 0, w, 0, 0, w, h)
                shadowSrc.set(0, 0, w, h)
                if (st == null) {
                    canvas?.drawBitmap(ob, shadowSrc, r, shadowSrcPaint)
                } else {
                    tileCapture?.capture(tx, ty)
                    st.editTile(tx, ty) { tc -> tc.drawBitmap(ob, shadowSrc, r, shadowSrcPaint) }
                }
            }
        }
    }

    /** b + o * (s - b) on premultiplied colour; [b], [s] and the result unpremultiplied ARGB. */
    private fun lerpPremul(b: Int, s: Int, o256: Int): Int {
        val ba = b ushr 24
        val sa = s ushr 24
        val a = ba + (((sa - ba) * o256) shr 8)
        if (a <= 0) return 0
        val bR = ((b shr 16) and 0xFF) * ba
        val bG = ((b shr 8) and 0xFF) * ba
        val bB = (b and 0xFF) * ba
        val r = bR + (((((s shr 16) and 0xFF) * sa) - bR) * o256 shr 8)
        val g = bG + (((((s shr 8) and 0xFF) * sa) - bG) * o256 shr 8)
        val bl = bB + ((((s and 0xFF) * sa) - bB) * o256 shr 8)
        val half = a / 2
        return (a shl 24) or
            (((r + half) / a).coerceIn(0, 255) shl 16) or
            (((g + half) / a).coerceIn(0, 255) shl 8) or
            ((bl + half) / a).coerceIn(0, 255)
    }

    /** Distance travelled in this stroke, drives the taper-in. */
    private var travel = 0f

    /** The brush's pressure curve (null = linear). */
    private var curve: PressureCurve? = null
    /** Pen speed (document px per input sample, smoothed) for velocity dynamics. */
    private var speed = 0f
    private var velocityOn = false
    /** Per-dab colour jitter: last colour and its filter, reused while equal. */
    private var jitterFilterColor = 0
    private var jitterFilter: android.graphics.ColorFilter? = null

    // Wet bleed scratch: the patch under the brush, its pixels and the mask.
    private var bleedPatch: Bitmap? = null
    private var bleedMask: Bitmap? = null
    private var bleedMaskCanvas: Canvas? = null
    private var bleedPx = IntArray(0)
    private var bleedM = IntArray(0)
    private val bleedRect = Rect()
    private val bleedDst = RectF()
    private val bleedMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun mapPressure(p: Float): Float {
        val c = curve ?: return p
        return c.map(p).coerceIn(0.02f, 1f)
    }

    /** Input stabiliser strength (0..1) shared by all brushes. */
    private var assist = 0f
    private val assistXs = FloatArray(24)
    private val assistYs = FloatArray(24)
    private val assistPs = FloatArray(24)

    /** The pen's own (mapped) pressure at the latest sample, unsmoothed. */
    private var rawPressure = 1f
    private var assistCount = 0
    private var assistHead = 0

    fun begin(
        target: Layer,
        brush: BrushPreset,
        color: Int,
        x: Float,
        y: Float,
        initialPressure: Float,
        initialTilt: Float,
        captureBase: Boolean = true,
        assistStrength: Float = 0f,
        startTaper: Boolean = true
    ) {
        layer = target
        preset = brush
        smudge = brush.category == BrushCategory.Smudge
        dab = BrushDab.dab(brush)
        curve = PressureCurve.of(brush.pressureCurve)
        velocityOn = brush.velocitySize != 0f || brush.velocityOpacity != 0f
        speed = 0f
        jitterFilter = null
        // Per-stroke colour jitter: the whole stroke takes one varied colour.
        val strokeColor = if (brush.category == BrushCategory.Brush &&
            (brush.strokeHueJitter > 0f || brush.strokeSatJitter > 0f || brush.strokeLumJitter > 0f)
        ) {
            jitterColor(color, brush.strokeHueJitter, brush.strokeSatJitter, brush.strokeLumJitter)
        } else {
            color
        }
        dirty.setEmpty()
        assist = assistStrength.coerceIn(0f, 1f)
        assistCount = 0
        assistHead = 0

        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = true
        // Painting a mask subtracts coverage (hides); the eraser puts coverage
        // back (reveals). That matches "paint to hide" expectations.
        val maskMode = target.editMask && target.hasMask
        paint.blendMode = when {
            maskMode && brush.category == BrushCategory.Eraser -> BlendMode.SRC_OVER
            maskMode -> BlendMode.DST_OUT
            brush.category == BrushCategory.Eraser -> BlendMode.DST_OUT
            target.alphaLock -> BlendMode.SRC_ATOP
            brush.category == BrushCategory.Brush -> BrushPreset.blendMode(brush.blend)
            else -> BlendMode.SRC_OVER
        }
        paint.colorFilter = when {
            maskMode && brush.category == BrushCategory.Eraser ->
                PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            maskMode -> null
            brush.category == BrushCategory.Brush -> PorterDuffColorFilter(strokeColor, PorterDuff.Mode.SRC_IN)
            else -> null
        }

        // Opacity caps the whole stroke (see [strokeOpacity]); the flow is
        // what each dab lays down. Smudge and wet-bleed brushes keep the old
        // per-dab meaning: they move paint that is already there.
        releaseShadows()
        strokeOpacity = if (!smudge && !brush.bleeds && brush.opacity < 0.995f &&
            (brush.category == BrushCategory.Brush || brush.category == BrushCategory.Eraser)
        ) brush.opacity.coerceIn(0.01f, 1f) else 1f
        baseAlpha = if (strokeOpacity < 1f) brush.flow.coerceIn(0.02f, 1f)
        else (brush.opacity * brush.flow).coerceIn(0.02f, 1f)
        brushColor = strokeColor
        mixColor = strokeColor
        mixFilter = null
        travelAngle = 0f

        val useSparse = target.paintStore.isSparse
        if (useSparse) {
            val existing = tileCapture
            if (captureBase || existing == null || existing.store !== target.paintStore) {
                existing?.release()
                tileCapture = TileCapture(target.paintStore)
            }
            store = target.paintStore
            // The dab is rendered into the scratch with a neutral blend and then
            // composited with the real one, so the scratch only has to hold the
            // dab's colour and coverage.
            selectionClip = engine.selectionClip
            selectionBounds = selectionClip?.let { p ->
                RectF().also { p.computeBounds(it, true) }
            }
            canvas = null
            clipped = false
        } else {
            tileCapture?.release()
            tileCapture = null
            store = null
            selectionClip = null
            selectionBounds = null
            paint.isFilterBitmap = true
            smudgeDstPaint.isFilterBitmap = true
            val c = target.paintStore.writeCanvas()
            val selPath = engine.selectionClip
            if (selPath != null) {
                c.save()
                c.clipPath(selPath)
                clipped = true
            } else {
                clipped = false
            }
            canvas = c
            engine.captureStrokeBase(target, captureBase)
        }

        lastX = x
        lastY = y
        smoothX = x
        smoothY = y
        lastRawX = x
        lastRawY = y
        lastStampX = x
        lastStampY = y
        distanceSinceStamp = 0f
        // A drawn shape starts at full size: no taper where its ends meet.
        travel = if (startTaper) 0f else 1e9f
        pressure = mapPressure(initialPressure.coerceIn(0.02f, 1f))
        rawPressure = pressure
        tilt = initialTilt.coerceIn(0f, 1f)
        isActive = true

        stampAt(x, y, sizeFor(pressure, tilt), alphaFor(pressure, tilt))
    }

    fun move(x: Float, y: Float, pressureIn: Float, tiltIn: Float) {
        if (!isActive) return
        val brush = preset ?: return

        if (velocityOn) {
            val d = hypot(x - lastRawX, y - lastRawY)
            speed += (d - speed) * 0.3f
        }
        lastRawX = x
        lastRawY = y
        val mapped = mapPressure(pressureIn.coerceIn(0.02f, 1f))
        rawPressure = mapped
        var targetP = mapped

        // 1. Stroke assist: average the last few raw samples. A wide average
        //    removes hand tremor without adding much latency.
        var targetX = x
        var targetY = y
        if (assist > 0.01f) {
            assistXs[assistHead] = x
            assistYs[assistHead] = y
            assistPs[assistHead] = mapped
            assistHead = (assistHead + 1) % assistXs.size
            if (assistCount < assistXs.size) assistCount++

            val wanted = (1 + assist * (assistXs.size - 1)).toInt().coerceAtMost(assistCount)
            var sx = 0f
            var sy = 0f
            var sp = 0f
            var idx = assistHead - 1
            for (i in 0 until wanted) {
                if (idx < 0) idx += assistXs.size
                sx += assistXs[idx]
                sy += assistYs[idx]
                sp += assistPs[idx]
                idx--
            }
            targetX = sx / wanted
            targetY = sy / wanted
            // Pressure averaged over the same samples: it must lag exactly as
            // much as the position, or lifting the pen thins the stroke far
            // behind the point where the pen left.
            targetP = sp / wanted
        }

        // 2. Streamline: chase the (assisted) target.
        val k = 1f - (brush.smoothing.coerceIn(0f, 1f) * 0.88f)
        smoothX += (targetX - smoothX) * max(k, 0.08f)
        smoothY += (targetY - smoothY) * max(k, 0.08f)

        // Pressure follows at the streamline rate when that is slower than its
        // own (0.45), for the same reason.
        pressure += (targetP - pressure) * min(0.45f, max(k, 0.08f))
        tilt += (tiltIn.coerceIn(0f, 1f) - tilt) * 0.3f

        val size = sizeFor(pressure, tilt)
        // Smudge and wet bleed need a coarser stamp interval: each stamp
        // reads and rewrites pixels.
        val spacingFactor = when {
            brush.bleeds -> max(0.1f, brush.spacing)
            smudge -> max(0.06f, brush.spacing)
            else -> max(0.02f, brush.spacing)
        }
        val step = max(0.8f, size * spacingFactor)

        var dx = smoothX - lastX
        var dy = smoothY - lastY
        val dist = hypot(dx, dy)
        if (dist < 0.0005f) return

        val ux = dx / dist
        val uy = dy / dist
        travelAngle = Math.toDegrees(kotlin.math.atan2(uy, ux).toDouble()).toFloat()
        var remaining = dist
        var px = lastX
        var py = lastY
        var guard = 0

        while (distanceSinceStamp + remaining >= step && guard < 4096) {
            val advance = step - distanceSinceStamp
            px += ux * advance
            py += uy * advance
            remaining -= advance
            distanceSinceStamp = 0f
            travel += step
            stampAt(px, py, size, alphaFor(pressure, tilt))
            guard++
        }
        distanceSinceStamp += remaining
        lastX = smoothX
        lastY = smoothY
    }

    fun end() {
        if (!isActive) return
        // Assist and streamline both lag behind the input; close the gap so the
        // stroke always reaches the point where the user lifted the pen.
        val brush = preset
        if (brush != null) {
            val dx = lastRawX - lastX
            val dy = lastRawY - lastY
            if (hypot(dx, dy) > 0.75f) {
                // The pen is lifting, so the last pressure is near zero: drawn at
                // that size alone the gap became a thin stub or a dot sticking
                // out of the full-width stroke. It narrows from the last stamp
                // instead, so the stroke tapers into the lift point.
                // Down to the pressure the pen really had when it left.
                val endSize = sizeFor(rawPressure, tilt)
                val endAlpha = alphaFor(rawPressure, tilt)
                val fromSize = if (lastStampSize > 0f) lastStampSize else endSize
                val fromAlpha = if (lastStampSize > 0f) lastStampAlpha else endAlpha
                stampTaper(lastX, lastY, lastRawX, lastRawY, fromSize, endSize, fromAlpha, endAlpha)
            }
        }
        val c = canvas
        if (c != null && clipped) {
            runCatching { c.restore() }
        }
        canvas = null
        layer = null
        preset = null
        dab = null
        isActive = false
        clipped = false
        store = null
        selectionClip = null
        selectionBounds = null
        releaseShadows()
        strokeOpacity = 1f
        // tileCapture deliberately survives: the session turns it into a patch.
    }

    private fun sizeFor(pressure: Float, tilt: Float): Float {
        val brush = preset ?: return 1f
        val p = 1f - brush.pressureSize.coerceIn(0f, 1f) * (1f - pressure)
        val t = 1f + brush.tiltSize.coerceIn(0f, 1f) * tilt * 2.2f
        if (velocityOn && brush.velocitySize != 0f) {
            val v = (1f + brush.velocitySize * speedFactor(brush)).coerceAtLeast(0.15f)
            return max(1.2f, brush.size * p * t * v)
        }
        return max(1.2f, brush.size * p * t)
    }

    /** 0 (still) .. 1 (fast), relative to the brush size. */
    private fun speedFactor(brush: BrushPreset): Float =
        (speed / max(2f, brush.size * 0.35f)).coerceIn(0f, 1f)

    private fun alphaFor(pressure: Float, tilt: Float): Float {
        val brush = preset ?: return 1f
        val p = 1f - brush.pressureOpacity.coerceIn(0f, 1f) * (1f - pressure)
        val to = brush.tiltOpacity.coerceIn(0f, 1f)
        // Leaning the pen shades lighter (exactly 1 when the brush ignores tilt).
        val t = if (to > 0f) 1f - to * tilt * 0.75f else 1f
        if (velocityOn && brush.velocityOpacity != 0f) {
            val v = (1f + brush.velocityOpacity * speedFactor(brush)).coerceIn(0.05f, 2f)
            return (baseAlpha * p * t * v).coerceIn(0.004f, 1f)
        }
        return (baseAlpha * p * t).coerceIn(0.004f, 1f)
    }

    /**
     * Taper so a stroke starts as a point instead of a full-size blob, which
     * is what a stylus press looks like when the pen lands at full pressure.
     */
    private fun taper(size: Float): Float {
        val full = max(6f, size * 1.1f)
        if (travel >= full) return size
        val f = 0.42f + 0.58f * (travel / full)
        return size * f.coerceIn(0.42f, 1f)
    }

    /** Pen azimuth in radians, set by the session before each move. */
    @Volatile var orientation = 0f
    /** Direction of travel in degrees (for direction-following stamps). */
    private var travelAngle = 0f
    /** Wet mix: the colour the brush is carrying right now. */
    private var mixColor = 0
    private var mixFilterColor = 0
    private var mixFilter: android.graphics.ColorFilter? = null
    private var brushColor = 0

    /** Where the pen is painting and how big the dab is right now (cursor). */
    @Volatile var liveX = 0f
        private set
    @Volatile var liveY = 0f
        private set
    @Volatile var liveSize = 0f
        private set

    private fun stampAt(x: Float, y: Float, size0: Float, alpha0: Float) {
        lastStampSize = size0
        lastStampAlpha = alpha0
        var size = size0
        var alpha = alpha0
        val brush0 = preset
        if (brush0 != null && brush0.taperIn > 0f) {
            // Fade in over the first taperIn brush widths.
            val f = (travel / max(1f, brush0.taperIn * brush0.size)).coerceIn(0f, 1f)
            size *= 0.25f + 0.75f * f
            alpha *= f
        }
        val sized = taper(size)
        liveX = x
        liveY = y
        liveSize = sized
        val ang = stampAngle()
        stampInternal(x, y, sized, alpha, ang)
        if (engine.symmetryX) {
            stampInternal(engine.symmetryXPos * 2f - x, y, sized, alpha, if (ang == 0f) 0f else 180f - ang)
        }
        if (engine.symmetryY) {
            stampInternal(x, engine.symmetryYPos * 2f - y, sized, alpha, -ang)
        }
        if (engine.symmetryX && engine.symmetryY) {
            stampInternal(
                engine.symmetryXPos * 2f - x,
                engine.symmetryYPos * 2f - y,
                sized,
                alpha,
                if (ang == 0f) 0f else ang + 180f
            )
        }
        val radial = engine.symmetryRadial
        if (radial >= 2) {
            // Copies rotated about the symmetry centre; with mirror on, each
            // segment is also reflected across its own axis.
            val cx = engine.symmetryXPos
            val cy = engine.symmetryYPos
            val dx = x - cx
            val dy = y - cy
            val step = (2.0 * Math.PI / radial)
            for (k in 0 until radial) {
                val a = step * k
                val c = kotlin.math.cos(a).toFloat()
                val s = kotlin.math.sin(a).toFloat()
                val turn = (360f / radial) * k
                if (k > 0) stampInternal(cx + dx * c - dy * s, cy + dx * s + dy * c, sized, alpha, if (ang == 0f) 0f else ang + turn)
                if (engine.symmetryMirror) {
                    // Reflect across the horizontal axis, then rotate.
                    stampInternal(cx + dx * c + dy * s, cy + dx * s - dy * c, sized, alpha, if (ang == 0f) 0f else turn - ang)
                }
            }
        }
        lastStampX = x
        lastStampY = y
    }

    /** Size / alpha of the last stamp of the stroke (0 = none yet). */
    private var lastStampSize = 0f
    private var lastStampAlpha = 0f

    /**
     * Stamps from (x0, y0) to (x1, y1) with size and alpha moving linearly
     * from the first values to the second, spacing following the size.
     */
    private fun stampTaper(
        x0: Float, y0: Float, x1: Float, y1: Float,
        size0: Float, size1: Float, alpha0: Float, alpha1: Float
    ) {
        val brush = preset ?: return
        val dx = x1 - x0
        val dy = y1 - y0
        val dist = hypot(dx, dy)
        if (dist < 0.001f) return
        val ux = dx / dist
        val uy = dy / dist
        val spacing = max(0.02f, brush.spacing)
        var t = max(0.8f, size0 * spacing)
        var guard = 0
        while (t <= dist && guard < 4096) {
            val f = t / dist
            val size = size0 + (size1 - size0) * f
            travel += max(0.8f, size * spacing)
            stampAt(x0 + ux * t, y0 + uy * t, size, alpha0 + (alpha1 - alpha0) * f)
            t += max(0.8f, size * spacing)
            guard++
        }
    }

    private fun stampLine(x0: Float, y0: Float, x1: Float, y1: Float, size: Float, alpha: Float) {
        val brush = preset ?: return
        val step = max(0.8f, size * max(0.02f, brush.spacing))
        val dx = x1 - x0
        val dy = y1 - y0
        val dist = hypot(dx, dy)
        if (dist < 0.001f) return
        val ux = dx / dist
        val uy = dy / dist
        var t = step
        var guard = 0
        while (t <= dist && guard < 4096) {
            travel += step
            stampAt(x0 + ux * t, y0 + uy * t, size, alpha)
            t += step
            guard++
        }
    }

    /** The stamp angle in degrees for the preset's rotation mode (0 = upright). */
    private fun stampAngle(): Float {
        val brush = preset ?: return 0f
        val base = when (brush.rotationMode) {
            1 -> travelAngle
            2 -> Math.toDegrees(orientation.toDouble()).toFloat()
            3 -> random.nextFloat() * 360f
            else -> 0f
        }
        return base + brush.rotation
    }

    /**
     * Wet mix: before a dab, the carried colour takes in some of the layer's
     * colour under the dab and drifts back toward the brush colour, so paint
     * smears into what is already there (oil, watercolour). Reads the layer
     * store, so dense and sparse layers mix identically.
     */
    private fun wetMixAt(x: Float, y: Float, brush: BrushPreset) {
        val target = layer ?: return
        val ix = x.toInt()
        val iy = y.toInt()
        if (ix < 0 || iy < 0 || ix >= engine.width || iy >= engine.height) return
        val under = target.paintStore.getPixel(ix, iy)
        val ua = under ushr 24
        val pick = brush.wetMix.coerceIn(0f, 1f) * 0.35f * (ua / 255f)
        val back = (1f - brush.wetMix.coerceIn(0f, 1f)) * 0.08f + 0.02f
        fun ch(c: Int, sh: Int) = (c shr sh) and 0xFF
        fun mix(sh: Int): Int {
            var v = ch(mixColor, sh).toFloat()
            v += (ch(under, sh) - v) * pick
            v += (ch(brushColor, sh) - v) * back
            return v.toInt().coerceIn(0, 255)
        }
        mixColor = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        if (mixFilter == null || mixColor != mixFilterColor) {
            mixFilterColor = mixColor
            mixFilter = PorterDuffColorFilter(mixColor, PorterDuff.Mode.SRC_IN)
        }
        paint.colorFilter = mixFilter
    }

    private fun stampInternal(x: Float, y: Float, size: Float, alpha: Float, angle: Float = 0f) {
        val d = dab ?: return
        val brush = preset ?: return

        var sx = x
        var sy = y
        if (brush.jitter > 0.01f) {
            sx += (random.nextFloat() - 0.5f) * brush.jitter * size * 2f
            sy += (random.nextFloat() - 0.5f) * brush.jitter * size * 2f
        }

        var sz = size
        var al = alpha
        var ang = angle
        // Per-dab dynamics; nothing is drawn from the generator unless used.
        if (brush.sizeJitter > 0f) sz *= 1f - brush.sizeJitter * 0.85f * random.nextFloat()
        if (brush.opacityJitter > 0f) al *= 1f - brush.opacityJitter * random.nextFloat()
        if (brush.angleJitter > 0f) ang += (random.nextFloat() - 0.5f) * 2f * brush.angleJitter

        val half = sz * 0.5f
        if (half < 0.3f) return

        val profile = CompositeProfile.enabled
        val t0 = if (profile) System.nanoTime() else 0L
        var reachExtra = 0f
        if (brush.sprayCount > 1) {
            // Spray: several small particles scattered over the brush disc.
            val pHalf = max(0.5f, half * brush.sprayParticle.coerceIn(0.05f, 1f))
            val spread = half * (1f + brush.jitter)
            for (k in 0 until brush.sprayCount) {
                val a = random.nextFloat() * 6.2831855f
                val r = spread * kotlin.math.sqrt(random.nextFloat())
                dabOnce(d, brush, sx + kotlin.math.cos(a) * r, sy + kotlin.math.sin(a) * r, pHalf, al, ang)
            }
            reachExtra = spread - half + pHalf
        } else {
            dabOnce(d, brush, sx, sy, half, al, ang)
        }
        if (profile) {
            CompositeProfile.paintNanos += System.nanoTime() - t0
            CompositeProfile.paintDabs++
        }

        // A rotated stamp reaches past its square: up to the half-diagonal.
        var reach = if (ang != 0f) half * 1.4143f else half
        reach += reachExtra
        if (brush.bleeds) reach = max(reach, half * (1f + brush.bleedSpread) + 1f)
        stampRect.set(
            (sx - reach).toInt() - 2,
            (sy - reach).toInt() - 2,
            (sx + reach).toInt() + 2,
            (sy + reach).toInt() + 2
        )
        engine.markDirty(stampRect)
        if (dirty.isEmpty) dirty.set(stampRect) else dirty.union(stampRect)
    }

    /** One dab: smudge, wet bleed, or paint (with colour jitter and wet mix). */
    private fun dabOnce(d: Bitmap, brush: BrushPreset, sx: Float, sy: Float, half: Float, alpha: Float, angle: Float) {
        val maskEdit = layer?.editMask == true
        if (smudge) {
            if (brush.bleeds && !maskEdit) {
                stampBleed(d, brush, sx, sy, half, alpha, brush.bleed, brush.bleedPaint)
            } else {
                stampSmudge(d, brush, sx, sy, half)
            }
            return
        }
        if (brush.wetMix > 0.001f && brush.category == BrushCategory.Brush && !maskEdit) {
            wetMixAt(sx, sy, brush)
        }
        if (brush.category == BrushCategory.Brush && !maskEdit &&
            (brush.hueJitter > 0f || brush.satJitter > 0f || brush.lumJitter > 0f)
        ) {
            val base = if (brush.wetMix > 0.001f) mixColor else brushColor
            val c = jitterColor(base, brush.hueJitter, brush.satJitter, brush.lumJitter)
            if (jitterFilter == null || c != jitterFilterColor) {
                jitterFilterColor = c
                jitterFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
            }
            paint.colorFilter = jitterFilter
        }
        paint.alpha = (alpha * 255f).toInt().coerceIn(1, 255)
        dabAngle = angle
        drawDab(d, sx - half, sy - half, sx + half, sy + half, paint)
        dabAngle = 0f
        // A wet brush: the paint just laid down bleeds into its surroundings.
        if (brush.bleeds && brush.category == BrushCategory.Brush && !maskEdit) {
            stampBleed(d, brush, sx, sy, half, alpha, brush.bleed * 0.7f, brush.bleedPaint)
        }
    }

    /** [c] with hue / saturation / lightness varied at random by the given amounts. */
    private fun jitterColor(c: Int, h: Float, s: Float, l: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (h > 0f) hsv[0] = ((hsv[0] + (random.nextFloat() - 0.5f) * 2f * h * 180f) % 360f + 360f) % 360f
        if (s > 0f) hsv[1] = (hsv[1] + (random.nextFloat() - 0.5f) * 2f * s * 0.6f).coerceIn(0f, 1f)
        if (l > 0f) hsv[2] = (hsv[2] + (random.nextFloat() - 0.5f) * 2f * l * 0.5f).coerceIn(0f, 1f)
        return Color.HSVToColor(c ushr 24, hsv)
    }

    /**
     * Wet bleed under one dab ([WetBleed]): reads the pixels around the brush,
     * diffuses them where the (widened) dab covers, and writes them back 1:1.
     */
    private fun stampBleed(
        d: Bitmap, brush: BrushPreset, x: Float, y: Float, half: Float,
        alpha: Float, strength: Float, paintAmount: Float
    ) {
        val target = layer ?: return
        val reach = half * (1f + brush.bleedSpread)
        val rect = bleedRect
        rect.set(
            floor(x - reach).toInt() - 1, floor(y - reach).toInt() - 1,
            ceil(x + reach).toInt() + 1, ceil(y + reach).toInt() + 1
        )
        if (!rect.intersect(0, 0, engine.width, engine.height)) return
        val sel = selectionClip
        val selBounds = selectionBounds
        if (sel != null && selBounds != null) {
            dabBoundsF.set(rect)
            if (!RectF.intersects(selBounds, dabBoundsF)) return
        }
        val w = rect.width()
        val h = rect.height()
        val n = w * h
        val patch = bleedPatch?.takeIf { !it.isRecycled && it.width >= w && it.height >= h }
            ?: Bitmap.createBitmap(max(w, bleedPatch?.width ?: 0), max(h, bleedPatch?.height ?: 0), Bitmap.Config.ARGB_8888)
                .also { bleedPatch?.recycle(); bleedPatch = it }
        val maskBmp = bleedMask?.takeIf { !it.isRecycled && it.width >= w && it.height >= h }
            ?: Bitmap.createBitmap(patch.width, patch.height, Bitmap.Config.ARGB_8888)
                .also { bleedMask?.recycle(); bleedMask = it; bleedMaskCanvas = Canvas(it) }
        val mc = bleedMaskCanvas ?: Canvas(maskBmp).also { bleedMaskCanvas = it }
        if (bleedPx.size < n) {
            bleedPx = IntArray(n)
            bleedM = IntArray(n)
        }
        // The layer under the brush, exactly as stored (both stores alike).
        val sub = if (patch.width == w && patch.height == h) patch
        else Bitmap.createBitmap(patch, 0, 0, w, h)
        val b0 = System.nanoTime()
        target.paintStore.readRectInto(rect, sub)
        sub.getPixels(bleedPx, 0, w, 0, 0, w, h)
        bleedRead += System.nanoTime() - b0
        // The widened dab as the bleed mask, scaled by the stroke's alpha.
        mc.drawColor(0, BlendMode.CLEAR)
        bleedDst.set(x - reach - rect.left, y - reach - rect.top, x + reach - rect.left, y + reach - rect.top)
        bleedMaskPaint.alpha = 255
        mc.drawBitmap(d, null, bleedDst, bleedMaskPaint)
        maskBmp.getPixels(bleedM, 0, w, 0, 0, w, h)
        val k = alpha.coerceIn(0f, 1f) / max(0.02f, (brush.opacity * brush.flow).coerceIn(0.02f, 1f))
        val scale = k.coerceIn(0f, 1f)
        for (i in 0 until n) bleedM[i] = ((bleedM[i] ushr 24) * scale).toInt()
        WetBleed.apply(
            bleedPx, w, h, bleedM, rect.left, rect.top,
            half, strength.coerceIn(0f, 1f),
            brushColor, paintAmount.coerceIn(0f, 1f) * (brush.opacity * brush.flow).coerceIn(0f, 1f),
            target.alphaLock
        )
        val b1 = System.nanoTime()
        sub.setPixels(bleedPx, 0, w, 0, 0, w, h)
        blitReplace(sub, rect)
        bleedWrite += System.nanoTime() - b1
        bleedDabs++
        if (sub !== patch) sub.recycle()
    }

    /** Wet-bleed stage timings (ns) and dab count, read by the harness. */
    var bleedRead = 0L
    var bleedWrite = 0L
    var bleedDabs = 0

    /** Writes [src] (its top-left [rect] size) over [rect] of the layer, 1:1, replacing. */
    private fun blitReplace(src: Bitmap, rect: Rect) {
        dabBlendPaint.reset()
        dabBlendPaint.blendMode = BlendMode.SRC
        dabBlendPaint.isAntiAlias = false
        dabBlendPaint.isFilterBitmap = false
        dabSrcRect.set(0, 0, rect.width(), rect.height())
        dabDstRect.set(rect)
        val st = store
        if (st == null) {
            val c = canvas ?: return
            c.drawBitmap(src, dabSrcRect, dabDstRect, dabBlendPaint)
            return
        }
        val sel = selectionClip
        val tileSize = st.tileSize
        val ty1 = (rect.bottom - 1) / tileSize
        val tx1 = (rect.right - 1) / tileSize
        for (ty in rect.top / tileSize..ty1) {
            for (tx in rect.left / tileSize..tx1) {
                tileCapture?.capture(tx, ty)
                st.editTile(tx, ty) { tc ->
                    if (sel != null) tc.clipPath(sel)
                    tc.drawBitmap(src, dabSrcRect, dabDstRect, dabBlendPaint)
                }
            }
        }
    }

    private fun drawDab(
        source: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        blit: Paint
    ) {
        stampDab(source, left, top, right, bottom, blit)
    }

    /**
     * Stamps one dab, identically for the dense bitmap and for sparse tiles.
     *
     * Two passes. First the dab is resampled into a scratch bitmap sized to the
     * dab's own bounding box with a plain SRC_OVER blend and no colour
     * modulation, so the scratch ends up holding exactly the dab's coverage.
     * Then the scratch is drawn into the layer 1:1 with the *real* paint (the
     * colour filter, the alpha and the blend mode — DST_OUT for the eraser,
     * SRC_ATOP for alpha lock, the mask and clipping rules, the smudge alpha).
     * Colour filter, alpha and blend therefore run in one pipeline, which is the
     * same pipeline as drawing the dab straight onto the layer; the only extra
     * step is that the resampled coverage is rounded to 8 bits once, instead of
     * being modulated first and rounded twice. That keeps the accumulated
     * difference of a soft, hundreds-of-dabs stroke at the 1 LSB level.
     *
     * It also needs no read-back of the layer and no canvas-sized surface: the
     * scratch is bounded by the brush, so a dab's cost does not depend on the
     * document size.
     *
     * The selection clip is applied to the *destination* (the tile canvas or the
     * layer canvas), which keeps the "leave the pixel alone" semantics of a
     * canvas clip.
     */
    /** Angle of the dab being stamped (degrees), read by [stampDab]. */
    private var dabAngle = 0f

    private fun stampDab(
        source: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        blit: Paint
    ) {
        // Integer bounding box of the dab plus one pixel of margin: a filtered,
        // antialiased edge can reach past its own rect.
        val px = dabBounds
        val rot = dabAngle
        if (rot != 0f) {
            // Rotated: the scratch must hold the turned square.
            val cx = (left + right) * 0.5f
            val cy = (top + bottom) * 0.5f
            val r = (right - left) * 0.5f * 1.4143f
            px.set(floor(cx - r).toInt(), floor(cy - r).toInt(), ceil(cx + r).toInt(), ceil(cy + r).toInt())
        } else {
            px.set(
                floor(left).toInt(), floor(top).toInt(),
                ceil(right).toInt(), ceil(bottom).toInt()
            )
        }
        px.inset(-1, -1)
        px.intersect(0, 0, engine.width, engine.height)
        if (px.isEmpty) return

        val sel = selectionClip
        val selBounds = selectionBounds
        if (sel != null && selBounds != null) {
            dabBoundsF.set(px)
            if (!RectF.intersects(selBounds, dabBoundsF)) return
        }

        val w = px.width()
        val h = px.height()
        val scratch = ensureDabScratch(w, h) ?: return
        val sc = dabScratchCanvas ?: return

        dart.set(left, top, right, bottom)

        // 1. Dab colour + coverage, at the dab's real document position.
        val s0 = sc.save()
        sc.clipRect(0, 0, w, h)
        sc.drawColor(0, BlendMode.CLEAR)
        sc.restoreToCount(s0)

        dabScratchPaint.reset()
        dabScratchPaint.isAntiAlias = blit.isAntiAlias
        dabScratchPaint.isFilterBitmap = true
        dabScratchPaint.isDither = blit.isDither
        dabScratchPaint.blendMode = BlendMode.SRC_OVER
        // No colour filter and no alpha here: this pass captures the resampled
        // coverage only. Modulating it here would round the source twice and
        // accumulate a visible bias on a soft stroke.
        val s1 = sc.save()
        sc.translate(-px.left.toFloat(), -px.top.toFloat())
        // Rotation happens only here, in the resample: the blend pass below is
        // the same 1:1 blit for every store.
        if (rot != 0f) sc.rotate(rot, dart.centerX(), dart.centerY())
        sc.drawBitmap(source, null, dart, dabScratchPaint)
        sc.restoreToCount(s1)

        // 2. Modulate and blend with the real paint, 1:1.
        dabBlendPaint.reset()
        dabBlendPaint.blendMode = blit.blendMode
        dabBlendPaint.isAntiAlias = false
        dabBlendPaint.isFilterBitmap = false
        dabBlendPaint.isDither = blit.isDither
        dabBlendPaint.alpha = blit.alpha
        dabBlendPaint.colorFilter = blit.colorFilter
        dabSrcRect.set(0, 0, w, h)
        dabDstRect.set(px)

        if (strokeOpacity < 1f && blit === paint) {
            stampBuffered(scratch, px)
            return
        }
        val st = store
        if (st == null) {
            val c = canvas ?: return
            // The layer canvas already carries the selection clip.
            c.drawBitmap(scratch, dabSrcRect, dabDstRect, dabBlendPaint)
            return
        }
        statDabs++
        statWritePx += w.toLong() * h.toLong()
        val tileSize = st.tileSize
        val ty1 = (px.bottom - 1) / tileSize
        val tx1 = (px.right - 1) / tileSize
        for (ty in px.top / tileSize..ty1) {
            for (tx in px.left / tileSize..tx1) {
                statWriteDraws++
                tileCapture?.capture(tx, ty)
                st.editTile(tx, ty) { tc ->
                    if (sel != null) tc.clipPath(sel)
                    tc.drawBitmap(scratch, dabSrcRect, dabDstRect, dabBlendPaint)
                }
            }
        }
    }

    /**
     * Grows the dab scratch to hold at least [w] x [h]. One bitmap is reused for
     * the session; its size follows the brush, never the canvas.
     */
    private fun ensureDabScratch(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val cur = dabScratch
        if (cur != null && !cur.isRecycled && cur.width >= w && cur.height >= h) return cur
        val nw = max(w, cur?.width ?: 0)
        val nh = max(h, cur?.height ?: 0)
        cur?.recycle()
        val bmp = AllocTracker.bitmap("stroke.dab", nw, nh)
        dabScratch = bmp
        dabScratchCanvas = Canvas(bmp)
        return bmp
    }

    /** Grows the smudge source patch to at least [w] x [h]. */
    private fun ensureSmudgePatch(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val cur = smudgePatch
        if (cur != null && !cur.isRecycled && cur.width >= w && cur.height >= h) return cur
        val nw = max(w, cur?.width ?: 0)
        val nh = max(h, cur?.height ?: 0)
        cur?.recycle()
        val bmp = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        smudgePatch = bmp
        return bmp
    }

    /**
     * Warms the dab scratch so the first stroke does not allocate inside the
     * gesture. Its size follows the current brush.
     */
    fun preallocate() {
        val size = ((preset?.size ?: 192f).toInt().coerceIn(32, 512)) + 8
        ensureDabScratch(size, size)
        ensureSmudgePatch(size, size)
    }

    /**
     * Lifts the pixels under the previous stamp into [smudgeBuffer], cuts them to
     * a soft dab, and lays them down at the current position. Repeating along the
     * stroke pulls texture from where the brush has been to where it is going.
     */
    private fun stampSmudge(
        d: Bitmap,
        brush: BrushPreset,
        x: Float,
        y: Float,
        half: Float
    ) {
        val target = layer ?: return
        smudgeSrcRect.set(
            (lastStampX - half).toInt(),
            (lastStampY - half).toInt(),
            (lastStampX + half).toInt(),
            (lastStampY + half).toInt()
        )
        if (!smudgeSrcRect.intersect(0, 0, engine.width, engine.height)) return

        val diameter = half * 2f
        val scale = SMUDGE_RES / diameter
        smudgeCanvas.drawColor(0, BlendMode.CLEAR)

        // Map the (possibly clipped) source rect into buffer space.
        smudgeDstRect.set(
            (smudgeSrcRect.left - (lastStampX - half)) * scale,
            (smudgeSrcRect.top - (lastStampY - half)) * scale,
            (smudgeSrcRect.right - (lastStampX - half)) * scale,
            (smudgeSrcRect.bottom - (lastStampY - half)) * scale
        )
        smudgeSrcPaint.blendMode = BlendMode.SRC
        // Sample the source region into a dab-sized patch and scale that into the
        // buffer. Both stores go through the patch, so the scaled draw has the
        // same source geometry either way and the result cannot depend on how the
        // layer happens to be stored.
        val sw = smudgeSrcRect.width()
        val sh = smudgeSrcRect.height()
        val patch = ensureSmudgePatch(sw, sh)
        if (patch == null) return
        target.paintStore.readRectInto(smudgeSrcRect, patch)
        smudgePatchRect.set(0, 0, sw, sh)
        smudgeCanvas.drawBitmap(patch, smudgePatchRect, smudgeDstRect, smudgeSrcPaint)

        // Feather the lifted patch with the dab so the smudge has soft edges.
        smudgeMaskPaint.blendMode = BlendMode.DST_IN
        smudgeFullRect.set(0f, 0f, SMUDGE_RES.toFloat(), SMUDGE_RES.toFloat())
        smudgeCanvas.drawBitmap(d, null, smudgeFullRect, smudgeMaskPaint)

        smudgeDstPaint.alpha = (brush.smudgeAmount * 255f).toInt().coerceIn(1, 255)
        smudgeDstPaint.blendMode = BlendMode.SRC_OVER
        drawDab(smudgeBuffer, x - half, y - half, x + half, y + half, smudgeDstPaint)
    }

    /** Rolls the layer back to the pre-stroke snapshot. */
    fun cancel() {
        val target = layer
        val c = canvas
        if (c != null && clipped) runCatching { c.restore() }
        if (target != null) {
            val cap = tileCapture
            if (cap != null && cap.store === target.paintStore) {
                cap.restore()
                engine.markDirty(dirty)
            } else {
                engine.restoreFromBase(target, dirty)
            }
        }
        dirty.setEmpty()
        releaseShadows()
        strokeOpacity = 1f
        canvas = null
        layer = null
        preset = null
        dab = null
        isActive = false
        clipped = false
        store = null
        selectionClip = null
        selectionBounds = null
        // tileCapture is kept so a quick-shape redraw starts from the original
        // pre-stroke pixels.
    }

    /** Forgets the tile snapshot after the session has consumed it. */
    fun clearTileCapture() {
        tileCapture = null
    }

    /** Cancels the stroke and frees the snapshot (gesture aborted). */
    fun releaseTileCapture() {
        tileCapture?.release()
        tileCapture = null
    }

    /** What one dab of a stroke with [preset] looks like (see [cursorDab]). */
    class CursorDab(val size: Float, val alpha: Float, val angle: Float)

    companion object {
        /**
         * The dab a stroke with [brush] lays down at the lowest pressure, for
         * the hover cursor: the same size, alpha and angle rules as a stroke
         * ([sizeFor], [alphaFor], [stampAngle], stroke opacity), with the pen
         * at rest (no speed), past the start taper, and the random parts
         * (jitter, random angle) at their neutral value. [alpha] is the
         * opacity the stroke builds up where its dabs overlap: dab alpha
         * accumulated over ~1/spacing dabs, capped by the stroke opacity.
         * The harness ("cursordab") checks it against a real stroke.
         */
        fun cursorDab(brush: BrushPreset, tilt: Float, orientation: Float, directionDeg: Float): CursorDab {
            val curve = PressureCurve.of(brush.pressureCurve)
            val pressure = (curve?.map(0.02f) ?: 0.02f).coerceIn(0.02f, 1f)
            val t = tilt.coerceIn(0f, 1f)
            // sizeFor at rest.
            val ps = 1f - brush.pressureSize.coerceIn(0f, 1f) * (1f - pressure)
            val ts = 1f + brush.tiltSize.coerceIn(0f, 1f) * t * 2.2f
            val size = max(1.2f, brush.size * ps * ts)
            // alphaFor at rest, with begin()'s base alpha and stroke opacity.
            val smudge = brush.category == BrushCategory.Smudge
            val strokeOpacity = if (!smudge && !brush.bleeds && brush.opacity < 0.995f &&
                (brush.category == BrushCategory.Brush || brush.category == BrushCategory.Eraser)
            ) brush.opacity.coerceIn(0.01f, 1f) else 1f
            val baseAlpha = if (strokeOpacity < 1f) brush.flow.coerceIn(0.02f, 1f)
            else (brush.opacity * brush.flow).coerceIn(0.02f, 1f)
            val pa = 1f - brush.pressureOpacity.coerceIn(0f, 1f) * (1f - pressure)
            val to = brush.tiltOpacity.coerceIn(0f, 1f)
            val ta = if (to > 0f) 1f - to * t * 0.75f else 1f
            val dabAlpha = (baseAlpha * pa * ta).coerceIn(0.004f, 1f)
            // How the stroke builds up on its centre line: every dab whose
            // footprint covers a point adds its own tip coverage there (read
            // from the real dab), composited the way the stroke composites
            // (1 - product of (1 - alpha * coverage)), then capped by the
            // stroke opacity. The cursor draws the dab so that its centre
            // shows exactly that.
            val profile = dabProfile(brush)
            val n = profile.size
            val step = max(0.8f, size * max(0.02f, brush.spacing))
            var keep = 1.0
            if (n > 0) {
                var k = -(size / 2f / step).toInt() - 1
                while (k * step <= size / 2f) {
                    val d = k * step
                    if (kotlin.math.abs(d) < size / 2f) {
                        val idx = (n / 2f + d / size * n).toInt().coerceIn(0, n - 1)
                        keep *= (1.0 - dabAlpha * profile[idx])
                    }
                    k++
                }
            }
            val centre = ((1.0 - keep).toFloat() * strokeOpacity).coerceIn(0f, 1f)
            val c0 = if (n > 0) profile[n / 2] else 1f
            val alpha = (if (n > 0) centre / max(0.05f, c0) else dabAlpha * strokeOpacity).coerceIn(0.004f, 1f)
            val base = when (brush.rotationMode) {
                1 -> directionDeg
                2 -> Math.toDegrees(orientation.toDouble()).toFloat()
                else -> 0f
            }
            return CursorDab(size, alpha, base + brush.rotation)
        }

        private val profiles = HashMap<String, FloatArray>()

        /** Coverage (0..1) across the middle of [brush]'s dab, a 5-row band averaged. */
        private fun dabProfile(brush: BrushPreset): FloatArray = synchronized(profiles) {
            profiles.getOrPut(brush.dabKey) {
                val dab = runCatching { BrushDab.dab(brush) }.getOrNull() ?: return@getOrPut FloatArray(0)
                val w = dab.width
                val h = dab.height
                val rows = 5
                val y0 = (h / 2 - rows / 2).coerceIn(0, max(0, h - rows))
                val px = IntArray(w * rows)
                dab.getPixels(px, 0, w, 0, y0, w, minOf(rows, h - y0))
                FloatArray(w) { x ->
                    var sum = 0
                    for (r in 0 until rows) sum += px[r * w + x] ushr 24
                    sum / (rows * 255f)
                }
            }
        }

        /** Resolution of the smudge sample buffer (1:1 with the dab rect). */
        private const val SMUDGE_RES = 128

        // Debug counters read by the harness benchmark; harmless in the app.
        @JvmField var statDabs = 0L
        @JvmField var statWriteDraws = 0L
        @JvmField var statWritePx = 0L

        fun resetStats() {
            statDabs = 0
            statWriteDraws = 0
            statWritePx = 0
        }
    }
}
