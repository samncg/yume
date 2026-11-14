package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Brushes from other apps, turned into Yume presets:
 *
 * - Procreate `.brush` / `.brushset`: the shape and grain images, plus the
 *   name, spacing, size, opacity, jitter and pressure response read from the
 *   brush's settings archive.
 * - Photoshop `.abr` (versions 1, 2, 6 to 10): every sampled tip; spacing
 *   where the file says it.
 * - Clip Studio Paint `.sut` (best effort): brush names and any embedded tip
 *   images; a brush whose tip is stored in Clip Studio's own image format
 *   comes in with a round tip.
 * - GIMP `.gbr`, and plain images (PNG, JPEG, WebP) as a tip shape.
 *
 * Tip images are greyscale masks; whichever way round a file stores them
 * (white paint on black, or black on white) is detected from its border.
 */
object ForeignBrushes {
    class Result(val presets: List<BrushPreset>, val note: String)

    fun import(bytes: ByteArray, fileName: String, scratch: File): Result {
        val lower = fileName.lowercase()
        return runCatching {
            when {
                isZip(bytes) -> procreate(bytes, baseName(fileName))
                isSqlite(bytes) -> clipStudio(bytes, baseName(fileName), scratch)
                lower.endsWith(".gbr") || isGbr(bytes) -> gimp(bytes, baseName(fileName))
                lower.endsWith(".abr") || isAbr(bytes) -> photoshop(bytes, baseName(fileName))
                else -> image(bytes, baseName(fileName))
            }
        }.getOrElse { Result(emptyList(), "That brush file could not be read (${it.message ?: it.javaClass.simpleName}).") }
    }

    // ------------------------------------------------------------- helpers

    private fun baseName(name: String) = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Imported" }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    private fun isSqlite(b: ByteArray) = b.size > 16 && String(b, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
    private fun isGbr(b: ByteArray) = b.size > 28 && String(b, 20, 4, Charsets.US_ASCII) == "GIMP"
    private fun isAbr(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val v = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        return v in 1..2 || v in 6..10
    }

    private fun newPreset(name: String, shapeId: String = "", grainId: String = "", spacing: Float = 0.12f) =
        BrushLibrary.defaults.first().copy(
            id = "imported-" + UUID.randomUUID().toString().substring(0, 8),
            name = name.take(40),
            category = BrushCategory.Brush,
            builtIn = false,
            group = "Imported",
            size = 40f,
            hardness = 0.9f,
            flow = 1f,
            opacity = 1f,
            spacing = spacing.coerceIn(0.02f, 2f),
            smoothing = 0.3f,
            grain = if (grainId.isNotEmpty()) 0.6f else 0f,
            shapeId = shapeId,
            grainId = grainId,
            pressureSize = 0.7f,
            pressureOpacity = 0.3f,
            wetMix = 0f,
            glow = false,
            jitter = 0f,
            rotationMode = 0,
            rotation = 0f
        )

    /**
     * A greyscale mask ([w] x [h], one value per pixel) as a coverage bitmap.
     * The side that fills the border is the empty one: a mask framed by high
     * values is inverted.
     */
    private fun coverageOf(values: IntArray, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0 || values.size < w * h) return null
        var sum = 0L
        var n = 0
        for (x in 0 until w) { sum += values[x] + values[(h - 1) * w + x]; n += 2 }
        for (y in 0 until h) { sum += values[y * w] + values[y * w + w - 1]; n += 2 }
        val invert = n > 0 && sum / n > 127
        val px = IntArray(w * h)
        var any = false
        for (i in 0 until w * h) {
            val v = values[i].coerceIn(0, 255).let { if (invert) 255 - it else it }
            if (v > 8) any = true
            px[i] = (v shl 24) or 0x00FFFFFF
        }
        if (!any) return null
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Greyscale (or alpha, when the image has transparency) values of an image. */
    private fun grey(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var transparent = false
        for (i in px.indices step max(1, px.size / 4000)) if ((px[i] ushr 24) < 250) { transparent = true; break }
        return IntArray(w * h) { i ->
            val c = px[i]
            // On transparency the alpha is the mask; an opaque image is a
            // greyscale mask (either way round: see [coverageOf]).
            if (transparent) c ushr 24
            else ((c shr 16 and 0xFF) * 218 + (c shr 8 and 0xFF) * 732 + (c and 0xFF) * 74) shr 10
        }
    }

