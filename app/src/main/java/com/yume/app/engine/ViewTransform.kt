package com.yume.app.engine

import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.min

/**
 * Maps document space to screen space: the document centre is placed at
 * ([tx], [ty]), then scaled and rotated around it.
 */
class ViewTransform {

    var docWidth = 0f
        private set
    var docHeight = 0f
        private set

    var viewWidth = 0
        private set
    var viewHeight = 0
        private set

    var scale = 1f
        private set
    var rotationDeg = 0f
        private set
    var tx = 0f
        private set
    var ty = 0f
        private set

    /** Fit scale, used to clamp zoom limits. */
    private var fitScale = 1f

    /** Screen pixels reserved by the floating interface at the top. */
    var topInset = 0f

    val matrix = Matrix()
    private val inverse = Matrix()

    fun setDocument(w: Int, h: Int) {
        docWidth = w.toFloat()
        docHeight = h.toFloat()
        update()
    }

    fun setViewport(w: Int, h: Int) {
        viewWidth = w
        viewHeight = h
        update()
    }

    private fun computeFit(): Float {
        if (docWidth <= 0f || viewWidth <= 0 || viewHeight <= 0) return 1f
        val availableHeight = (viewHeight - topInset).coerceAtLeast(1f)
        return min(viewWidth / docWidth, availableHeight / docHeight) * 0.92f
    }

    fun fit() {
        fitScale = computeFit()
        scale = fitScale
        rotationDeg = 0f
        tx = viewWidth / 2f
        ty = topInset + (viewHeight - topInset) / 2f
        update()
    }

    /** Zoom so the whole document is visible, keeping the current rotation. */
    fun fitKeepRotation() {
        fitScale = computeFit()
        scale = fitScale
        tx = viewWidth / 2f
        ty = topInset + (viewHeight - topInset) / 2f
        update()
    }

    fun update() {
        matrix.reset()
        matrix.postTranslate(-docWidth / 2f, -docHeight / 2f)
        matrix.postScale(scale, scale)
        matrix.postRotate(rotationDeg)
        matrix.postTranslate(tx, ty)
        matrix.invert(inverse)
    }

    fun minScale(): Float = max(fitScale * 0.35f, 0.02f)

    fun maxScale(): Float = max(fitScale * 32f, 8f)

    fun isFitted(): Boolean = fitScale > 0f && kotlin.math.abs(scale - fitScale) < 0.0001f

    /** Screen -> document. */
    fun toDocument(sx: Float, sy: Float, out: FloatArray) {
        out[0] = sx
        out[1] = sy
        inverse.mapPoints(out)
    }

    /** Document -> screen. */
    fun toScreen(dx: Float, dy: Float, out: FloatArray) {
        out[0] = dx
        out[1] = dy
        matrix.mapPoints(out)
    }

    /**
     * Applies a pinch/rotate around [focusX],[focusY] (screen space) so the
     * document point under the fingers stays put.
     */
    fun applyGesture(
        focusX: Float,
        focusY: Float,
        scaleFactor: Float,
        angleDelta: Float,
        panX: Float,
        panY: Float
    ) {
        if (docWidth <= 0f) return
        val pivot = FloatArray(2)
        toDocument(focusX, focusY, pivot)

        scale = (scale * scaleFactor).coerceIn(minScale(), maxScale())
        rotationDeg = (rotationDeg + angleDelta) % 360f
        if (rotationDeg < 0f) rotationDeg += 360f

        update()
        val mapped = FloatArray(2)
        matrix.mapPoints(mapped, pivot)
        tx += focusX - mapped[0] + panX
        ty += focusY - mapped[1] + panY
        update()
    }

    fun pan(dx: Float, dy: Float) {
        tx += dx
        ty += dy
        update()
    }

    fun setRotation(deg: Float) {
        rotationDeg = ((deg % 360f) + 360f) % 360f
        update()
    }

    fun setScale(newScale: Float) {
        scale = newScale.coerceIn(minScale(), maxScale())
        update()
    }

    fun zoomFactor(): Float = if (fitScale > 0f) scale / fitScale else 1f

    /** Snapshot for the render thread and UI. */
    fun snapshot(): ViewSnapshot = ViewSnapshot(scale, rotationDeg, tx, ty, fitScale)
}

data class ViewSnapshot(
    val scale: Float,
    val rotationDeg: Float,
    val tx: Float,
    val ty: Float,
    val fitScale: Float
)
