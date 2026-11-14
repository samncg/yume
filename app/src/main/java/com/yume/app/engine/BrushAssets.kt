package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Imported brush stamp shapes and grain textures, stored as normalised
 * coverage PNGs (white, with coverage in alpha) under the app's files
 * directory and referenced from presets by id.
 */
object BrushAssets {
    const val RES = 256

    @Volatile var root: File? = null
    private val cache = HashMap<String, Bitmap>()

    private fun dir(kind: String): File? = root?.let { File(it, "brush_$kind").apply { mkdirs() } }

    @Synchronized
    fun shape(id: String): Bitmap? = load("shapes", id)

    @Synchronized
    fun grain(id: String): Bitmap? = load("grains", id)

    private fun load(kind: String, id: String): Bitmap? {
        if (id.isEmpty()) return null
        val key = "$kind/$id"
        cache[key]?.let { if (!it.isRecycled) return it }
        val f = File(dir(kind) ?: return null, "$id.png")
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        cache[key] = b
        return b
    }

    /**
     * Turns any image into a coverage map. An image with transparency uses its
     * alpha; an opaque one uses darkness (dark paints, white is empty), which
     * is how most brush-tip images are drawn.
     */
    fun toCoverage(source: Bitmap, square: Boolean): Bitmap {
        val w: Int
        val h: Int
        if (square) {
            w = RES; h = RES
        } else {
            val s = RES.toFloat() / max(source.width, source.height)
            w = max(1, (source.width * s).toInt()); h = max(1, (source.height * s).toInt())
        }
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(scaled)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (square) {
            // Fit, centred, keeping the shape's proportions.
            val s = min(w.toFloat() / source.width, h.toFloat() / source.height)
            val dw = source.width * s
            val dh = source.height * s
            c.drawBitmap(source, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p)
        } else {
            c.drawBitmap(source, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
        }
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        // The source's own transparency decides the rule (the clear margins a
        // fit adds must not), so sample the original on a coarse grid.
        var transparent = false
        if (source.hasAlpha()) {
            val step = max(1, max(source.width, source.height) / 200)
            var y = 0
            while (y < source.height && !transparent) {
                var x = 0
                while (x < source.width) {
                    if ((source.getPixel(x, y) ushr 24) < 250) { transparent = true; break }
                    x += step
                }
                y += step
            }
        }
        for (i in px.indices) {
            val v = px[i]
            val a = v ushr 24
            val r = v shr 16 and 0xFF
            val g = v shr 8 and 0xFF
            val b = v and 0xFF
            val luma = (r * 218 + g * 732 + b * 74) shr 10
            val cov = if (transparent) a * (255 - luma / 2) / 255 else (255 - luma) * a / 255
            px[i] = (cov.coerceIn(0, 255) shl 24) or 0x00FFFFFF
        }
        scaled.setPixels(px, 0, w, 0, 0, w, h)
        return scaled
    }

    /**
     * Stores a ready coverage map (white, coverage in alpha) as a shape
     * ([square], fitted and centred) or a grain (aspect kept); returns its id.
     */
    fun storeCoverage(kind: String, coverage: Bitmap, square: Boolean): String? {
        val w: Int
        val h: Int
        if (square) {
            w = RES; h = RES
        } else {
            val s = RES.toFloat() / max(coverage.width, coverage.height)
            w = max(1, (coverage.width * s).toInt()); h = max(1, (coverage.height * s).toInt())
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val sc = if (square) min(w.toFloat() / coverage.width, h.toFloat() / coverage.height) else w.toFloat() / coverage.width
        val dw = coverage.width * sc
        val dh = coverage.height * sc
        Canvas(out).drawBitmap(coverage, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p)
        return store(kind, out)
    }

    /** Stores an imported image as a stamp shape; returns its id. */
    fun importShape(source: Bitmap): String? = store("shapes", toCoverage(source, square = true))

    /** Stores an imported image as a grain texture (tiled); returns its id. */
    fun importGrain(source: Bitmap): String? = store("grains", toCoverage(source, square = false))

    @Synchronized
    private fun store(kind: String, coverage: Bitmap): String? {
        val d = dir(kind) ?: return null
        val id = UUID.randomUUID().toString().substring(0, 12)
        File(d, "$id.png").outputStream().use { coverage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache["$kind/$id"] = coverage
        return id
    }

    /** PNG bytes of a stored asset, for exporting presets. */
    fun bytes(kind: String, id: String): ByteArray? {
        if (id.isEmpty()) return null
        val f = File(dir(kind) ?: return null, "$id.png")
        return if (f.exists()) f.readBytes() else null
    }

    /** Writes asset bytes from an imported preset file under [id]. */
    @Synchronized
    fun put(kind: String, id: String, bytes: ByteArray): Boolean {
        val d = dir(kind) ?: return false
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val safe = id.filter { it.isLetterOrDigit() || it == '-' }.take(40).ifEmpty { return false }
        File(d, "$safe.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache["$kind/$safe"] = bmp
        return true
    }

    fun pngBytes(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
