package com.yume.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.yume.app.engine.DrawingSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the user can export a document as. */
enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    Png("PNG", "png", "image/png"),
    Jpeg("JPEG", "jpg", "image/jpeg"),
    Webp("WebP", "webp", "image/webp"),
    Pdf("PDF", "pdf", "application/pdf"),
    Ora("OpenRaster (layers)", "ora", "image/openraster"),
    Timelapse("Timelapse video", "mp4", "video/mp4")
}

/**
 * Document exports, backups and the timelapse. Every pixel read of the
 * engine happens on its owner thread ([DrawingSession.runOnOwner]); nothing
 * here builds a canvas-sized bitmap for an 8K document except where a format
 * needs one in memory (JPEG / WebP), which is capped at [MAX_RASTER_PIXELS].
 */
object Exporter {
    /**
     * How far the running export is, 0..1, or -1 while it cannot tell (the
     * export dialog shows a moving bar then).
     */
    @Volatile @JvmField var progress: Float = -1f

    /** JPEG and WebP are encoded from one bitmap: at most this many pixels (16.8 MP). */
    const val MAX_RASTER_PIXELS = 4096L * 4096L

    /** Composite at full size, or scaled so it holds at most [maxPixels]. */
    fun flattenScaled(session: DrawingSession, maxPixels: Long, opaqueBackground: Int?): Bitmap? {
        val e = session.engine
        val px = e.width.toLong() * e.height
        val s = if (px <= maxPixels) 1f else sqrt(maxPixels.toDouble() / px).toFloat()
        val w = max(1, (e.width * s).toInt())
        val h = max(1, (e.height * s).toInt())
        val out = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val c = Canvas(out)
        if (opaqueBackground != null) c.drawColor(opaqueBackground)
        val ok = session.runOnOwner {
            e.compositeAll()
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            c.save()
            c.scale(w.toFloat() / e.width, h.toFloat() / e.height)
            val st = e.compositeTiles
            if (st != null) st.drawInto(c, p) else c.drawBitmap(e.composite, 0f, 0f, p)
            c.restore()
        }
        if (!ok) {
            out.recycle()
            return null
        }
        return out
    }

    fun writePng(session: DrawingSession, out: OutputStream): Boolean {
        if (session.engine.compositeIsSparse) return session.exportBandedTo(out)
        val flat = session.flattenBlocking() ?: return false
        return try { flat.compress(Bitmap.CompressFormat.PNG, 100, out) } finally { flat.recycle() }
    }

    fun writeRaster(session: DrawingSession, format: ExportFormat, out: OutputStream): Boolean {
        // JPEG has no alpha: a transparent background becomes white.
        val bg = if (format == ExportFormat.Jpeg) {
            val b = session.engine.backgroundColor
            if (Color.alpha(b) == 255) b else Color.WHITE
        } else null
        val bmp = flattenScaled(session, MAX_RASTER_PIXELS, bg) ?: return false
        return try {
            val cf = if (format == ExportFormat.Jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.WEBP_LOSSY
            bmp.compress(cf, 92, out)
        } finally {
            bmp.recycle()
        }
    }

    /** One page the size of the document (1 px = 1 pt), drawn tile by tile. */
    fun writePdf(session: DrawingSession, out: OutputStream): Boolean {
        val e = session.engine
        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(e.width, e.height, 1).create()
            var ok = false
            session.runOnOwner {
                e.compositeAll()
                val page = doc.startPage(info)
                val st = e.compositeTiles
                val p = Paint(Paint.FILTER_BITMAP_FLAG)
                if (st != null) st.drawInto(page.canvas, p) else page.canvas.drawBitmap(e.composite, 0f, 0f, p)
                doc.finishPage(page)
                ok = true
            }
            if (!ok) return false
            doc.writeTo(out)
            return true
        } catch (t: Throwable) {
            return false
        } finally {
            doc.close()
        }
    }

    /**
     * OpenRaster: the project's own saved layer PNGs (the caller saves first),
     * a stack.xml with names, opacity, visibility, blend and groups, the
     * merged image and a thumbnail. Masks are not part of OpenRaster and are
     * not applied to the layer images.
     */
    /** Streams a tile layer file into [out] as a PNG, one tile row per band. */
    private fun tilesAsPng(file: File, out: OutputStream) {
        com.yume.app.engine.TileFile.RowReader(file).use { r ->
            val band = android.graphics.Bitmap.createBitmap(r.width, r.tileSize, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                com.yume.app.engine.BandedPng.encode(object : java.io.FilterOutputStream(out) {
                    // The zip entry stays open for the caller.
                    override fun close() = flush()
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                }, r.width, r.height, r.tileSize) { _, rows, px ->
                    val got = r.nextRow(band)
                    if (got != rows) throw java.io.IOException("tile file ended early")
                    band.getPixels(px, 0, r.width, 0, 0, r.width, rows)
                }
            } finally {
                band.recycle()
            }
        }
    }

