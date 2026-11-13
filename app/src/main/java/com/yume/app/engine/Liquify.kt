package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

enum class LiquifyMode(val label: String) {
    Push("Push"), TwirlRight("Twirl right"), TwirlLeft("Twirl left"), Pinch("Pinch"), Expand("Expand"), Restore("Restore")
}

/**
 * Liquify: each dab warps a circular region of the active layer by backward
 * mapping (every output pixel samples the source at a displaced position,
 * bilinearly, in premultiplied colour), with a smooth falloff to the rim.
 *
 * One pen stroke is one undo step (the same tile capture / stroke base the
 * brush uses). Restore pulls pixels back toward how the layer looked when
 * liquify started on it, kept as a lazy per-tile capture (sparse) or one
 * copy (dense, which the memory guard limits to small documents).
 */
class Liquify(private val engine: DrawingEngine) {
    private var layer: Layer? = null
    private var strokeCapture: TileCapture? = null
    private var baseCapture: TileCapture? = null
    private var baseLayerId = -1L
    private var denseBase: Bitmap? = null
    val touched = Rect()

    val active: Boolean get() = layer != null

    fun begin(target: Layer) {
        if (baseLayerId != target.id) releaseBase()
        layer = target
        touched.setEmpty()
        val store = target.paintStore
        if (store.isSparse) {
            strokeCapture = TileCapture(store)
            if (baseCapture == null) baseCapture = TileCapture(store)
        } else {
            engine.captureStrokeBase(target)
            if (denseBase == null) denseBase = store.readRect(Rect(0, 0, engine.width, engine.height))
        }
        baseLayerId = target.id
    }

    /**
     * One dab at ([cx], [cy]) with radius [r]. [dx], [dy] is the pen's
     * movement since the last dab (push), [strength] 0..1.
     */
    fun dab(mode: LiquifyMode, cx: Float, cy: Float, r: Float, dx: Float, dy: Float, strength: Float) {
        val target = layer ?: return
        val store = target.paintStore
        val rect = Rect(floor(cx - r).toInt(), floor(cy - r).toInt(), (cx + r).toInt() + 2, (cy + r).toInt() + 2)
        if (!rect.intersect(0, 0, engine.width, engine.height)) return
        // Samples can land up to one radius away (push); read that much more.
        val src = Rect(rect).apply {
            inset(-r.toInt() - 2, -r.toInt() - 2)
            intersect(0, 0, engine.width, engine.height)
        }
        val sw = src.width()
        val sh = src.height()
        val srcBmp = store.readRect(src)
        val px = IntArray(sw * sh)
        srcBmp.getPixels(px, 0, sw, 0, 0, sw, sh)
        srcBmp.recycle()
        premultiply(px)

        val base: IntArray? = if (mode == LiquifyMode.Restore) baseRegion(store, rect) else null

        val w = rect.width()
        val h = rect.height()
        val out = IntArray(w * h)
        val r2 = r * r
        val s = strength.coerceIn(0f, 1f)
        for (y in 0 until h) {
            val py = rect.top + y + 0.5f
            for (x in 0 until w) {
                val pxX = rect.left + x + 0.5f
                val ox = pxX - cx
                val oy = py - cy
                val d2 = ox * ox + oy * oy
                val own = px[(rect.top + y - src.top) * sw + (rect.left + x - src.left)]
                if (d2 >= r2) { out[y * w + x] = own; continue }
                val f = 1f - d2 / r2
                val fall = f * f
                if (mode == LiquifyMode.Restore) {
                    val k = (fall * s * 0.6f)
                    out[y * w + x] = lerpPremul(own, base!![y * w + x], k)
                    continue
                }
                var sx: Float
                var sy: Float
                when (mode) {
                    LiquifyMode.Push -> { sx = pxX - dx * fall * s * 1.2f; sy = py - dy * fall * s * 1.2f }
                    LiquifyMode.TwirlRight, LiquifyMode.TwirlLeft -> {
                        val a = (if (mode == LiquifyMode.TwirlRight) -1f else 1f) * fall * s * 0.35f
                        val c = cos(a); val sn = sin(a)
                        sx = cx + ox * c - oy * sn; sy = cy + ox * sn + oy * c
                    }
                    LiquifyMode.Pinch -> { val k = 1f + fall * s * 0.18f; sx = cx + ox * k; sy = cy + oy * k }
                    LiquifyMode.Expand -> { val k = 1f - fall * s * 0.18f; sx = cx + ox * k; sy = cy + oy * k }
                    LiquifyMode.Restore -> { sx = pxX; sy = py }
                }
                out[y * w + x] = sample(px, sw, sh, sx - src.left - 0.5f, sy - src.top - 0.5f)
            }
        }
        unpremultiply(out)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)

