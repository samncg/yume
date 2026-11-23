package com.yume.app.engine

import android.graphics.Bitmap

/**
 * Yume's own clipboard: the pixels last copied or cut, and where they sat in
 * their document. It lives as long as the app, so a copy can be pasted into
 * another artwork.
 */
object PixelClipboard {

    /** The copied pixels, cropped to what was copied. */
    var bitmap: Bitmap? = null
        private set

    /** Where the pixels' top-left sat in the document they came from. */
    var left = 0
        private set
    var top = 0
        private set

    val isEmpty: Boolean get() = bitmap == null

    fun set(pixels: Bitmap, left: Int, top: Int) {
        val old = bitmap
        bitmap = pixels
        this.left = left
        this.top = top
        if (old != null && old !== pixels) old.recycle()
    }

    fun clear() {
        bitmap?.recycle()
        bitmap = null
    }
}