    fun writeOra(session: DrawingSession, projectDir: File, out: OutputStream, scratch: File): Boolean = runCatching {
        val manifest = JSONObject(File(projectDir, "manifest.json").readText())
        val layers = manifest.getJSONArray("layers")
        val groups = manifest.optJSONArray("groups") ?: JSONArray()
        val groupInfo = HashMap<Long, JSONObject>()
        for (i in 0 until groups.length()) groups.getJSONObject(i).let { groupInfo[it.getLong("id")] = it }
        val merged = File(scratch, "merged.png")
        FileOutputStream(merged).use { if (!writePng(session, it)) error("merge") }

        val zip = ZipOutputStream(out)
        // mimetype first and stored, as the spec requires.
        val mime = "image/openraster".toByteArray()
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong()
            crc = CRC32().apply { update(mime) }.value
        })
        zip.write(mime)
        zip.closeEntry()

        fun opAttr(blend: String): String = when (blend) {
            "Multiply" -> "svg:multiply"; "Screen" -> "svg:screen"; "Overlay" -> "svg:overlay"
            "Darken" -> "svg:darken"; "Lighten" -> "svg:lighten"; "ColorDodge" -> "svg:color-dodge"
            "ColorBurn" -> "svg:color-burn"; "HardLight" -> "svg:hard-light"; "SoftLight" -> "svg:soft-light"
            "Difference" -> "svg:difference"; "Exclusion" -> "svg:exclusion"; "Hue" -> "svg:hue"
            "Saturation" -> "svg:saturation"; "Color" -> "svg:color"; "Luminosity" -> "svg:luminosity"
            "Add" -> "svg:plus"; else -> "svg:src-over"
        }
        fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        val xml = StringBuilder()
        xml.append("<?xml version='1.0' encoding='UTF-8'?>\n")
        xml.append("<image version=\"0.0.3\" w=\"${manifest.getInt("width")}\" h=\"${manifest.getInt("height")}\">\n<stack>\n")
        // Top of the stack first; consecutive members of a group become a sub-stack.
        var i = layers.length() - 1
        var n = 0
        while (i >= 0) {
            val l = layers.getJSONObject(i)
            val gid = l.optLong("group", 0L)
            fun layerXml(o: JSONObject): String {
                progress = n.toFloat() / layers.length().coerceAtLeast(1)
                val src = "data/layer${n++}.png"
                val f = File(projectDir, o.getString("file"))
                zip.putNextEntry(ZipEntry(src))
                if (com.yume.app.engine.TileFile.isTileFile(f)) {
                    // Tile layer file: stream it out as a PNG, one tile row at
                    // a time, never as a whole-layer bitmap.
                    tilesAsPng(f, zip)
                } else {
                    f.inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
                return "<layer name=\"${esc(o.optString("name"))}\" src=\"$src\" x=\"0\" y=\"0\" " +
                    "opacity=\"${o.optDouble("opacity", 1.0)}\" visibility=\"${if (o.optBoolean("visible", true)) "visible" else "hidden"}\" " +
                    "composite-op=\"${opAttr(o.optString("blend"))}\"/>\n"
            }
            if (gid != 0L && groupInfo[gid] != null) {
                val g = groupInfo[gid]!!
                xml.append("<stack name=\"${esc(g.optString("name"))}\" opacity=\"${g.optDouble("opacity", 1.0)}\" " +
                    "visibility=\"${if (g.optBoolean("visible", true)) "visible" else "hidden"}\" composite-op=\"${opAttr(g.optString("blend"))}\" isolation=\"isolate\">\n")
                while (i >= 0 && layers.getJSONObject(i).optLong("group", 0L) == gid) {
                    xml.append(layerXml(layers.getJSONObject(i)))
                    i--
                }
                xml.append("</stack>\n")
            } else {
                xml.append(layerXml(l))
                i--
            }
        }
        xml.append("</stack>\n</image>\n")
        zip.putNextEntry(ZipEntry("stack.xml"))
        zip.write(xml.toString().toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("mergedimage.png"))
        merged.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        File(projectDir, "thumb.png").takeIf { it.exists() }?.let { t ->
            val b = BitmapFactory.decodeFile(t.absolutePath)
            if (b != null) {
                val s = min(1f, 256f / max(b.width, b.height))
                val small = Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
                zip.putNextEntry(ZipEntry("Thumbnails/thumbnail.png"))
                small.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
        zip.finish()
        merged.delete()
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------ backup

    /** Folders that are scratch and never part of a backup. */
    private val SKIP = setOf("journal", "cache")

    /** Zips a (saved) project folder. */
    fun backup(projectDir: File, out: OutputStream): Boolean = runCatching {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("yume-backup.json"))
            zip.write(JSONObject().put("format", "yume-project").put("version", 1).toString().toByteArray())
            zip.closeEntry()
            projectDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(projectDir).invariantSeparatorsPath
                if (rel.substringBefore('/') in SKIP || rel.endsWith(".tmp")) return@forEach
                zip.putNextEntry(ZipEntry("project/$rel"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        true
    }.getOrDefault(false)

    /**
     * Restores a backup zip into [targetDir] (a new, empty project folder).
     * Entries are confined to the folder (no path escapes); the archive must
     * contain a manifest. Returns false (and removes the folder) otherwise.
     */
    fun restore(input: java.io.InputStream, targetDir: File): Boolean {
        val ok = runCatching {
            targetDir.mkdirs()
            val root = targetDir.canonicalFile
            var sawManifest = false
            var sawMarker = false
            ZipInputStream(input).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.name == "yume-backup.json") { sawMarker = true; continue }
                    if (e.isDirectory || !e.name.startsWith("project/")) continue
                    val rel = e.name.removePrefix("project/")
                    val dest = File(root, rel).canonicalFile
                    if (!dest.path.startsWith(root.path + File.separator)) continue
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { zin.copyTo(it) }
                    if (rel == "manifest.json") sawManifest = true
                }
            }
            sawMarker && sawManifest
        }.getOrDefault(false)
        if (!ok) targetDir.deleteRecursively()
        return ok
    }

    // --------------------------------------------------------- timelapse

    /**
     * Encodes the recorded frames (JPEGs in [framesDir]) as an H.264 MP4 at
     * 30 fps, holding the finished picture for two seconds. Frames are decoded
     * one at a time, so memory stays at one frame regardless of document size.
     */
    fun encodeTimelapse(framesDir: File, outFile: File): Boolean {
        val frames = framesDir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()
        if (frames.isEmpty()) return false
        val first = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frames.last().absolutePath, first)
        if (first.outWidth <= 0) return false
        // Fit within 1280 px, with even dimensions on a 16-pixel grid.
        val s = min(1f, 1280f / max(first.outWidth, first.outHeight))
        val w = max(16, ((first.outWidth * s).toInt() / 16) * 16)
        val h = max(16, ((first.outHeight * s).toInt() / 16) * 16)
        val fps = 30
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: return false
        var muxer: MediaMuxer? = null
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            val mx = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            var track = -1
            var written = 0L
            val info = MediaCodec.BufferInfo()
            fun drain(end: Boolean) {
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return else continue }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mx.addTrack(codec.outputFormat)
                        mx.start()
                        continue
                    }
                    if (idx < 0) continue
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && track >= 0) {
                        // Our own clock: one frame per 1/30 s, whatever the wall time was.
                        info.presentationTimeUs = written * 1_000_000L / fps
                        written++
                        mx.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            fun draw(bmp: Bitmap) {
                val c = surface.lockHardwareCanvas()
                c.drawColor(Color.BLACK)
                val fs = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
                val dw = bmp.width * fs
                val dh = bmp.height * fs
                c.drawBitmap(bmp, null, RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), paint)
                surface.unlockCanvasAndPost(c)
                drain(false)
            }
            var last: Bitmap? = null
            for ((fi, f) in frames.withIndex()) {
                progress = fi.toFloat() / frames.size
                val b = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                draw(b)
                last?.recycle()
                last = b
                Thread.sleep(4)
            }
            last?.let { b -> repeat(fps * 2) { draw(b); Thread.sleep(2) } }
            last?.recycle()
            codec.signalEndOfInputStream()
            drain(true)
            true
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
