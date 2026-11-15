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

    /**
     * Multi-finger taps: where each finger landed, and whether any of them
     * has moved further than a tap allows. Tracked per finger, so a finger
     * lifting early (or one landing late) never reads as movement.
     */
    private val fingerDown = HashMap<Int, FloatArray>()
    private var tapSlopExceeded = false
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
                session.pushInput(
                    InputEvent.Hover(
                        event.x, event.y, true,
                        if (stylus) tiltAt(event, 0) else 0f,
                        if (stylus) orientationAt(event, 0) else 0f
                    )
                )
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
        fingerDown.clear()
        fingerDown[event.getPointerId(0)] = floatArrayOf(event.x, event.y)
        tapSlopExceeded = false
        twoFinger = false
        lastX = event.x
        lastY = event.y
        activePointerId = event.getPointerId(0)

        val barrelDown = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        // The pen's side button does what Settings says: erase while held (the
        // default), pick a colour, or run an action instead of a stroke.
        val custom = com.yume.app.data.Customization
        val penAction = if (barrelDown && stylus) custom.actionFor(com.yume.app.data.Customization.Gesture.PenButton)
        else com.yume.app.data.Customization.Action.None
        val barrel = barrelDown && penAction == com.yume.app.data.Customization.Action.Eraser
        if (penAction == com.yume.app.data.Customization.Action.Eyedropper) {
            session.pushInput(InputEvent.PickStart(event.x, event.y))
            mode = Mode.Pick
            return
        }
        if (penAction != com.yume.app.data.Customization.Action.None &&
            penAction != com.yume.app.data.Customization.Action.Eraser
        ) {
            custom.dispatch(penAction)
            mode = Mode.None
            twoFinger = false
            return
        }

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
        val ai = event.actionIndex
        fingerDown[event.getPointerId(ai)] = floatArrayOf(event.getX(ai), event.getY(ai))
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
        if (event.pointerCount == 2 && maxFingers == 2) scheduleMenuHold() else cancelMenuHold()
    }

    private fun onMove(event: MotionEvent) {
        accumulateMovement(event)
        if (!tapSlopExceeded && fingerDown.isNotEmpty()) {
            val slop = TAP_SLOP_DP * density
            for (i in 0 until event.pointerCount) {
                val start = fingerDown[event.getPointerId(i)] ?: continue
                if (hypot(event.getX(i) - start[0], event.getY(i) - start[1]) > slop) {
                    tapSlopExceeded = true
                    break
                }
            }
        }

        if (twoFinger) {
            if (menuHoldFired) {
                if (ringFingers) aimRing(event)
                return
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
        releaseRing()
        cancelMenuHold()
        // Rebase the pinch maths on the fingers that are still down.
        if (twoFinger) {
            rebaseGesture(event, event.actionIndex)
        }
    }

    private fun onUp(event: MotionEvent) {
        releaseRing()
        // A quick single tap (fill, text, auto-select) lifts inside the hold.
        if (mode == Mode.Pending) commitPending()
        cancelScheduledLongPress()
        cancelQuickShape()
        cancelMenuHold()

        session.predictedTip = null
        if (twoFinger) {
            val duration = event.eventTime - downTime
            // More fingers land and lift less together: allow them more time.
            val limit = if (maxFingers >= 3) MULTI_TAP_MS else TWO_TAP_MS
            if (!menuHoldFired && !tapSlopExceeded && duration < limit) {
                val c = com.yume.app.data.Customization
                val gesture = when (maxFingers) {
                    2 -> com.yume.app.data.Customization.Gesture.TwoFingerTap
                    3 -> com.yume.app.data.Customization.Gesture.ThreeFingerTap
                    4 -> com.yume.app.data.Customization.Gesture.FourFingerTap
                    else -> null
                }
                if (gesture != null) {
                    val action = c.actionFor(gesture)
                    // Without an editor listening (harness), the classic taps.
                    if (c.dispatcher == null) session.pushInput(InputEvent.Tap(maxFingers))
                    else c.dispatch(action)
                }
            }
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
        ringFingers = false
        com.yume.app.input.PenGestures.ringFingers = emptyList()
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
            val holdAction = com.yume.app.data.Customization.actionFor(
                com.yume.app.data.Customization.Gesture.TwoFingerHold
            )
            if (twoFinger && maxFingers == 2 && !holdDrifted() &&
                holdAction != com.yume.app.data.Customization.Action.None
            ) {
                menuHoldFired = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                if (holdAction == com.yume.app.data.Customization.Action.ShortcutMenu) {
                    ringFingers = true
                    ringStart.clear()
                    com.yume.app.input.PenGestures.ringFingers = emptyList()
                    com.yume.app.input.PenGestures.onMenuAt?.invoke(gestureFocusX, gestureFocusY)
                } else {
                    com.yume.app.data.Customization.dispatch(holdAction)
                }
            }
        }
        menuHoldAction = action
        handler.postDelayed(action, MENU_HOLD_MS)
    }

    private fun cancelMenuHold() {
        menuHoldAction?.let { handler.removeCallbacks(it) }
        menuHoldAction = null
    }

    /** The ring opened under these two fingers; orbiting one picks an item. */
    private var ringFingers = false
    private val ringStart = HashMap<Int, FloatArray>()

    /**
     * The fingers that opened the ring, wherever they have moved to (canvas
     * px): sliding one onto an item and lifting it chooses the item. Fingers
     * that have barely moved (still resting where the ring opened) are left
     * out.
     */
    private fun aimRing(event: MotionEvent) {
        val slop = RING_AIM_DP * density
        val out = ArrayList<androidx.compose.ui.geometry.Offset>(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            val s = ringStart.getOrPut(event.getPointerId(i)) { floatArrayOf(event.getX(i), event.getY(i)) }
            if (hypot(event.getX(i) - s[0], event.getY(i) - s[1]) >= slop) {
                out.add(androidx.compose.ui.geometry.Offset(event.getX(i), event.getY(i)))
            }
        }
        com.yume.app.input.PenGestures.ringFingers = out
    }

    /** A finger lifted: the ring runs the item under a finger, if any (once). */
    private fun releaseRing() {
        if (!ringFingers) return
        ringFingers = false
        if (com.yume.app.input.PenGestures.ringFingers.isNotEmpty()) {
            com.yume.app.input.PenGestures.onRingRelease?.invoke()
        }
        com.yume.app.input.PenGestures.ringFingers = emptyList()
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
        val hold = com.yume.app.data.AppSettings.quickShapeHoldMs.toLong()
        lateinit var action: Runnable
        action = Runnable {
            if (mode != Mode.Stroke || twoFinger) return@Runnable
            // Only once the stroke is fully drawn: while painting still trails
            // the pen, wait, and give the full hold after it catches up.
            val now = android.os.SystemClock.uptimeMillis()
            val since = now - session.backlogAt
            // (backlogAt moves on for as long as painting trails the pen.)
            if (since < hold) {
                handler.postDelayed(action, (hold - since).coerceAtLeast(16L))
                return@Runnable
            }
            session.pushInput(InputEvent.QuickShape)
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        quickShapeAction = action
        handler.postDelayed(action, hold)
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
        // The tracked pointer lifted: another finger's position is not movement.
        if (index < 0) return
        val x = event.getX(index)
        val y = event.getY(index)
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
        // The response curve is the brush's own (Brush Studio), applied in
        // the stroke painter.
        return if (isStylus(event)) {
            raw.coerceIn(0.02f, 1f)
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
/** How far a finger moves from where the ring opened before it can pick an item. */
private const val RING_AIM_DP = 28f

/** How long the pen rests before QuickShape snaps, and how still it must be. */
private const val QUICKSHAPE_SLOP_DP = 11f

/** Tip prediction: how far ahead, and never further than this from the pen. */
private const val PREDICT_AHEAD_MS = 22f
private const val PREDICT_MAX_DP = 40f

/** Multi-finger taps: how far a finger may drift, and how long the tap may take. */
private const val TAP_SLOP_DP = 30f
private const val TWO_TAP_MS = 450L
private const val MULTI_TAP_MS = 700L
/**
 * How long a lone finger is held back before it reaches the tool: two-finger
 * pans land their fingers well within this, a finger stroke loses nothing
 * (its points are replayed).
 */
private const val FINGER_COMMIT_MS = 110L

/** Holding a stroke this still for this long picks a colour instead. */

/** Tools where a pen held still turns into the eyedropper. */
private val EYEDROPPER_TOOLS = setOf(Tool.Brush, Tool.Eraser, Tool.Smudge)
