package com.yume.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/**
 * Yume's tile layer file ("YTL1"), the on-disk form of a sparse layer or mask.
 *
 * A PNG has to be inflated top to bottom in full, even where the layer is
 * empty or one flat colour, so an 8K layer took seconds to open and a 16K one
 * many times that. This file stores the tile grid instead:
 *
 *     "YTL1" | int version | int width | int height | int tileSize | int default
 *     per tile, row by row:  byte kind
 *         0 = the default pixel          nothing follows
 *         1 = one colour throughout      4 raw pixel bytes
 *         2 = pixels                     int length, deflated raw bytes
 *     int CRC32 of everything after the magic
 *
 * Raw bytes are the tile's premultiplied memory exactly as [Bitmap] holds it,
 * so a save and reload is lossless (a PNG round trip is not, below full alpha).
 * Pixel tiles are compressed and decompressed independently, on several cores.
 * PNGs written by older versions still load (see [SparseTileStore]).
 */
object TileFile {
    private val MAGIC = byteArrayOf('Y'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 1
    const val KIND_DEFAULT = 0
    const val KIND_SOLID = 1
    const val KIND_PIXELS = 2

    /** Extension of tile layer files. */
    const val EXT = "ytl"

    private val workers by lazy {
        val n = max(2, min(4, Runtime.getRuntime().availableProcessors()))
        Executors.newFixedThreadPool(n) { r -> Thread(r, "yume-tiles").apply { isDaemon = true } }
    }

    fun isTileFile(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < MAGIC.size) return false
        FileInputStream(file).use { s ->
            val b = ByteArray(MAGIC.size)
            s.read(b) == MAGIC.size && b.contentEquals(MAGIC)
        }
    }.getOrDefault(false)

    /** One tile as the writer sees it: kind plus its raw bytes (4 for a solid). */
    class TileData(val kind: Int, val bytes: ByteArray?)

