package com.yume.app.data

import android.graphics.Bitmap
import android.graphics.Rect
import com.yume.app.engine.DocumentSnapshot
import com.yume.app.engine.DrawingSession
import com.yume.app.engine.GroupSnapshotData
import com.yume.app.engine.LayerBlend
import com.yume.app.engine.LayerSnapshotData
import com.yume.app.engine.MemoryBudget
import com.yume.app.engine.TileFile
import com.yume.app.engine.TileStoreConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/** A PSD the user should hear about (unsupported or broken), with a readable reason. */
class PsdException(message: String) : Exception(message)

/**
 * Photoshop documents: writing a layered PSD of the open artwork, and reading
 * one into a new artwork.
 *
 * Both directions stream. The writer reads each layer in bands on the
 * engine's owner thread and PackBits-compresses it into scratch files (PSD
 * puts every length before its data); the reader decodes each PSD layer a tile
 * row at a time straight into Yume's tile files. Neither ever holds a whole
 * layer, so a 16K document works like a small one.
 */
object Psd {

    // ------------------------------------------------------------ blend modes

    private fun keyOf(b: LayerBlend): String = when (b) {
        LayerBlend.Normal -> "norm"
        LayerBlend.Multiply -> "mul "
        LayerBlend.Screen -> "scrn"
        LayerBlend.Overlay -> "over"
        LayerBlend.Darken -> "dark"
        LayerBlend.Lighten -> "lite"
        LayerBlend.ColorDodge -> "div "
        LayerBlend.ColorBurn -> "idiv"
        LayerBlend.SoftLight -> "sLit"
        LayerBlend.HardLight -> "hLit"
        LayerBlend.Difference -> "diff"
        LayerBlend.Exclusion -> "smud"
        LayerBlend.Hue -> "hue "
        LayerBlend.Saturation -> "sat "
        LayerBlend.Color -> "colr"
        LayerBlend.Luminosity -> "lum "
        LayerBlend.Add -> "lddg"
    }

    private fun blendOf(key: String): LayerBlend = when (key) {
        "mul " -> LayerBlend.Multiply
        "scrn" -> LayerBlend.Screen
        "over" -> LayerBlend.Overlay
        "dark", "dkCl" -> LayerBlend.Darken
        "lite", "lgCl" -> LayerBlend.Lighten
        "div " -> LayerBlend.ColorDodge
        "idiv" -> LayerBlend.ColorBurn
        "sLit" -> LayerBlend.SoftLight
        "hLit" -> LayerBlend.HardLight
        "diff" -> LayerBlend.Difference
        "smud" -> LayerBlend.Exclusion
        "hue " -> LayerBlend.Hue
        "sat " -> LayerBlend.Saturation
        "colr" -> LayerBlend.Color
        "lum " -> LayerBlend.Luminosity
        "lddg" -> LayerBlend.Add
        else -> LayerBlend.Normal
    }

    // --------------------------------------------------------------- PackBits

    /** PackBits-encodes [len] bytes of [src] into [dst] (big enough: len + len / 128 + 1); returns the size. */
    private fun packBits(src: ByteArray, len: Int, dst: ByteArray): Int {
        var i = 0
        var o = 0
        while (i < len) {
            // A run of at least 3 equal bytes is worth a repeat packet.
            var run = 1
            while (i + run < len && run < 128 && src[i + run] == src[i]) run++
            if (run >= 3) {
                dst[o++] = (1 - run).toByte()
                dst[o++] = src[i]
                i += run
                continue
            }
            // Literal: up to the next run of 3 (or 128 bytes).
            val start = i
            var n = 0
            while (i < len && n < 128) {
                if (i + 2 < len && src[i] == src[i + 1] && src[i] == src[i + 2]) break
                i++
                n++
            }
            dst[o++] = (n - 1).toByte()
            System.arraycopy(src, start, dst, o, n)
            o += n
        }
        return o
    }

    /** Unpacks one PackBits row of [srcLen] bytes into [dst] ([dstLen] bytes expected). */
    private fun unpackBits(src: ByteArray, srcLen: Int, dst: ByteArray, dstLen: Int) {
        var i = 0
        var o = 0
        while (i < srcLen && o < dstLen) {
            val n = src[i++].toInt()
            if (n >= 0) {
                val c = min(n + 1, min(dstLen - o, srcLen - i))
                System.arraycopy(src, i, dst, o, c)
                i += n + 1
                o += c
            } else if (n != -128) {
                val c = min(1 - n, dstLen - o)
                if (i >= srcLen) break
                java.util.Arrays.fill(dst, o, o + c, src[i])
                i++
                o += c
            }
        }
        if (o < dstLen) java.util.Arrays.fill(dst, o, dstLen, 0)
    }

    // ================================================================= write

    /** One PackBits channel staged on disk: row byte counts plus the packed rows. */
    private class StagedChannel(val id: Int, val rows: Int, val file: File?) {
        val counts = IntArray(rows)
        var bytes = 0L
        /** Its size inside a layer's channel data: compression + counts + rows. */
        val length: Long get() = if (file == null) 2L else 2L + rows * 2L + bytes
    }

    private class Record(
        val bounds: Rect,
        val channels: List<StagedChannel>,
        val key: String,
        val opacity: Int,
        val clipping: Boolean,
        val hidden: Boolean,
        val name: String,
        val section: Int,
        val maskRect: Rect?,
        val maskDisabled: Boolean
    )