        // Capture the tiles before writing, for undo (and for Restore).
        strokeCapture?.let { cap -> forTiles(store, rect) { tx, ty -> cap.capture(tx, ty) } }
        baseCapture?.let { cap -> forTiles(store, rect) { tx, ty -> cap.capture(tx, ty) } }
        store.writeRect(rect, result, Rect(0, 0, w, h))
        result.recycle()
        target.touch()
        engine.markDirty(rect)
        if (touched.isEmpty) touched.set(rect) else touched.union(rect)
    }

    /** The undo entry for the stroke that just ended (null when nothing changed). */
    fun end(): HistoryAction? {
        val target = layer ?: return null
        layer = null
        if (touched.isEmpty) {
            strokeCapture?.release()
            strokeCapture = null
            return null
        }
        val cap = strokeCapture
        strokeCapture = null
        return if (cap != null) {
            engine.makeTilePatch(target, cap, touched, "Liquify", EditKind.Stroke)
        } else {
            engine.makePatch(target, touched, "Liquify", EditKind.Stroke)
        }
    }

    /** Forgets the Restore base (leaving the tool, or another layer). */
    fun releaseBase() {
        baseCapture?.release()
        baseCapture = null
        denseBase?.recycle()
        denseBase = null
        baseLayerId = -1L
    }

    private fun baseRegion(store: TileStore, rect: Rect): IntArray {
        val w = rect.width()
        val h = rect.height()
        val bmp: Bitmap
        val dense = denseBase
        if (dense != null) {
            bmp = Bitmap.createBitmap(dense, rect.left, rect.top, w, h)
        } else {
            // Current pixels, with the tiles changed since liquify began
            // replaced by their captured originals.
            bmp = store.readRect(rect)
            val cap = baseCapture
            if (cap != null) {
                val c = Canvas(bmp)
                val p = Paint().apply { blendMode = android.graphics.BlendMode.SRC }
                forTiles(store, rect) { tx, ty ->
                    if (cap.has(tx, ty)) {
                        val ts = store.tileSize
                        val tile = cap.beforeAt(tx, ty)
                        val x0 = tx * ts - rect.left
                        val y0 = ty * ts - rect.top
                        if (tile != null) {
                            c.drawBitmap(tile, x0.toFloat(), y0.toFloat(), p)
                        } else {
                            // Captured as empty: the original was the default.
                            val save = c.save()
                            c.clipRect(x0, y0, x0 + ts, y0 + ts)
                            c.drawColor(store.defaultPixel, android.graphics.BlendMode.SRC)
                            c.restoreToCount(save)
                        }
                    }
                }
            }
        }
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        bmp.recycle()
        premultiply(out)
        return out
    }

    private inline fun forTiles(store: TileStore, rect: Rect, action: (Int, Int) -> Unit) {
        val ts = store.tileSize
        for (ty in rect.top / ts..(rect.bottom - 1) / ts) {
            for (tx in rect.left / ts..(rect.right - 1) / ts) action(tx, ty)
        }
    }

    private fun sample(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val ax = fx - x0
        val ay = fy - y0
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]
        val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        var res = 0
        for (sh in intArrayOf(24, 16, 8, 0)) {
            val top = ((c00 shr sh) and 0xFF) * (1 - ax) + ((c10 shr sh) and 0xFF) * ax
            val bot = ((c01 shr sh) and 0xFF) * (1 - ax) + ((c11 shr sh) and 0xFF) * ax
            val v = (top * (1 - ay) + bot * ay + 0.5f).toInt().coerceIn(0, 255)
            res = res or (v shl sh)
        }
        return res
    }

    private fun lerpPremul(a: Int, b: Int, t: Float): Int {
        var res = 0
        for (sh in intArrayOf(24, 16, 8, 0)) {
            val va = (a shr sh) and 0xFF
            val vb = (b shr sh) and 0xFF
            res = res or (((va + (vb - va) * t) + 0.5f).toInt().coerceIn(0, 255) shl sh)
        }
        return res
    }

    private fun premultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 255) continue
            if (a == 0) { px[i] = 0; continue }
            val r = ((c shr 16) and 0xFF) * a / 255
            val g = ((c shr 8) and 0xFF) * a / 255
            val b = (c and 0xFF) * a / 255
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun unpremultiply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 255 || a == 0) { if (a == 0) px[i] = 0; continue }
            val r = min(255, ((c shr 16) and 0xFF) * 255 / a)
            val g = min(255, ((c shr 8) and 0xFF) * 255 / a)
            val b = min(255, (c and 0xFF) * 255 / a)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