    private fun storeShape(bmp: Bitmap?): String = bmp?.let { BrushAssets.storeCoverage("shapes", it, square = true) }.orEmpty()
    private fun storeGrain(bmp: Bitmap?): String = bmp?.let { BrushAssets.storeCoverage("grains", it, square = false) }.orEmpty()

    // ------------------------------------------------------------- images

    private fun image(bytes: ByteArray, name: String): Result {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return Result(emptyList(), "That file is not a brush Yume can read.")
        val cov = coverageOf(grey(bmp), bmp.width, bmp.height)
        val id = storeShape(cov)
        if (id.isEmpty()) return Result(emptyList(), "That image has no brush shape in it.")
        return Result(listOf(newPreset(name, shapeId = id, spacing = 0.15f).copy(rotationMode = 0)), "Imported “$name” as a stamp brush.")
    }

    // ------------------------------------------------------------- GIMP

    private fun gimp(bytes: ByteArray, name: String): Result {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val header = b.int
        val version = b.int
        val w = b.int
        val h = b.int
        val depth = b.int
        b.int // magic
        val spacing = if (version >= 2) b.int else 25
        val title = String(bytes, 28, max(0, header - 29).coerceAtMost(bytes.size - 28), Charsets.UTF_8).trim('\u0000', ' ').ifBlank { name }
        b.position(header)
        val values = IntArray(w * h) {
            if (depth == 4) {
                val r = b.get().toInt() and 0xFF
                b.get(); b.get()
                val a = b.get().toInt() and 0xFF
                (255 - r) * a / 255
            } else 255 - (b.get().toInt() and 0xFF)
        }
        // GIMP masks: 0 is paint once inverted above; let the border decide.
        val id = storeShape(coverageOf(values, w, h))
        if (id.isEmpty()) return Result(emptyList(), "That GIMP brush has no shape in it.")
        return Result(listOf(newPreset(title, shapeId = id, spacing = spacing / 100f)), "Imported “$title”.")
    }

    // ------------------------------------------------------------- Photoshop

    private class Reader(val b: ByteArray) {
        var p = 0
        fun u8(): Int = b[p++].toInt() and 0xFF
        fun s16(): Int = (u8() shl 8) or u8()
        fun s32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun skip(n: Int) { p += n }
        val left: Int get() = b.size - p
    }

    private fun photoshop(bytes: ByteArray, name: String): Result {
        val r = Reader(bytes)
        val version = r.s16()
        val out = ArrayList<BrushPreset>()
        if (version == 1 || version == 2) {
            val count = r.s16()
            for (i in 0 until count) {
                if (r.left < 6) break
                val type = r.s16()
                val size = r.s32()
                val next = r.p + size
                if (type == 2) {
                    r.s32() // misc
                    val spacing = r.s16()
                    var title = "$name ${i + 1}"
                    if (version == 2) {
                        val len = r.s32()
                        val sb = StringBuilder()
                        repeat(len) { val c = r.s16(); if (c != 0) sb.append(c.toChar()) }
                        if (sb.isNotBlank()) title = sb.toString()
                    }
                    r.u8() // antialias
                    r.skip(8) // short bounds
                    val top = r.s32(); val left = r.s32(); val bottom = r.s32(); val right = r.s32()
                    val depth = r.s16()
                    val compressed = r.u8()
                    val mask = sampled(r, right - left, bottom - top, depth, compressed)
                    val id = storeShape(mask?.let { coverageOf(it, right - left, bottom - top) })
                    if (id.isNotEmpty()) out.add(newPreset(title, shapeId = id, spacing = max(1, spacing) / 100f))
                }
                r.p = next
            }
        } else {
            val sub = r.s16()
            while (r.left > 12) {
                val sig = String(bytes, r.p, 4, Charsets.US_ASCII)
                if (sig != "8BIM") break
                r.skip(4)
                val key = String(bytes, r.p, 4, Charsets.US_ASCII)
                r.skip(4)
                val len = r.s32()
                val end = r.p + len
                if (key == "samp") {
                    var n = 0
                    while (r.p < end - 4) {
                        val size = r.s32()
                        var padded = size
                        while (padded % 4 != 0) padded++
                        val next = r.p + padded
                        r.skip(if (sub == 1) 47 else 301)
                        val top = r.s32(); val left = r.s32(); val bottom = r.s32(); val right = r.s32()
                        val depth = r.s16()
                        val compressed = r.u8()
                        val w = right - left
                        val h = bottom - top
                        n++
                        if (w in 1..5000 && h in 1..5000) {
                            val mask = runCatching { sampled(r, w, h, depth, compressed) }.getOrNull()
                            val id = storeShape(mask?.let { coverageOf(it, w, h) })
                            if (id.isNotEmpty()) out.add(newPreset("$name $n", shapeId = id, spacing = 0.25f))
                        }
                        r.p = next
                    }
                }
                r.p = end + (len and 1)
            }
        }
        return if (out.isEmpty()) Result(emptyList(), "No sampled brush tips found in that .abr file.")
        else Result(out, "Imported ${out.size} Photoshop brush${if (out.size == 1) "" else "es"}.")
    }