    /**
     * Writes the open artwork as a layered PSD to [out]. Layers, groups,
     * opacity, blend modes, visibility, clipping and masks are kept, and the
     * flattened picture is included for apps that only read that.
     */
    fun write(session: DrawingSession, out: OutputStream, scratch: File): Boolean {
        val dir = File(scratch, "psd-${System.nanoTime()}").apply { mkdirs() }
        try {
            return writeInner(session, out, dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeInner(session: DrawingSession, out: OutputStream, dir: File): Boolean {
        val engine = session.engine
        var width = 0
        var height = 0
        var background = 0
        var layers: List<com.yume.app.engine.Layer> = emptyList()
        var groups: Map<Long, com.yume.app.engine.LayerGroup> = emptyMap()
        if (!session.runOnOwner {
                width = engine.width
                height = engine.height
                background = engine.backgroundColor
                layers = engine.layers.toList()
                groups = engine.groups.associateBy { it.id }
            }
        ) return false
        if (width > 30000 || height > 30000) return false

        val band = 32
        val bmp = Bitmap.createBitmap(width, band, Bitmap.Config.ARGB_8888)
        val px = IntArray(width * band)
        val rowBuf = ByteArray(width)
        val packed = ByteArray(width + width / 128 + 8)
        var fileNo = 0

        /**
         * Stages [ids] channels of [rect] read from [read] (which fills the
         * bitmap with the band's pixels, on the owner thread).
         */
        fun stage(rect: Rect, ids: IntArray, read: (Rect, Bitmap) -> Unit): List<StagedChannel>? {
            val w = rect.width()
            val h = rect.height()
            val chans = ids.map { StagedChannel(it, h, File(dir, "c${fileNo++}")) }
            val outs = chans.map { BufferedOutputStream(FileOutputStream(it.file!!), 1 shl 16) }
            try {
                var y = 0
                val r = Rect()
                while (y < h) {
                    val rows = min(band, h - y)
                    r.set(rect.left, rect.top + y, rect.right, rect.top + y + rows)
                    if (!session.runOnOwner { read(r, bmp) }) return null
                    bmp.getPixels(px, 0, w, 0, 0, w, rows)
                    for (k in 0 until rows) {
                        val base = k * w
                        for ((ci, ch) in chans.withIndex()) {
                            val shift = when (ch.id) { -1 -> 24; 0 -> 16; 1 -> 8; 2 -> 0; else -> 16 }
                            for (x in 0 until w) rowBuf[x] = (px[base + x] ushr shift).toByte()
                            val n = packBits(rowBuf, w, packed)
                            outs[ci].write(packed, 0, n)
                            ch.counts[y + k] = n
                            ch.bytes += n
                        }
                    }
                    y += rows
                }
            } finally {
                outs.forEach { it.close() }
            }
            return chans
        }

        fun emptyChannels() = listOf(-1, 0, 1, 2).map { StagedChannel(it, 0, null) }

        // ------------------------------------------------- layer records
        val records = ArrayList<Record>()
        val canvas = Rect(0, 0, width, height)
        var openGroup = 0L
        fun closeGroup() {
            val g = groups[openGroup] ?: return
            records.add(
                Record(
                    Rect(), emptyChannels(), if (g.blend == LayerBlend.Normal) "pass" else keyOf(g.blend),
                    (g.opacity * 255f).toInt().coerceIn(0, 255), false, !g.visible, g.name, 1, null, false
                )
            )
        }
        for ((index, layer) in layers.withIndex()) {
            Exporter.progress = index.toFloat() / (layers.size + 1)
            if (layer.groupId != openGroup) {
                if (openGroup != 0L) closeGroup()
                openGroup = layer.groupId
                if (openGroup != 0L && groups[openGroup] != null) {
                    records.add(Record(Rect(), emptyChannels(), "norm", 255, false, false, "</Layer group>", 3, null, false))
                } else {
                    openGroup = 0L
                }
            }
            var bounds = Rect()
            if (!session.runOnOwner {
                    bounds = if (layer.tiles.isSparse) layer.tiles.nonEmptyBounds() else Rect(canvas)
                }
            ) return false
            if (!bounds.intersect(canvas)) bounds = Rect()
            val chans = ArrayList<StagedChannel>()
            if (bounds.isEmpty) {
                chans.addAll(emptyChannels())
            } else {
                chans.addAll(stage(bounds, intArrayOf(-1, 0, 1, 2)) { r, b -> layer.tiles.readRectInto(r, b) } ?: return false)
            }
            val mask = layer.maskTiles
            var maskRect: Rect? = null
            if (mask != null) {
                maskRect = Rect(canvas)
                chans.addAll(stage(canvas, intArrayOf(-2)) { r, b -> mask.readRectInto(r, b) } ?: return false)
            }
            records.add(
                Record(
                    bounds, chans, keyOf(layer.blend), (layer.opacity * 255f).toInt().coerceIn(0, 255),
                    layer.clipping, !layer.visible, layer.name, 0, maskRect, !layer.maskEnabled
                )
            )
        }
        if (openGroup != 0L) closeGroup()

        // -------------------------------------------------- merged image
        val opaque = (background ushr 24) == 0xFF
        val mergedIds = if (opaque) intArrayOf(0, 1, 2) else intArrayOf(0, 1, 2, -1)
        if (!session.runOnOwner { if (engine.compositeIsSparse) engine.compositeAll() }) return false
        val merged = stage(canvas, mergedIds) { r, b ->
            val st = engine.compositeTiles
            if (st != null) st.readRectInto(r, b) else engine.readFlattened(r, b)
        } ?: return false
        bmp.recycle()
        Exporter.progress = 0.98f

        // ------------------------------------------------------- assemble
        val recBytes = ByteArrayOutputStream()
        val rec = DataOutputStream(recBytes)
        for (r in records) writeRecord(rec, r)
        rec.flush()
        var channelBytes = 0L
        for (r in records) for (c in r.channels) channelBytes += c.length
        var layerInfo = 2L + recBytes.size() + channelBytes
        val pad = ((4 - layerInfo % 4) % 4).toInt()
        layerInfo += pad
        val section = 4L + layerInfo + 4L
        if (section > 0xFFFFFFFFL) return false

        val o = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
        o.writeBytes("8BPS")
        o.writeShort(1)
        o.write(ByteArray(6))
        o.writeShort(mergedIds.size)
        o.writeInt(height)
        o.writeInt(width)
        o.writeShort(8)
        o.writeShort(3)
        o.writeInt(0) // colour mode data
        o.writeInt(0) // image resources
        o.writeInt(section.toInt())
        o.writeInt(layerInfo.toInt())
        // Negative: the merged image's last channel is its transparency.
        o.writeShort(if (opaque) records.size else -records.size)
        recBytes.writeTo(o)
        for (r in records) for (c in r.channels) writeChannel(o, c)
        o.write(ByteArray(pad))
        o.writeInt(0) // global layer mask info
        // Merged image: one compression for all, every count table, then rows.
        o.writeShort(1)
        for (c in merged) for (n in c.counts) o.writeShort(n)
        for (c in merged) c.file?.let { f -> FileInputStream(f).use { it.copyTo(o) } }
        o.flush()
        Exporter.progress = 1f
        return true
    }

    private fun writeChannel(o: DataOutputStream, c: StagedChannel) {
        if (c.file == null) {
            o.writeShort(0) // raw, no rows
            return
        }
        o.writeShort(1)
        for (n in c.counts) o.writeShort(n)
        FileInputStream(c.file).use { it.copyTo(o) }
    }

    private fun writeRecord(o: DataOutputStream, r: Record) {
        o.writeInt(r.bounds.top)
        o.writeInt(r.bounds.left)
        o.writeInt(r.bounds.bottom)
        o.writeInt(r.bounds.right)
        o.writeShort(r.channels.size)
        for (c in r.channels) {
            o.writeShort(c.id)
            o.writeInt(c.length.toInt())
        }
        o.writeBytes("8BIM")
        o.writeBytes(r.key)
        o.writeByte(r.opacity)
        o.writeByte(if (r.clipping) 1 else 0)
        // Bit 1: hidden; bit 3: bit 4 is meaningful; bit 4: no pixel data (groups).
        var flags = if (r.hidden) 0x02 else 0
        if (r.section != 0) flags = flags or 0x18
        o.writeByte(flags)
        o.writeByte(0)
        val extra = ByteArrayOutputStream()
        val e = DataOutputStream(extra)
        val m = r.maskRect
        if (m != null) {
            e.writeInt(20)
            e.writeInt(m.top); e.writeInt(m.left); e.writeInt(m.bottom); e.writeInt(m.right)
            e.writeByte(255)
            e.writeByte(if (r.maskDisabled) 0x02 else 0)
            e.writeShort(0)
        } else {
            e.writeInt(0)
        }
        e.writeInt(0) // blending ranges
        // Pascal name (ASCII), padded to 4 with its length byte.
        val ascii = r.name.map { if (it.code in 32..126) it.code.toByte() else '?'.code.toByte() }
            .take(255).toByteArray()
        e.writeByte(ascii.size)
        e.write(ascii)
        e.write(ByteArray((4 - (ascii.size + 1) % 4) % 4))
        // Unicode name.
        val uni = r.name
        var uniLen = 4 + uni.length * 2
        val uniPad = (4 - uniLen % 4) % 4
        uniLen += uniPad
        e.writeBytes("8BIM"); e.writeBytes("luni")
        e.writeInt(uniLen)
        e.writeInt(uni.length)
        for (ch in uni) e.writeShort(ch.code)
        e.write(ByteArray(uniPad))
        if (r.section != 0) {
            e.writeBytes("8BIM"); e.writeBytes("lsct")
            e.writeInt(12)
            e.writeInt(r.section)
            e.writeBytes("8BIM")
            e.writeBytes(if (r.section == 3) "pass" else r.key)
        }
        e.flush()
        o.writeInt(extra.size())
        extra.writeTo(o)
    }

    // ================================================================== read

    /** Big-endian reads over a [RandomAccessFile], through a small buffer. */
    private class Reader(val raf: RandomAccessFile) {
        private val buf = ByteArray(8)
        var pos: Long
            get() = raf.filePointer
            set(v) = raf.seek(v)
        fun u8(): Int = raf.readUnsignedByte()
        fun u16(): Int = raf.readUnsignedShort()
        fun i16(): Int = raf.readShort().toInt()
        fun i32(): Int = raf.readInt()
        fun u32(): Long = raf.readInt().toLong() and 0xFFFFFFFFL
        fun u64(): Long = raf.readLong()
        fun str(n: Int): String {
            raf.readFully(buf, 0, n)
            return String(buf, 0, n, Charsets.ISO_8859_1)
        }
        fun bytes(n: Int): ByteArray = ByteArray(n).also { raf.readFully(it) }
        fun skip(n: Long) { pos += n }
    }

    private class Chan(val id: Int, val length: Long) {
        var offset = 0L
    }

    private class Rec(
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val chans: List<Chan>,
        val key: String,
        val opacity: Int,
        val clipping: Boolean,
        val hidden: Boolean
    ) {
        var name = ""
        var section = 0
        var sectionKey: String? = null
        var fill = 255
        /** An adjustment or fill layer (no pixels of its own to bring over). */
        var adjustment = false
        var maskRect: Rect? = null
        var maskDefault = 255
        var maskDisabled = false
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * One channel's rows, decoded on demand: raw and PackBits are read in
     * place; ZIP is inflated once to a scratch file (undoing the prediction)
     * and then read as raw.
     */
    private class Rows(
        private val raf: RandomAccessFile,
        val width: Int,
        val height: Int,
        private val bps: Int,
        compression: Int,
        dataStart: Long,
        dataLength: Long,
        counts: LongArray?,
        scratch: File
    ) {
        private val rowBytes = width * bps
        private var rawStart = dataStart
        private var rawFile: RandomAccessFile? = null
        private var offsets: LongArray? = null
        private val packed: ByteArray
        private val raw = ByteArray(rowBytes)

        init {
            when (compression) {
                0 -> Unit
                1 -> {
                    val c = counts!!
                    val o = LongArray(height + 1)
                    o[0] = dataStart
                    var biggest = 0L
                    for (y in 0 until height) {
                        o[y + 1] = o[y] + c[y]
                        biggest = max(biggest, c[y])
                    }
                    offsets = o
                }
                2, 3 -> {
                    val f = File(scratch, "z${System.nanoTime()}")
                    inflateTo(raf, dataStart, dataLength, f, compression == 3)
                    rawFile = RandomAccessFile(f, "r")
                    f.deleteOnExit()
                    rawStart = 0L
                }
                else -> throw PsdException("This PSD uses a compression Yume can’t read.")
            }
            packed = ByteArray(if (compression == 1) max(16, (counts?.maxOrNull() ?: 0L).toInt()) else 1)
        }

        private fun inflateTo(raf: RandomAccessFile, start: Long, length: Long, f: File, predicted: Boolean) {
            val inf = Inflater()
            val inBuf = ByteArray(1 shl 16)
            val row = ByteArray(rowBytes)
            var filled = 0
            var left = length
            raf.seek(start)
            BufferedOutputStream(FileOutputStream(f), 1 shl 16).use { o ->
                while (!inf.finished()) {
                    if (inf.needsInput()) {
                        if (left <= 0) break
                        val n = raf.read(inBuf, 0, min(inBuf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        left -= n
                        inf.setInput(inBuf, 0, n)
                    }
                    val k = inf.inflate(row, filled, rowBytes - filled)
                    filled += k
                    if (filled == rowBytes) {
                        if (predicted) unpredict(row)
                        o.write(row)
                        filled = 0
                    } else if (k == 0 && inf.needsInput() && left <= 0) {
                        break
                    }
                }
            }
            inf.end()
        }

        private fun unpredict(row: ByteArray) {
            if (bps == 1) {
                for (i in 1 until row.size) row[i] = (row[i] + row[i - 1]).toByte()
            } else {
                var prev = ((row[0].toInt() and 255) shl 8) or (row[1].toInt() and 255)
                var i = 2
                while (i + 1 < row.size) {
                    val v = (prev + (((row[i].toInt() and 255) shl 8) or (row[i + 1].toInt() and 255))) and 0xFFFF
                    row[i] = (v ushr 8).toByte()
                    row[i + 1] = v.toByte()
                    prev = v
                    i += 2
                }
            }
        }

        /** Row [y] as 8-bit samples into [out] (width bytes). */
        fun read(y: Int, out: ByteArray) {
            val o = offsets
            if (o != null) {
                val n = (o[y + 1] - o[y]).toInt()
                raf.seek(o[y])
                raf.readFully(packed, 0, n)
                unpackBits(packed, n, raw, rowBytes)
            } else {
                val f = rawFile ?: raf
                f.seek(rawStart + y.toLong() * rowBytes)
                f.readFully(raw, 0, rowBytes)
            }
            if (bps == 1) {
                System.arraycopy(raw, 0, out, 0, width)
            } else {
                for (x in 0 until width) out[x] = raw[x * bps]
            }
        }

        fun close() {
            rawFile?.close()
        }
    }

    private class Doc(
        val width: Int,
        val height: Int,
        val channels: Int,
        val depth: Int,
        val mode: Int,
        val psb: Boolean,
        val records: List<Rec>,
        val mergedStart: Long
    )

    private fun parse(r: Reader): Doc {
        if (r.str(4) != "8BPS") throw PsdException("That file is not a Photoshop document.")
        val version = r.u16()
        if (version != 1 && version != 2) throw PsdException("This PSD version is not supported.")
        val psb = version == 2
        r.skip(6)
        val channels = r.u16()
        val height = r.i32()
        val width = r.i32()
        val depth = r.u16()
        val mode = r.u16()
        if (mode != 3 && mode != 1) throw PsdException("Only RGB and greyscale PSDs can be opened (this one is ${modeName(mode)}).")
        if (depth != 8 && depth != 16) throw PsdException("Only 8- and 16-bit PSDs can be opened (this one is $depth-bit).")
        if (width <= 0 || height <= 0) throw PsdException("This PSD has no pixels.")
        if (width > MemoryBudget.MAX_SPARSE_EDGE || height > MemoryBudget.MAX_SPARSE_EDGE) {
            throw PsdException("This PSD is ${width}×$height; Yume opens up to ${MemoryBudget.MAX_SPARSE_EDGE} px a side.")
        }
        r.skip(r.u32()) // colour mode data
        r.skip(r.u32()) // image resources
        val sectionLen = if (psb) r.u64() else r.u32()
        val sectionStart = r.pos
        val sectionEnd = sectionStart + sectionLen
        var records: List<Rec> = emptyList()
        if (sectionLen > 0) {
            val infoLen = if (psb) r.u64() else r.u32()
            val infoStart = r.pos
            if (infoLen > 0) records = parseLayerInfo(r, psb)
            r.pos = infoStart + infoLen
            // 16-bit documents keep their layers in an "Lr16" block instead.
            if (records.isEmpty() && r.pos + 4 <= sectionEnd) {
                val gm = r.u32()
                r.skip(gm)
                while (r.pos + 12 <= sectionEnd) {
                    val sig = r.str(4)
                    if (sig != "8BIM" && sig != "8B64") break
                    val key = r.str(4)
                    val len = if (psb && key in LONG_KEYS) r.u64() else r.u32()
                    val start = r.pos
                    if (key == "Lr16" || key == "Layr") {
                        records = parseLayerInfo(r, psb)
                        break
                    }
                    r.pos = start + len + (len and 1L)
                }
            }
        }
        return Doc(width, height, channels, depth, mode, psb, records, sectionEnd)
    }

    /** Tagged blocks that make a layer an adjustment or fill layer. */
    private val ADJUSTMENT_KEYS = setOf(
        "SoCo", "GdFl", "PtFl", "brit", "levl", "curv", "expA", "vibA", "hue ", "hue2", "blnc",
        "blwh", "phfl", "mixr", "clrL", "nvrt", "post", "thrs", "grdm", "selc"
    )

    private val LONG_KEYS = setOf("LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD")

    private fun modeName(mode: Int) = when (mode) {
        0 -> "bitmap"; 2 -> "indexed colour"; 4 -> "CMYK"; 7 -> "multichannel"; 8 -> "duotone"; 9 -> "Lab"
        else -> "mode $mode"
    }

    private fun parseLayerInfo(r: Reader, psb: Boolean): List<Rec> {
        val count = kotlin.math.abs(r.i16())
        val recs = ArrayList<Rec>(count)
        for (i in 0 until count) {
            val top = r.i32(); val left = r.i32(); val bottom = r.i32(); val right = r.i32()
            val nch = r.u16()
            val chans = (0 until nch).map { Chan(r.i16(), if (psb) r.u64() else r.u32()) }
            r.str(4) // 8BIM
            val key = r.str(4)
            val opacity = r.u8()
            val clipping = r.u8() != 0
            val flags = r.u8()
            r.u8()
            val rec = Rec(top, left, bottom, right, chans, key, opacity, clipping, (flags and 0x02) != 0)
            val extraLen = r.u32()
            val extraEnd = r.pos + extraLen
            val maskLen = r.u32()
            val maskStart = r.pos
            if (maskLen >= 20) {
                val mt = r.i32(); val ml = r.i32(); val mb = r.i32(); val mr = r.i32()
                rec.maskDefault = r.u8()
                val mflags = r.u8()
                rec.maskDisabled = (mflags and 0x02) != 0
                rec.maskRect = Rect(ml, mt, mr, mb)
            }
            r.pos = maskStart + maskLen
            r.skip(r.u32()) // blending ranges
            val nameLen = r.u8()
            rec.name = String(r.bytes(nameLen), Charsets.ISO_8859_1)
            r.skip(((4 - (nameLen + 1) % 4) % 4).toLong())
            while (r.pos + 12 <= extraEnd) {
                val sig = r.str(4)
                if (sig != "8BIM" && sig != "8B64") break
                val k = r.str(4)
                val len = if (psb && k in LONG_KEYS) r.u64() else r.u32()
                val start = r.pos
                when (k) {
                    "luni" -> {
                        val n = r.u32().toInt().coerceIn(0, 4096)
                        val sb = StringBuilder()
                        for (c in 0 until n) sb.append(r.u16().toChar())
                        rec.name = sb.toString().trimEnd('\u0000')
                    }
                    "lsct", "lsdk" -> {
                        rec.section = r.u32().toInt()
                        if (len >= 12) {
                            r.str(4)
                            rec.sectionKey = r.str(4)
                        }
                    }
                    "iOpa" -> rec.fill = r.u8()
                    in ADJUSTMENT_KEYS -> rec.adjustment = true
                }
                r.pos = start + len
            }
            r.pos = extraEnd
            recs.add(rec)
        }
        // Channel data follows the records, layer by layer, channel by channel.
        var p = r.pos
        for (rec in recs) for (c in rec.chans) {
            c.offset = p
            p += c.length
        }
        r.pos = p
        return recs
    }

    /** Opens the rows of one layer channel (starting with its compression field). */
    private fun channelRows(
        r: Reader, doc: Doc, c: Chan, w: Int, h: Int, scratch: File
    ): Rows? {
        if (w <= 0 || h <= 0 || c.length < 2) return null
        r.pos = c.offset
        val comp = r.u16()
        var counts: LongArray? = null
        var data = c.offset + 2
        if (comp == 1) {
            val wide = doc.psb
            val table = r.bytes(h * if (wide) 4 else 2)
            counts = LongArray(h) { y ->
                if (wide) java.nio.ByteBuffer.wrap(table, y * 4, 4).int.toLong() and 0xFFFFFFFFL
                else ((table[y * 2].toInt() and 255) shl 8 or (table[y * 2 + 1].toInt() and 255)).toLong()
            }
            data += table.size
        }
        return Rows(r.raf, w, h, doc.depth / 8, comp, data, c.offset + c.length - data, counts, scratch)
    }

    /** The merged image's channels (R, G, B, and alpha when there is one). */
    private fun mergedRows(r: Reader, doc: Doc, scratch: File): List<Rows>? = runCatching {
        r.pos = doc.mergedStart
        val comp = r.u16()
        val n = doc.channels
        val h = doc.height
        val w = doc.width
        val bps = doc.depth / 8
        val rows = ArrayList<Rows>()
        when (comp) {
            0 -> for (c in 0 until n) {
                rows.add(Rows(r.raf, w, h, bps, 0, doc.mergedStart + 2 + c.toLong() * w * h * bps, 0, null, scratch))
            }
            1 -> {
                val wide = doc.psb
                val table = r.bytes(n * h * if (wide) 4 else 2)
                var data = doc.mergedStart + 2 + table.size
                for (c in 0 until n) {
                    val counts = LongArray(h) { y ->
                        val i = c * h + y
                        if (wide) java.nio.ByteBuffer.wrap(table, i * 4, 4).int.toLong() and 0xFFFFFFFFL
                        else ((table[i * 2].toInt() and 255) shl 8 or (table[i * 2 + 1].toInt() and 255)).toLong()
                    }
                    rows.add(Rows(r.raf, w, h, bps, 1, data, 0, counts, scratch))
                    data += counts.sum()
                }
            }
            else -> return@runCatching null
        }
        rows
    }.getOrNull()

    /** A layer as it lands in Yume. */
    private class Planned(
        val rec: Rec?,
        var name: String,
        var opacity: Float,
        val blend: LayerBlend,
        var visible: Boolean,
        val clipping: Boolean,
        var groupId: Long = 0L
    )

    /**
     * Reads the PSD [file] into a new artwork called [title] and returns its
     * project id. Throws [PsdException] with a reason the user can read.
     */
    fun importAsProject(store: ProjectStore, file: File, title: String): String {
        if (!TileStoreConfig.useSparseLayers) throw PsdException("PSD import needs tiled layers.")
        val scratch = File(store.cacheRoot(), "psd-in-${System.nanoTime()}").apply { mkdirs() }
        val id = java.util.UUID.randomUUID().toString()
        val dir = store.projectDir(id)
        try {
            RandomAccessFile(file, "r").use { raf ->
                val r = Reader(raf)
                val doc = parse(r)
                val w = doc.width
                val h = doc.height
                if (MemoryBudget.freeDisk() < file.length() * 2 + MemoryBudget.DISK_HEADROOM) {
                    throw PsdException("Not enough free storage to open this PSD.")
                }
                dir.mkdirs()

                // Layers bottom to top, groups from their divider / folder records.
                val planned = ArrayList<Planned>()
                val groups = ArrayList<GroupSnapshotData>()
                val stack = ArrayList<MutableList<Planned>>()
                for (rec in doc.records) {
                    when (rec.section) {
                        3 -> stack.add(ArrayList())
                        1, 2 -> {
                            val members = if (stack.isEmpty()) continue else stack.removeAt(stack.size - 1)
                            val op = rec.opacity / 255f
                            if (stack.isEmpty()) {
                                // Yume groups do not nest: the outermost group keeps its
                                // members, inner ones fold their opacity and visibility in.
                                val gid = (groups.size + 1).toLong()
                                groups.add(
                                    GroupSnapshotData(
                                        gid, rec.name.ifBlank { "Group" }, op,
                                        blendOf(rec.sectionKey ?: rec.key), !rec.hidden, false, rec.section == 1
                                    )
                                )
                                members.forEach { it.groupId = gid }
                            } else {
                                members.forEach {
                                    it.opacity *= op
                                    if (rec.hidden) it.visible = false
                                }
                                stack[stack.size - 1].addAll(members)
                            }
                        }
                        else -> {
                            // Adjustment and fill layers have no pixels Yume can use.
                            if (rec.adjustment) continue
                            val p = Planned(
                                rec, rec.name.ifBlank { "Layer ${planned.size + 1}" },
                                rec.opacity / 255f * rec.fill / 255f, blendOf(rec.key), !rec.hidden, rec.clipping
                            )
                            planned.add(p)
                            stack.lastOrNull()?.add(p)
                        }
                    }
                }
                // A layer clipped to nothing (its base was skipped) is not clipped.
                if (planned.isNotEmpty() && planned[0].clipping) planned[0] = planned[0].let {
                    Planned(it.rec, it.name, it.opacity, it.blend, it.visible, false, it.groupId)
                }

                val merged = mergedRows(r, doc, scratch)
                val ts = TileStoreConfig.tileSize
                val layersOut = ArrayList<LayerSnapshotData>()
                if (planned.isEmpty()) {
                    // A flattened PSD: its merged image is the one layer.
                    val m = merged ?: throw PsdException("This PSD has no layers and no picture to read.")
                    val f = File(dir, "layer_0.${TileFile.EXT}")
                    val alpha = if (doc.channels >= 4 && doc.mode == 3) m[3] else if (doc.channels >= 2 && doc.mode == 1) m[1] else null
                    val colour = if (doc.mode == 1) listOf(m[0], m[0], m[0]) else m.take(3)
                    writeLayer(f, w, h, ts, Rect(0, 0, w, h), alpha, colour)
                    layersOut.add(LayerSnapshotData("Background", null, 1f, LayerBlend.Normal, true, false, file = f))
                } else {
                    for ((i, p) in planned.withIndex()) {
                        Exporter.progress = i.toFloat() / planned.size
                        val rec = p.rec!!
                        val bounds = Rect(rec.left, rec.top, rec.right, rec.bottom)
                        fun rows(id: Int) = rec.chans.firstOrNull { it.id == id }
                            ?.let { channelRows(r, doc, it, rec.width, rec.height, scratch) }
                        val alpha = rows(-1)
                        val colour = if (doc.mode == 1) {
                            val g = rows(0)
                            listOf(g, g, g)
                        } else listOf(rows(0), rows(1), rows(2))
                        val f = File(dir, "layer_$i.${TileFile.EXT}")
                        writeLayer(f, w, h, ts, bounds, alpha, colour)
                        alpha?.close(); colour.toSet().forEach { it?.close() }
                        var maskFile: File? = null
                        val mr = rec.maskRect
                        val mc = rec.chans.firstOrNull { it.id == -2 }
                        if (mr != null && mc != null) {
                            val mrows = channelRows(r, doc, mc, mr.width(), mr.height(), scratch)
                            val mf = File(dir, "mask_$i.${TileFile.EXT}")
                            writeMask(mf, w, h, ts, mr, rec.maskDefault, mrows)
                            mrows?.close()
                            maskFile = mf
                        }
                        layersOut.add(
                            LayerSnapshotData(
                                p.name, null, p.opacity.coerceIn(0f, 1f), p.blend, p.visible, false,
                                clipping = p.clipping, maskFile = maskFile,
                                maskEnabled = !rec.maskDisabled, groupId = p.groupId, file = f
                            )
                        )
                    }
                }

                // The thumbnail comes from the PSD's own merged picture.
                val thumb: ((Int) -> Bitmap?)? = merged?.let { m ->
                    { maxPx: Int -> runCatching { thumbOf(doc, m, maxPx) }.getOrNull() }
                }
                val snapshot = DocumentSnapshot(
                    title = title, width = w, height = h, background = 0,
                    activeIndex = layersOut.size - 1, layers = layersOut,
                    groups = groups, composeThumb = thumb
                )
                store.saveProject(id, snapshot)
                merged?.forEach { it.close() }
                Exporter.progress = 1f
            }
            return id
        } catch (e: Throwable) {
            dir.deleteRecursively()
            if (e is PsdException) throw e
            if (e is OutOfMemoryError) throw PsdException("Not enough memory to open this PSD.")
            throw PsdException("This PSD could not be read (${e.javaClass.simpleName}).")
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** Writes one layer's tile file from its channel rows ([bounds] in document px). */
    private fun writeLayer(
        f: File, w: Int, h: Int, ts: Int, bounds: Rect,
        alpha: Rows?, colour: List<Rows?>
    ) {
        val vis = Rect(bounds)
        if (!vis.intersect(0, 0, w, h)) vis.setEmpty()
        val lw = bounds.width()
        val chanRow = Array(4) { ByteArray(max(1, lw)) }
        val cols = (w + ts - 1) / ts
        val band = IntArray(ts * w)
        val tiles = HashMap<Long, Bitmap>()
        try {
            TileFile.write(f, w, h, ts, 0) { ty ->
                val out = Array(cols) { TileFile.TileData(TileFile.KIND_DEFAULT, null) }
                val y0 = ty * ts
                val th = min(ts, h - y0)
                val ly0 = max(y0, vis.top)
                val ly1 = min(y0 + th, vis.bottom)
                if (vis.isEmpty || ly0 >= ly1) return@write out
                java.util.Arrays.fill(band, 0, th * w, 0)
                for (y in ly0 until ly1) {
                    val sy = y - bounds.top
                    if (alpha != null) alpha.read(sy, chanRow[0]) else java.util.Arrays.fill(chanRow[0], 255.toByte())
                    for (c in 0 until 3) {
                        val rows = colour[c]
                        if (rows != null) rows.read(sy, chanRow[c + 1]) else java.util.Arrays.fill(chanRow[c + 1], 0)
                    }
                    val base = (y - y0) * w
                    for (x in vis.left until vis.right) {
                        val sx = x - bounds.left
                        band[base + x] = ((chanRow[0][sx].toInt() and 255) shl 24) or
                            ((chanRow[1][sx].toInt() and 255) shl 16) or
                            ((chanRow[2][sx].toInt() and 255) shl 8) or
                            (chanRow[3][sx].toInt() and 255)
                    }
                }
                for (tx in vis.left / ts..(vis.right - 1) / ts) {
                    val x0 = tx * ts
                    val tw = min(ts, w - x0)
                    var any = false
                    loop@ for (k in 0 until th) {
                        val b = k * w + x0
                        for (x in 0 until tw) if (band[b + x] ushr 24 != 0) { any = true; break@loop }
                    }
                    if (!any) continue
                    val key = (tw.toLong() shl 32) or th.toLong()
                    val bmp = tiles.getOrPut(key) { Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888) }
                    bmp.setPixels(band, x0, w, 0, 0, tw, th)
                    val bytes = ByteArray(bmp.byteCount)
                    bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
                    out[tx] = TileFile.TileData(TileFile.KIND_PIXELS, bytes)
                }
                out
            }
        } finally {
            tiles.values.forEach { it.recycle() }
        }
    }

    /** Writes a layer mask's tile file: grey levels, white (shown) by default. */
    private fun writeMask(f: File, w: Int, h: Int, ts: Int, rect: Rect, default: Int, rows: Rows?) {
        val cols = (w + ts - 1) / ts
        val band = IntArray(ts * w)
        val row = ByteArray(max(1, rect.width()))
        val outside = 0xFF000000.toInt() or (default * 0x010101)
        val white = 0xFFFFFFFF.toInt()
        val tiles = HashMap<Long, Bitmap>()
        try {
            TileFile.write(f, w, h, ts, white) { ty ->
                val out = Array(cols) { TileFile.TileData(TileFile.KIND_DEFAULT, null) }
                val y0 = ty * ts
                val th = min(ts, h - y0)
                java.util.Arrays.fill(band, 0, th * w, outside)
                for (y in max(y0, rect.top) until min(y0 + th, rect.bottom)) {
                    if (rows == null) break
                    rows.read(y - rect.top, row)
                    val base = (y - y0) * w
                    for (x in max(0, rect.left) until min(w, rect.right)) {
                        band[base + x] = 0xFF000000.toInt() or ((row[x - rect.left].toInt() and 255) * 0x010101)
                    }
                }
                for (tx in 0 until cols) {
                    val x0 = tx * ts
                    val tw = min(ts, w - x0)
                    var allWhite = true
                    loop@ for (k in 0 until th) {
                        val b = k * w + x0
                        for (x in 0 until tw) if (band[b + x] != white) { allWhite = false; break@loop }
                    }
                    if (allWhite) continue
                    val key = (tw.toLong() shl 32) or th.toLong()
                    val bmp = tiles.getOrPut(key) { Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888) }
                    bmp.setPixels(band, x0, w, 0, 0, tw, th)
                    val bytes = ByteArray(bmp.byteCount)
                    bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bytes))
                    out[tx] = TileFile.TileData(TileFile.KIND_PIXELS, bytes)
                }
                out
            }
        } finally {
            tiles.values.forEach { it.recycle() }
        }
    }

    /** The merged picture scaled to fit [maxPx], sampling every n-th row and column. */
    private fun thumbOf(doc: Doc, m: List<Rows>, maxPx: Int): Bitmap {
        val step = max(1, (max(doc.width, doc.height) + maxPx - 1) / maxPx)
        val tw = max(1, doc.width / step)
        val th = max(1, doc.height / step)
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val grey = doc.mode == 1
        val hasAlpha = if (grey) doc.channels >= 2 else doc.channels >= 4
        val rowBufs = Array(4) { ByteArray(doc.width) }
        val line = IntArray(tw)
        for (oy in 0 until th) {
            val y = oy * step
            if (grey) {
                m[0].read(y, rowBufs[0])
                if (hasAlpha) m[1].read(y, rowBufs[3])
            } else {
                for (c in 0 until 3) m[c].read(y, rowBufs[c])
                if (hasAlpha) m[3].read(y, rowBufs[3])
            }
            for (ox in 0 until tw) {
                val x = ox * step
                val a = if (hasAlpha) rowBufs[3][x].toInt() and 255 else 255
                val rr = rowBufs[0][x].toInt() and 255
                val gg = if (grey) rr else rowBufs[1][x].toInt() and 255
                val bb = if (grey) rr else rowBufs[2][x].toInt() and 255
                line[ox] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.setPixels(line, 0, tw, 0, oy, tw, 1)
        }
        return out
    }

    /** True when [file] starts like a Photoshop document. */
    fun isPsd(file: File): Boolean = runCatching {
        BufferedInputStream(FileInputStream(file)).use { i ->
            val b = ByteArray(4)
            i.read(b) == 4 && String(b, Charsets.ISO_8859_1) == "8BPS"
        }
    }.getOrDefault(false)
}
