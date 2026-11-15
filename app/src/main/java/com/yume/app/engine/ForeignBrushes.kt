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
 * - Clip Studio Paint `.sut` and `.sutg` (a group of brushes): every brush,
 *   with its tip and texture read out of Clip Studio's own material format
 *   ([ClipStudioMaterial]), and its size, spacing, opacity, flow, hardness,
 *   direction and texture strength.
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
    private fun coverageOf(values: IntArray, w: Int, h: Int, autoInvert: Boolean = true): Bitmap? {
        if (w <= 0 || h <= 0 || values.size < w * h) return null
        var sum = 0L
        var n = 0
        for (x in 0 until w) { sum += values[x] + values[(h - 1) * w + x]; n += 2 }
        for (y in 0 until h) { sum += values[y * w] + values[y * w + w - 1]; n += 2 }
        val invert = autoInvert && n > 0 && sum / n > 127
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
            // Tips by their id, and the descriptor (names, sizes, spacing).
            val tips = LinkedHashMap<String, String>()
            var desc: ByteArray? = null
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
                        val idLen = bytes[r.p].toInt() and 0xFF
                        val tipId = if (idLen in 1..80 && r.p + 1 + idLen <= bytes.size)
                            String(bytes, r.p + 1, idLen, Charsets.US_ASCII) else ""
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
                            if (id.isNotEmpty()) tips[tipId.ifEmpty { "#$n" }] = id
                        }
                        r.p = next
                    }
                } else if (key == "desc" && end <= bytes.size) {
                    desc = bytes.copyOfRange(r.p, end)
                }
                r.p = end + (len and 1)
            }
            var capped = false
            val used = HashSet<String>()
            desc?.let { d ->
                for (b in abrDescriptor(d)) {
                    val id = tips[b.tipId] ?: continue
                    used.add(b.tipId)
                    var p = newPreset(b.name.ifBlank { "$name ${out.size + 1}" }, shapeId = id,
                        spacing = (b.spacing ?: 25f) / 100f)
                    b.diameter?.let {
                        if (it > 900f) capped = true
                        p = p.copy(size = it.coerceIn(1f, 900f))
                    }
                    out.add(p)
                }
            }
            // Tips no brush in the descriptor refers to (or no descriptor).
            var n = 0
            for ((tid, id) in tips) {
                n++
                if (tid in used) continue
                out.add(newPreset("$name $n", shapeId = id, spacing = 0.25f))
            }
            if (capped && out.isNotEmpty()) {
                return Result(out, "Imported ${out.size} Photoshop brush${if (out.size == 1) "" else "es"}. Sizes above 900 px were set to 900 px.")
            }
        }
        return if (out.isEmpty()) Result(emptyList(), "No sampled brush tips found in that .abr file.")
        else Result(out, "Imported ${out.size} Photoshop brush${if (out.size == 1) "" else "es"}.")
    }

    private class AbrBrush(val name: String, val tipId: String, val diameter: Float?, val spacing: Float?)

    /**
     * The brushes of an .abr descriptor ("desc" section): every brushPreset
     * object's name, its sampled tip id, diameter (px) and spacing (%). Read
     * by locating the keys inside each preset's span, not by a full
     * descriptor parse.
     */
    private fun abrDescriptor(d: ByteArray): List<AbrBrush> {
        val marker = "brushPreset".toByteArray(Charsets.US_ASCII)
        val starts = ArrayList<Int>()
        var i = 0
        while (true) {
            i = indexOf(d, marker, i)
            if (i < 0) break
            // The preset object, not the "brushPreset" bool of a tool preset.
            if (i + marker.size + 4 <= d.size && String(d, i + marker.size, 4, Charsets.US_ASCII) != "bool") starts.add(i)
            i += marker.size
        }
        val out = ArrayList<AbrBrush>()
        for ((k, st) in starts.withIndex()) {
            val en = if (k + 1 < starts.size) starts[k + 1] else d.size
            val name = abrText(d, "Nm  TEXT", st, en).orEmpty()
            val tip = abrText(d, "sampledDataTEXT", st, en) ?: continue
            out.add(AbrBrush(name, tip, abrUnit(d, "Dmtr", "#Pxl", st, en), abrUnit(d, "Spcn", "#Prc", st, en)))
        }
        return out
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int, until: Int = hay.size): Int {
        var i = max(0, from)
        val last = min(until, hay.size) - needle.size
        while (i <= last) {
            var k = 0
            while (k < needle.size && hay[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
    }

    /** A TEXT value (u32 length in UTF-16 units, then UTF-16BE) after [key]. */
    private fun abrText(d: ByteArray, key: String, from: Int, until: Int): String? {
        val k = indexOf(d, key.toByteArray(Charsets.US_ASCII), from, until)
        if (k < 0) return null
        val at = k + key.length
        if (at + 4 > d.size) return null
        val n = ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or
            ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)
        if (n !in 0..1000 || at + 4 + 2 * n > d.size) return null
        return String(d, at + 4, 2 * n, Charsets.UTF_16BE).trimEnd('\u0000').trim()
    }

    /** A UntF value (unit tag, then a big-endian double) after [key]. */
    private fun abrUnit(d: ByteArray, key: String, unit: String, from: Int, until: Int): Float? {
        val k = indexOf(d, (key + "UntF" + unit).toByteArray(Charsets.US_ASCII), from, until)
        if (k < 0) return null
        val at = k + key.length + 8
        if (at + 8 > d.size) return null
        val v = ByteBuffer.wrap(d, at, 8).order(ByteOrder.BIG_ENDIAN).double
        return if (v.isFinite()) v.toFloat() else null
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
        // Each brush folder carries a Reset/ copy (its original settings) and
        // a QuickLook/ preview: neither is a brush of its own.
        folders.keys.removeAll { d -> d.split('/').any { it == "Reset" || it == "QuickLook" } }
        val anyBrush = folders.values.any { it.containsKey("Brush.archive") || it.containsKey("Shape.png") }
        // The set's own order (brushset.plist lists the folders).
        val order = folders[""]?.get("brushset.plist")?.let { plist ->
            Regex("<string>([0-9A-Fa-f-]{36})</string>").findAll(plist.decodeToString()).map { it.groupValues[1] }.toList()
        }.orEmpty()
        val ordered = folders.entries.sortedBy { (d, _) -> order.indexOf(d).let { if (it < 0) Int.MAX_VALUE else it } }
        for ((dir, files) in ordered) {
            val shape = files["Shape.png"]
            val grain = files["Grain.png"]
            val archive = files["Brush.archive"]
            if (shape == null && archive == null) {
                if (anyBrush) continue
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
            // Size: the slider (0..1) between the brush's own min and max
            // (both in hundreds of pixels).
            num("paintSize")?.let { ps ->
                val mx = num("maxSize") ?: 2f
                val mn = num("minSize") ?: 0.01f
                p = p.copy(size = ((mn + (mx - mn) * ps) * 100f).coerceIn(2f, 900f))
            }
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
        var capped = false
        var roundTips = 0
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                f.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            db.use {
                // Materials (tips and textures) by catalog path.
                val materials = HashMap<String, ByteArray>()
                runCatching {
                    // Read in 1 MB pieces: a cursor row holds at most 2 MB, and a
                    // material with a large tip or texture is several MB.
                    val rows = ArrayList<Triple<Long, String, Int>>()
                    val withPath = runCatching {
                        db.rawQuery("SELECT rowid, CatalogPath, length(FileData) FROM MaterialFile", null).use { c ->
                            while (c.moveToNext()) {
                                val path = c.getString(1)?.trimStart('.') ?: "#" + c.getLong(0)
                                rows.add(Triple(c.getLong(0), path, c.getInt(2)))
                            }
                        }
                    }.isSuccess
                    if (!withPath) {
                        // Older files: no catalog path, materials by row.
                        db.rawQuery("SELECT rowid, length(FileData) FROM MaterialFile", null).use { c ->
                            while (c.moveToNext()) rows.add(Triple(c.getLong(0), "#" + c.getLong(0), c.getInt(1)))
                        }
                    }
                    for ((rowid, path, len) in rows) {
                        if (len <= 0 || len > 256 * 1024 * 1024 || materials.containsKey(path)) continue
                        val buf = java.io.ByteArrayOutputStream(len)
                        val piece = 1 shl 20
                        var at = 0
                        while (at < len) {
                            val n = minOf(piece, len - at)
                            db.rawQuery(
                                "SELECT substr(FileData, ?, ?) FROM MaterialFile WHERE rowid = ?",
                                arrayOf((at + 1).toString(), n.toString(), rowid.toString())
                            ).use { c -> if (c.moveToFirst()) c.getBlob(0)?.let { buf.write(it) } }
                            at += n
                        }
                        materials[path] = buf.toByteArray()
                    }
                }
                val shapeIds = HashMap<String, String>()
                val grainIds = HashMap<String, String>()
                fun materialFor(ref: ByteArray?): String? {
                    if (ref == null) return null
                    val text = utf16Strings(ref)
                    return materials.keys.firstOrNull { key -> text.any { it.contains(key) } }
                }
                fun shapeFor(key: String): String = shapeIds.getOrPut(key) {
                    val blob = materials[key] ?: return@getOrPut ""
                    val mask = runCatching { ClipStudioMaterial.decode(blob) }.getOrNull()
                    val bmp = mask?.let { coverageOf(it.values, it.width, it.height, autoInvert = false) }
                        ?: embeddedPng(blob)?.let { png ->
                            BitmapFactory.decodeByteArray(png, 0, png.size)?.let { coverageOf(grey(it), it.width, it.height) }
                        }
                    storeShape(bmp)
                }
                fun grainFor(key: String): String = grainIds.getOrPut(key) {
                    val blob = materials[key] ?: return@getOrPut ""
                    val mask = runCatching { ClipStudioMaterial.decode(blob) }.getOrNull() ?: return@getOrPut ""
                    storeGrain(coverageOf(mask.values, mask.width, mask.height, autoInvert = false))
                }

                // Brush settings by variant id.
                class Variant(
                    val size: Float?, val interval: Float?, val opacity: Float?, val flow: Float?,
                    val hardness: Float?, val rotation: Float?, val rotationEffector: Int?,
                    val textureDensity: Float?, val pattern: ByteArray?, val texture: ByteArray?
                )
                val variants = HashMap<Long, Variant>()
                runCatching {
                    db.rawQuery("SELECT * FROM Variant", null).use { c ->
                        fun col(n: String) = c.getColumnIndex(n)
                        fun num(n: String): Float? = col(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getDouble(it).toFloat() }
                        fun int(n: String): Int? = col(n).takeIf { it >= 0 && !c.isNull(it) }?.let {
                            if (c.getType(it) == android.database.Cursor.FIELD_TYPE_INTEGER) c.getInt(it) else null
                        }
                        fun blob(n: String): ByteArray? = col(n).takeIf { it >= 0 && !c.isNull(it) }?.let {
                            if (c.getType(it) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(it) else null
                        }
                        while (c.moveToNext()) {
                            val id = col("VariantID").takeIf { it >= 0 }?.let { c.getLong(it) } ?: continue
                            variants[id] = Variant(
                                num("BrushSize"), num("BrushInterval"), num("Opacity"), num("BrushFlow"),
                                num("BrushHardness"), num("BrushRotation"), int("BrushRotationEffector"),
                                num("TextureDensity"), blob("BrushPatternImageArray"), blob("TextureImage")
                            )
                        }
                    }
                }

                // One preset per named brush (a .sutg holds several).
                val nodes = ArrayList<Pair<String, Long>>()
                runCatching {
                    db.rawQuery("SELECT NodeName, NodeVariantID FROM Node", null).use { c ->
                        while (c.moveToNext()) {
                            val n = c.getString(0)?.trim().orEmpty()
                            val v = if (c.isNull(1)) 0L else c.getLong(1)
                            if (n.isNotEmpty() && v != 0L) nodes.add(n to v)
                        }
                    }
                }
                if (nodes.isEmpty()) {
                    // Older files: the names alone, with the first variant.
                    runCatching {
                        db.rawQuery("SELECT NodeName FROM Node", null).use { c ->
                            while (c.moveToNext()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                nodes.add(it to (variants.keys.minOrNull() ?: 0L))
                            }
                        }
                    }
                }
                if (nodes.isEmpty()) nodes.add(name to (variants.keys.minOrNull() ?: 0L))

                // A brush whose tip reference cannot be resolved (older or
                // hand-made files) takes the first material that decodes.
                val fallbackShape: String by lazy {
                    materials.keys.asSequence().map(::shapeFor).firstOrNull { it.isNotEmpty() }.orEmpty()
                }
                for ((brushName, vid) in nodes) {
                    val v = variants[vid]
                    val shapeId = (materialFor(v?.pattern)?.let(::shapeFor)).orEmpty()
                        .ifEmpty { if (materialFor(v?.pattern) == null) fallbackShape else "" }
                    val grainId = materialFor(v?.texture)?.let(::grainFor).orEmpty()
                    if (shapeId.isEmpty()) roundTips++
                    // Clip Studio's interval is a percentage of the brush size.
                    val spacing = ((v?.interval ?: 10f) / 100f).coerceIn(0.02f, 2f)
                    var p = newPreset(brushName, shapeId = shapeId, grainId = grainId, spacing = spacing)
                    v?.size?.let {
                        if (it > 900f) capped = true
                        p = p.copy(size = it.coerceIn(1f, 900f))
                    }
                    v?.opacity?.let { p = p.copy(opacity = (it / 100f).coerceIn(0.02f, 1f)) }
                    v?.flow?.let { p = p.copy(flow = (it / 100f).coerceIn(0.02f, 1f)) }
                    if (shapeId.isEmpty()) v?.hardness?.let { p = p.copy(hardness = (it / 100f).coerceIn(0f, 1f)) }
                    v?.rotation?.let { p = p.copy(rotation = ((it % 360f) + 360f) % 360f) }
                    // Rotation effector bit 64: the tip follows the stroke direction.
                    if ((v?.rotationEffector ?: 0) and 64 != 0) p = p.copy(rotationMode = 1)
                    if (grainId.isNotEmpty()) {
                        p = p.copy(grain = ((v?.textureDensity ?: 60f) / 100f).coerceIn(0.05f, 1f))
                    }
                    out.add(p)
                }
            }
        } finally {
            f.delete()
        }
        val n = out.size
        val what = if (n == 1) "“${out[0].name}”" else "$n brushes"
        val notes = buildList {
            if (capped) add("Sizes above 900 px were set to 900 px.")
            if (roundTips > 0) add(
                if (roundTips == n) "Its tip could not be read, so it uses a round tip."
                else "$roundTips of them use a round tip (their tip could not be read)."
            )
        }
        return Result(out, "Imported $what from Clip Studio." + notes.joinToString(" ", prefix = if (notes.isEmpty()) "" else " "))
    }

    /** Printable UTF-16BE runs inside [b] (material references are stored that way). */
    private fun utf16Strings(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < b.size) {
            val hi = b[i].toInt() and 0xFF
            val lo = b[i + 1].toInt() and 0xFF
            if (hi == 0 && lo in 0x20..0x7E) {
                sb.append(lo.toChar())
            } else {
                if (sb.length >= 6) out.add(sb.toString())
                sb.setLength(0)
            }
            i += 2
        }
        if (sb.length >= 6) out.add(sb.toString())
        // A reference can also sit at an odd offset: try that alignment too.
        if (out.isEmpty() && b.size > 2) return utf16Strings(b.copyOfRange(1, b.size))
        return out
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