    /** One sampled tip: raw or PackBits rows, 8 or 16 bit; returns 8-bit values. */
    private fun sampled(r: Reader, w: Int, h: Int, depth: Int, compressed: Int): IntArray? {
        if (w <= 0 || h <= 0) return null
        val bpp = max(1, depth / 8)
        val rowBytes = w * bpp
        val raw = ByteArray(rowBytes * h)
        if (compressed == 0) {
            System.arraycopy(r.b, r.p, raw, 0, min(raw.size, r.left))
            r.skip(raw.size)
        } else {
            val counts = IntArray(h) { r.s16() }
            for (y in 0 until h) {
                val end = r.p + counts[y]
                var o = y * rowBytes
                val rowEnd = o + rowBytes
                while (r.p < end && o < rowEnd) {
                    val n = r.b[r.p++].toInt()
                    if (n >= 0) {
                        val k = min(n + 1, rowEnd - o)
                        System.arraycopy(r.b, r.p, raw, o, k)
                        r.p += n + 1
                        o += k
                    } else if (n != -128) {
                        val v = r.b[r.p++]
                        repeat(min(1 - n, rowEnd - o)) { raw[o++] = v }
                    }
                }
                r.p = end
            }
        }
        return IntArray(w * h) { i -> raw[i * bpp].toInt() and 0xFF }
    }

    // ------------------------------------------------------------- Procreate

