package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.view.SurfaceHolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Dedicated render thread.
 *
 * Owns the surface, drains the session's command/input queues, and repaints
 * only when something actually changed. Bitmaps are blitted through a
 * hardware canvas when available, which keeps the flatten + blit on the GPU.
 */
class SurfaceRenderer(
    private val session: DrawingSession,
    private val holder: SurfaceHolder,
    private val density: Float
) : Thread("Yume-Render") {

    @Volatile private var running = true
    private var surfaceW = 0
    private var surfaceH = 0
    private var hasFitted = false

    private val lock = ReentrantLock()
    private val wakeSignal = lock.newCondition()

    @Volatile private var renderRequested = true
    private var hardware = true
    private var loggedGeometry = false
    private var framesInWindow = 0
    private var windowStartNanos = 0L
    private var lastFrameStartNanos = 0L

    private companion object {
        /** Mip refresh time per frame; the rest carries over to the next frames. */
        const val MIP_FRAME_BUDGET_NS = 6_000_000L
        /** Flip to true to log frame timing and canvas geometry to logcat. */
        const val DEBUG_STATS = false

        /** Below this document-to-screen scale the mip level is used instead. */
        const val MIP_ZOOM_THRESHOLD = 0.4f
    }

    private val checker: Bitmap
    private val checkerPaint = Paint()
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val antPath = Path()
    private val tempMatrix = Matrix()
    private val invView = Matrix()
    private val viewCorners = FloatArray(8)
    private val viewportRect = Rect()
    private val prefetchRect = Rect()

    /**
     * The document rectangle the surface currently shows, so a sparse composite
     * only reads the tiles the user can actually see.
     */
    private fun documentViewport(view: ViewTransform, w: Int, h: Int): Rect {
        invView.set(view.matrix)
        if (!invView.invert(invView)) return Rect(0, 0, w, h)
        viewCorners[0] = 0f
        viewCorners[1] = 0f
        viewCorners[2] = surfaceW.toFloat()
        viewCorners[3] = 0f
        viewCorners[4] = surfaceW.toFloat()
        viewCorners[5] = surfaceH.toFloat()
        viewCorners[6] = 0f
        viewCorners[7] = surfaceH.toFloat()
        invView.mapPoints(viewCorners)
        var minX = viewCorners[0]
        var maxX = minX
        var minY = viewCorners[1]
        var maxY = minY
        var i = 2
        while (i < 8) {
            val x = viewCorners[i]
            val y = viewCorners[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 2
        }
        viewportRect.set(
            max(0, floor(minX).toInt() - 2),
            max(0, floor(minY).toInt() - 2),
            min(w, ceil(maxX).toInt() + 2),
            min(h, ceil(maxY).toInt() + 2)
        )
        return viewportRect
    }

    init {
        checker = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val c = Canvas(checker)
        c.drawColor(0xFF26262B.toInt())
        val p = Paint()
        p.color = 0xFF34343A.toInt()
        c.drawRect(0f, 0f, 16f, 16f, p)
        c.drawRect(16f, 16f, 32f, 32f, p)
        checkerPaint.shader = BitmapShader(checker, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        checkerPaint.isFilterBitmap = false
    }

    fun setSurfaceSize(width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        wake()
    }

    fun wake() {
        lock.withLock {
            renderRequested = true
            wakeSignal.signalAll()
        }
    }

    fun shutdown() {
        running = false
        wake()
        try {
            join(1500)
        } catch (ignored: InterruptedException) {
        }
    }

    override fun run() {
        session.wakeListener = { wake() }
        session.ownerThread = Thread.currentThread()
        try {
            while (running) {
                var work = false
                session.runCommands()
                if (session.runInput()) work = true
                if (session.consumePendingPreview()) work = true
                if (session.tickFrame()) work = true
                session.rebuildTransformIfNeeded()

                val engine = session.engine
                // Tile cache: adopt finished prefetches, then composite without
                // blocking on disk (non-active layers' evicted tiles are deferred
                // and their composite tiles stay stale until they land).
                val cache = TileCache.active
                cache?.adoptPrefetched()
                if (cache != null) cache.blockingLoads = false
                try {
                    if (engine.flush()) work = true
                } finally {
                    if (cache != null) cache.blockingLoads = true
                }
                // While tiles are pending, keep polling for them.
                if (cache != null && cache.pendingPrefetches() > 0) work = true
                // The zoomed-out view still has mip tiles to refresh (they are
                // done a few milliseconds per frame).
                if (mipCarry) work = true

                val antsActive = engine.selection != null
                if (session.publishIfNeeded() && !work) work = true

                if (work) {
                    lock.withLock { renderRequested = true }
                }

                if (renderRequested) {
                    renderRequested = false
                    drawFrame()
                    updateGlass()
                    // Safe point: nothing holds a writable tile between frames.
                    cache?.evictIfNeeded()
                } else {
                    lock.withLock {
                        if (!renderRequested && running) {
                            // A live selection keeps animating its marching ants.
                            wakeSignal.await(if (antsActive) 40L else 14L, TimeUnit.MILLISECONDS)
                        }
                    }
                    if (antsActive && running) {
                        lock.withLock { renderRequested = true }
                    }
                }
            }
        } finally {
            session.wakeListener = null
            session.ownerThread = null
        }
    }

    private var lastGlass = 0L
    private val glassCanvas = Canvas()
    private val glassViewport = Rect()

    /**
     * Publishes a quarter-resolution copy of what the surface shows for the
     * liquid-glass UI ([CanvasGlass]). Throttled, skipped while the UI is
     * hidden, and drawn from the zoom-out mip when one exists, so it costs a
     * fraction of a frame.
     */
    private fun updateGlass() {
        if (!session.chromeVisible || surfaceW <= 0 || surfaceH <= 0) return
        val now = android.os.SystemClock.uptimeMillis()
        val interval = if (session.strokeInProgress) CanvasGlass.STROKE_INTERVAL_MS
        else CanvasGlass.MIN_INTERVAL_MS
        if (now - lastGlass < interval) return
        lastGlass = now
        val s = CanvasGlass.SCALE
        val bmp = CanvasGlass.obtain(
            (surfaceW / s).coerceAtLeast(1), (surfaceH / s).coerceAtLeast(1)
        )
        val c = glassCanvas
        c.setBitmap(bmp)
        c.drawColor(surroundColor())
        c.save()
        c.scale(1f / s, 1f / s)
        val engine = session.engine
        c.concat(session.view.matrix)
        if (engine.compositeIsSparse) {
            engine.enableMip()
            if (engine.hasMipDirty) engine.updateMip(MIP_FRAME_BUDGET_NS)
            if (engine.mipReady()) {
                engine.drawMip(c)
            } else {
                glassViewport.set(documentViewport(session.view, engine.width, engine.height))
                engine.drawComposite(c, glassViewport)
            }
        } else {
            glassViewport.set(0, 0, engine.width, engine.height)
            engine.drawComposite(c, glassViewport)
        }
        c.restore()
        c.setBitmap(null)
        CanvasGlass.publish(bmp)
    }

    private fun recordFrame() {
        // Rolling frame-time logger (debug only; a boolean read when off).
        if (FrameLogger.enabled) {
            val now = System.nanoTime()
            val prev = lastFrameStartNanos
            lastFrameStartNanos = now
            if (prev != 0L) FrameLogger.record(now - prev)
        }
        if (!DEBUG_STATS) return
        framesInWindow++
        val now = System.nanoTime()
        if (windowStartNanos == 0L) {
            windowStartNanos = now
            return
        }
        val elapsed = now - windowStartNanos
        if (elapsed >= 1_000_000_000L) {
            val fps = framesInWindow * 1_000_000_000.0 / elapsed
            android.util.Log.i("Yume", String.format("fps=%.1f", fps))
            framesInWindow = 0
            windowStartNanos = now
        }
    }

    /** The area around the canvas: the UI theme's background. */
    private fun surroundColor(): Int = com.yume.app.ui.YumeThemes.current.bg.let {
        android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
    }

    private fun lockCanvas(): Canvas? {
        if (hardware) {
            try {
                return holder.lockHardwareCanvas()
            } catch (t: Throwable) {
                hardware = false
            }
        }
        return try {
            holder.lockCanvas()
        } catch (t: Throwable) {
            null
        }
    }

    /** Set when a frame left mip work for the next ones. */
    @Volatile private var mipCarry = false

    private fun drawFrame() {
        mipCarry = false
        if (surfaceW <= 0 || surfaceH <= 0) return
        val view = session.view
        if (view.viewWidth != surfaceW || view.viewHeight != surfaceH) {
            view.setViewport(surfaceW, surfaceH)
            if (!hasFitted) {
                view.topInset = session.chromeTopInset
                view.fit()
                hasFitted = true
            }
        }

        val canvas = lockCanvas() ?: return
        try {
            if (DEBUG_STATS && !loggedGeometry) {
                loggedGeometry = true
                android.util.Log.i(
                    "Yume",
                    "surface=${surfaceW}x$surfaceH inset=${view.topInset} scale=${view.scale} " +
                        "rotation=${view.rotationDeg} t=(${view.tx},${view.ty}) doc=${view.docWidth}x${view.docHeight}"
                )
            }
            canvas.drawColor(surroundColor())
            val engine = session.engine
            val matrix = view.matrix

            canvas.save()
            canvas.concat(matrix)

            if (engine.isTransparentBackground) {
                canvas.drawRect(0f, 0f, engine.width.toFloat(), engine.height.toFloat(), checkerPaint)
            }
            if (engine.compositeIsSparse) {
                val rect = documentViewport(view, engine.width, engine.height)
                engine.priorityRect.set(rect)
                if (view.scale < MIP_ZOOM_THRESHOLD) {
                    // Zoomed out far enough that a full-resolution blit would
                    // touch every visible tile: use the downscaled composite.
                    engine.enableMip()
                    if (engine.hasMipDirty) {
                        engine.updateMip(MIP_FRAME_BUDGET_NS)
                        mipCarry = engine.hasMipDirty
                    }
                    if (engine.mipReady()) engine.drawMip(canvas)
                    else engine.drawComposite(canvas, rect)
                } else {
                    engine.drawComposite(canvas, rect)
                    // Warm the visible tiles (plus a tile of margin) of the layer
                    // being painted. Other layers are not needed to pan: the
                    // composite stays resident, and prefetching all of them would
                    // only thrash the budget.
                    TileCache.active?.let { _ ->
                        val m = TileStoreConfig.tileSize
                        prefetchRect.set(
                            max(0, rect.left - m), max(0, rect.top - m),
                            min(engine.width, rect.right + m), min(engine.height, rect.bottom + m)
                        )
                        engine.activeLayer?.let { l ->
                            (l.tiles as? SparseTileStore)?.prefetchIn(prefetchRect)
                            (l.maskTiles as? SparseTileStore)?.prefetchIn(prefetchRect)
                        }
                        // The composite is what is on screen: keep the tiles
                        // around the viewport warm so panning rarely reads disk.
                        engine.compositeTiles?.prefetchIn(prefetchRect)
                    }
                }
            } else {
                canvas.drawBitmap(engine.composite, 0f, 0f, blitPaint)
            }

            canvas.restore()

            drawCanvasBorder(canvas, matrix, engine.width, engine.height)
            drawGuides(canvas, matrix, engine.width, engine.height)
            drawShapePreview(canvas, matrix, engine.width, engine.height)
            drawSymmetry(canvas, matrix, engine.width, engine.height)
            drawSelection(canvas, matrix)
            drawGradientGuide(canvas, matrix)
            drawPrediction(canvas, matrix)
            drawHover(canvas, matrix)
            recordFrame()
        } catch (t: Throwable) {
            // A dropped frame is always better than a dead render thread.
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun drawCanvasBorder(canvas: Canvas, matrix: Matrix, w: Int, h: Int) {
        val pts = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        tempMatrix.set(matrix)
        tempMatrix.mapPoints(pts)

        antPath.reset()
        antPath.moveTo(pts[0], pts[1])
        antPath.lineTo(pts[2], pts[3])
        antPath.lineTo(pts[4], pts[5])
        antPath.lineTo(pts[6], pts[7])
        antPath.close()

        overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = 1.4f * density
        overlay.color = 0x33FFFFFF
        overlay.pathEffect = null
        canvas.drawPath(antPath, overlay)
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Drawing guides and radial symmetry spokes, in document space so lines
     * stay put while panning; stroke widths are divided by the zoom so they
     * read as hairlines at any scale. Handles are drawn in screen space.
     */
    private fun drawGuides(canvas: Canvas, matrix: Matrix, w: Int, h: Int) {
        val engine = session.engine
        val g = engine.guides
        val radial = engine.symmetryRadial
        if (!g.visible && radial < 2) return
        val scale = session.view.scale.coerceAtLeast(0.0001f)
        val save = canvas.save()
        canvas.concat(matrix)
        canvas.clipRect(0f, 0f, w.toFloat(), h.toFloat())
        guidePaint.strokeWidth = 1f * density / scale
        guidePaint.color = 0x664A7BE0
        when (g.mode) {
            com.yume.app.engine.GuideMode.Grid -> {
                val step = g.gridSize
                // Too dense to read at this zoom: show every Nth line.
                var k = 1
                while (step * k * scale < 10f * density) k *= 2
                val s = step * k
                var x = 0f
                while (x <= w) { canvas.drawLine(x, 0f, x, h.toFloat(), guidePaint); x += s }
                var y = 0f
                while (y <= h) { canvas.drawLine(0f, y, w.toFloat(), y, guidePaint); y += s }
            }
            com.yume.app.engine.GuideMode.Perspective -> {
                val far = maxOf(w, h) * 8f
                for (i in 0 until g.vpCount) {
                    val vx = g.vpX[i]
                    val vy = g.vpY[i]
                    val rays = 48
                    for (r in 0 until rays) {
                        val a = 2.0 * Math.PI * r / rays
                        canvas.drawLine(
                            vx, vy,
                            vx + far * kotlin.math.cos(a).toFloat(), vy + far * kotlin.math.sin(a).toFloat(),
                            guidePaint
                        )
                    }
                }
                // Horizon through the first two points (or level through one).
                guidePaint.color = 0xAAE9A13B.toInt()
                guidePaint.strokeWidth = 1.6f * density / scale
                if (g.vpCount >= 2) {
                    val dx = g.vpX[1] - g.vpX[0]
                    val dy = g.vpY[1] - g.vpY[0]
                    val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                    canvas.drawLine(
                        g.vpX[0] - dx / l * far, g.vpY[0] - dy / l * far,
                        g.vpX[0] + dx / l * far, g.vpY[0] + dy / l * far, guidePaint
                    )
                } else {
                    canvas.drawLine(-far, g.vpY[0], far, g.vpY[0], guidePaint)
                }
            }
            com.yume.app.engine.GuideMode.Ruler -> {
                guidePaint.color = 0xCCE9A13B.toInt()
                guidePaint.strokeWidth = 2f * density / scale
                val dx = g.rulerX[1] - g.rulerX[0]
                val dy = g.rulerY[1] - g.rulerY[0]
                val l = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
                val far = maxOf(w, h) * 4f
                canvas.drawLine(
                    g.rulerX[0] - dx / l * far, g.rulerY[0] - dy / l * far,
                    g.rulerX[0] + dx / l * far, g.rulerY[0] + dy / l * far, guidePaint
                )
            }
            com.yume.app.engine.GuideMode.Off -> Unit
        }
        if (radial >= 2) {
            guidePaint.color = 0xAAE9A13B.toInt()
            guidePaint.strokeWidth = 1.3f * density / scale
            val far = maxOf(w, h) * 2f
            for (k in 0 until radial) {
                val a = 2.0 * Math.PI * k / radial
                canvas.drawLine(
                    engine.symmetryXPos, engine.symmetryYPos,
                    engine.symmetryXPos + far * kotlin.math.cos(a).toFloat(),
                    engine.symmetryYPos + far * kotlin.math.sin(a).toFloat(),
                    guidePaint
                )
            }
        }
        canvas.restoreToCount(save)

        // Handles, in screen space, while the Guides panel is open.
        if (!session.guideEditing) return
        val pts = FloatArray(2)
        fun handle(x: Float, y: Float) {
            pts[0] = x; pts[1] = y
            matrix.mapPoints(pts)
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = 0xE6FFFFFF.toInt()
            canvas.drawCircle(pts[0], pts[1], 9f * density, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 2.5f * density
            handlePaint.color = 0xFFE9A13B.toInt()
            canvas.drawCircle(pts[0], pts[1], 9f * density, handlePaint)
        }
        for (i in 0 until g.handleCount()) handle(g.handleX(i), g.handleY(i))
        if (radial >= 2) handle(engine.symmetryXPos, engine.symmetryYPos)
    }

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val previewPath = android.graphics.Path()
    private val copyMatrix = Matrix()

    /**
     * A shape being placed (shape tool, QuickShape): a stroked path at the
     * brush's width and colour, with the symmetry copies the brush will make.
     * Stamping happens once, when the pen lifts.
     */
    private fun drawShapePreview(canvas: Canvas, matrix: Matrix, w: Int, h: Int) {
        val p = session.shapePreview ?: return
        if (p.xs.isEmpty()) return
        previewPath.reset()
        previewPath.moveTo(p.xs[0], p.ys[0])
        for (i in 1 until p.xs.size) previewPath.lineTo(p.xs[i], p.ys[i])
        if (p.closed) previewPath.close()
        previewPaint.strokeWidth = p.width
        previewPaint.color = p.color
        val engine = session.engine
        val save = canvas.save()
        canvas.concat(matrix)
        canvas.clipRect(0f, 0f, w.toFloat(), h.toFloat())
        fun draw(m: Matrix?) {
            if (m == null) canvas.drawPath(previewPath, previewPaint)
            else {
                val s = canvas.save()
                canvas.concat(m)
                canvas.drawPath(previewPath, previewPaint)
                canvas.restoreToCount(s)
            }
        }
        draw(null)
        val cx = engine.symmetryXPos
        val cy = engine.symmetryYPos
        if (engine.symmetryX) { copyMatrix.setScale(-1f, 1f, cx, 0f); draw(copyMatrix) }
        if (engine.symmetryY) { copyMatrix.setScale(1f, -1f, 0f, cy); draw(copyMatrix) }
        if (engine.symmetryX && engine.symmetryY) { copyMatrix.setScale(-1f, -1f, cx, cy); draw(copyMatrix) }
        val radial = engine.symmetryRadial
        if (radial >= 2) {
            for (k in 0 until radial) {
                val deg = 360f * k / radial
                if (k > 0) { copyMatrix.setRotate(deg, cx, cy); draw(copyMatrix) }
                if (engine.symmetryMirror) {
                    copyMatrix.setScale(1f, -1f, 0f, cy)
                    copyMatrix.postRotate(deg, cx, cy)
                    draw(copyMatrix)
                }
            }
        }
        canvas.restoreToCount(save)
    }

    /** Dashed mirror guides, draggable on the canvas. */
    private fun drawSymmetry(canvas: Canvas, matrix: Matrix, w: Int, h: Int) {
        val engine = session.engine
        if (!engine.symmetryX && !engine.symmetryY) return
        antPath.reset()
        if (engine.symmetryX) {
            val pts = floatArrayOf(engine.symmetryXPos, 0f, engine.symmetryXPos, h.toFloat())
            matrix.mapPoints(pts)
            antPath.moveTo(pts[0], pts[1])
            antPath.lineTo(pts[2], pts[3])
        }
        if (engine.symmetryY) {
            val pts = floatArrayOf(0f, engine.symmetryYPos, w.toFloat(), engine.symmetryYPos)
            matrix.mapPoints(pts)
            antPath.moveTo(pts[0], pts[1])
            antPath.lineTo(pts[2], pts[3])
        }
        overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = 1.3f * density
        overlay.color = if (engine.symmetryLocked) 0x55E9A13B else 0xCCE9A13B.toInt()
        overlay.pathEffect = DashPathEffect(floatArrayOf(9f * density, 7f * density), 0f)
        canvas.drawPath(antPath, overlay)
        overlay.pathEffect = null
    }

    private fun drawSelection(canvas: Canvas, matrix: Matrix) {
        val engine = session.engine
        val outline = engine.selectionOutline
        val preview = session.selectionPreview

        if (outline != null) {
            antPath.reset()
            antPath.set(outline)
            antPath.transform(matrix)

            overlay.style = Paint.Style.STROKE
            overlay.strokeWidth = 1.2f * density
            overlay.pathEffect = null

            // White base plus animated dark dashes = classic marching ants.
            overlay.color = 0xFFFFFFFF.toInt()
            canvas.drawPath(antPath, overlay)

            val dash = 5f * density
            val phase = (System.nanoTime() / 1_000_000L % 1000L).toFloat() / 1000f * dash * 2f
            overlay.pathEffect = DashPathEffect(floatArrayOf(dash, dash), phase)
            overlay.color = 0xFF14141A.toInt()
            canvas.drawPath(antPath, overlay)
            overlay.pathEffect = null
        }

        if (preview != null) {
            antPath.reset()
            antPath.set(preview)
            antPath.transform(matrix)

            overlay.style = Paint.Style.STROKE
            overlay.strokeWidth = 1.3f * density
            overlay.pathEffect = null
            overlay.color = 0xCCE9A13B.toInt()
            canvas.drawPath(antPath, overlay)

            overlay.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
            overlay.color = 0xFFFFFFFF.toInt()
            canvas.drawPath(antPath, overlay)
            overlay.pathEffect = null
        }
    }

    /** The line being dragged out with the gradient tool, with end handles. */
    private fun drawGradientGuide(canvas: Canvas, matrix: Matrix) {
        val g = session.gradientGuide ?: return
        val pts = floatArrayOf(g[0], g[1], g[2], g[3])
        matrix.mapPoints(pts)
        overlay.style = Paint.Style.STROKE
        overlay.pathEffect = null
        overlay.strokeWidth = 3f * density
        overlay.color = 0x99000000.toInt()
        canvas.drawLine(pts[0], pts[1], pts[2], pts[3], overlay)
        overlay.strokeWidth = 1.5f * density
        overlay.color = 0xFFFFFFFF.toInt()
        canvas.drawLine(pts[0], pts[1], pts[2], pts[3], overlay)
        overlay.style = Paint.Style.FILL
        canvas.drawCircle(pts[0], pts[1], 5f * density, overlay)
        canvas.drawCircle(pts[2], pts[3], 5f * density, overlay)
    }

    private val predictPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    /**
     * The stroke's predicted leading tip: from the newest painted dab to where
     * the pen is expected to be now, in the brush colour and width. It is
     * replaced by real paint as the samples arrive, so the line on screen
     * keeps up with the pen instead of trailing behind it.
     */
    private fun drawPrediction(canvas: Canvas, matrix: Matrix) {
        val tip = session.predictedTip ?: return
        if (!session.strokeInProgress) return
        val live = session.liveCursor() ?: return
        val from = floatArrayOf(live[0], live[1])
        matrix.mapPoints(from)
        val w = max(1f, live[2] * session.view.scale)
        predictPaint.strokeWidth = w
        predictPaint.color = session.color
        predictPaint.alpha = (255 * session.brushOpacity.coerceIn(0.05f, 1f) * 0.85f).toInt()
        canvas.drawLine(from[0], from[1], tip[0], tip[1], predictPaint)
    }

    private fun drawHover(canvas: Canvas, matrix: Matrix) {
        if (session.transformActive) return
        // While the pen paints, the ring sits on the newest dab and has its
        // size, so it follows pressure, tilt and the start taper live.
        val live = session.liveCursor()
        if (live == null && !session.hoverVisible) return
        val mapped = FloatArray(2)
        val docX = live?.get(0) ?: session.hoverX
        val docY = live?.get(1) ?: session.hoverY
        val docSize = live?.get(2) ?: session.brushSize
        matrix.mapPoints(mapped, floatArrayOf(docX, docY))
        val radius = max(2.5f, docSize * 0.5f * session.view.scale)

        overlay.style = Paint.Style.STROKE
        overlay.pathEffect = null
        overlay.strokeWidth = 2.6f * density
        overlay.color = 0x66000000
        canvas.drawCircle(mapped[0], mapped[1], radius, overlay)

        overlay.strokeWidth = 1.1f * density
        overlay.color = 0xE6FFFFFF.toInt()
        canvas.drawCircle(mapped[0], mapped[1], radius, overlay)
    }
}