    /**
     * Writes [width] x [height] tiles to [file]. [row] returns one tile row's
     * data (it is called once per row, in order, and may read the live store
     * on its owner thread). Returns false if [row] fails.
     */
    fun write(
        file: File,
        width: Int,
        height: Int,
        tileSize: Int,
        defaultPixel: Int,
        row: (ty: Int) -> Array<TileData>?
    ): Boolean {
        val countY = (height + tileSize - 1) / tileSize
        val crc = CRC32()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { raw ->
            raw.write(MAGIC)
            val out = DataOutputStream(CheckedOutputStream(raw, crc))
            out.writeInt(VERSION)
            out.writeInt(width)
            out.writeInt(height)
            out.writeInt(tileSize)
            out.writeInt(defaultPixel)
            for (ty in 0 until countY) {
                val tiles = row(ty) ?: return false
                // Compress the row's pixel tiles in parallel, write in order.
                val packed = tiles.map { t ->
                    if (t.kind == KIND_PIXELS) workers.submit(Callable { deflate(t.bytes!!) }) else null
                }
                for ((i, t) in tiles.withIndex()) {
                    out.writeByte(t.kind)
                    when (t.kind) {
                        KIND_SOLID -> out.write(t.bytes!!, 0, 4)
                        KIND_PIXELS -> {
                            val z = packed[i]!!.get()
                            out.writeInt(z.size)
                            out.write(z)
                        }
                    }
                }
            }
            out.flush()
            raw.writeInt(crc.value.toInt())
        }
        return true
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED)
        try {
            d.setInput(bytes)
            d.finish()
            val out = java.io.ByteArrayOutputStream(bytes.size / 4 + 64)
            val buf = ByteArray(1 shl 15)
            while (!d.finished()) {
                val n = d.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun inflate(z: ByteArray, size: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(z)
            val out = ByteArray(size)
            var n = 0
            while (n < size) {
                val k = inf.inflate(out, n, size - n)
                if (k == 0) {
                    if (inf.finished() || inf.needsInput()) break
                }
                n += k
            }
            if (n != size) throw IOException("tile data short: $n of $size")
            return out
        } finally {
            inf.end()
        }
    }

    class Header(val width: Int, val height: Int, val tileSize: Int, val defaultPixel: Int)

    /**
     * Reads [file], calling [sink] for every tile that is not the default with
     * its kind and raw bytes (4 for a solid, the full tile for pixels).
     * Returns the header, or null if the file is not a tile file or is
     * damaged (nothing is reported for a damaged file's later tiles, and the
     * CRC is checked before returning).
     */
    fun read(file: File, sink: (tx: Int, ty: Int, kind: Int, bytes: ByteArray) -> Unit): Header? {
        val crc = CRC32()
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { raw ->
            val m = ByteArray(MAGIC.size)
            raw.readFully(m)
            if (!m.contentEquals(MAGIC)) return null
            val inp = DataInputStream(CheckedInputStream(raw, crc))
            if (inp.readInt() != VERSION) return null
            val w = inp.readInt()
            val h = inp.readInt()
            val ts = inp.readInt()
            val def = inp.readInt()
            if (w <= 0 || h <= 0 || ts <= 0 || w > 65536 || h > 65536) return null
            val cx = (w + ts - 1) / ts
            val cy = (h + ts - 1) / ts
            for (ty in 0 until cy) {
                val th = min(ts, h - ty * ts)
                val jobs = ArrayList<Triple<Int, Int, java.util.concurrent.Future<ByteArray>?>>()
                val solids = HashMap<Int, ByteArray>()
                for (tx in 0 until cx) {
                    val tw = min(ts, w - tx * ts)
                    when (inp.readUnsignedByte()) {
                        KIND_DEFAULT -> Unit
                        KIND_SOLID -> {
                            val b = ByteArray(4)
                            inp.readFully(b)
                            solids[tx] = b
                            jobs.add(Triple(tx, KIND_SOLID, null))
                        }
                        KIND_PIXELS -> {
                            val n = inp.readInt()
                            if (n < 0 || n > tw * th * 8 + 1024) throw IOException("bad tile length")
                            val z = ByteArray(n)
                            inp.readFully(z)
                            val size = tw * th * 4
                            jobs.add(Triple(tx, KIND_PIXELS, workers.submit(Callable { inflate(z, size) })))
                        }
                        else -> throw IOException("bad tile kind")
                    }
                }
                for ((tx, kind, f) in jobs) {
                    sink(tx, ty, kind, if (kind == KIND_SOLID) solids[tx]!! else f!!.get())
                }
            }
            val want = crc.value.toInt()
            if (raw.readInt() != want) throw IOException("tile file checksum mismatch")
            return Header(w, h, ts, def)
        }
    }

    /**
     * Pulls the tiles of [file] one tile row at a time (streaming export).
     * [nextRow] fills [band] (width x that row's height, transparent where a
     * tile is empty) and returns the row's height, or 0 at the end.
     */
    class RowReader(file: File) : java.io.Closeable {
        private val raw = DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16))
        val width: Int
        val height: Int
        val tileSize: Int
        val defaultPixel: Int
        private var ty = 0
        private val paint = Paint().apply { blendMode = android.graphics.BlendMode.SRC }

        init {
            val m = ByteArray(MAGIC.size)
            raw.readFully(m)
            if (!m.contentEquals(MAGIC)) throw IOException("not a tile file")
            if (raw.readInt() != VERSION) throw IOException("unknown tile file version")
            width = raw.readInt()
            height = raw.readInt()
            tileSize = raw.readInt()
            defaultPixel = raw.readInt()
        }

