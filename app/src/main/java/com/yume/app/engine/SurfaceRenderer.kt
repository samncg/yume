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
        /** Mip work per frame while a stroke is being painted. */
        const val MIP_STROKE_BUDGET_NS = 1_000_000L
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
    private val repeatShade = Paint().apply { color = 0x1F000000 }
    private val repeatRect = Rect()

    /**
     * Pattern mode: the tile drawn again at each lattice step that shows on
     * screen (only the visible part of each), lightly shaded so the real
     * canvas stands out. The canvas is already transformed to document space.
     */
    private fun drawPatternRepeats(canvas: Canvas) {
        val engine = session.engine
        val w = engine.width
        val h = engine.height
        // The whole view in document space (not clipped to the canvas).
        invView.set(session.view.matrix)
        if (!invView.invert(invView)) return
        val c = floatArrayOf(0f, 0f, surfaceW.toFloat(), 0f, surfaceW.toFloat(), surfaceH.toFloat(), 0f, surfaceH.toFloat())
        invView.mapPoints(c)
        val minX = minOf(c[0], c[2], c[4], c[6])
        val maxX = maxOf(c[0], c[2], c[4], c[6])
        val minY = minOf(c[1], c[3], c[5], c[7])
        val maxY = maxOf(c[1], c[3], c[5], c[7])
        val b = engine.patternBasis()
        // Enough steps to cover the view, capped (zoomed far out).
        val reach = (maxOf(maxX - minX, maxY - minY) / minOf(w, h).coerceAtLeast(1)).toInt() + 2
        val n = reach.coerceAtMost(8)
        val zoomedOut = session.view.scale < MIP_ZOOM_THRESHOLD && engine.compositeIsSparse && engine.mipDrawable()
        for (j in -n..n) for (i in -n..n) {
            if (i == 0 && j == 0) continue
            val ox = i * b[0] + j * b[2]
            val oy = i * b[1] + j * b[3]
            // The view in this repeat's own coordinates, clipped to the tile.
            val l = maxOf(0f, minX - ox)
            val t = maxOf(0f, minY - oy)
            val r = minOf(w.toFloat(), maxX - ox)
            val btm = minOf(h.toFloat(), maxY - oy)
            if (l >= r || t >= btm) continue
            repeatRect.set(kotlin.math.floor(l).toInt(), kotlin.math.floor(t).toInt(), kotlin.math.ceil(r).toInt(), kotlin.math.ceil(btm).toInt())
            val save = canvas.save()
            canvas.translate(ox, oy)
            canvas.clipRect(0, 0, w, h)
            if (engine.isTransparentBackground) {
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), checkerPaint)
            }
            when {
                !engine.compositeIsSparse -> canvas.drawBitmap(engine.composite, 0f, 0f, blitPaint)
                zoomedOut -> engine.drawMip(canvas)
                else -> engine.drawComposite(canvas, repeatRect)
            }
            canvas.drawRect(repeatRect, repeatShade)
            canvas.restoreToCount(save)
        }
    }

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
                val st0 = System.nanoTime()
                session.runCommands()
                val st1 = System.nanoTime()
                if (session.runInput(INPUT_BUDGET_NS)) work = true
                val st2 = System.nanoTime()
                if (session.consumePendingPreview()) work = true
                if (session.tickFrame()) work = true
                session.rebuildTransformIfNeeded()

                val engine = session.engine
                // Tile cache: adopt finished prefetches, then composite without
                // blocking on disk (non-active layers' evicted tiles are deferred
                // and their composite tiles stay stale until they land).
                val cache = TileCache.active
                cache?.adoptPrefetched()
                if (cache != null) {
                    // Catching up (after an undo, a fill, opening a file) goes
                    // three times faster while the user is not drawing or
                    // moving the view; during a stroke or a pinch frames stay
                    // light.
                    val v = session.view
                    val sig = v.scale.toBits().toLong() * 31L + v.tx.toBits() * 17L + v.ty.toBits() + v.rotationDeg.toBits() * 7L
                    val now = android.os.SystemClock.uptimeMillis()
                    if (sig != lastViewSig) {
                        lastViewSig = sig
                        lastViewMove = now
                    }
                    val busy = session.strokeInProgress || now - lastViewMove < 250L
                    cache.frameCompositeBudgetNs = if (busy) 4_000_000L else 12_000_000L
                    cache.blockingLoads = false
                }
                try {
                    if (engine.flush()) work = true
                } finally {
                    if (cache != null) cache.blockingLoads = true
                }
                val st3 = System.nanoTime()
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
                    val st4 = System.nanoTime()
                    updateGlass()
                    val st5 = System.nanoTime()
                    // Safe point: nothing holds a writable tile between frames.
                    cache?.evictIfNeeded()
                    val st6 = System.nanoTime()
                    // Debug: any turn of the loop long enough to feel like a
                    // freeze, and where its time went.
                    if (com.yume.app.BuildConfig.DEBUG && st6 - st0 > 150_000_000L) {
                        android.util.Log.w("YumeStall", "turn ${(st6 - st0) / 1_000_000} ms: commands ${(st1 - st0) / 1_000_000} " +
                            "input ${(st2 - st1) / 1_000_000} flush ${(st3 - st2) / 1_000_000} draw ${(st4 - st3) / 1_000_000} " +
                            "glass ${(st5 - st4) / 1_000_000} evict ${(st6 - st5) / 1_000_000}")
                    }
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
            if (engine.mipDrawable()) {
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

    private var lastMipLog = 0L
    private var lastViewSig = 0L
    private var lastViewMove = 0L

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
                        // While painting, the frame's time goes to the stroke:
                        // the fresh paint is drawn from the full-resolution
                        // composite below, and the mip catches up afterwards.
                        engine.updateMip(if (session.strokeInProgress) MIP_STROKE_BUDGET_NS else MIP_FRAME_BUDGET_NS)
                        mipCarry = engine.hasMipDirty
                    }
                    if (com.yume.app.BuildConfig.DEBUG) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastMipLog > 500L) {
                            lastMipLog = now
                            android.util.Log.i("YumeMip", "zoomed out scale=${view.scale} carry=$mipCarry ${engine.mipDebug()}")
                        }
                    }
                    if (engine.mipDrawable()) {
                        engine.drawMip(canvas)
                        engine.drawMipPending(canvas, rect)
                    } else engine.drawComposite(canvas, rect)
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
            if (engine.patternMode != 0) drawPatternRepeats(canvas)

            canvas.restore()

            drawCanvasBorder(canvas, matrix, engine.width, engine.height)
            drawGuides(canvas, matrix, engine.width, engine.height)
            drawShapePreview(canvas, matrix, engine.width, engine.height)
            drawSymmetry(canvas, matrix, engine.width, engine.height)
            drawSelection(canvas, matrix)
            drawTransform(canvas, matrix)
            drawCloneSource(canvas, matrix)
            drawInsertEditor(canvas, matrix)
            drawGradientGuide(canvas, matrix)
            drawPendingInk(canvas, matrix)
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
            com.yume.app.engine.GuideMode.Isometric -> {
                // Lines at 30 and 150 degrees plus verticals, one cell apart.
                val step = g.gridSize
                var k = 1
                while (step * k * scale < 10f * density) k *= 2
                val sp = step * k
                val far = (w + h).toFloat() * 2f
                for (deg in listOf(30.0, 150.0, 90.0)) {
                    val a = Math.toRadians(deg)
                    val dx = kotlin.math.cos(a).toFloat()
                    val dy = kotlin.math.sin(a).toFloat()
                    // Offsets along the normal cover the canvas.
                    val nx = -dy
                    val ny = dx
                    val reach = (kotlin.math.abs(nx) * w + kotlin.math.abs(ny) * h)
                    // Equilateral lattice: every family is sp * sin 60 apart
                    // and passes through the canvas centre, so they meet.
                    val gap = sp * 0.8660254f
                    var o = -kotlin.math.ceil(reach / gap) * gap
                    while (o <= reach) {
                        val ox = w / 2f + nx * o
                        val oy = h / 2f + ny * o
                        canvas.drawLine(ox - dx * far, oy - dy * far, ox + dx * far, oy + dy * far, guidePaint)
                        o += gap
                    }
                }
            }
            com.yume.app.engine.GuideMode.Fisheye -> {
                val p = g.fishPoints()
                val r = g.fishR
                // Arcs through left/right and through top/bottom.
                for (fam in 0..1) {
                    val ax = p[fam * 4]; val ay = p[fam * 4 + 1]
                    val bx = p[fam * 4 + 2]; val by = p[fam * 4 + 3]
                    for (k in -10..10) {
                        val t = k / 10f * r * 1.6f
                        val px = if (fam == 0) g.fishX else g.fishX + t
                        val py = if (fam == 0) g.fishY + t else g.fishY
                        when (val sn = com.yume.app.engine.GuideSnap.through(ax, ay, bx, by, px, py)) {
                            is com.yume.app.engine.GuideSnap.Circle -> canvas.drawCircle(sn.cx, sn.cy, sn.r, guidePaint)
                            is com.yume.app.engine.GuideSnap.Line -> {
                                val far = maxOf(w, h) * 4f
                                canvas.drawLine(sn.ox - sn.ux * far, sn.oy - sn.uy * far, sn.ox + sn.ux * far, sn.oy + sn.uy * far, guidePaint)
                            }
                            else -> Unit
                        }
                    }
                }
                if (g.fishCount == 5) {
                    val far = maxOf(w, h) * 4f
                    for (k in 0 until 32) {
                        val a = 2.0 * Math.PI * k / 32
                        canvas.drawLine(g.fishX, g.fishY, g.fishX + far * kotlin.math.cos(a).toFloat(), g.fishY + far * kotlin.math.sin(a).toFloat(), guidePaint)
                    }
                }
                guidePaint.color = 0xAAE9A13B.toInt()
                guidePaint.strokeWidth = 1.6f * density / scale
                canvas.drawCircle(g.fishX, g.fishY, r, guidePaint)
            }
            com.yume.app.engine.GuideMode.Ellipse -> {
                val save2 = canvas.save()
                canvas.translate(g.ellX, g.ellY)
                canvas.rotate(Math.toDegrees(g.ellAngle.toDouble()).toFloat())
                for (k in listOf(0.25f, 0.5f, 0.75f, 1.5f, 2f, 3f)) {
                    canvas.drawOval(-g.ellRx * k, -g.ellRy * k, g.ellRx * k, g.ellRy * k, guidePaint)
                }
                canvas.drawLine(-g.ellRx * 3f, 0f, g.ellRx * 3f, 0f, guidePaint)
                canvas.drawLine(0f, -g.ellRy * 3f, 0f, g.ellRy * 3f, guidePaint)
                guidePaint.color = 0xCCE9A13B.toInt()
                guidePaint.strokeWidth = 2f * density / scale
                canvas.drawOval(-g.ellRx, -g.ellRy, g.ellRx, g.ellRy, guidePaint)
                canvas.restoreToCount(save2)
            }
            com.yume.app.engine.GuideMode.Curve -> {
                val pts = g.curvePoints()
                guidePaint.color = 0xCCE9A13B.toInt()
                guidePaint.strokeWidth = 2f * density / scale
                val path = android.graphics.Path()
                path.moveTo(pts[0], pts[1])
                var i = 2
                while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
                canvas.drawPath(path, guidePaint)
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

    private val xformLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val xformPath = android.graphics.Path()

    /** The clone tool's source: a ring with a cross, where it is copying from. */
    private fun drawCloneSource(canvas: Canvas, matrix: Matrix) {
        val at = session.cloneMarker() ?: return
        val p = floatArrayOf(at[0], at[1])
        matrix.mapPoints(p)
        val r = maxOf(10f * density, session.brushSize * 0.5f * session.view.scale)
        xformLine.strokeWidth = 3f * density
        xformLine.color = 0x66000000
        canvas.drawCircle(p[0], p[1], r, xformLine)
        canvas.drawLine(p[0] - 6f * density, p[1], p[0] + 6f * density, p[1], xformLine)
        canvas.drawLine(p[0], p[1] - 6f * density, p[0], p[1] + 6f * density, xformLine)
        xformLine.strokeWidth = 1.4f * density
        xformLine.color = 0xF2FFFFFF.toInt()
        canvas.drawCircle(p[0], p[1], r, xformLine)
        canvas.drawLine(p[0] - 6f * density, p[1], p[0] + 6f * density, p[1], xformLine)
        canvas.drawLine(p[0], p[1] - 6f * density, p[0], p[1] + 6f * density, xformLine)
    }

    private val framePath = android.graphics.Path()
    private val frameRect = android.graphics.Path()

    private fun mappedRect(r: android.graphics.RectF, matrix: Matrix): android.graphics.Path {
        frameRect.reset()
        frameRect.addRect(r, android.graphics.Path.Direction.CW)
        frameRect.transform(matrix)
        return frameRect
    }

    /**
     * Editing a previewed insert: the frames grid (cells dashed, panels with
     * a resize dot, the selected one with a delete button, the panel being
     * dragged as a green or red ghost), or the focus lines' centre.
     */
    private val markPath = android.graphics.Path()
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Background removal: the keep (green) and remove (red) marks. */
    private fun drawCutOutMarks(canvas: Canvas, matrix: Matrix) {
        val marks = session.cutOutMarks() ?: return
        val scale = session.view.scale
        for (m in marks) {
            if (m.count == 0) continue
            markPath.reset()
            markPath.moveTo(m.points[0], m.points[1])
            // A single tap still shows a dot.
            if (m.count == 1) markPath.lineTo(m.points[0] + 0.01f, m.points[1])
            for (i in 1 until m.count) markPath.lineTo(m.points[i * 2], m.points[i * 2 + 1])
            markPath.transform(matrix)
            markPaint.strokeWidth = m.radius * 2f * scale
            markPaint.color = if (m.mode == 1) 0x6648C774 else 0x66E04848
            canvas.drawPath(markPath, markPaint)
        }
    }

    /** A shape being edited: its box, corner dots and the rotate handle. */
    private fun drawShapeEditor(canvas: Canvas, matrix: Matrix) {
        val h = session.editShapeHandles() ?: return
        val pts = h.copyOf()
        matrix.mapPoints(pts)
        overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = 1.4f * density
        overlay.color = 0xCCE9A13B.toInt()
        overlay.pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
        if (pts.size >= 12) {
            framePath.reset()
            framePath.moveTo(pts[0], pts[1])
            for (k in 1..3) framePath.lineTo(pts[k * 2], pts[k * 2 + 1])
            framePath.close()
            canvas.drawPath(framePath, overlay)
            overlay.pathEffect = null
            canvas.drawLine(pts[10], pts[11], pts[8], pts[9], overlay)
        }
        overlay.pathEffect = null
        val dots = if (pts.size >= 12) 5 else 2
        for (k in 0 until dots) {
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = 0xF2FFFFFF.toInt()
            canvas.drawCircle(pts[k * 2], pts[k * 2 + 1], 7f * density, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 2.5f * density
            handlePaint.color = 0xFFE9A13B.toInt()
            canvas.drawCircle(pts[k * 2], pts[k * 2 + 1], 7f * density, handlePaint)
        }
    }

    private fun drawInsertEditor(canvas: Canvas, matrix: Matrix) {
        drawCutOutMarks(canvas, matrix)
        drawShapeEditor(canvas, matrix)
        session.focusCenterMarker()?.let { at ->
            val p = floatArrayOf(at[0], at[1])
            matrix.mapPoints(p)
            val r = 14f * density
            for (pass in 0..1) {
                xformLine.strokeWidth = if (pass == 0) 3.5f * density else 1.6f * density
                xformLine.color = if (pass == 0) 0x77000000 else 0xF2FFFFFF.toInt()
                canvas.drawCircle(p[0], p[1], r, xformLine)
                canvas.drawLine(p[0] - r * 1.6f, p[1], p[0] + r * 1.6f, p[1], xformLine)
                canvas.drawLine(p[0], p[1] - r * 1.6f, p[0], p[1] + r * 1.6f, xformLine)
            }
        }
        val f = session.framesOverlay() ?: return
        // Empty cells: dashed.
        framePath.reset()
        for (c in f.cells) framePath.addRect(c, android.graphics.Path.Direction.CW)
        framePath.transform(matrix)
        overlay.style = Paint.Style.STROKE
        overlay.strokeWidth = 1f * density
        overlay.pathEffect = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)
        overlay.color = 0x66E9A13B
        canvas.drawPath(framePath, overlay)
        overlay.pathEffect = null
        // Panels: the outline sits just inside the black border so the
        // border itself (and the Border slider) stays visible.
        val scale = session.view.scale.coerceAtLeast(1e-4f)
        for ((i, r) in f.panels.withIndex()) {
            val sel = i == f.selected
            overlay.style = Paint.Style.STROKE
            overlay.strokeWidth = (if (sel) 3f else 1.6f) * density
            overlay.color = if (sel) 0xFFE9A13B.toInt() else 0xCCE9A13B.toInt()
            val inset = (f.border / 2f + (overlay.strokeWidth / 2f + 1.5f * density) / scale)
                .coerceAtMost(minOf(r.width(), r.height()) / 4f)
            val inner = android.graphics.RectF(r).apply { inset(inset, inset) }
            canvas.drawPath(mappedRect(inner, matrix), overlay)
            val corner = floatArrayOf(r.right, r.bottom, r.right, r.top)
            matrix.mapPoints(corner)
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = 0xF2FFFFFF.toInt()
            canvas.drawCircle(corner[0], corner[1], 7f * density, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 2.5f * density
            handlePaint.color = 0xFFE9A13B.toInt()
            canvas.drawCircle(corner[0], corner[1], 7f * density, handlePaint)
            if (sel) {
                val x = corner[2]
                val y = corner[3]
                val rr = 11f * density
                handlePaint.style = Paint.Style.FILL
                handlePaint.color = 0xFFD9534F.toInt()
                canvas.drawCircle(x, y, rr, handlePaint)
                xformLine.strokeWidth = 2f * density
                xformLine.color = 0xFFFFFFFF.toInt()
                val k = rr * 0.45f
                canvas.drawLine(x - k, y - k, x + k, y + k, xformLine)
                canvas.drawLine(x - k, y + k, x + k, y - k, xformLine)
            }
        }
        f.ghost?.let { g ->
            val path = mappedRect(g, matrix)
            overlay.style = Paint.Style.FILL
            overlay.color = if (f.ghostValid) 0x3348C774 else 0x44E04848
            canvas.drawPath(path, overlay)
            overlay.style = Paint.Style.STROKE
            overlay.strokeWidth = 2.5f * density
            overlay.color = if (f.ghostValid) 0xFF48C774.toInt() else 0xFFE04848.toInt()
            canvas.drawPath(path, overlay)
        }
    }

    /** The transform's outline, warp grid and draggable handles, in screen space. */
    private fun drawTransform(canvas: Canvas, matrix: Matrix) {
        val lines = session.transformOutlines() ?: return
        xformPath.reset()
        for (line in lines) {
            if (line.size < 4) continue
            val pts = line.copyOf()
            matrix.mapPoints(pts)
            xformPath.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                xformPath.lineTo(pts[i], pts[i + 1])
                i += 2
            }
        }
        xformLine.strokeWidth = 3f * density
        xformLine.color = 0x66000000
        canvas.drawPath(xformPath, xformLine)
        xformLine.strokeWidth = 1.2f * density
        xformLine.color = 0xE6FFFFFF.toInt()
        canvas.drawPath(xformPath, xformLine)
        val handles = session.transformHandles() ?: return
        if (handles.isEmpty()) return
        val pts = handles.copyOf()
        matrix.mapPoints(pts)
        var i = 0
        while (i < pts.size) {
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = 0xE6FFFFFF.toInt()
            canvas.drawCircle(pts[i], pts[i + 1], 8f * density, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 2.5f * density
            handlePaint.color = 0xFFE9A13B.toInt()
            canvas.drawCircle(pts[i], pts[i + 1], 8f * density, handlePaint)
            i += 2
        }
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
    private val pendingPts = FloatArray(4096)
    private val pendingPath = android.graphics.Path()
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * While painting trails the pen (a huge brush on a big canvas), the part
     * of the stroke not painted yet is drawn as plain ink at the brush's
     * current size and colour, so the stroke appears under the pen at once;
     * the real dabs replace it as they catch up.
     */
    private fun drawPendingInk(canvas: Canvas, matrix: Matrix) {
        val n = session.pendingInk(pendingPts)
        if (n == 0) return
        // The eraser's unpainted part shows the paper (exact on a single
        // layer; the real erase replaces it a moment later either way).
        val paper = session.engine.backgroundColor
        fun look(ink: DrawingSession.LiveInk, widthPx: Float) {
            pendingPaint.strokeWidth = max(1f, widthPx)
            pendingPaint.color = if (!ink.eraser) ink.color
                else if ((paper ushr 24) == 0) 0xFFE6E6E6.toInt() else paper or (0xFF shl 24)
            pendingPaint.alpha = (255 * ink.opacity.coerceIn(0.05f, 1f)).toInt()
        }
        var i = 0
        // The stroke being painted: from the painted tip, in its own look
        // (kept when the tool changes mid-stroke).
        val ink = session.liveInk
        val live = session.liveCursor()
        if (!pendingPts[0].isNaN()) {
            if (ink != null && live != null) {
                val from = floatArrayOf(live[0], live[1])
                matrix.mapPoints(from)
                pendingPath.rewind()
                pendingPath.moveTo(from[0], from[1])
                while (i < n && !pendingPts[i * 2].isNaN()) {
                    pendingPath.lineTo(pendingPts[i * 2], pendingPts[i * 2 + 1])
                    i++
                }
                look(ink, live[2] * session.view.scale)
                canvas.drawPath(pendingPath, pendingPaint)
            } else {
                while (i < n && !pendingPts[i * 2].isNaN()) i++
            }
        }
        if (i >= n) return
        // Strokes drawn after it, still queued: in the tool's current look,
        // at the brush's full size.
        val queued = session.queuedInk() ?: return
        pendingPath.rewind()
        while (i < n) {
            if (pendingPts[i * 2].isNaN()) {
                i++
                if (i < n) {
                    pendingPath.moveTo(pendingPts[i * 2], pendingPts[i * 2 + 1])
                    // A tap leaves a dot.
                    pendingPath.lineTo(pendingPts[i * 2] + 0.01f, pendingPts[i * 2 + 1])
                }
            } else {
                pendingPath.lineTo(pendingPts[i * 2], pendingPts[i * 2 + 1])
            }
            i++
        }
        look(queued, session.brushSize * session.view.scale)
        canvas.drawPath(pendingPath, pendingPaint)
    }

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

    private val cursorPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val cursorDst = android.graphics.RectF()
    private var cursorTint = 0
    private var cursorTintFilter: android.graphics.ColorFilter? = null

    private fun drawHover(canvas: Canvas, matrix: Matrix) {
        if (session.transformActive) return
        // While painting there is no cursor at all: only the stroke itself.
        if (session.liveCursor() != null) return
        val live: FloatArray? = null
        if (!session.hoverVisible) return
        val mapped = FloatArray(2)
        val docX = live?.get(0) ?: session.hoverX
        val docY = live?.get(1) ?: session.hoverY
        val docSize = live?.get(2) ?: session.brushSize
        matrix.mapPoints(mapped, floatArrayOf(docX, docY))

        // Hovering: a preview of the dab itself (shape, hardness, grain, wet
        // edges), at the brush's size and angle, in its colour and opacity.
        // Hovering: the dab the stroke will lay down at the lowest pressure:
        // its size, angle, opacity and blend from the stroke's own rules
        // (StrokePainter.cursorDab), the tip from the painter's dab cache,
        // drawn in document space so zoom, view rotation and flip match.
        val preset = if (live == null) session.cursorPreset else null
        val cd = if (preset != null) session.cursorDab() else null
        var ringSize = docSize
        if (preset != null && cd != null) {
            ringSize = cd.size
            val dab = runCatching { BrushDab.dab(preset) }.getOrNull()
            if (dab != null && !dab.isRecycled && cd.size * session.view.scale >= 3f) {
                val tint = when (preset.category) {
                    BrushCategory.Brush -> session.color
                    // What the eraser and smudge do depends on the paint
                    // under them: shown as a neutral light / grey dab.
                    BrushCategory.Eraser -> 0xFFFFFFFF.toInt()
                    BrushCategory.Smudge -> 0xFFB8B8C0.toInt()
                }
                if (tint != cursorTint) {
                    cursorTint = tint
                    cursorTintFilter = android.graphics.PorterDuffColorFilter(tint or 0xFF000000.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                }
                cursorPaint.colorFilter = cursorTintFilter
                cursorPaint.alpha = (cd.alpha * ((tint ushr 24) / 255f) * 255f).toInt().coerceIn(1, 255)
                cursorPaint.blendMode = if (preset.category == BrushCategory.Brush) BrushPreset.blendMode(preset.blend)
                else android.graphics.BlendMode.SRC_OVER
                val h = cd.size * 0.5f
                canvas.save()
                canvas.concat(matrix)
                canvas.translate(docX, docY)
                if (cd.angle != 0f) canvas.rotate(cd.angle)
                cursorDst.set(-h, -h, h, h)
                canvas.drawBitmap(dab, null, cursorDst, cursorPaint)
                canvas.restore()
                // The preview is the cursor: no outline ring around it.
                return
            }
        }
        val radius = max(2.5f, ringSize * 0.5f * session.view.scale)

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

/** Input painted per render-loop turn before a frame is drawn (the rest waits a turn). */
private const val INPUT_BUDGET_NS = 12_000_000L