    private fun procreate(bytes: ByteArray, name: String): Result {
        // Group the archive's files by folder: a .brush is one folder (the
        // root), a .brushset one folder per brush.
        val folders = LinkedHashMap<String, HashMap<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val path = e.name.replace('\\', '/')
                val dir = path.substringBeforeLast('/', "")
                val file = path.substringAfterLast('/')
                if (file.startsWith(".")) continue
                val data = zip.readBytes()
                if (data.size > 64 * 1024 * 1024) continue
                folders.getOrPut(dir) { HashMap() }[file] = data
            }
        }
        val out = ArrayList<BrushPreset>()
        for ((dir, files) in folders) {
            val shape = files["Shape.png"]
            val grain = files["Grain.png"]
            val archive = files["Brush.archive"]
            if (shape == null && archive == null) {
                // A zip of plain tip images.
                for ((f, data) in files) {
                    if (!f.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") }) continue
                    out += image(data, f.substringBeforeLast('.')).presets
                }
                continue
            }
            val shapeBmp = shape?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            val grainBmp = grain?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            // Procreate tips and grains: white is paint.
            val shapeId = storeShape(shapeBmp?.let { coverageOf(grey(it), it.width, it.height) })
            val grainId = storeGrain(grainBmp?.let { g ->
                val v = grey(g)
                val px = IntArray(v.size) { (v[it] shl 24) or 0x00FFFFFF }
                Bitmap.createBitmap(px, g.width, g.height, Bitmap.Config.ARGB_8888)
            })
            val settings = archive?.let { runCatching { Bplist.keyedRoot(it) }.getOrNull() }.orEmpty()
            fun num(key: String): Float? = (settings[key] as? Number)?.toFloat()
            val title = (settings["name"] as? String)?.takeIf { it.isNotBlank() }
                ?: if (dir.isEmpty()) name else "$name ${out.size + 1}"
            var p = newPreset(title, shapeId = shapeId, grainId = grainId, spacing = num("plotSpacing") ?: 0.1f)
            num("paintOpacity")?.let { p = p.copy(opacity = it.coerceIn(0.05f, 1f)) }
            num("paintSize")?.let { p = p.copy(size = (4f + it * 200f).coerceIn(2f, 300f)) }
            num("plotJitter")?.let { p = p.copy(jitter = it.coerceIn(0f, 1f)) }
            num("plotSmoothing")?.let { p = p.copy(smoothing = it.coerceIn(0f, 1f)) }
            num("dynamicsPressureSize")?.let { p = p.copy(pressureSize = it.coerceIn(0f, 1f)) }
            num("dynamicsPressureOpacity")?.let { p = p.copy(pressureOpacity = it.coerceIn(0f, 1f)) }
            num("shapeRotation")?.let { p = p.copy(rotation = it * 360f) }
            if (settings["shapeRandomise"] == true) p = p.copy(rotationMode = 3)
            if (settings["shapeOrientation"] is Number && (settings["shapeOrientation"] as Number).toInt() == 1) p = p.copy(rotationMode = 1)
            if (grainId.isEmpty()) p = p.copy(grain = 0f)
            if (shapeId.isEmpty()) p = p.copy(hardness = 0.85f)
            out.add(p)
        }
        return if (out.isEmpty()) Result(emptyList(), "No brushes found in that file.")
        else Result(out, "Imported ${out.size} Procreate brush${if (out.size == 1) "" else "es"}.")
    }

    // ------------------------------------------------------------- Clip Studio

    private fun clipStudio(bytes: ByteArray, name: String, scratch: File): Result {
        val f = File(scratch, "import-${System.nanoTime()}.sut")
        f.writeBytes(bytes)
        val out = ArrayList<BrushPreset>()
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                f.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            db.use {
                val names = ArrayList<String>()
                runCatching {
                    db.rawQuery("SELECT NodeName FROM Node", null).use { c ->
                        while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(names::add)
                    }
                }
                val tips = ArrayList<String>()
                runCatching {
                    db.rawQuery("SELECT FileData FROM MaterialFile", null).use { c ->
                        while (c.moveToNext()) {
                            val blob = c.getBlob(0) ?: continue
                            embeddedPng(blob)?.let { png ->
                                BitmapFactory.decodeByteArray(png, 0, png.size)?.let { bmp ->
                                    storeShape(coverageOf(grey(bmp), bmp.width, bmp.height)).takeIf { it.isNotEmpty() }?.let(tips::add)
                                }
                            }
                        }
                    }
                }
                val title = names.lastOrNull() ?: name
                out.add(newPreset(title, shapeId = tips.firstOrNull().orEmpty(), spacing = 0.1f))
            }
        } finally {
            f.delete()
        }
        val tipNote = if (out.firstOrNull()?.shapeId.isNullOrEmpty())
            " Its tip is stored in Clip Studio's own format, so it uses a round tip." else ""
        return Result(out, "Imported “${out.firstOrNull()?.name ?: name}” from Clip Studio.$tipNote")
    }

    /** The first PNG stream inside [blob], if any. */
    private fun embeddedPng(blob: ByteArray): ByteArray? {
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        var i = 0
        while (i <= blob.size - 8) {
            if (blob[i] == sig[0] && blob[i + 1] == sig[1] && blob[i + 2] == sig[2] && blob[i + 3] == sig[3]) {
                // Find IEND.
                var j = i + 8
                while (j <= blob.size - 8) {
                    if (blob[j + 4] == 'I'.code.toByte() && blob[j + 5] == 'E'.code.toByte() &&
                        blob[j + 6] == 'N'.code.toByte() && blob[j + 7] == 'D'.code.toByte()
                    ) {
                        return blob.copyOfRange(i, min(blob.size, j + 12))
                    }
                    j++
                }
                return null
            }
            i++
        }
        return null
    }
}