        fun nextRow(band: Bitmap): Int {
            val rows = min(tileSize, height - ty * tileSize)
            if (rows <= 0) return 0
            val c = Canvas(band)
            c.drawColor(defaultPixel, android.graphics.BlendMode.SRC)
            val cx = (width + tileSize - 1) / tileSize
            for (tx in 0 until cx) {
                val tw = min(tileSize, width - tx * tileSize)
                val bytes: ByteArray
                val kind = raw.readUnsignedByte()
                when (kind) {
                    KIND_DEFAULT -> continue
                    KIND_SOLID -> { bytes = ByteArray(4); raw.readFully(bytes) }
                    KIND_PIXELS -> {
                        val z = ByteArray(raw.readInt())
                        raw.readFully(z)
                        bytes = inflate(z, tw * rows * 4)
                    }
                    else -> throw IOException("bad tile kind")
                }
                val t = tileBitmap(kind, bytes, tw, rows)
                c.drawBitmap(t, (tx * tileSize).toFloat(), 0f, paint)
                t.recycle()
            }
            ty++
            return rows
        }

        override fun close() = raw.close()
    }

    /** Reads just the header (size check before loading). */
    fun header(file: File): Header? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file), 64)).use { inp ->
            val m = ByteArray(MAGIC.size)
            inp.readFully(m)
            if (!m.contentEquals(MAGIC)) return null
            if (inp.readInt() != VERSION) return null
            Header(inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt())
        }
    }.getOrNull()

    /** A tile's raw bytes as a bitmap of [w] x [h] (solids repeat their 4 bytes). */
    fun tileBitmap(kind: Int, bytes: ByteArray, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val full = if (kind == KIND_SOLID) {
            val out = ByteArray(w * h * 4)
            var i = 0
            while (i < out.size) {
                out[i] = bytes[0]; out[i + 1] = bytes[1]; out[i + 2] = bytes[2]; out[i + 3] = bytes[3]
                i += 4
            }
            out
        } else bytes
        b.copyPixelsFromBuffer(ByteBuffer.wrap(full))
        return b
    }

    /** The whole layer as one bitmap (dense debug engine, OpenRaster export). */
    fun decodeFull(file: File): Bitmap? = runCatching {
        val head = header(file) ?: return null
        val out = Bitmap.createBitmap(head.width, head.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(head.defaultPixel)
        val c = Canvas(out)
        val p = Paint().apply { blendMode = android.graphics.BlendMode.SRC }
        read(file) { tx, ty, kind, bytes ->
            val ts = head.tileSize
            val tw = min(ts, head.width - tx * ts)
            val th = min(ts, head.height - ty * ts)
            val t = tileBitmap(kind, bytes, tw, th)
            c.drawBitmap(t, (tx * ts).toFloat(), (ty * ts).toFloat(), p)
            t.recycle()
        } ?: run { out.recycle(); return null }
        out
    }.getOrNull()

    /** Roughly [maxPx] on the long edge, for thumbnails. */
    fun decodeSampled(file: File, maxPx: Int): Bitmap? = runCatching {
        val head = header(file) ?: return null
        val scale = min(1f, maxPx.toFloat() / max(head.width, head.height))
        val ow = max(1, (head.width * scale).toInt())
        val oh = max(1, (head.height * scale).toInt())
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        out.eraseColor(head.defaultPixel)
        val c = Canvas(out)
        c.scale(ow.toFloat() / head.width, oh.toFloat() / head.height)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val fill = Paint()
        read(file) { tx, ty, kind, bytes ->
            val ts = head.tileSize
            val tw = min(ts, head.width - tx * ts)
            val th = min(ts, head.height - ty * ts)
            if (kind == KIND_SOLID) {
                // A flat tile is a rectangle (raw bytes are premultiplied RGBA).
                val a = bytes[3].toInt() and 0xFF
                if (a == 0) return@read
                fun un(v: Byte) = ((v.toInt() and 0xFF) * 255 / a).coerceAtMost(255)
                fill.color = android.graphics.Color.argb(a, un(bytes[0]), un(bytes[1]), un(bytes[2]))
                c.drawRect(Rect(tx * ts, ty * ts, tx * ts + tw, ty * ts + th), fill)
                return@read
            }
            val t = tileBitmap(kind, bytes, tw, th)
            c.drawBitmap(t, null, Rect(tx * ts, ty * ts, tx * ts + tw, ty * ts + th), p)
            t.recycle()
        } ?: run { out.recycle(); return null }
        out
    }.getOrNull()
}
