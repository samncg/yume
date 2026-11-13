package com.yume.app.engine

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.min

/**
 * Streaming PNG encoder and decoder that work one horizontal band at a time
 * (Stage 3c), so neither saving nor opening a layer needs a canvas-sized bitmap.
 *
 * Pixels cross the boundary as unpremultiplied ARGB ints, the same values
 * `Bitmap.getPixels` returns and `Bitmap.setPixels` accepts. The encoder always
 * writes 8-bit RGBA (colour type 6), non-interlaced, adaptively filtered, which
 * every ordinary image tool reads. The decoder handles 8-bit greyscale, RGB,
 * palette, grey+alpha and RGBA without interlacing (everything this app and the
 * Android encoder write); anything else returns false so the caller can fall
 * back to a full decode.
 */
object BandedPng {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A
    )

    /** Fills [out] with rows y0 until y0+rows of unpremultiplied ARGB, stride = width. */
    fun interface BandReader {
        fun read(y0: Int, rows: Int, out: IntArray)
    }

    /** Receives rows y0 until y0+rows of unpremultiplied ARGB, stride = width. */
    fun interface BandSink {
        fun write(y0: Int, rows: Int, px: IntArray)
    }

    // ---------------------------------------------------------------- encoder

    /**
     * Writes a w x h RGBA PNG to [out], asking [reader] for [bandRows] rows at a
     * time. Peak memory is one band of ints plus a few rows of bytes.
     */
    fun encode(
        out: OutputStream,
        w: Int,
        h: Int,
        bandRows: Int,
        level: Int = 4,
        reader: BandReader
    ) {
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, w)
        putInt(ihdr, 4, h)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 6 // RGBA
        ihdr[10] = 0
        ihdr[11] = 0
        ihdr[12] = 0
        writeChunk(out, "IHDR", ihdr, ihdr.size)

        val idat = ChunkWriter(out, "IDAT", 1 shl 16)
        val deflater = Deflater(level)
        val stride = w * 4
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        val filtered = Array(5) { ByteArray(stride + 1) }
        val defBuf = ByteArray(1 shl 16)
        val band = IntArray(w * min(bandRows, h).coerceAtLeast(1))
        try {
            var y = 0
            while (y < h) {
                val rows = min(bandRows, h - y)
                reader.read(y, rows, band)
                for (r in 0 until rows) {
                    var o = 0
                    var i = r * w
                    for (x in 0 until w) {
                        val c = band[i++]
                        cur[o] = (c ushr 16).toByte()
                        cur[o + 1] = (c ushr 8).toByte()
                        cur[o + 2] = c.toByte()
                        cur[o + 3] = (c ushr 24).toByte()
                        o += 4
                    }
                    val best = filterRow(cur, prev, filtered, stride)
                    deflater.setInput(filtered[best], 0, stride + 1)
                    while (!deflater.needsInput()) {
                        val n = deflater.deflate(defBuf, 0, defBuf.size, Deflater.NO_FLUSH)
                        if (n > 0) idat.write(defBuf, 0, n)
                    }
                    val t = prev
                    prev = cur
                    cur = t
                }
                y += rows
            }
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(defBuf)
                if (n > 0) idat.write(defBuf, 0, n)
            }
            idat.flush()
        } finally {
            deflater.end()
        }
        writeChunk(out, "IEND", ByteArray(0), 0)
    }

    /**
     * Applies the five PNG filters and returns the index whose output has the
     * smallest sum of absolute signed bytes (the libpng heuristic).
     */
    private fun filterRow(cur: ByteArray, prev: ByteArray, f: Array<ByteArray>, stride: Int): Int {
        val bpp = 4
        for (t in 0..4) f[t][0] = t.toByte()
        var s0 = 0L
        var s1 = 0L
        var s2 = 0L
        var s3 = 0L
        var s4 = 0L
        for (i in 0 until stride) {
            val x = cur[i].toInt() and 0xFF
            val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            val v0 = x
            val v1 = (x - a) and 0xFF
            val v2 = (x - b) and 0xFF
            val v3 = (x - ((a + b) ushr 1)) and 0xFF
            val v4 = (x - paeth(a, b, c)) and 0xFF
            f[0][i + 1] = v0.toByte()
            f[1][i + 1] = v1.toByte()
            f[2][i + 1] = v2.toByte()
            f[3][i + 1] = v3.toByte()
            f[4][i + 1] = v4.toByte()
            s0 += abs(v0.toByte().toInt())
            s1 += abs(v1.toByte().toInt())
            s2 += abs(v2.toByte().toInt())
            s3 += abs(v3.toByte().toInt())
            s4 += abs(v4.toByte().toInt())
        }
        var best = 0
        var bs = s0
        if (s1 < bs) { best = 1; bs = s1 }
        if (s2 < bs) { best = 2; bs = s2 }
        if (s3 < bs) { best = 3; bs = s3 }
        if (s4 < bs) { best = 4 }
        return best
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private class ChunkWriter(val out: OutputStream, type: String, cap: Int) {
        private val typeBytes = type.toByteArray(Charsets.US_ASCII)
        private val buf = ByteArray(cap)
        private var n = 0
        fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                val k = min(l, buf.size - n)
                System.arraycopy(b, o, buf, n, k)
                n += k
                o += k
                l -= k
                if (n == buf.size) flush()
            }
        }
        fun flush() {
            if (n == 0) return
            writeChunk(out, typeBytes, buf, n)
            n = 0
        }
    }

    private fun writeChunk(out: OutputStream, type: String, data: ByteArray, len: Int) =
        writeChunk(out, type.toByteArray(Charsets.US_ASCII), data, len)

    private fun writeChunk(out: OutputStream, type: ByteArray, data: ByteArray, len: Int) {
        val head = ByteArray(8)
        putInt(head, 0, len)
        System.arraycopy(type, 0, head, 4, 4)
        out.write(head)
        out.write(data, 0, len)
        val crc = CRC32()
        crc.update(type, 0, 4)
        crc.update(data, 0, len)
        val tail = ByteArray(4)
        putInt(tail, 0, crc.value.toInt())
        out.write(tail)
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    // ---------------------------------------------------------------- decoder

    class Header(val width: Int, val height: Int)

    /** Reads only the IHDR size, or null when [file] is not a PNG. */
    fun readSize(file: File): Header? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { din ->
            val sig = ByteArray(8)
            din.readFully(sig)
            if (!sig.contentEquals(SIGNATURE)) return null
            val len = din.readInt()
            val type = ByteArray(4)
            din.readFully(type)
            if (String(type, Charsets.US_ASCII) != "IHDR" || len != 13) return null
            Header(din.readInt(), din.readInt())
        }
    }.getOrNull()

    /**
     * Decodes [file] band by band into [sink]. Returns false (without having
     * called [sink]) when the format is one this decoder does not handle, so
     * the caller can fall back. Throws [IOException] on a corrupt file.
     */
    fun decode(file: File, bandRows: Int, sink: BandSink): Boolean {
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { din ->
            val sig = ByteArray(8)
            din.readFully(sig)
            if (!sig.contentEquals(SIGNATURE)) return false
            var w = 0
            var h = 0
            var colorType = -1
            var palette: IntArray? = null
            var trns: ByteArray? = null
            val inflater = Inflater()
            try {
                var bpp = 0
                var stride = 0
                var prev = ByteArray(0)
                var cur = ByteArray(0)
                var rowFill = 0
                var y = 0
                var band = IntArray(0)
                var bandStart = 0
                var bandFill = 0
                val inBuf = ByteArray(1 shl 16)
                var started = false
                val crc = CRC32()

                fun emitRow() {
                    val f = cur[0].toInt()
                    unfilter(f, cur, prev, stride, bpp)
                    val o = bandFill * w
                    toArgb(cur, o, band, w, colorType, palette, trns)
                    bandFill++
                    if (bandFill == min(bandRows, h - bandStart)) {
                        sink.write(bandStart, bandFill, band)
                        bandStart += bandFill
                        bandFill = 0
                    }
                    val t = prev
                    prev = cur
                    cur = t
                    y++
                }

                while (true) {
                    val len = try {
                        din.readInt()
                    } catch (e: EOFException) {
                        throw IOException("PNG ended before IEND")
                    }
                    if (len < 0) throw IOException("bad chunk length")
                    val typeB = ByteArray(4)
                    din.readFully(typeB)
                    val type = String(typeB, Charsets.US_ASCII)
                    crc.reset()
                    crc.update(typeB)
                    if (type == "IDAT") {
                        if (!started) {
                            if (w <= 0 || h <= 0) throw IOException("IDAT before IHDR")
                            bpp = when (colorType) {
                                0, 3 -> 1
                                4 -> 2
                                2 -> 3
                                6 -> 4
                                else -> return false
                            }
                            if (colorType == 3 && palette == null) throw IOException("palette PNG without PLTE")
                            stride = w * bpp
                            prev = ByteArray(stride + 1)
                            cur = ByteArray(stride + 1)
                            band = IntArray(w * min(bandRows, h))
                            started = true
                        }
                        var left = len
                        while (left > 0) {
                            val k = din.read(inBuf, 0, min(left, inBuf.size))
                            if (k < 0) throw IOException("truncated IDAT")
                            crc.update(inBuf, 0, k)
                            left -= k
                            inflater.setInput(inBuf, 0, k)
                            // Drain until inflate produces nothing: zlib can hold
                            // decoded output even after it consumed all input.
                            while (!inflater.finished() && y < h) {
                                val n = try {
                                    inflater.inflate(cur, rowFill, stride + 1 - rowFill)
                                } catch (e: java.util.zip.DataFormatException) {
                                    throw IOException("corrupt IDAT: ${e.message}")
                                }
                                if (n == 0) {
                                    if (inflater.needsDictionary()) throw IOException("dictionary")
                                    break
                                }
                                rowFill += n
                                if (rowFill == stride + 1) {
                                    rowFill = 0
                                    emitRow()
                                }
                            }
                        }
                        if (din.readInt() != crc.value.toInt()) throw IOException("IDAT crc mismatch")
                        continue
                    }
                    val data = ByteArray(len)
                    din.readFully(data)
                    crc.update(data)
                    if (din.readInt() != crc.value.toInt()) throw IOException("$type crc mismatch")
                    when (type) {
                        "IHDR" -> {
                            w = getInt(data, 0)
                            h = getInt(data, 4)
                            val depth = data[8].toInt() and 0xFF
                            colorType = data[9].toInt() and 0xFF
                            val interlace = data[12].toInt() and 0xFF
                            if (depth != 8 || interlace != 0) return false
                            if (colorType !in intArrayOf(0, 2, 3, 4, 6)) return false
                        }
                        "PLTE" -> {
                            val n = len / 3
                            palette = IntArray(n) { i ->
                                (0xFF shl 24) or ((data[i * 3].toInt() and 0xFF) shl 16) or
                                    ((data[i * 3 + 1].toInt() and 0xFF) shl 8) or
                                    (data[i * 3 + 2].toInt() and 0xFF)
                            }
                        }
                        "tRNS" -> trns = data
                        // A real colour profile would make the platform decoder
                        // convert colours; keep its behaviour by falling back.
                        "iCCP", "cICP", "cHRM" -> if (!started) return false
                        "IEND" -> {
                            if (y < h) throw IOException("PNG has $y of $h rows")
                            if (bandFill > 0) sink.write(bandStart, bandFill, band)
                            return true
                        }
                    }
                }
            } finally {
                inflater.end()
            }
        }
    }

    private fun getInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** In-place unfilter of one row ([row][0] is the filter byte). */
    private fun unfilter(f: Int, row: ByteArray, prev: ByteArray, stride: Int, bpp: Int) {
        when (f) {
            0 -> {}
            1 -> for (i in 1 + bpp..stride) row[i] = (row[i] + row[i - bpp]).toByte()
            2 -> for (i in 1..stride) row[i] = (row[i] + prev[i]).toByte()
            3 -> for (i in 1..stride) {
                val a = if (i > bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                row[i] = (row[i] + ((a + b) ushr 1)).toByte()
            }
            4 -> for (i in 1..stride) {
                val a = if (i > bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i > bpp) prev[i - bpp].toInt() and 0xFF else 0
                row[i] = (row[i] + paeth(a, b, c)).toByte()
            }
            else -> throw IOException("bad filter $f")
        }
    }

    private fun toArgb(
        row: ByteArray, out0: Int, out: IntArray, w: Int,
        colorType: Int, palette: IntArray?, trns: ByteArray?
    ) {
        var o = out0
        var i = 1
        when (colorType) {
            6 -> for (x in 0 until w) {
                out[o++] = ((row[i + 3].toInt() and 0xFF) shl 24) or
                    ((row[i].toInt() and 0xFF) shl 16) or
                    ((row[i + 1].toInt() and 0xFF) shl 8) or (row[i + 2].toInt() and 0xFF)
                i += 4
            }
            2 -> {
                val key = if (trns != null && trns.size >= 6) {
                    ((trns[1].toInt() and 0xFF) shl 16) or ((trns[3].toInt() and 0xFF) shl 8) or
                        (trns[5].toInt() and 0xFF)
                } else -1
                for (x in 0 until w) {
                    val rgb = ((row[i].toInt() and 0xFF) shl 16) or
                        ((row[i + 1].toInt() and 0xFF) shl 8) or (row[i + 2].toInt() and 0xFF)
                    out[o++] = if (rgb == key) rgb else (0xFF shl 24) or rgb
                    i += 3
                }
            }
            0 -> {
                val key = if (trns != null && trns.size >= 2) trns[1].toInt() and 0xFF else -1
                for (x in 0 until w) {
                    val g = row[i++].toInt() and 0xFF
                    val a = if (g == key) 0 else 0xFF
                    out[o++] = (a shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
            4 -> for (x in 0 until w) {
                val g = row[i].toInt() and 0xFF
                val a = row[i + 1].toInt() and 0xFF
                out[o++] = (a shl 24) or (g shl 16) or (g shl 8) or g
                i += 2
            }
            3 -> {
                val pal = palette!!
                for (x in 0 until w) {
                    val idx = row[i++].toInt() and 0xFF
                    val c = if (idx < pal.size) pal[idx] else 0xFF000000.toInt()
                    val a = if (trns != null && idx < trns.size) trns[idx].toInt() and 0xFF else 0xFF
                    out[o++] = (a shl 24) or (c and 0x00FFFFFF)
                }
            }
        }
    }
}
