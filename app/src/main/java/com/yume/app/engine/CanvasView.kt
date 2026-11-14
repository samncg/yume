package com.yume.app.engine

import kotlin.math.abs
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
 *  - two-finger long press (held still) = the shortcut ring menu
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
    private var menuHoldAction: Runnable? = null
    /** The two-finger hold opened the menu: the rest of this gesture is spent. */
    private var menuHoldFired = false
    /** Where the two fingers were when the hold started: drift is measured from here. */
    private var holdFocusX = 0f
    private var holdFocusY = 0f
    private var holdSpan = 0f
    private var holdAngle = 0f

    private var renderer: SurfaceRenderer? = null

    private enum class Mode { None, Pending, Stroke, Selection, Transform, Pick }

    private var mode = Mode.None

    /*
     * A finger that lands alone may be the first half of a two-finger pan.
     * Its events are held for a moment ([FINGER_COMMIT_MS]) and only then
     * replayed to the tool; a second finger in that window drops them, so the
     * tool never sees the touch at all. A stylus is never held back.
     */
    private var pendingMode = Mode.None
    private val pendingEvents = ArrayList<InputEvent>()
    private var commitAction: Runnable? = null
    /** The next gesture event starts a new multi-finger gesture. */
    private var gestureFresh = false

    /** Tremor filter and tip predictor for the current stroke. */
    private val motionFilter = com.yume.app.input.MotionFilter()
    private val predictor = com.yume.app.input.StrokePredictor()
    private val filtered = FloatArray(2)
    private var filterStroke = false
    private var predictStroke = false

    /** Three-finger sideways scrub through history. */
    private var scrubStartX = 0f
    private var scrubStartY = 0f
    private var scrubDecided = false
    private var scrubbing = false
    private var scrubSteps = 0
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
        if (stylus) {
            com.yume.app.input.PenGestures.lastX = event.x
            com.yume.app.input.PenGestures.lastY = event.y
        }
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
        if (isStylus(event)) {
            com.yume.app.input.PenGestures.lastX = event.x
            com.yume.app.input.PenGestures.lastY = event.y
        }
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

        val target = when {
            session.tool == Tool.Selection -> Mode.Selection
            session.tool == Tool.Transform -> Mode.Transform
            !stylus && !session.fingerPaint -> Mode.None
            else -> Mode.Stroke
        }
        if (target == Mode.None) {
            mode = Mode.None
            twoFinger = true
            rebaseGesture(event, -1)
            return
        }
        // Tremor filtering and tip prediction apply to painting strokes.
        filterStroke = target == Mode.Stroke
        motionFilter.strength = if (filterStroke) com.yume.app.data.AppSettings.motionFiltering else 0f
        motionFilter.reset(event.x, event.y, event.eventTime)
        predictor.reset()
        predictStroke = stylus && target == Mode.Stroke && session.tool == Tool.Brush &&
            com.yume.app.data.AppSettings.prediction
        session.predictedTip = null
        // The pen's samples reach us as they arrive, not batched per frame.
        if (stylus && android.os.Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(event)
        val down = downEvent(event, 0, barrel)
        if (stylus) {
            mode = target
            session.pushInput(down)
            startTimers()
        } else {
            mode = Mode.Pending
            pendingMode = target
            pendingEvents.clear()
            pendingEvents.add(down)
            val action = Runnable { commitPending() }
            commitAction = action
            handler.postDelayed(action, FINGER_COMMIT_MS)
        }
    }

    /** Timers that run while a single pointer is on a tool. */
    private fun startTimers() {
        // Holding still picks a colour only with the painting tools: liquify,
        // shapes and the rest use a resting pen themselves.
        if (mode == Mode.Stroke && session.tool in EYEDROPPER_TOOLS) {
            val elapsed = android.os.SystemClock.uptimeMillis() - downTime
            scheduleLongPress((com.yume.app.data.AppSettings.eyedropperHoldMs - elapsed).coerceAtLeast(0L))
        }
    }

    /** The held-back finger is a real single touch: hand it to the tool. */
    private fun commitPending() {
        commitAction?.let { handler.removeCallbacks(it) }
        commitAction = null
        if (mode != Mode.Pending) return
        mode = pendingMode
        for (e in pendingEvents) session.pushInput(e)
        pendingEvents.clear()
        startTimers()
    }

    /** A second finger arrived in time: the held-back touch never happened. */
    private fun dropPending() {
        commitAction?.let { handler.removeCallbacks(it) }
        commitAction = null
        pendingEvents.clear()
        if (mode == Mode.Pending) mode = Mode.None
    }

    private fun onPointerDown(event: MotionEvent) {
        // While the pen is drawing, extra contacts are palm noise: ignore them
        // completely instead of cancelling the stroke.
        if (stylusActive) return
        if (isPalm(event, event.actionIndex)) return

        maxFingers = max(maxFingers, event.pointerCount)
        cancelScheduledLongPress()
        cancelQuickShape()
        if (mode == Mode.Pending) {
            dropPending()
        } else if (mode == Mode.Stroke || mode == Mode.Selection || mode == Mode.Transform || mode == Mode.Pick) {
            // A slower second finger: roll back whatever the first one started.
            session.pushInput(InputEvent.CancelStroke)
            if (mode == Mode.Pick) session.pushInput(InputEvent.PickEnd)
        }
        mode = Mode.None
        twoFinger = true
        gestureFresh = true
        session.predictedTip = null
        rebaseGesture(event, -1)
        if (event.pointerCount == 3) {
            scrubStartX = gestureFocusX
            scrubStartY = gestureFocusY
            scrubDecided = false
            scrubbing = false
            scrubSteps = 0
        }
        if (event.pointerCount == 2 && maxFingers == 2) scheduleMenuHold() else cancelMenuHold()
    }

    private fun onMove(event: MotionEvent) {
        accumulateMovement(event)

        if (twoFinger) {
            if (menuHoldFired) return
            if (event.pointerCount == 3 && maxFingers == 3 && com.yume.app.data.AppSettings.threeFingerScrub) {
                if (handleScrub(event)) return
            }
            if (event.pointerCount >= 2) {
                sendGesture(event)
                // Panning, pinching or rotating cancels the hold. Measured from
                // where it started, so resting fingers' jitter never adds up.
                if (menuHoldAction != null && holdDrifted()) cancelMenuHold()
            }
            return
        }

        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return

        when (mode) {
            Mode.Stroke, Mode.Selection, Mode.Transform -> session.pushInput(moveEvent(event, index))
            Mode.Pending -> pendingEvents.add(moveEvent(event, index))
            Mode.Pick -> session.pushInput(InputEvent.PickMove(event.x, event.y))
            Mode.None -> Unit
        }

        // Holding still at the end of a stroke straightens it (QuickShape).
        // Measured on the filtered path, so tremor does not restart the wait.
        if (mode == Mode.Stroke && !twoFinger) {
            if (filterStroke) scheduleQuickShape(motionFilter.lastX, motionFilter.lastY)
            else scheduleQuickShape(event.x, event.y)
        }
    }

    private fun onPointerUp(event: MotionEvent) {
        if (stylusActive) return
        cancelMenuHold()
        // Rebase the pinch maths on the fingers that are still down.
        if (twoFinger) {
            rebaseGesture(event, event.actionIndex)
        }
    }

    private fun onUp(event: MotionEvent) {
        // A quick single tap (fill, text, auto-select) lifts inside the hold.
        if (mode == Mode.Pending) commitPending()
        cancelScheduledLongPress()
        cancelQuickShape()
        cancelMenuHold()

        session.predictedTip = null
        if (twoFinger) {
            val duration = event.eventTime - downTime
            if (!menuHoldFired && !scrubbing && duration < 420L && moved < 26f * density) {
                val s = com.yume.app.data.AppSettings
                val allowed = when (maxFingers) {
                    2 -> s.twoFingerUndo
                    3 -> s.threeFingerRedo
                    4 -> s.fourFingerUi
                    else -> true
                }
                if (allowed) session.pushInput(InputEvent.Tap(maxFingers))
            }
            scrubbing = false
            twoFinger = false
        } else {
            when (mode) {
                Mode.Stroke, Mode.Selection, Mode.Transform ->
                    session.pushInput(InputEvent.Up(activePointerId))
                Mode.Pick -> session.pushInput(InputEvent.PickEnd)
                Mode.Pending, Mode.None -> Unit
            }
        }
        mode = Mode.None
        activePointerId = -1
        maxFingers = 1
        stylusActive = false
        menuHoldFired = false
    }

    private fun cancelEverything() {
        session.predictedTip = null
        dropPending()
        cancelScheduledLongPress()
        cancelQuickShape()
        cancelMenuHold()
        menuHoldFired = false
        session.pushInput(InputEvent.CancelStroke)
        session.pushInput(InputEvent.PickEnd)
        mode = Mode.None
        twoFinger = false
        activePointerId = -1
        stylusActive = false
    }

    private fun downEvent(event: MotionEvent, index: Int, barrel: Boolean): InputEvent =
        InputEvent.Down(
            pointerId = activePointerId,
            x = event.getX(index),
            y = event.getY(index),
            pressure = pressureAt(event, index),
            tilt = tiltAt(event, index),
            stylus = isStylus(event),
            eraserButton = barrel
        )

    private fun moveEvent(event: MotionEvent, index: Int): InputEvent {
        val count = event.historySize + 1
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        val ps = FloatArray(count)
        for (h in 0 until event.historySize) {
            sample(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h))
            xs[h] = filtered[0]
            ys[h] = filtered[1]
            ps[h] = pressureAt(event, index)
        }
        sample(event.getX(index), event.getY(index), event.eventTime)
        xs[count - 1] = filtered[0]
        ys[count - 1] = filtered[1]
        ps[count - 1] = pressureAt(event, index)
        if (predictStroke && mode == Mode.Stroke) {
            val tip = FloatArray(2)
            session.predictedTip = if (predictor.predict(PREDICT_AHEAD_MS, PREDICT_MAX_DP * density, tip)) tip else null
        }
        return InputEvent.Move(activePointerId, xs, ys, ps, tiltAt(event, index), orientationAt(event, index))
    }

    /** One pen sample through the tremor filter (into [filtered]) and the predictor. */
    private fun sample(x: Float, y: Float, t: Long) {
        if (filterStroke) motionFilter.filter(x, y, t, filtered) else {
            filtered[0] = x
            filtered[1] = y
        }
        if (predictStroke) predictor.add(filtered[0], filtered[1], t)
    }

    /**
     * Three fingers dragged sideways step through history: every
     * [SCRUB_STEP_DP] to the left is one undo, back to the right redoes.
     * Returns true while the gesture is a scrub (no pan).
     */
    private fun handleScrub(event: MotionEvent): Boolean {
        val fx = focusX(event, -1)
        val fy = focusY(event, -1)
        val dx = fx - scrubStartX
        val dy = fy - scrubStartY
        if (!scrubDecided) {
            val slop = 28f * density
            if (kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) return true
            scrubDecided = true
            scrubbing = kotlin.math.abs(dx) > 1.8f * kotlin.math.abs(dy)
            if (!scrubbing) {
                // A pan or pinch with three fingers: continue as a gesture.
                rebaseGesture(event, -1)
                return false
            }
            scrubStartX = fx
        }
        if (!scrubbing) return false
        val target = (-(fx - scrubStartX) / (SCRUB_STEP_DP * density)).toInt()
        while (scrubSteps < target) {
            session.undo()
            scrubSteps++
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        while (scrubSteps > target) {
            session.redo()
            scrubSteps--
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        return true
    }

    private fun scheduleLongPress(delay: Long = com.yume.app.data.AppSettings.eyedropperHoldMs.toLong()) {
        val action = Runnable {
            if (mode == Mode.Stroke && moved < 9f * density) {
                session.pushInput(InputEvent.CancelStroke)
                session.pushInput(InputEvent.PickStart(lastX, lastY))
                mode = Mode.Pick
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
        longPressAction = action
        handler.postDelayed(action, delay)
    }

    private fun cancelScheduledLongPress() {
        longPressAction?.let { handler.removeCallbacks(it) }
        longPressAction = null
    }

    /**
     * Two fingers held still open the shortcut ring between them. It waits
     * longer than a two-finger tap (undo) and gives up as soon as the fingers
     * pan, pinch or rotate, or a third finger lands.
     */
    private fun holdDrifted(): Boolean {
        val slop = MENU_HOLD_SLOP_DP * density
        var turn = Math.toDegrees((gestureAngle - holdAngle).toDouble())
        if (turn > 180.0) turn -= 360.0
        if (turn < -180.0) turn += 360.0
        return hypot(gestureFocusX - holdFocusX, gestureFocusY - holdFocusY) > slop ||
            abs(gestureSpan - holdSpan) > slop ||
            abs(turn) > MENU_HOLD_TURN_DEG
    }

    private fun scheduleMenuHold() {
        cancelMenuHold()
        holdFocusX = gestureFocusX
        holdFocusY = gestureFocusY
        holdSpan = gestureSpan
        holdAngle = gestureAngle
        val action = Runnable {
            menuHoldAction = null
            if (twoFinger && maxFingers == 2 && !holdDrifted()) {
                menuHoldFired = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                com.yume.app.input.PenGestures.onMenuAt?.invoke(gestureFocusX, gestureFocusY)
            }
        }
        menuHoldAction = action
        handler.postDelayed(action, MENU_HOLD_MS)
    }

    private fun cancelMenuHold() {
        menuHoldAction?.let { handler.removeCallbacks(it) }
        menuHoldAction = null
    }

    private var holdAnchorX = 0f
    private var holdAnchorY = 0f

    /**
     * QuickShape fires when the pen rests. A stylus never stops reporting
     * moves (pressure and sub-pixel jitter), so the timer restarts only when
     * the pen leaves a small circle around where the rest began, not on every
     * move event; otherwise it would never fire with a pen.
     */
    private fun scheduleQuickShape(x: Float, y: Float) {
        // The wait restarts only when the pen leaves a small circle around
        // where it came to rest (pressure wobble and drift stay inside it).
        if (quickShapeAction != null && hypot(x - holdAnchorX, y - holdAnchorY) < QUICKSHAPE_SLOP_DP * density) return
        holdAnchorX = x
        holdAnchorY = y
        quickShapeAction?.let { handler.removeCallbacks(it) }
        val action = Runnable {
            if (mode == Mode.Stroke && !twoFinger) {
                session.pushInput(InputEvent.QuickShape)
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        quickShapeAction = action
        handler.postDelayed(action, com.yume.app.data.AppSettings.quickShapeHoldMs.toLong())
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
                panY = panY,
                first = gestureFresh
            )
        )
        gestureFresh = false

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
        val curve = com.yume.app.data.AppSettings.pressureCurve
        return if (isStylus(event)) {
            val p = raw.coerceIn(0.02f, 1f)
            // The user's pressure curve (Settings): p ^ curve.
            if (curve == 1f) p else Math.pow(p.toDouble(), curve.toDouble()).toFloat().coerceIn(0.02f, 1f)
        } else if (raw <= 0.01f) {
            1f
        } else {
            raw.coerceIn(0.05f, 1f)
        }
    }

    private fun orientationAt(event: MotionEvent, index: Int): Float =
        runCatching { event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index) }.getOrDefault(0f)

    private fun tiltAt(event: MotionEvent, index: Int): Float {
        return try {
            val radians = event.getAxisValue(MotionEvent.AXIS_TILT, index)
            (radians / (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
        } catch (t: Throwable) {
            0f
        }
    }
}

/** Two-finger hold before the shortcut ring opens, and how far fingers may drift. */
private const val MENU_HOLD_MS = 520L
private const val MENU_HOLD_SLOP_DP = 14f
private const val MENU_HOLD_TURN_DEG = 6.0

/** How long the pen rests before QuickShape snaps, and how still it must be. */
private const val QUICKSHAPE_SLOP_DP = 11f

/** Tip prediction: how far ahead, and never further than this from the pen. */
private const val PREDICT_AHEAD_MS = 22f
private const val PREDICT_MAX_DP = 40f

/** Three-finger scrub: sideways distance per history step. */
private const val SCRUB_STEP_DP = 36f
/**
 * How long a lone finger is held back before it reaches the tool: two-finger
 * pans land their fingers well within this, a finger stroke loses nothing
 * (its points are replayed).
 */
private const val FINGER_COMMIT_MS = 110L

/** Holding a stroke this still for this long picks a colour instead. */

/** Tools where a pen held still turns into the eyedropper. */
private val EYEDROPPER_TOOLS = setOf(Tool.Brush, Tool.Eraser, Tool.Smudge)
