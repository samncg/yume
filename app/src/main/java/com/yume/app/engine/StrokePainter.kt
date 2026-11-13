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
 * document size. See the Stage 2 notes in README.md.
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

    /** Distance travelled in this stroke, drives the taper-in. */
    private var travel = 0f

    /** Input stabiliser strength (0..1) shared by all brushes. */
    private var assist = 0f
    private val assistXs = FloatArray(24)
    private val assistYs = FloatArray(24)
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
        assistStrength: Float = 0f
    ) {
        layer = target
        preset = brush
        smudge = brush.category == BrushCategory.Smudge
        dab = BrushDab.dab(brush)
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
            else -> BlendMode.SRC_OVER
        }
        paint.colorFilter = when {
            maskMode && brush.category == BrushCategory.Eraser ->
                PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            maskMode -> null
            brush.category == BrushCategory.Brush -> PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            else -> null
        }

        baseAlpha = (brush.opacity * brush.flow).coerceIn(0.02f, 1f)

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
        travel = 0f
        pressure = initialPressure.coerceIn(0.02f, 1f)
        tilt = initialTilt.coerceIn(0f, 1f)
        isActive = true

        stampAt(x, y, sizeFor(pressure, tilt), alphaFor(pressure))
    }

    fun move(x: Float, y: Float, pressureIn: Float, tiltIn: Float) {
        if (!isActive) return
        val brush = preset ?: return

        lastRawX = x
        lastRawY = y

        // 1. Stroke assist: average the last few raw samples. A wide average
        //    removes hand tremor without adding much latency.
        var targetX = x
        var targetY = y
        if (assist > 0.01f) {
            assistXs[assistHead] = x
            assistYs[assistHead] = y
            assistHead = (assistHead + 1) % assistXs.size
            if (assistCount < assistXs.size) assistCount++

            val wanted = (1 + assist * (assistXs.size - 1)).toInt().coerceAtMost(assistCount)
            var sx = 0f
            var sy = 0f
            var idx = assistHead - 1
            for (i in 0 until wanted) {
                if (idx < 0) idx += assistXs.size
                sx += assistXs[idx]
                sy += assistYs[idx]
                idx--
            }
            targetX = sx / wanted
            targetY = sy / wanted
        }

        // 2. Streamline: chase the (assisted) target.
        val k = 1f - (brush.smoothing.coerceIn(0f, 1f) * 0.88f)
        smoothX += (targetX - smoothX) * max(k, 0.08f)
        smoothY += (targetY - smoothY) * max(k, 0.08f)

        pressure += (pressureIn.coerceIn(0.02f, 1f) - pressure) * 0.45f
        tilt += (tiltIn.coerceIn(0f, 1f) - tilt) * 0.3f

        val size = sizeFor(pressure, tilt)
        // Smudge needs a coarser stamp interval: each stamp copies pixels.
        val spacingFactor = if (smudge) max(0.06f, brush.spacing) else max(0.02f, brush.spacing)
        val step = max(0.8f, size * spacingFactor)

        var dx = smoothX - lastX
        var dy = smoothY - lastY
        val dist = hypot(dx, dy)
        if (dist < 0.0005f) return

        val ux = dx / dist
        val uy = dy / dist
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
            stampAt(px, py, size, alphaFor(pressure))
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
                stampLine(lastX, lastY, lastRawX, lastRawY, sizeFor(pressure, tilt), alphaFor(pressure))
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
        // tileCapture deliberately survives: the session turns it into a patch.
    }

    private fun sizeFor(pressure: Float, tilt: Float): Float {
        val brush = preset ?: return 1f
        val p = 1f - brush.pressureSize.coerceIn(0f, 1f) * (1f - pressure)
        val t = 1f + brush.tiltSize.coerceIn(0f, 1f) * tilt * 2.2f
        return max(1.2f, brush.size * p * t)
    }

    private fun alphaFor(pressure: Float): Float {
        val brush = preset ?: return 1f
        val p = 1f - brush.pressureOpacity.coerceIn(0f, 1f) * (1f - pressure)
        return (baseAlpha * p).coerceIn(0.004f, 1f)
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

    private fun stampAt(x: Float, y: Float, size: Float, alpha: Float) {
        val sized = taper(size)
        stampInternal(x, y, sized, alpha)
        if (engine.symmetryX) {
            stampInternal(engine.symmetryXPos * 2f - x, y, sized, alpha)
        }
        if (engine.symmetryY) {
            stampInternal(x, engine.symmetryYPos * 2f - y, sized, alpha)
        }
        if (engine.symmetryX && engine.symmetryY) {
            stampInternal(
                engine.symmetryXPos * 2f - x,
                engine.symmetryYPos * 2f - y,
                sized,
                alpha
            )
        }
        lastStampX = x
        lastStampY = y
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

    private fun stampInternal(x: Float, y: Float, size: Float, alpha: Float) {
        val d = dab ?: return
        val brush = preset ?: return

        var sx = x
        var sy = y
        if (brush.jitter > 0.01f) {
            sx += (random.nextFloat() - 0.5f) * brush.jitter * size * 2f
            sy += (random.nextFloat() - 0.5f) * brush.jitter * size * 2f
        }

        val half = size * 0.5f
        if (half < 0.3f) return

        val profile = CompositeProfile.enabled
        val t0 = if (profile) System.nanoTime() else 0L
        if (smudge) {
            stampSmudge(d, brush, sx, sy, half)
        } else {
            paint.alpha = (alpha * 255f).toInt().coerceIn(1, 255)
            drawDab(d, sx - half, sy - half, sx + half, sy + half, paint)
        }
        if (profile) {
            CompositeProfile.paintNanos += System.nanoTime() - t0
            CompositeProfile.paintDabs++
        }

        stampRect.set(
            (sx - half).toInt() - 2,
            (sy - half).toInt() - 2,
            (sx + half).toInt() + 2,
            (sy + half).toInt() + 2
        )
        engine.markDirty(stampRect)
        if (dirty.isEmpty) dirty.set(stampRect) else dirty.union(stampRect)
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
        px.set(
            floor(left).toInt(), floor(top).toInt(),
            ceil(right).toInt(), ceil(bottom).toInt()
        )
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

    companion object {
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