/**
 * Minimal binary property list reader, enough for an NSKeyedArchiver root
 * dictionary of scalars and strings (Procreate's Brush.archive).
 */
internal object Bplist {
    fun keyedRoot(data: ByteArray): Map<String, Any?> {
        val objs = parse(data)
        val top = objs[topIndex] as? Map<*, *> ?: return emptyMap()
        val objects = top["\$objects"] as? List<*> ?: return emptyMap()
        val rootRef = (top["\$top"] as? Map<*, *>)?.get("root") as? Uid ?: return emptyMap()
        val root = objects.getOrNull(rootRef.v) as? Map<*, *> ?: return emptyMap()
        val out = HashMap<String, Any?>()
        for ((k, v) in root) {
            val key = k as? String ?: continue
            out[key] = when (v) {
                is Uid -> objects.getOrNull(v.v).takeIf { it !is Map<*, *> && it != "\$null" }
                else -> v
            }
        }
        return out
    }

    class Uid(val v: Int)

    private var topIndex = 0

    private fun parse(d: ByteArray): List<Any?> {
        if (d.size < 40 || String(d, 0, 6, Charsets.US_ASCII) != "bplist") throw IllegalArgumentException("not a bplist")
        val t = d.size - 32
        val offSize = d[t + 6].toInt() and 0xFF
        val refSize = d[t + 7].toInt() and 0xFF
        val count = be(d, t + 8, 8).toInt()
        topIndex = be(d, t + 16, 8).toInt()
        val tableAt = be(d, t + 24, 8).toInt()
        if (count <= 0 || count > 200_000) throw IllegalArgumentException("bad bplist")
        val offsets = IntArray(count) { be(d, tableAt + it * offSize, offSize).toInt() }
        val cache = arrayOfNulls<Any?>(count)
        val done = BooleanArray(count)
        fun obj(i: Int, depth: Int): Any? {
            if (i !in 0 until count || depth > 64) return null
            if (done[i]) return cache[i]
            var p = offsets[i]
            val marker = d[p].toInt() and 0xFF
            val hi = marker shr 4
            val lo = marker and 0x0F
            p++
            fun lenOf(): Int {
                if (lo != 0x0F) return lo
                val m = d[p].toInt() and 0x0F
                val n = 1 shl m
                val v = be(d, p + 1, n).toInt()
                p += 1 + n
                return v
            }
            val v: Any? = when (hi) {
                0x0 -> when (lo) { 0x8 -> false; 0x9 -> true; else -> null }
                0x1 -> be(d, p, 1 shl lo)
                0x2 -> if (lo == 3) Double.fromBits(be(d, p, 8)) else Float.fromBits(be(d, p, 4).toInt()).toDouble()
                0x5 -> { val n = lenOf(); String(d, p, n, Charsets.US_ASCII) }
                0x6 -> { val n = lenOf(); String(d, p, n * 2, Charsets.UTF_16BE) }
                0x8 -> Uid(be(d, p, lo + 1).toInt())
                0xA -> { val n = lenOf(); List(n) { k -> obj(be(d, p + k * refSize, refSize).toInt(), depth + 1) } }
                0xD -> {
                    val n = lenOf()
                    val m = LinkedHashMap<Any?, Any?>()
                    for (k in 0 until n) {
                        val key = obj(be(d, p + k * refSize, refSize).toInt(), depth + 1)
                        val value = obj(be(d, p + (n + k) * refSize, refSize).toInt(), depth + 1)
                        m[key] = value
                    }
                    m
                }
                else -> null
            }
            cache[i] = v
            done[i] = true
            return v
        }
        return List(count) { obj(it, 0) }
    }

    private fun be(d: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (d[at + k].toLong() and 0xFF)
        return v
    }
}
