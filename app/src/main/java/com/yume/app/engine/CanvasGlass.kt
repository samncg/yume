package com.yume.app.engine

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live, low-resolution copy of what the canvas surface shows, used as the
 * backdrop of the liquid-glass UI.
 *
 * The painting is drawn on a SurfaceView, a separate window layer that view
 * and Compose effects cannot sample. The render thread therefore draws the
 * visible composite (plus the dark surround) at 1/[SCALE] of the surface size
 * into one of three rotating bitmaps and publishes it here, at most every
 * [MIN_INTERVAL_MS]. The glass surfaces draw it shifted by their own window
 * position, so they refract the real artwork while the paint path is untouched.
 */
object CanvasGlass {
    const val SCALE = 4
    const val MIN_INTERVAL_MS = 80L

    /** The newest published frame (screen space, 1/[SCALE] resolution). */
    @Volatile var frame: Bitmap? = null
        private set

    private val _version = MutableStateFlow(0)
    /** Bumped on every publish; the UI redraws its glass when it changes. */
    val version: StateFlow<Int> = _version

    private val buffers = arrayOfNulls<Bitmap>(3)
    private var next = 0

    /** A buffer to draw the next frame into (render thread). */
    fun obtain(w: Int, h: Int): Bitmap {
        val b = buffers[next]
        if (b != null && !b.isRecycled && b.width == w && b.height == h) return b
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffers[next] = fresh
        return fresh
    }

    /** Publishes the buffer last returned by [obtain] (render thread). */
    fun publish(bmp: Bitmap) {
        frame = bmp
        next = (next + 1) % buffers.size
        _version.value = _version.value + 1
    }
}
