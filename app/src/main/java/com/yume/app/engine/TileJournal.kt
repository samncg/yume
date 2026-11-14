package com.yume.app.engine

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Crash-recovery tile journal (Stage 3c).
 *
 * Autosave appends only the tiles that changed since the previous autosave to
 * `journal/tiles.log`, then atomically replaces `journal/state.json`, which
 * names the layers, their base PNGs (from the last full save) and how many log
 * bytes belong to this autosave. Recovery replays the log up to that length on
 * top of the base PNGs, so a crash restores the document to its LAST AUTOSAVE.
 * A full save writes PNGs and deletes the journal; `saveSeq` in manifest.json
 * and state.json ties a journal to the PNG set it was written against.
 *
 * Record: 40-byte big-endian header
 *   magic 'YTJ1' | slot | tx | ty | w | h | kind | payloadLen | payloadCrc | headerCrc
 * kind TILE_RAW / TILE_DEFLATE carry `copyPixelsToBuffer` bytes, DEFAULT means
 * the tile now reads as the store default, RESET means the whole store was
 * cleared (fill / clear) before the records that follow.
 */
object TileJournal {

    const val MAGIC = 0x594A5431 // "YJT1"
    const val HEADER = 40
    const val TILE_RAW = 0
    const val TILE_DEFLATE = 1
    const val DEFAULT = 2
    const val RESET = 3
    /** A flat tile: the payload is its 4 raw pixel bytes. */
    const val TILE_SOLID = 4

    /** The 4 raw bytes of a flat tile whose pixels all read as [raw]. */
    fun solidBytes(raw: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(raw).array()

    /** One change captured on the owner thread ([raw] is a private copy). */
    class Change(
        val slot: Int,
        val tx: Int,
        val ty: Int,
        val kind: Int,
        val w: Int,
        val h: Int,
        val raw: ByteArray?
    )

    /** Raw pixel bytes of [tile], copied so the owner thread can keep painting. */
    fun copyRaw(tile: Bitmap): ByteArray {
        val buf = ByteBuffer.allocate(tile.byteCount)
        tile.copyPixelsToBuffer(buf)
        return buf.array()
    }

    /**
     * Appends [changes] to [log] and fsyncs it. Returns the new valid length.
     * Throws on any IO failure (the caller falls back to a full save).
     */
    fun append(log: File, changes: List<Change>): Long {
        log.parentFile?.mkdirs()
        val deflater = Deflater(1)
        val crc = CRC32()
        try {
            FileOutputStream(log, true).use { fos ->
                val out = java.io.BufferedOutputStream(fos, 1 shl 16)
                val head = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN)
                var pack = ByteArray(0)
                for (c in changes) {
                    var kind = c.kind
                    var payload: ByteArray = EMPTY
                    var len = 0
                    val raw = c.raw
                    if (c.kind == TILE_SOLID && raw != null) {
                        payload = raw
                        len = raw.size
                    } else if (raw != null) {
                        if (pack.size < raw.size + 64) pack = ByteArray(raw.size + 64)
                        deflater.reset()
                        deflater.setInput(raw)
                        deflater.finish()
                        var n = 0
                        while (!deflater.finished() && n < pack.size) {
                            n += deflater.deflate(pack, n, pack.size - n)
                        }
                        if (deflater.finished() && n < raw.size) {
                            kind = TILE_DEFLATE
                            payload = pack
                            len = n
                        } else {
                            kind = TILE_RAW
                            payload = raw
                            len = raw.size
                        }
                    }
                    crc.reset()
                    crc.update(payload, 0, len)
                    val pcrc = crc.value.toInt()
                    head.clear()
                    head.putInt(MAGIC).putInt(c.slot).putInt(c.tx).putInt(c.ty)
                        .putInt(c.w).putInt(c.h).putInt(kind).putInt(len).putInt(pcrc)
                    crc.reset()
                    crc.update(head.array(), 0, HEADER - 4)
                    head.putInt(crc.value.toInt())
                    out.write(head.array())
                    out.write(payload, 0, len)
                }
                out.flush()
                fos.fd.sync()
            }
        } finally {
            deflater.end()
        }
        return log.length()
    }

