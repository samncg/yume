package com.yume.app.engine

/**
 * Raw input from the canvas view. Coordinates are in screen space; the render
 * thread maps them into document space using the live view transform, which is
 * both correct for queued events and keeps gesture maths off the UI thread.
 */
sealed interface InputEvent {

    data class Down(
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val pressure: Float,
        val tilt: Float,
        val stylus: Boolean,
        val eraserButton: Boolean,
        /** Pen azimuth in radians (AXIS_ORIENTATION), for tilt-following stamps. */
        val orientation: Float = 0f
    ) : InputEvent

    data class Move(
        val pointerId: Int,
        val xs: FloatArray,
        val ys: FloatArray,
        val pressures: FloatArray,
        val tilt: Float,
        val orientation: Float = 0f
    ) : InputEvent

    data class Up(val pointerId: Int) : InputEvent

    /** Two-finger pan/zoom/rotate. */
    data class Gesture(
        val focusX: Float,
        val focusY: Float,
        val scaleFactor: Float,
        val angleDelta: Float,
        val panX: Float,
        val panY: Float,
        /** First event of a new multi-finger gesture. */
        val first: Boolean = false
    ) : InputEvent

    /** Multi-finger tap: 2 = undo, 3 = redo, 4 = toggle interface. */
    data class Tap(val fingers: Int) : InputEvent

    data class Hover(
        val x: Float, val y: Float, val visible: Boolean,
        val tilt: Float = 0f, val orientation: Float = 0f
    ) : InputEvent

    data class PickStart(val x: Float, val y: Float) : InputEvent
    data class PickMove(val x: Float, val y: Float) : InputEvent
    data object PickEnd : InputEvent

    /**
     * The stylus/finger has been held still at the end of a stroke: snap the
     * stroke to a straight line (Procreate's QuickShape).
     */
    data object QuickShape : InputEvent

    /** Drops the in-progress stroke (long press eyedropper, cancel). */
    data object CancelStroke : InputEvent
}
