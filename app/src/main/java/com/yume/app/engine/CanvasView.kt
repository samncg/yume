package com.yume.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Surface the engine paints into, plus all pointer handling.
 *
 * Gesture model (matching Procreate closely):
 *  - stylus / finger paints (finger painting can be disabled)
 *  - two fingers pan, pinch-zoom and rotate
 *  - two-finger tap = undo, three = redo, four = toggle the interface
 *  - long press while starting a stroke = eyedropper
 *  - stylus barrel button paints with the eraser
 */
class CanvasView(
    context: Context,
    private val session: DrawingSession
) : SurfaceView(context), SurfaceHolder.Callback {

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var longPressAction: Runnable? = null
    private var quickShapeAction: Runnable? = null

    private var renderer: SurfaceRenderer? = null

    private enum class Mode { None, Stroke, Selection, Transform, Pick }

    private var mode = Mode.None
    private var activePointerId = -1
    private var twoFinger = false
    /** A stylus is currently in contact — fingers are treated as palms. */
    private var stylusActive = false
    /** A stylus is hovering above the screen — fingers are palms too. */
    private var stylusNear = false
    private var maxFingers = 1
    private var downTime = 0L
    private var moved = 0f
    private var lastX = 0f
    private var lastY = 0f

    private var gestureFocusX = 0f
    private var gestureFocusY = 0f
    private var gestureSpan = 0f
    private var gestureAngle = 0f

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
        isFocusable = true
        isHapticFeedbackEnabled = true
    }

    // ------------------------------------------------------------- surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRenderer()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer?.setSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRenderer()
    }

    override fun onDetachedFromWindow() {
        stopRenderer()
        cancelScheduledLongPress()
        super.onDetachedFromWindow()
    }

    private fun startRenderer() {
        if (renderer != null) return
        if (width <= 0 || height <= 0) return
        updateTopInset()
        val r = SurfaceRenderer(session, holder, density)
        renderer = r
        r.setSurfaceSize(width, height)
        r.start()
    }

    /** Tells the view transform how much room the floating toolbar takes. */
    private fun updateTopInset() {
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(this)
        val statusBar = insets
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            ?.top ?: 0
        // 54dp toolbar + status bar, matching EditorScreen's TOP_BAR_HEIGHT.
        session.chromeTopInset = statusBar + 54f * density
    }

    private fun stopRenderer() {
        val r = renderer ?: return
        renderer = null
        r.shutdown()
    }

    // --------------------------------------------------------------- input

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val stylus = isStylus(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (stylus) stylusNear = true
                session.pushInput(InputEvent.Hover(event.x, event.y, true))
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (stylus) stylusNear = false
                session.pushInput(InputEvent.Hover(event.x, event.y, false))
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> cancelEverything()
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        // Palm rejection: a broad contact is never a drawing finger.
        if (isPalm(event, 0)) {
            mode = Mode.None
            twoFinger = false
            return
        }
        val stylus = isStylus(event)
        // Fingers are ignored while the pen is hovering or already down.
        if (!stylus && (stylusNear || stylusActive)) {
            mode = Mode.None
            twoFinger = false
            return
        }
        stylusActive = stylus

        downTime = event.eventTime
        moved = 0f
        maxFingers = 1
        twoFinger = false
        lastX = event.x
        lastY = event.y
        activePointerId = event.getPointerId(0)

        val barrel = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        when {
            session.tool == Tool.Selection -> {
                mode = Mode.Selection
                pushDown(event, 0, barrel)
            }
            session.tool == Tool.Transform -> {
                mode = Mode.Transform
                pushDown(event, 0, barrel)
            }
            !stylus && !session.fingerPaint -> {
                mode = Mode.None
                twoFinger = true
                rebaseGesture(event, -1)
            }
            else -> {
                mode = Mode.Stroke
                pushDown(event, 0, barrel)
                scheduleLongPress()
            }
        }
    }

    private fun onPointerDown(event: MotionEvent) {
        // While the pen is drawing, extra contacts are palm noise: ignore them
        // completely instead of cancelling the stroke.
        if (stylusActive) return
        if (isPalm(event, event.actionIndex)) return

        maxFingers = max(maxFingers, event.pointerCount)
        cancelScheduledLongPress()
        cancelQuickShape()
        if (mode == Mode.Stroke || mode == Mode.Selection) {
            // Roll back whatever single-pointer action had started.
            session.pushInput(InputEvent.CancelStroke)
            mode = Mode.None
        }
        twoFinger = true
        rebaseGesture(event, -1)
    }

    private fun onMove(event: MotionEvent) {
        accumulateMovement(event)

        if (twoFinger) {
            if (event.pointerCount >= 2) sendGesture(event)
            return
        }

        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return

        when (mode) {
            Mode.Stroke, Mode.Selection, Mode.Transform -> {
                val count = event.historySize + 1
                val xs = FloatArray(count)
                val ys = FloatArray(count)
                val ps = FloatArray(count)
                for (h in 0 until event.historySize) {
                    xs[h] = event.getHistoricalX(index, h)
                    ys[h] = event.getHistoricalY(index, h)
                    ps[h] = pressureAt(event, index)
                }
                xs[count - 1] = event.x
                ys[count - 1] = event.y
                ps[count - 1] = pressureAt(event, index)
                session.pushInput(
                    InputEvent.Move(activePointerId, xs, ys, ps, tiltAt(event, index))
                )
            }
            Mode.Pick -> session.pushInput(InputEvent.PickMove(event.x, event.y))
            Mode.None -> Unit
        }

        // Holding still at the end of a stroke straightens it (QuickShape).
        if (mode == Mode.Stroke && !twoFinger) scheduleQuickShape()
    }

    private fun onPointerUp(event: MotionEvent) {
        if (stylusActive) return
        // Rebase the pinch maths on the fingers that are still down.
        if (twoFinger) {
            rebaseGesture(event, event.actionIndex)
        }
    }

    private fun onUp(event: MotionEvent) {
        cancelScheduledLongPress()
        cancelQuickShape()

        if (twoFinger) {
            val duration = event.eventTime - downTime
            if (duration < 420L && moved < 26f * density) {
                session.pushInput(InputEvent.Tap(maxFingers))
            }
            twoFinger = false
        } else {
            when (mode) {
                Mode.Stroke, Mode.Selection, Mode.Transform ->
                    session.pushInput(InputEvent.Up(activePointerId))
                Mode.Pick -> session.pushInput(InputEvent.PickEnd)
                Mode.None -> Unit
            }
        }
        mode = Mode.None
        activePointerId = -1
        maxFingers = 1
        stylusActive = false
    }

    private fun cancelEverything() {
        cancelScheduledLongPress()
        cancelQuickShape()
        session.pushInput(InputEvent.CancelStroke)
        session.pushInput(InputEvent.PickEnd)
        mode = Mode.None
        twoFinger = false
        activePointerId = -1
        stylusActive = false
    }

    private fun pushDown(event: MotionEvent, index: Int, barrel: Boolean) {
        session.pushInput(
            InputEvent.Down(
                pointerId = activePointerId,
                x = event.getX(index),
                y = event.getY(index),
                pressure = pressureAt(event, index),
                tilt = tiltAt(event, index),
                stylus = isStylus(event),
                eraserButton = barrel
            )
        )
    }

    private fun scheduleLongPress() {
        val action = Runnable {
            if (mode == Mode.Stroke && moved < 9f * density) {
                session.pushInput(InputEvent.CancelStroke)
                session.pushInput(InputEvent.PickStart(lastX, lastY))
                mode = Mode.Pick
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
        longPressAction = action
        handler.postDelayed(action, 380L)
    }

    private fun cancelScheduledLongPress() {
        longPressAction?.let { handler.removeCallbacks(it) }
        longPressAction = null
    }

    private fun scheduleQuickShape() {
        quickShapeAction?.let { handler.removeCallbacks(it) }
        val action = Runnable {
            if (mode == Mode.Stroke && !twoFinger) {
                session.pushInput(InputEvent.QuickShape)
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        quickShapeAction = action
        handler.postDelayed(action, 700L)
    }

    private fun cancelQuickShape() {
        quickShapeAction?.let { handler.removeCallbacks(it) }
        quickShapeAction = null
    }

    // ------------------------------------------------------------ gestures

    private fun rebaseGesture(event: MotionEvent, excludeIndex: Int) {
        gestureFocusX = focusX(event, excludeIndex)
        gestureFocusY = focusY(event, excludeIndex)
        gestureSpan = spanOf(event, excludeIndex)
        gestureAngle = angleOf(event, excludeIndex)
    }

    private fun sendGesture(event: MotionEvent) {
        val fx = focusX(event, -1)
        val fy = focusY(event, -1)
        val span = spanOf(event, -1)
        val angle = angleOf(event, -1)

        val factor = if (gestureSpan > 1f && span > 1f) span / gestureSpan else 1f
        var delta = Math.toDegrees((angle - gestureAngle).toDouble()).toFloat()
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f

        val panX = fx - gestureFocusX
        val panY = fy - gestureFocusY

        session.pushInput(
            InputEvent.Gesture(
                focusX = gestureFocusX,
                focusY = gestureFocusY,
                scaleFactor = factor.coerceIn(0.25f, 4f),
                angleDelta = delta,
                panX = panX,
                panY = panY
            )
        )

        gestureFocusX = fx
        gestureFocusY = fy
        gestureSpan = span
        gestureAngle = angle
    }

    private fun focusX(event: MotionEvent, exclude: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == exclude) continue
            sum += event.getX(i)
            count++
        }
        return if (count == 0) event.x else sum / count
    }

    private fun focusY(event: MotionEvent, exclude: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == exclude) continue
            sum += event.getY(i)
            count++
        }
        return if (count == 0) event.y else sum / count
    }

    private fun spanOf(event: MotionEvent, exclude: Int): Float {
        val cx = focusX(event, exclude)
        val cy = focusY(event, exclude)
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == exclude) continue
            sum += hypot(event.getX(i) - cx, event.getY(i) - cy)
            count++
        }
        if (count == 0) return 0f
        // Twice the mean distance is a good proxy for two-finger span and
        // degrades gracefully with three or more fingers.
        return sum / count * 2f
    }

    private fun angleOf(event: MotionEvent, exclude: Int): Float {
        var first = -1
        var second = -1
        for (i in 0 until event.pointerCount) {
            if (i == exclude) continue
            if (first < 0) first = i else if (second < 0) second = i
        }
        if (first < 0 || second < 0) return gestureAngle
        return atan2(
            (event.getY(second) - event.getY(first)).toDouble(),
            (event.getX(second) - event.getX(first)).toDouble()
        ).toFloat()
    }

    private fun accumulateMovement(event: MotionEvent) {
        val index = event.findPointerIndex(activePointerId)
        val x = if (index >= 0) event.getX(index) else event.x
        val y = if (index >= 0) event.getY(index) else event.y
        moved += hypot(x - lastX, y - lastY)
        lastX = x
        lastY = y
    }

    // ----------------------------------------------------------- utilities

    private fun isStylus(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    /**
     * A palm/fist covers far more area than a fingertip (~1cm across), so a
     * large contact ellipse is discarded before it can start a stroke.
     */
    private fun isPalm(event: MotionEvent, index: Int): Boolean {
        val major = try {
            event.getTouchMajor(index)
        } catch (t: Throwable) {
            0f
        }
        return major > 80f * density
    }

    private fun pressureAt(event: MotionEvent, index: Int): Float {
        val raw = try {
            event.getPressure(index)
        } catch (t: Throwable) {
            1f
        }
        // A stylus reports ~0 pressure as it leaves the glass. Substituting 1.0
        // for that produced a fat blob at the end of every stroke, so stylus
        // pressures are used as-is with a small floor.
        return if (isStylus(event)) {
            raw.coerceIn(0.02f, 1f)
        } else if (raw <= 0.01f) {
            1f
        } else {
            raw.coerceIn(0.05f, 1f)
        }
    }

    private fun tiltAt(event: MotionEvent, index: Int): Float {
        return try {
            val radians = event.getAxisValue(MotionEvent.AXIS_TILT, index)
            (radians / (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
        } catch (t: Throwable) {
            0f
        }
    }
}