    /** Writes [text] to [target] through a synced temp file and an atomic rename. */
    fun writeAtomically(target: File, text: String) {
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("rename failed for ${target.name}")
        }
    }

    /** A replayed record: the caller installs it into the store for [slot]. */
    fun interface Visitor {
        /** [tile] is a pooled bitmap for TILE kinds (caller releases), null otherwise. */
        fun visit(slot: Int, tx: Int, ty: Int, kind: Int, tile: Bitmap?)
    }

    /**
     * Replays [log] up to [validLength] bytes. Returns the number of records
     * applied; throws [IOException] when a record inside the valid length is
     * corrupt (the caller then reports that recovery was incomplete).
     */
    fun replay(log: File, validLength: Long, visitor: Visitor): Int {
        if (validLength <= 0L || !log.exists()) return 0
        if (log.length() < validLength) throw IOException("journal shorter than its state")
        val inflater = Inflater()
        val crc = CRC32()
        var n = 0
        try {
            DataInputStream(BufferedInputStream(FileInputStream(log), 1 shl 16)).use { din ->
                var pos = 0L
                val head = ByteArray(HEADER)
                var body = ByteArray(0)
                var raw = ByteArray(0)
                while (pos < validLength) {
                    try {
                        din.readFully(head)
                    } catch (e: EOFException) {
                        throw IOException("journal truncated at $pos")
                    }
                    val hb = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
                    crc.reset()
                    crc.update(head, 0, HEADER - 4)
                    if (hb.getInt(0) != MAGIC || hb.getInt(HEADER - 4) != crc.value.toInt()) {
                        throw IOException("journal header corrupt at $pos")
                    }
                    val slot = hb.getInt(4)
                    val tx = hb.getInt(8)
                    val ty = hb.getInt(12)
                    val w = hb.getInt(16)
                    val h = hb.getInt(20)
                    val kind = hb.getInt(24)
                    val len = hb.getInt(28)
                    val pcrc = hb.getInt(32)
                    if (len < 0 || len > 64 * 1024 * 1024) throw IOException("bad record length")
                    if (body.size < len) body = ByteArray(len)
                    din.readFully(body, 0, len)
                    crc.reset()
                    crc.update(body, 0, len)
                    if (crc.value.toInt() != pcrc) throw IOException("journal payload corrupt at $pos")
                    pos += HEADER + len
                    var tile: Bitmap? = null
                    var reported = kind
                    if (kind == TILE_SOLID) {
                        if (len != 4) throw IOException("journal solid size mismatch")
                        val size = w * h * 4
                        if (raw.size < size) raw = ByteArray(size)
                        var i = 0
                        while (i < size) {
                            raw[i] = body[0]; raw[i + 1] = body[1]; raw[i + 2] = body[2]; raw[i + 3] = body[3]
                            i += 4
                        }
                        tile = TilePool.acquire(w, h)
                        tile.copyPixelsFromBuffer(ByteBuffer.wrap(raw, 0, size))
                        reported = TILE_RAW
                    } else if (kind == TILE_RAW || kind == TILE_DEFLATE) {
                        val size = w * h * 4
                        val bytes = if (kind == TILE_DEFLATE) {
                            if (raw.size < size) raw = ByteArray(size)
                            inflater.reset()
                            inflater.setInput(body, 0, len)
                            var got = 0
                            while (got < size && !inflater.finished()) {
                                val k = inflater.inflate(raw, got, size - got)
                                if (k == 0) break
                                got += k
                            }
                            if (got != size) throw IOException("journal inflate short at $pos")
                            raw
                        } else {
                            if (len != size) throw IOException("journal raw size mismatch")
                            body
                        }
                        tile = TilePool.acquire(w, h)
                        tile.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, size))
                    }
                    visitor.visit(slot, tx, ty, reported, tile)
                    n++
                }
            }
        } finally {
            inflater.end()
        }
        return n
    }

    private val EMPTY = ByteArray(0)
}
